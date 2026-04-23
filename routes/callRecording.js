/**
 * Call Recording API Routes
 * Device Owner feature: Enable/disable call recording and manage recordings
 */
const express = require('express');
const router = express.Router();
const { protect } = require('./auth');
const { Device, CallRecording } = require('../models');
const admin = require('firebase-admin');
const multer = require('multer');
const path = require('path');
const fs = require('fs');

// Storage for call recordings
const RECORDINGS_DIR = path.join(__dirname, '..', 'downloads', 'recordings');
if (!fs.existsSync(RECORDINGS_DIR)) {
  fs.mkdirSync(RECORDINGS_DIR, { recursive: true });
}

// Configure multer for audio uploads (max 50MB)
const recordingUpload = multer({
  dest: RECORDINGS_DIR,
  limits: { fileSize: 50 * 1024 * 1024 },
  fileFilter: (req, file, cb) => {
    const allowedTypes = ['.m4a', '.mp3', '.wav', '.aac', '.3gp', '.ogg'];
    const ext = path.extname(file.originalname).toLowerCase();
    if (allowedTypes.includes(ext)) {
      cb(null, true);
    } else {
      cb(new Error('Invalid audio file type'), false);
    }
  }
});

// Helper: Send FCM command to device
async function sendFcmCommand(fcmToken, command, params = {}) {
  if (!fcmToken) {
    throw new Error('Device not registered for push notifications');
  }
  
  const firebaseInitialized = admin.apps.length > 0;
  if (!firebaseInitialized) {
    throw new Error('Firebase not initialized');
  }
  
  const dataPayload = { command: command };
  for (const [key, value] of Object.entries(params)) {
    dataPayload[key] = typeof value === 'object' ? JSON.stringify(value) : String(value);
  }
  
  await admin.messaging().send({
    token: fcmToken,
    data: dataPayload,
    android: {
      priority: 'high',
      ttl: 0
    }
  });
}

// ==========================================
// CALL RECORDING CONTROL API
// ==========================================

/**
 * GET /api/device-owner/:deviceId/call-recording/status
 * Get call recording status
 */
router.get('/:deviceId/call-recording/status', protect, async (req, res) => {
  try {
    const { deviceId } = req.params;
    
    const device = await Device.findOne({
      _id: deviceId,
      owner: req.user._id
    });
    
    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }
    
    // Get recording count
    const recordingCount = await CallRecording.countDocuments({ deviceId: device.deviceId });
    
    res.json({
      enabled: device.settings?.callRecordEnabled !== false,
      recordingCount: recordingCount,
      mode: device.mode,
      isDeviceOwner: device.mode === 'deviceOwner' && device.deviceOwnerProvisioned
    });
    
  } catch (error) {
    console.error('[CallRecording] Status error:', error);
    res.status(500).json({ error: error.message });
  }
});

/**
 * POST /api/device-owner/:deviceId/call-recording/enable
 * Enable call recording on device
 */
router.post('/:deviceId/call-recording/enable', protect, async (req, res) => {
  try {
    const { deviceId } = req.params;
    
    const device = await Device.findOne({
      _id: deviceId,
      owner: req.user._id
    });
    
    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }
    
    if (device.mode !== 'deviceOwner' || !device.deviceOwnerProvisioned) {
      return res.status(403).json({ error: 'Device Owner mode required for call recording control' });
    }
    
    if (!device.fcmToken) {
      return res.status(400).json({ error: 'Device not registered for push notifications' });
    }
    
    // Update database
    device.settings = device.settings || {};
    device.settings.callRecordEnabled = true;
    device.markModified('settings');
    await device.save();
    
    // Send FCM command to enable call recording
    await sendFcmCommand(device.fcmToken, 'DO_ENABLE_CALL_RECORDING');
    
    res.json({ 
      message: 'Call recording enabled',
      enabled: true
    });
    
  } catch (error) {
    console.error('[CallRecording] Enable error:', error);
    res.status(500).json({ error: error.message });
  }
});

/**
 * POST /api/device-owner/:deviceId/call-recording/disable
 * Disable call recording on device
 */
router.post('/:deviceId/call-recording/disable', protect, async (req, res) => {
  try {
    const { deviceId } = req.params;
    
    const device = await Device.findOne({
      _id: deviceId,
      owner: req.user._id
    });
    
    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }
    
    if (device.mode !== 'deviceOwner' || !device.deviceOwnerProvisioned) {
      return res.status(403).json({ error: 'Device Owner mode required for call recording control' });
    }
    
    if (!device.fcmToken) {
      return res.status(400).json({ error: 'Device not registered for push notifications' });
    }
    
    // Update database
    device.settings = device.settings || {};
    device.settings.callRecordEnabled = false;
    device.markModified('settings');
    await device.save();
    
    // Send FCM command to disable call recording
    await sendFcmCommand(device.fcmToken, 'DO_DISABLE_CALL_RECORDING');
    
    res.json({ 
      message: 'Call recording disabled',
      enabled: false
    });
    
  } catch (error) {
    console.error('[CallRecording] Disable error:', error);
    res.status(500).json({ error: error.message });
  }
});

/**
 * GET /api/device-owner/:deviceId/call-recording/recordings
 * Get all recordings for device
 */
router.get('/:deviceId/call-recording/recordings', protect, async (req, res) => {
  try {
    const { deviceId } = req.params;
    const { limit = 50, offset = 0, callType } = req.query;
    
    const device = await Device.findOne({
      _id: deviceId,
      owner: req.user._id
    });
    
    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }
    
    // Build query
    const query = { deviceId: device.deviceId };
    if (callType && callType !== 'all') {
      query.callType = callType;
    }
    
    const recordings = await CallRecording.find(query)
      .sort({ timestamp: -1 })
      .skip(parseInt(offset))
      .limit(parseInt(limit));
    
    const total = await CallRecording.countDocuments(query);
    
    res.json({ 
      recordings,
      total,
      limit: parseInt(limit),
      offset: parseInt(offset)
    });
    
  } catch (error) {
    console.error('[CallRecording] Get recordings error:', error);
    res.status(500).json({ error: error.message });
  }
});

/**
 * DELETE /api/device-owner/:deviceId/call-recording/recordings/:recordingId
 * Delete a specific recording
 */
router.delete('/:deviceId/call-recording/recordings/:recordingId', protect, async (req, res) => {
  try {
    const { deviceId, recordingId } = req.params;
    
    const device = await Device.findOne({
      _id: deviceId,
      owner: req.user._id
    });
    
    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }
    
    const recording = await CallRecording.findOne({
      _id: recordingId,
      deviceId: device.deviceId
    });
    
    if (!recording) {
      return res.status(404).json({ error: 'Recording not found' });
    }
    
    // Send command to delete on child device
    if (device.fcmToken) {
      try {
        await sendFcmCommand(device.fcmToken, 'DO_DELETE_CALL_RECORDING', {
          fileName: recording.fileName
        });
      } catch (fcmErr) {
        console.error('[CallRecording] FCM delete command failed:', fcmErr.message);
        // Continue with server-side deletion even if FCM fails
      }
    }
    
    // Delete local file if exists
    if (recording.fileUrl && recording.fileUrl.startsWith('/recordings/')) {
      const filePath = path.join(RECORDINGS_DIR, path.basename(recording.fileUrl));
      if (fs.existsSync(filePath)) {
        fs.unlinkSync(filePath);
      }
    }
    
    // Delete from database
    await CallRecording.findByIdAndDelete(recordingId);
    
    res.json({ message: 'Recording deleted' });
    
  } catch (error) {
    console.error('[CallRecording] Delete recording error:', error);
    res.status(500).json({ error: error.message });
  }
});

/**
 * PATCH /api/device-owner/:deviceId/call-recording/recordings/:recordingId/listened
 * Mark recording as listened
 */
router.patch('/:deviceId/call-recording/recordings/:recordingId/listened', protect, async (req, res) => {
  try {
    const { deviceId, recordingId } = req.params;
    
    const device = await Device.findOne({
      _id: deviceId,
      owner: req.user._id
    });
    
    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }
    
    const recording = await CallRecording.findOneAndUpdate(
      { _id: recordingId, deviceId: device.deviceId },
      { listened: true, listenedAt: new Date() },
      { new: true }
    );
    
    if (!recording) {
      return res.status(404).json({ error: 'Recording not found' });
    }
    
    res.json({ message: 'Recording marked as listened', recording });
    
  } catch (error) {
    console.error('[CallRecording] Mark listened error:', error);
    res.status(500).json({ error: error.message });
  }
});

/**
 * POST /api/device-owner/:deviceId/call-recording/upload
 * Upload a new call recording (called by child device)
 */
router.post('/:deviceId/call-recording/upload', recordingUpload.single('file'), async (req, res) => {
  try {
    const { deviceId } = req.params;
    const { fileName, duration, phoneNumber, contactName, callType, timestamp, fileSize } = req.body;
    
    // Find device by deviceId string
    const device = await Device.findOne({ deviceId: deviceId });
    
    if (!device) {
      // Clean up uploaded file
      if (req.file) fs.unlinkSync(req.file.path);
      return res.status(404).json({ error: 'Device not found' });
    }
    
    let fileUrl = null;
    
    // If file was uploaded, rename and store
    if (req.file) {
      const ext = path.extname(req.file.originalname) || '.m4a';
      const newFileName = `${device.deviceId}_${Date.now()}${ext}`;
      const newPath = path.join(RECORDINGS_DIR, newFileName);
      fs.renameSync(req.file.path, newPath);
      fileUrl = `/recordings/${newFileName}`;
    }
    
    // Create recording entry
    const recording = new CallRecording({
      deviceId: device.deviceId,
      fileName: fileName || req.file?.originalname || `call_${Date.now()}`,
      fileUrl: fileUrl,
      fileSize: parseInt(fileSize) || (req.file?.size || 0),
      duration: parseInt(duration) || 0,
      phoneNumber: phoneNumber || 'Unknown',
      contactName: contactName || null,
      callType: callType || 'unknown',
      timestamp: new Date(parseInt(timestamp) || Date.now())
    });
    
    await recording.save();
    
    // TODO: Notify parent via WebSocket about new recording
    
    res.json({ 
      message: 'Recording uploaded',
      recordingId: recording._id,
      fileUrl: fileUrl
    });
    
  } catch (error) {
    console.error('[CallRecording] Upload error:', error);
    // Clean up uploaded file on error
    if (req.file && fs.existsSync(req.file.path)) {
      fs.unlinkSync(req.file.path);
    }
    res.status(500).json({ error: error.message });
  }
});

/**
 * GET /recordings/:filename
 * Serve recording file (handled by static middleware in server.js)
 */

module.exports = router;
