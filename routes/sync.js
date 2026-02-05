const express = require('express');
const mongoose = require('mongoose');
const { Device, Notification, CallLog, AppUsage, LocationHistory, Photo, SMS, BrowserHistory } = require('../models');

const router = express.Router();

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
        lastSeen: device.lastSeen,
        battery: device.battery,
        fcmToken: device.fcmToken || null,
        hasFcmToken: !!device.fcmToken
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
    const deviceIdHeader = req.headers['x-device-id'];
    console.log(`[verifyDevice] Received X-Device-ID: ${deviceIdHeader}`);
    
    if (!deviceIdHeader) {
      console.log('[verifyDevice] No X-Device-ID header provided');
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

    const docs = notifications.map(n => ({
      deviceId: req.device.deviceId,
      packageName: n.packageName,
      appName: n.appName,
      title: n.title,
      content: n.content,
      imageUrl: n.imageUrl,
      timestamp: n.timestamp || new Date()
    }));

    await Notification.insertMany(docs, { ordered: false });

    res.json({
      success: true,
      count: docs.length
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
    
    const doc = {
      deviceId: device.deviceId,
      packageName: notification.packageName,
      appName: notification.appName,
      title: notification.title,
      content: notification.text || notification.content,
      timestamp: notification.timestamp ? new Date(notification.timestamp) : new Date()
    };
    
    await Notification.create(doc);
    
    console.log(`[Notification] Saved notification from ${notification.appName} for device ${device.name}`);
    
    res.json({ success: true });
  } catch (error) {
    // Ignore duplicate key errors
    if (error.code !== 11000) {
      console.error('Upload notification error:', error);
    }
    res.json({ success: true });
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
                         'call-logs', 'app-usage', 'location', 'admin', 'debug'];
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
      const notifDocs = notifications.map(n => ({
        deviceId: device.deviceId,
        ...n,
        timestamp: n.timestamp || new Date()
      }));
      await Notification.insertMany(notifDocs, { ordered: false }).catch(() => {});
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
      const notifDocs = notifications.map(n => ({
        deviceId: req.device.deviceId,
        ...n,
        timestamp: n.timestamp || new Date()
      }));
      await Notification.insertMany(notifDocs, { ordered: false }).catch(() => {});
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
      lastUpdated: new Date()
    };

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

// Sync photos from device
router.post('/photos', verifyDevice, async (req, res) => {
  try {
    const { photos } = req.body;

    if (!Array.isArray(photos)) {
      return res.status(400).json({ error: 'Photos must be an array' });
    }

    // Get the user to check storage quota
    const user = await User.findById(req.device.owner);
    if (!user) {
      return res.status(404).json({ error: 'User not found' });
    }

    const storageLimit = user.photoStorageLimit || (200 * 1024 * 1024); // 200MB default
    let currentStorage = user.photoStorageUsed || 0;
    
    // Calculate how much storage these photos would use
    let newPhotosSize = 0;
    const photosToSync = [];
    
    for (const p of photos) {
      // Estimate size from base64 (base64 is ~4/3 of original)
      const photoSize = p.size || 0;
      
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
          timestamp: new Date()
        });
        newPhotosSize += photoSize;
      }
    }

    // Insert photos that fit within quota
    if (photosToSync.length > 0) {
      await Photo.insertMany(photosToSync, { ordered: false });
      
      // Update user's storage used
      await User.findByIdAndUpdate(req.device.owner, {
        $inc: { photoStorageUsed: newPhotosSize }
      });
    }

    const quotaExceeded = photos.length > photosToSync.length;
    const remainingStorage = storageLimit - (currentStorage + newPhotosSize);

    console.log(`Device ${req.device.deviceId} - Synced ${photosToSync.length}/${photos.length} photos (Storage: ${((currentStorage + newPhotosSize) / 1024 / 1024).toFixed(2)}MB / ${(storageLimit / 1024 / 1024).toFixed(0)}MB)`);

    res.json({
      success: true,
      count: photosToSync.length,
      skipped: photos.length - photosToSync.length,
      quotaExceeded,
      storageUsed: currentStorage + newPhotosSize,
      storageLimit,
      remainingStorage,
      message: quotaExceeded ? 'Storage quota exceeded. Delete photos from dashboard to sync more.' : null
    });
  } catch (error) {
    // Ignore duplicate key errors
    if (error.code !== 11000) {
      console.error('Sync photos error:', error);
    }
    res.json({ success: true });
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

module.exports = router;
