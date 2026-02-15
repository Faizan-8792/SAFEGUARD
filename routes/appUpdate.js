const express = require('express');
const router = express.Router();
const multer = require('multer');
const path = require('path');
const fs = require('fs');
const admin = require('firebase-admin');
const { Device } = require('../models');

// Configure storage for APK uploads
const storage = multer.diskStorage({
  destination: (req, file, cb) => {
    const uploadPath = path.join(__dirname, '..', 'downloads', 'updates');
    if (!fs.existsSync(uploadPath)) {
      fs.mkdirSync(uploadPath, { recursive: true });
    }
    cb(null, uploadPath);
  },
  filename: (req, file, cb) => {
    const timestamp = Date.now();
    cb(null, `familyguard_${timestamp}.apk`);
  }
});

const upload = multer({
  storage,
  limits: { fileSize: 200 * 1024 * 1024 }, // 200MB
  fileFilter: (req, file, cb) => {
    if (file.mimetype === 'application/vnd.android.package-archive' || 
        file.originalname.endsWith('.apk')) {
      cb(null, true);
    } else {
      cb(new Error('Only APK files are allowed'));
    }
  }
});

// Get current app version on device
router.get('/:deviceId/app-version', async (req, res) => {
  try {
    const { deviceId } = req.params;
    
    // Try finding by MongoDB _id first (dashboard sends _id), then by Android deviceId
    const mongoose = require('mongoose');
    let device = null;
    if (mongoose.Types.ObjectId.isValid(deviceId)) {
      device = await Device.findById(deviceId);
    }
    if (!device) {
      device = await Device.findOne({ deviceId });
    }
    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }
    
    res.json({
      deviceVersion: device.appVersion || 'Unknown',
      versionCode: device.appVersionCode || 0,
      lastUpdated: device.appLastUpdated || null
    });
    
  } catch (error) {
    console.error('Error getting app version:', error);
    res.status(500).json({ error: 'Failed to get app version' });
  }
});

// Get latest available APK info
router.get('/latest', async (req, res) => {
  try {
    const updatesPath = path.join(__dirname, '..', 'downloads', 'updates');
    
    if (!fs.existsSync(updatesPath)) {
      return res.json({ available: false });
    }
    
    const files = fs.readdirSync(updatesPath)
      .filter(f => f.endsWith('.apk'))
      .map(f => ({
        name: f,
        path: path.join(updatesPath, f),
        stats: fs.statSync(path.join(updatesPath, f))
      }))
      .sort((a, b) => b.stats.mtime - a.stats.mtime);
    
    if (files.length === 0) {
      return res.json({ available: false });
    }
    
    const latest = files[0];
    res.json({
      available: true,
      fileName: latest.name,
      fileSize: latest.stats.size,
      uploadedAt: latest.stats.mtime,
      downloadUrl: `/updates/${latest.name}`
    });
    
  } catch (error) {
    console.error('Error getting latest APK:', error);
    res.status(500).json({ error: 'Failed to get latest APK info' });
  }
});

// Upload new APK version
router.post('/upload', upload.single('apk'), async (req, res) => {
  try {
    if (!req.file) {
      return res.status(400).json({ error: 'No APK file uploaded' });
    }
    
    res.json({
      success: true,
      fileName: req.file.filename,
      fileSize: req.file.size,
      downloadUrl: `/updates/${req.file.filename}`,
      message: 'APK uploaded successfully'
    });
    
  } catch (error) {
    console.error('Error uploading APK:', error);
    res.status(500).json({ error: 'Failed to upload APK' });
  }
});

// Push live update to device via FCM
router.post('/:deviceId/push-update', async (req, res) => {
  try {
    const { deviceId } = req.params;
    const { apkUrl } = req.body;
    
    const device = await Device.findOne({ deviceId });
    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }
    
    if (!device.fcmToken) {
      return res.status(400).json({ error: 'Device FCM token not available' });
    }
    
    // Determine the APK URL
    let updateUrl = apkUrl;
    if (!updateUrl) {
      // Use latest uploaded APK
      const updatesPath = path.join(__dirname, '..', 'downloads', 'updates');
      if (fs.existsSync(updatesPath)) {
        const files = fs.readdirSync(updatesPath)
          .filter(f => f.endsWith('.apk'))
          .sort((a, b) => {
            const statA = fs.statSync(path.join(updatesPath, a));
            const statB = fs.statSync(path.join(updatesPath, b));
            return statB.mtime - statA.mtime;
          });
        
        if (files.length > 0) {
          // Use server URL with the APK path
          const serverUrl = process.env.SERVER_URL || 'http://localhost:3000';
          updateUrl = `${serverUrl}/updates/${files[0]}`;
        }
      }
    }
    
    if (!updateUrl) {
      return res.status(400).json({ error: 'No APK URL provided and no uploaded APK available' });
    }
    
    // Send FCM to trigger self-update
    const message = {
      token: device.fcmToken,
      data: {
        command: 'DO_SELF_UPDATE',
        apkUrl: updateUrl,
        timestamp: Date.now().toString()
      },
      android: {
        priority: 'high',
        ttl: 3600000 // 1 hour
      }
    };
    
    await admin.messaging().send(message);
    
    // Log the update push
    device.lastUpdatePush = new Date();
    device.lastUpdateUrl = updateUrl;
    await device.save();
    
    res.json({
      success: true,
      message: 'Update command sent to device',
      apkUrl: updateUrl
    });
    
  } catch (error) {
    console.error('Error pushing update:', error);
    res.status(500).json({ error: 'Failed to push update: ' + error.message });
  }
});

// Download latest APK
router.get('/download/latest', (req, res) => {
  try {
    const updatesPath = path.join(__dirname, '..', 'downloads', 'updates');
    
    if (!fs.existsSync(updatesPath)) {
      return res.status(404).json({ error: 'No APK available' });
    }
    
    const files = fs.readdirSync(updatesPath)
      .filter(f => f.endsWith('.apk'))
      .map(f => ({
        name: f,
        path: path.join(updatesPath, f),
        stats: fs.statSync(path.join(updatesPath, f))
      }))
      .sort((a, b) => b.stats.mtime - a.stats.mtime);
    
    if (files.length === 0) {
      return res.status(404).json({ error: 'No APK available' });
    }
    
    res.download(files[0].path, 'FamilyGuard-latest.apk');
    
  } catch (error) {
    console.error('Error downloading APK:', error);
    res.status(500).json({ error: 'Failed to download APK' });
  }
});

module.exports = router;
