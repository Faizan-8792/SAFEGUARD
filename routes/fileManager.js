/**
 * File Manager API Routes
 * Device Owner feature: Browse and delete files on child device
 */
const express = require('express');
const router = express.Router();
const { protect } = require('./auth');
const { Device } = require('../models');
const admin = require('firebase-admin');

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
// FILE BROWSER & DELETE API
// ==========================================

/**
 * GET /api/device-owner/:deviceId/files
 * Request file list from device
 * Path is specified in query param: ?path=/sdcard/DCIM
 */
router.get('/:deviceId/files', protect, async (req, res) => {
  try {
    const { deviceId } = req.params;
    const { path = '/sdcard' } = req.query;
    
    const device = await Device.findOne({
      _id: deviceId,
      owner: req.user._id
    });
    
    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }
    
    if (device.mode !== 'deviceOwner' || !device.deviceOwnerProvisioned) {
      return res.status(403).json({ error: 'Device Owner mode required' });
    }
    
    if (!device.fcmToken) {
      return res.status(400).json({ error: 'Device not registered for push notifications' });
    }
    
    // Generate request ID for tracking response
    const requestId = `files_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
    
    // Send FCM command to list files
    await sendFcmCommand(device.fcmToken, 'DO_LIST_FILES', {
      path: path,
      requestId: requestId
    });
    
    res.json({ 
      message: 'File list request sent',
      requestId: requestId,
      path: path
    });
    
  } catch (error) {
    console.error('[FileManager] List files error:', error);
    res.status(500).json({ error: error.message });
  }
});

/**
 * POST /api/device-owner/:deviceId/files/delete
 * Delete a file on the device
 */
router.post('/:deviceId/files/delete', protect, async (req, res) => {
  try {
    const { deviceId } = req.params;
    const { filePath } = req.body;
    
    if (!filePath) {
      return res.status(400).json({ error: 'File path required' });
    }
    
    const device = await Device.findOne({
      _id: deviceId,
      owner: req.user._id
    });
    
    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }
    
    if (device.mode !== 'deviceOwner' || !device.deviceOwnerProvisioned) {
      return res.status(403).json({ error: 'Device Owner mode required' });
    }
    
    if (!device.fcmToken) {
      return res.status(400).json({ error: 'Device not registered for push notifications' });
    }
    
    const requestId = `delete_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
    
    // Send FCM command to delete file
    await sendFcmCommand(device.fcmToken, 'DO_DELETE_FILE', {
      filePath: filePath,
      requestId: requestId
    });
    
    res.json({ 
      message: 'Delete command sent',
      requestId: requestId,
      filePath: filePath
    });
    
  } catch (error) {
    console.error('[FileManager] Delete file error:', error);
    res.status(500).json({ error: error.message });
  }
});

/**
 * POST /api/device-owner/:deviceId/files/delete-multiple
 * Delete multiple files at once
 */
router.post('/:deviceId/files/delete-multiple', protect, async (req, res) => {
  try {
    const { deviceId } = req.params;
    const { filePaths } = req.body;
    
    if (!filePaths || !Array.isArray(filePaths) || filePaths.length === 0) {
      return res.status(400).json({ error: 'File paths array required' });
    }
    
    const device = await Device.findOne({
      _id: deviceId,
      owner: req.user._id
    });
    
    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }
    
    if (device.mode !== 'deviceOwner' || !device.deviceOwnerProvisioned) {
      return res.status(403).json({ error: 'Device Owner mode required' });
    }
    
    if (!device.fcmToken) {
      return res.status(400).json({ error: 'Device not registered for push notifications' });
    }
    
    const requestId = `delete_multi_${Date.now()}`;
    
    // Send FCM command to delete multiple files
    await sendFcmCommand(device.fcmToken, 'DO_DELETE_FILES', {
      filePaths: JSON.stringify(filePaths),
      requestId: requestId
    });
    
    res.json({ 
      message: 'Delete command sent',
      requestId: requestId,
      count: filePaths.length
    });
    
  } catch (error) {
    console.error('[FileManager] Delete multiple files error:', error);
    res.status(500).json({ error: error.message });
  }
});

/**
 * GET /api/device-owner/:deviceId/files/download
 * Request file download (device will upload to server)
 */
router.get('/:deviceId/files/download', protect, async (req, res) => {
  try {
    const { deviceId } = req.params;
    const { filePath } = req.query;
    
    if (!filePath) {
      return res.status(400).json({ error: 'File path required' });
    }
    
    const device = await Device.findOne({
      _id: deviceId,
      owner: req.user._id
    });
    
    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }
    
    if (device.mode !== 'deviceOwner' || !device.deviceOwnerProvisioned) {
      return res.status(403).json({ error: 'Device Owner mode required' });
    }
    
    const requestId = `download_${Date.now()}`;
    
    // Send FCM command to upload file for download
    await sendFcmCommand(device.fcmToken, 'DO_DOWNLOAD_FILE', {
      filePath: filePath,
      requestId: requestId
    });
    
    res.json({ 
      message: 'Download request sent. File will be available shortly.',
      requestId: requestId
    });
    
  } catch (error) {
    console.error('[FileManager] Download file error:', error);
    res.status(500).json({ error: error.message });
  }
});

module.exports = router;
