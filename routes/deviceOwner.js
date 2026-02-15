const express = require('express');
const router = express.Router();
const { protect } = require('./auth');
const { Device, CallRecording } = require('../models');
const admin = require('firebase-admin');
const crypto = require('crypto');
const multer = require('multer');
const path = require('path');
const fs = require('fs');

// Temp APK storage directory
const TEMP_APK_DIR = path.join(__dirname, '..', 'downloads', 'temp');
if (!fs.existsSync(TEMP_APK_DIR)) {
  fs.mkdirSync(TEMP_APK_DIR, { recursive: true });
}

// Configure multer for APK uploads (max 200MB, .apk files only)
const apkUpload = multer({
  dest: TEMP_APK_DIR,
  limits: { fileSize: 200 * 1024 * 1024 },
  fileFilter: (req, file, cb) => {
    if (file.originalname.endsWith('.apk') || file.mimetype === 'application/vnd.android.package-archive') {
      cb(null, true);
    } else {
      cb(new Error('Only .apk files are allowed'), false);
    }
  }
});

// In-memory map to track temp APK files for download
const tempApkFiles = new Map(); // tempId -> { filePath, originalName, expiresAt }

// ============================================================
// DEVICE OWNER MODE API ROUTES
// All routes are under /api/device-owner
// These routes handle Device Owner provisioning and management
// ============================================================

// Helper: Send FCM command to device
async function sendFcmCommand(device, command, params = {}) {
  if (!device.fcmToken) {
    throw new Error('Device not registered for push notifications');
  }
  
  const firebaseInitialized = admin.apps.length > 0;
  if (!firebaseInitialized) {
    throw new Error('Firebase not initialized');
  }
  
  // Flatten params into top-level data map (FCM data values must be strings)
  const dataPayload = { command: command };
  for (const [key, value] of Object.entries(params)) {
    dataPayload[key] = typeof value === 'object' ? JSON.stringify(value) : String(value);
  }
  
  await admin.messaging().send({
    token: device.fcmToken,
    data: dataPayload,
    android: {
      priority: 'high',
      ttl: 0 // Deliver immediately
    }
  });
}

// Helper: Verify device belongs to user and is in DO mode
async function getDeviceOwnerDevice(deviceId, userId) {
  const device = await Device.findOne({
    _id: deviceId,
    owner: userId
  });
  
  if (!device) {
    return { error: 'Device not found', status: 404 };
  }
  
  return { device };
}

// ==========================================
// QR CODE PROVISIONING
// ==========================================

// POST /api/device-owner/generate-qr
// Generate QR code data for Device Owner provisioning
router.post('/generate-qr', protect, async (req, res) => {
  try {
    const { wifiSsid, wifiPassword, wifiSecurityType, deviceName } = req.body;
    
    const serverBase = process.env.BASE_URL || 'https://familyguard-backend-c2c9hkc8dwgzepdq.centralindia-01.azurewebsites.net';
    const apkDownloadUrl = process.env.APK_DOWNLOAD_URL || `${serverBase}/download/familyguard.apk`;
    
    // Hardcoded checksums from the debug signing certificate and APK file
    // SIGNATURE_CHECKSUM = SHA-256 of signing certificate (for Android 7+)
    // PACKAGE_CHECKSUM = SHA-256 of APK file itself (for older Android)
    const apkSignatureChecksum = process.env.APK_SIGNATURE_CHECKSUM || 'SmkdTDs477TqetjWxhIvR50q300AIbrAWNnJ6JlMKs4';
    const apkPackageChecksum = process.env.APK_PACKAGE_CHECKSUM || 'o2ecA0qtCvE7AgW6i4vfEMKc_CAg-dj2aqVBSIr9Du8';
    
    // Check if APK file exists on server
    const fs = require('fs');
    const apkPath = require('path').join(__dirname, '..', 'downloads', 'familyguard.apk');
    const apkExists = fs.existsSync(apkPath);
    
    let warnings = [];
    if (!apkExists && !process.env.APK_DOWNLOAD_URL) {
      warnings.push('No APK file found at downloads/familyguard.apk and no APK_DOWNLOAD_URL configured.');
    }
    
    // Build the provisioning extras for Android Device Owner
    // Keep payload minimal - extras bundle removed as it can cause issues on some OEMs
    const provisioningData = {
      'android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME': 
        'com.familyguardpro/com.familyguardpro.services.DeviceAdminReceiver',
      'android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION':
        apkDownloadUrl,
      'android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM': apkSignatureChecksum,
      'android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM': apkPackageChecksum,
      'android.app.extra.PROVISIONING_SKIP_ENCRYPTION': true,
      'android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED': true
    };
    
    // Add WiFi config if provided (crucial for factory-reset devices to download APK)
    if (wifiSsid) {
      provisioningData['android.app.extra.PROVISIONING_WIFI_SSID'] = wifiSsid;
      if (wifiPassword) {
        provisioningData['android.app.extra.PROVISIONING_WIFI_PASSWORD'] = wifiPassword;
      }
      provisioningData['android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE'] = 
        wifiSecurityType || 'WPA';
    }
    
    const response = {
      success: true,
      qrData: JSON.stringify(provisioningData),
      apkHosted: apkExists,
      checksumConfigured: true,
      instructions: [
        '1. Factory reset the child device',
        '2. On the Welcome screen, tap the screen 6 times rapidly',
        '3. The QR code scanner will appear',
        '4. Scan this QR code',
        '5. The device will automatically set up FamilyGuard Pro as Device Owner',
        '6. Wait for provisioning to complete'
      ]
    };
    
    if (warnings.length > 0) {
      response.warning = warnings.join(' | ');
      warnings.forEach(w => console.warn('[DO] WARNING:', w));
    }
    
    res.json(response);
  } catch (error) {
    console.error('[DO] QR generation error:', error);
    res.status(500).json({ error: 'Failed to generate QR code' });
  }
});

// POST /api/device-owner/confirm-provisioning
// Called by child device after DO provisioning completes
router.post('/confirm-provisioning', async (req, res) => {
  try {
    const { deviceId, parentUserId, provisioningToken, deviceName, model, androidVersion, method } = req.body;
    
    if (!deviceId || !parentUserId) {
      return res.status(400).json({ error: 'deviceId and parentUserId are required' });
    }
    
    // Find or create device
    let device = await Device.findOne({ deviceId });
    
    if (!device) {
      // Create new device entry
      device = new Device({
        deviceId,
        name: deviceName || 'DO Child Device',
        model: model || 'Unknown',
        androidVersion: androidVersion || 'Unknown',
        owner: parentUserId,
        mode: 'deviceOwner',
        deviceOwnerProvisioned: true,
        provisioningDate: new Date(),
        provisioningMethod: method || 'qr_code',
        deviceOwnerPolicies: {
          uninstallProtected: true,
          accessibilityAutoRecover: true,
          silentInstallEnabled: true
        }
      });
      await device.save();
      
      // Add device to user's device list
      const { User } = require('../models');
      await User.findByIdAndUpdate(parentUserId, {
        $addToSet: { devices: device._id }
      });
    } else {
      // Update existing device to DO mode
      device.mode = 'deviceOwner';
      device.deviceOwnerProvisioned = true;
      device.provisioningDate = new Date();
      device.provisioningMethod = method || 'qr_code';
      device.deviceOwnerPolicies = device.deviceOwnerPolicies || {};
      device.deviceOwnerPolicies.uninstallProtected = true;
      device.deviceOwnerPolicies.accessibilityAutoRecover = true;
      device.deviceOwnerPolicies.silentInstallEnabled = true;
      await device.save();
    }
    
    console.log(`[DO] Device ${deviceId} provisioned as Device Owner (method: ${method || 'qr_code'})`);
    
    res.json({
      success: true,
      device: {
        _id: device._id,
        deviceId: device.deviceId,
        mode: device.mode,
        deviceOwnerProvisioned: device.deviceOwnerProvisioned,
        provisioningDate: device.provisioningDate
      }
    });
  } catch (error) {
    console.error('[DO] Provisioning confirmation error:', error);
    res.status(500).json({ error: 'Failed to confirm provisioning' });
  }
});

// GET /api/device-owner/:deviceId/status
// Get Device Owner status for a device
router.get('/:deviceId/status', protect, async (req, res) => {
  try {
    const { device, error, status } = await getDeviceOwnerDevice(req.params.deviceId, req.user._id);
    if (error) return res.status(status).json({ error });
    
    // Auto-detect: if device reports deviceAdmin=true but mode isn't set yet, activate it
    if (device.permissions?.deviceAdmin && device.mode !== 'deviceOwner') {
      device.mode = 'deviceOwner';
      device.deviceOwnerProvisioned = true;
      device.provisioningDate = device.provisioningDate || new Date();
      device.provisioningMethod = device.provisioningMethod || 'auto-detected';
      device.deviceOwnerPolicies = device.deviceOwnerPolicies || {
        uninstallProtected: true,
        accessibilityAutoRecover: true,
        silentInstallEnabled: true
      };
      await device.save();
      console.log(`[DO] Auto-activated Device Owner for device ${device.deviceId} (permissions.deviceAdmin was true)`);
    }
    
    res.json({
      success: true,
      mode: device.mode,
      deviceOwnerProvisioned: device.deviceOwnerProvisioned,
      provisioningDate: device.provisioningDate,
      provisioningMethod: device.provisioningMethod,
      policies: device.deviceOwnerPolicies || {}
    });
  } catch (error) {
    console.error('[DO] Status error:', error);
    res.status(500).json({ error: 'Failed to get DO status' });
  }
});

// POST /api/device-owner/:deviceId/activate-adb
// Called from parent dashboard to mark a device as DO-provisioned (for ADB setups)
router.post('/:deviceId/activate-adb', protect, async (req, res) => {
  try {
    const { device, error, status } = await getDeviceOwnerDevice(req.params.deviceId, req.user._id);
    if (error) return res.status(status).json({ error });
    
    // Update device to DO mode
    device.mode = 'deviceOwner';
    device.deviceOwnerProvisioned = true;
    device.provisioningDate = new Date();
    device.provisioningMethod = 'adb';
    device.deviceOwnerPolicies = device.deviceOwnerPolicies || {};
    device.deviceOwnerPolicies.uninstallProtected = true;
    device.deviceOwnerPolicies.accessibilityAutoRecover = true;
    device.deviceOwnerPolicies.silentInstallEnabled = true;
    await device.save();
    
    console.log(`[DO] Device ${device.deviceId} activated as Device Owner via ADB`);
    
    res.json({
      success: true,
      mode: device.mode,
      deviceOwnerProvisioned: true,
      provisioningDate: device.provisioningDate,
      provisioningMethod: 'adb'
    });
  } catch (error) {
    console.error('[DO] ADB activation error:', error);
    res.status(500).json({ error: 'Failed to activate Device Owner: ' + error.message });
  }
});

// ==========================================
// APP HIDING (DO Feature #1)
// ==========================================

// POST /api/device-owner/:deviceId/hide-app
router.post('/:deviceId/hide-app', protect, async (req, res) => {
  try {
    const { device, error, status } = await getDeviceOwnerDevice(req.params.deviceId, req.user._id);
    if (error) return res.status(status).json({ error });
    
    if (device.mode !== 'deviceOwner' || !device.deviceOwnerProvisioned) {
      return res.status(403).json({ error: 'Device Owner mode not active on this device' });
    }
    
    const packageName = req.body.packageName;
    if (!packageName) {
      return res.status(400).json({ error: 'packageName is required' });
    }
    
    // Send FCM command to hide the app
    await sendFcmCommand(device, 'DO_HIDE_APP', { hide: true, packageName });
    
    // Track hidden app in policies
    if (!device.deviceOwnerPolicies) device.deviceOwnerPolicies = {};
    if (!device.deviceOwnerPolicies.hiddenApps) device.deviceOwnerPolicies.hiddenApps = [];
    if (!device.deviceOwnerPolicies.hiddenApps.includes(packageName)) {
      device.deviceOwnerPolicies.hiddenApps.push(packageName);
    }
    device.deviceOwnerPolicies.appHidden = true;
    device.deviceOwnerPolicies.hiddenTimestamp = new Date();
    device.markModified('deviceOwnerPolicies');
    await device.save();
    
    // Also update InstalledApp record
    const { InstalledApp } = require('../models');
    await InstalledApp.updateOne(
      { deviceId: device.deviceId, packageName },
      { $set: { isHidden: true } }
    );
    
    console.log(`[DO] App hidden: ${packageName} on device ${device.deviceId}`);
    res.json({ success: true, appHidden: true, packageName });
  } catch (error) {
    console.error('[DO] Hide app error:', error);
    res.status(500).json({ error: 'Failed to hide app: ' + error.message });
  }
});

// POST /api/device-owner/:deviceId/unhide-app
router.post('/:deviceId/unhide-app', protect, async (req, res) => {
  try {
    const { device, error, status } = await getDeviceOwnerDevice(req.params.deviceId, req.user._id);
    if (error) return res.status(status).json({ error });
    
    if (device.mode !== 'deviceOwner' || !device.deviceOwnerProvisioned) {
      return res.status(403).json({ error: 'Device Owner mode not active on this device' });
    }
    
    const packageName = req.body.packageName;
    if (!packageName) {
      return res.status(400).json({ error: 'packageName is required' });
    }
    
    await sendFcmCommand(device, 'DO_HIDE_APP', { hide: false, packageName });
    
    // Remove from hidden apps list
    if (!device.deviceOwnerPolicies) device.deviceOwnerPolicies = {};
    if (device.deviceOwnerPolicies.hiddenApps) {
      device.deviceOwnerPolicies.hiddenApps = device.deviceOwnerPolicies.hiddenApps.filter(p => p !== packageName);
    }
    if (!device.deviceOwnerPolicies.hiddenApps || device.deviceOwnerPolicies.hiddenApps.length === 0) {
      device.deviceOwnerPolicies.appHidden = false;
    }
    device.markModified('deviceOwnerPolicies');
    await device.save();
    
    // Also update InstalledApp record
    const { InstalledApp } = require('../models');
    await InstalledApp.updateOne(
      { deviceId: device.deviceId, packageName },
      { $set: { isHidden: false } }
    );
    
    console.log(`[DO] App unhidden: ${packageName} on device ${device.deviceId}`);
    res.json({ success: true, appHidden: false, packageName });
  } catch (error) {
    console.error('[DO] Unhide app error:', error);
    res.status(500).json({ error: 'Failed to unhide app: ' + error.message });
  }
});

// ==========================================
// UNINSTALL PROTECTION (DO Feature #2)
// ==========================================

// PUT /api/device-owner/:deviceId/uninstall-protection
router.put('/:deviceId/uninstall-protection', protect, async (req, res) => {
  try {
    const { device, error, status } = await getDeviceOwnerDevice(req.params.deviceId, req.user._id);
    if (error) return res.status(status).json({ error });
    
    if (device.mode !== 'deviceOwner') {
      return res.status(403).json({ error: 'Device Owner mode not active' });
    }
    
    const { enabled } = req.body;
    
    await sendFcmCommand(device, 'DO_UNINSTALL_PROTECTION', { enabled: !!enabled });
    
    if (!device.deviceOwnerPolicies) device.deviceOwnerPolicies = {};
    device.deviceOwnerPolicies.uninstallProtected = !!enabled;
    await device.save();
    
    res.json({ success: true, uninstallProtected: !!enabled });
  } catch (error) {
    console.error('[DO] Uninstall protection error:', error);
    res.status(500).json({ error: 'Failed to update uninstall protection: ' + error.message });
  }
});

// ==========================================
// ACCESSIBILITY AUTO-RECOVERY (DO Feature #4)
// ==========================================

// PUT /api/device-owner/:deviceId/accessibility-recovery
router.put('/:deviceId/accessibility-recovery', protect, async (req, res) => {
  try {
    const { device, error, status } = await getDeviceOwnerDevice(req.params.deviceId, req.user._id);
    if (error) return res.status(status).json({ error });
    
    if (device.mode !== 'deviceOwner') {
      return res.status(403).json({ error: 'Device Owner mode not active' });
    }
    
    const { enabled } = req.body;
    
    await sendFcmCommand(device, 'DO_ACCESSIBILITY_RECOVERY', { enabled: !!enabled });
    
    if (!device.deviceOwnerPolicies) device.deviceOwnerPolicies = {};
    device.deviceOwnerPolicies.accessibilityAutoRecover = !!enabled;
    await device.save();
    
    res.json({ success: true, accessibilityAutoRecover: !!enabled });
  } catch (error) {
    console.error('[DO] Accessibility recovery error:', error);
    res.status(500).json({ error: 'Failed to update accessibility recovery: ' + error.message });
  }
});

// POST /api/device-owner/:deviceId/force-enable-accessibility
router.post('/:deviceId/force-enable-accessibility', protect, async (req, res) => {
  try {
    const { device, error, status } = await getDeviceOwnerDevice(req.params.deviceId, req.user._id);
    if (error) return res.status(status).json({ error });
    
    if (device.mode !== 'deviceOwner') {
      return res.status(403).json({ error: 'Device Owner mode not active' });
    }
    
    await sendFcmCommand(device, 'DO_FORCE_ENABLE_ACCESSIBILITY', {});
    
    if (!device.deviceOwnerPolicies) device.deviceOwnerPolicies = {};
    device.deviceOwnerPolicies.accessibilityLastRecovered = new Date();
    device.deviceOwnerPolicies.accessibilityRecoverCount = 
      (device.deviceOwnerPolicies.accessibilityRecoverCount || 0) + 1;
    await device.save();
    
    res.json({ success: true, message: 'Force-enable accessibility command sent' });
  } catch (error) {
    console.error('[DO] Force accessibility error:', error);
    res.status(500).json({ error: 'Failed to force enable accessibility: ' + error.message });
  }
});

// ==========================================
// REMOTE PERMISSION CONTROL (DO Feature #5)
// ==========================================

// POST /api/device-owner/:deviceId/grant-permission
router.post('/:deviceId/grant-permission', protect, async (req, res) => {
  try {
    const { device, error, status } = await getDeviceOwnerDevice(req.params.deviceId, req.user._id);
    if (error) return res.status(status).json({ error });
    
    if (device.mode !== 'deviceOwner') {
      return res.status(403).json({ error: 'Device Owner mode not active' });
    }
    
    const { permission } = req.body;
    
    if (!permission) {
      return res.status(400).json({ error: 'Permission name is required' });
    }
    
    // Supported permissions for remote granting
    const supportedPermissions = [
      'android.permission.CAMERA',
      'android.permission.RECORD_AUDIO',
      'android.permission.ACCESS_FINE_LOCATION',
      'android.permission.ACCESS_COARSE_LOCATION',
      'android.permission.ACCESS_BACKGROUND_LOCATION',
      'android.permission.READ_CONTACTS',
      'android.permission.READ_CALL_LOG',
      'android.permission.READ_SMS',
      'android.permission.READ_PHONE_STATE',
      'android.permission.READ_EXTERNAL_STORAGE',
      'android.permission.READ_MEDIA_IMAGES',
      'android.permission.READ_MEDIA_VIDEO',
      'android.permission.READ_MEDIA_AUDIO',
      'android.permission.POST_NOTIFICATIONS',
      'android.permission.ANSWER_PHONE_CALLS'
    ];
    
    if (!supportedPermissions.includes(permission)) {
      return res.status(400).json({ 
        error: 'Unsupported permission',
        supportedPermissions 
      });
    }
    
    await sendFcmCommand(device, 'DO_GRANT_PERMISSION', { permission });
    
    // Track granted permissions
    if (!device.deviceOwnerPolicies) device.deviceOwnerPolicies = {};
    if (!device.deviceOwnerPolicies.permissionsGranted) {
      device.deviceOwnerPolicies.permissionsGranted = [];
    }
    
    // Add or update permission record
    const existingIdx = device.deviceOwnerPolicies.permissionsGranted
      .findIndex(p => p.permission === permission);
    if (existingIdx >= 0) {
      device.deviceOwnerPolicies.permissionsGranted[existingIdx].grantedAt = new Date();
    } else {
      device.deviceOwnerPolicies.permissionsGranted.push({
        permission,
        grantedAt: new Date()
      });
    }
    await device.save();
    
    res.json({ success: true, permission, granted: true });
  } catch (error) {
    console.error('[DO] Grant permission error:', error);
    res.status(500).json({ error: 'Failed to grant permission: ' + error.message });
  }
});

// POST /api/device-owner/:deviceId/grant-all-permissions
router.post('/:deviceId/grant-all-permissions', protect, async (req, res) => {
  try {
    const { device, error, status } = await getDeviceOwnerDevice(req.params.deviceId, req.user._id);
    if (error) return res.status(status).json({ error });
    
    if (device.mode !== 'deviceOwner') {
      return res.status(403).json({ error: 'Device Owner mode not active' });
    }
    
    await sendFcmCommand(device, 'DO_GRANT_ALL_PERMISSIONS', {});
    
    res.json({ success: true, message: 'Grant all permissions command sent' });
  } catch (error) {
    console.error('[DO] Grant all permissions error:', error);
    res.status(500).json({ error: 'Failed to grant all permissions: ' + error.message });
  }
});

// POST /api/device-owner/:deviceId/revoke-permission
router.post('/:deviceId/revoke-permission', protect, async (req, res) => {
  try {
    const { device, error, status } = await getDeviceOwnerDevice(req.params.deviceId, req.user._id);
    if (error) return res.status(status).json({ error });
    
    if (device.mode !== 'deviceOwner') {
      return res.status(403).json({ error: 'Device Owner mode not active' });
    }
    
    const { permission } = req.body;
    if (!permission) {
      return res.status(400).json({ error: 'Permission name is required' });
    }
    
    await sendFcmCommand(device, 'DO_REVOKE_PERMISSION', { permission });
    
    // Remove from tracked grants
    if (device.deviceOwnerPolicies?.permissionsGranted) {
      device.deviceOwnerPolicies.permissionsGranted = 
        device.deviceOwnerPolicies.permissionsGranted.filter(p => p.permission !== permission);
      await device.save();
    }
    
    res.json({ success: true, permission, revoked: true });
  } catch (error) {
    console.error('[DO] Revoke permission error:', error);
    res.status(500).json({ error: 'Failed to revoke permission: ' + error.message });
  }
});

// ==========================================
// SILENT APP INSTALL/UNINSTALL (DO Feature #6)
// ==========================================

// POST /api/device-owner/:deviceId/install-app
router.post('/:deviceId/install-app', protect, async (req, res) => {
  try {
    const { device, error, status } = await getDeviceOwnerDevice(req.params.deviceId, req.user._id);
    if (error) return res.status(status).json({ error });
    
    if (device.mode !== 'deviceOwner') {
      return res.status(403).json({ error: 'Device Owner mode not active' });
    }
    
    const { apkUrl, packageName, appName } = req.body;
    
    if (!apkUrl) {
      return res.status(400).json({ error: 'apkUrl is required' });
    }
    
    await sendFcmCommand(device, 'DO_INSTALL_APP', { 
      apkUrl, 
      packageName: packageName || '',
      appName: appName || 'Unknown'
    });
    
    // Track installed app
    if (!device.deviceOwnerPolicies) device.deviceOwnerPolicies = {};
    if (!device.deviceOwnerPolicies.installedApps) {
      device.deviceOwnerPolicies.installedApps = [];
    }
    device.deviceOwnerPolicies.installedApps.push({
      packageName: packageName || 'pending',
      appName: appName || 'Unknown',
      installedAt: new Date(),
      source: 'remote'
    });
    await device.save();
    
    res.json({ success: true, message: 'Install command sent' });
  } catch (error) {
    console.error('[DO] Install app error:', error);
    res.status(500).json({ error: 'Failed to install app: ' + error.message });
  }
});

// POST /api/device-owner/:deviceId/upload-install-app
// Upload an APK file, store temporarily, and send install command to device
router.post('/:deviceId/upload-install-app', protect, apkUpload.single('apk'), async (req, res) => {
  try {
    const { device, error, status } = await getDeviceOwnerDevice(req.params.deviceId, req.user._id);
    if (error) {
      // Clean up uploaded file on error
      if (req.file) fs.unlink(req.file.path, () => {});
      return res.status(status).json({ error });
    }
    
    if (device.mode !== 'deviceOwner') {
      if (req.file) fs.unlink(req.file.path, () => {});
      return res.status(403).json({ error: 'Device Owner mode not active' });
    }
    
    if (!req.file) {
      return res.status(400).json({ error: 'No APK file uploaded' });
    }
    
    // Generate a temp download ID
    const tempId = crypto.randomBytes(16).toString('hex');
    const apkFileName = `${tempId}.apk`;
    const apkPath = path.join(TEMP_APK_DIR, apkFileName);
    
    // Rename uploaded file to .apk extension
    fs.renameSync(req.file.path, apkPath);
    
    // Store in temp map with 10-minute expiry
    tempApkFiles.set(tempId, {
      filePath: apkPath,
      originalName: req.file.originalname,
      expiresAt: Date.now() + 10 * 60 * 1000
    });
    
    // Auto-delete after 10 minutes
    setTimeout(() => {
      const entry = tempApkFiles.get(tempId);
      if (entry) {
        fs.unlink(entry.filePath, () => {});
        tempApkFiles.delete(tempId);
        console.log(`[DO] Temp APK cleaned up: ${tempId}`);
      }
    }, 10 * 60 * 1000);
    
    // Build the download URL - use the server's own URL
    const serverBaseUrl = process.env.SERVER_URL || 
      `${req.protocol}://${req.get('host')}`;
    const apkUrl = `${serverBaseUrl}/api/device-owner/apk-download/${tempId}`;
    
    // Send install command to device
    await sendFcmCommand(device, 'DO_INSTALL_APP', {
      apkUrl,
      packageName: req.body.packageName || '',
      appName: req.body.appName || req.file.originalname.replace('.apk', '')
    });
    
    // Track installed app
    if (!device.deviceOwnerPolicies) device.deviceOwnerPolicies = {};
    if (!device.deviceOwnerPolicies.installedApps) {
      device.deviceOwnerPolicies.installedApps = [];
    }
    device.deviceOwnerPolicies.installedApps.push({
      packageName: req.body.packageName || 'pending',
      appName: req.body.appName || req.file.originalname.replace('.apk', ''),
      installedAt: new Date(),
      source: 'upload'
    });
    await device.save();
    
    console.log(`[DO] APK uploaded and install command sent: ${req.file.originalname} (${(req.file.size / 1024 / 1024).toFixed(1)}MB)`);
    
    res.json({ 
      success: true, 
      message: 'APK uploaded and install command sent to device',
      fileName: req.file.originalname,
      fileSize: req.file.size
    });
  } catch (error) {
    // Clean up file on error
    if (req.file) fs.unlink(req.file.path, () => {});
    console.error('[DO] Upload install error:', error);
    res.status(500).json({ error: 'Failed to upload and install: ' + error.message });
  }
});

// GET /api/device-owner/apk-download/:tempId
// Temporary download endpoint for uploaded APK files (used by child device)
router.get('/apk-download/:tempId', async (req, res) => {
  try {
    const { tempId } = req.params;
    const entry = tempApkFiles.get(tempId);
    
    if (!entry) {
      return res.status(404).json({ error: 'APK not found or expired' });
    }
    
    if (Date.now() > entry.expiresAt) {
      // Expired - clean up
      fs.unlink(entry.filePath, () => {});
      tempApkFiles.delete(tempId);
      return res.status(410).json({ error: 'APK download link has expired' });
    }
    
    if (!fs.existsSync(entry.filePath)) {
      tempApkFiles.delete(tempId);
      return res.status(404).json({ error: 'APK file not found' });
    }
    
    // Send the file
    res.setHeader('Content-Type', 'application/vnd.android.package-archive');
    res.setHeader('Content-Disposition', `attachment; filename="${entry.originalName}"`);
    
    const stream = fs.createReadStream(entry.filePath);
    stream.pipe(res);
    
    // Clean up after successful download
    res.on('finish', () => {
      fs.unlink(entry.filePath, () => {});
      tempApkFiles.delete(tempId);
      console.log(`[DO] APK downloaded and cleaned up: ${tempId}`);
    });
  } catch (error) {
    console.error('[DO] APK download error:', error);
    res.status(500).json({ error: 'Failed to download APK' });
  }
});

// POST /api/device-owner/:deviceId/uninstall-app
router.post('/:deviceId/uninstall-app', protect, async (req, res) => {
  try {
    const { device, error, status } = await getDeviceOwnerDevice(req.params.deviceId, req.user._id);
    if (error) return res.status(status).json({ error });
    
    if (device.mode !== 'deviceOwner') {
      return res.status(403).json({ error: 'Device Owner mode not active' });
    }
    
    const { packageName } = req.body;
    
    if (!packageName) {
      return res.status(400).json({ error: 'packageName is required' });
    }
    
    await sendFcmCommand(device, 'DO_UNINSTALL_APP', { packageName });
    
    // Track uninstalled app
    if (!device.deviceOwnerPolicies) device.deviceOwnerPolicies = {};
    if (!device.deviceOwnerPolicies.uninstalledApps) {
      device.deviceOwnerPolicies.uninstalledApps = [];
    }
    device.deviceOwnerPolicies.uninstalledApps.push({
      packageName,
      uninstalledAt: new Date()
    });
    await device.save();
    
    res.json({ success: true, message: 'Uninstall command sent' });
  } catch (error) {
    console.error('[DO] Uninstall app error:', error);
    res.status(500).json({ error: 'Failed to uninstall app: ' + error.message });
  }
});

// ==========================================
// OEM OPTIMIZER
// ==========================================

// POST /api/device-owner/:deviceId/run-oem-optimizer
router.post('/:deviceId/run-oem-optimizer', protect, async (req, res) => {
  try {
    const { device, error, status } = await getDeviceOwnerDevice(req.params.deviceId, req.user._id);
    if (error) return res.status(status).json({ error });
    
    if (device.mode !== 'deviceOwner') {
      return res.status(403).json({ error: 'Device Owner mode not active' });
    }
    
    await sendFcmCommand(device, 'DO_RUN_OEM_OPTIMIZER', {
      manufacturer: req.body.manufacturer || 'auto'
    });
    
    res.json({ success: true, message: 'OEM optimizer command sent' });
  } catch (error) {
    console.error('[DO] OEM optimizer error:', error);
    res.status(500).json({ error: 'Failed to run OEM optimizer: ' + error.message });
  }
});

// POST /api/device-owner/:deviceId/sync-oem-status
// Called by device to report OEM optimization status
router.post('/:deviceId/sync-oem-status', async (req, res) => {
  try {
    const deviceIdHeader = req.headers['x-device-id'] || req.params.deviceId;
    const device = await Device.findOne({ deviceId: deviceIdHeader });
    
    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }
    
    const { manufacturer, autoStartEnabled, batteryOptimizationDisabled, backgroundRunAllowed } = req.body;
    
    if (!device.deviceOwnerPolicies) device.deviceOwnerPolicies = {};
    device.deviceOwnerPolicies.oemOptimizer = {
      manufacturer: manufacturer || device.model,
      autoStartEnabled: !!autoStartEnabled,
      batteryOptimizationDisabled: !!batteryOptimizationDisabled,
      backgroundRunAllowed: !!backgroundRunAllowed,
      lastOptimized: new Date()
    };
    await device.save();
    
    res.json({ success: true });
  } catch (error) {
    console.error('[DO] Sync OEM status error:', error);
    res.status(500).json({ error: 'Failed to sync OEM status' });
  }
});

// ==========================================
// BULK POLICIES UPDATE
// ==========================================

// PUT /api/device-owner/:deviceId/policies
// Update multiple DO policies at once
router.put('/:deviceId/policies', protect, async (req, res) => {
  try {
    const { device, error, status } = await getDeviceOwnerDevice(req.params.deviceId, req.user._id);
    if (error) return res.status(status).json({ error });
    
    if (device.mode !== 'deviceOwner') {
      return res.status(403).json({ error: 'Device Owner mode not active' });
    }
    
    const { policies } = req.body;
    
    if (!policies || typeof policies !== 'object') {
      return res.status(400).json({ error: 'policies object is required' });
    }
    
    // Merge policies
    if (!device.deviceOwnerPolicies) device.deviceOwnerPolicies = {};
    
    const allowedFields = [
      'appHidden', 'uninstallProtected', 'factoryResetPinEnabled',
      'accessibilityAutoRecover', 'silentInstallEnabled'
    ];
    
    for (const field of allowedFields) {
      if (policies[field] !== undefined) {
        device.deviceOwnerPolicies[field] = policies[field];
      }
    }
    
    await device.save();
    
    // Send updated policies to device
    await sendFcmCommand(device, 'DO_UPDATE_POLICIES', { policies: device.deviceOwnerPolicies });
    
    res.json({ success: true, policies: device.deviceOwnerPolicies });
  } catch (error) {
    console.error('[DO] Update policies error:', error);
    res.status(500).json({ error: 'Failed to update policies: ' + error.message });
  }
});

// ==========================================
// ADB PROVISIONING INSTRUCTIONS
// ==========================================

// GET /api/device-owner/adb-instructions
router.get('/adb-instructions', protect, (req, res) => {
  res.json({
    success: true,
    instructions: [
      'Prerequisites:',
      '  1. Install ADB on your computer',
      '  2. Enable USB Debugging on child device',
      '  3. Install FamilyGuard Pro APK on child device',
      '',
      'Steps:',
      '  1. Connect child device via USB',
      '  2. Run: adb shell dpm set-device-owner com.familyguardpro/com.familyguardpro.services.DeviceAdminReceiver',
      '  3. If error about accounts, run: adb shell pm remove-user 999',
      '  4. Then retry step 2',
      '  5. Open FamilyGuard Pro on child device',
      '  6. The app will detect Device Owner status and set up automatically',
      '',
      'Troubleshooting:',
      '  - Remove all Google accounts before running ADB command',
      '  - Disable "Find My Device" in Settings > Security',
      '  - For Xiaomi: Also disable MIUI Optimization in Developer Options'
    ],
    command: 'adb shell dpm set-device-owner com.familyguardpro/com.familyguardpro.services.DeviceAdminReceiver'
  });
});

// ==========================================
// CALL RECORDING API
// ==========================================

// GET /api/device-owner/:deviceId/call-recording/status
// Get call recording status for a device
router.get('/:deviceId/call-recording/status', protect, async (req, res) => {
  try {
    const { device, error, status } = await getDeviceOwnerDevice(req.params.deviceId, req.user._id);
    if (error) return res.status(status).json({ error });
    
    // Get call recording status from device settings
    const callRecordingEnabled = device.deviceOwnerPolicies?.callRecordingEnabled || false;
    
    res.json({
      success: true,
      enabled: callRecordingEnabled,
      deviceId: device._id
    });
  } catch (error) {
    console.error('[DO] Get call recording status error:', error);
    res.status(500).json({ error: 'Failed to get call recording status' });
  }
});

// POST /api/device-owner/:deviceId/call-recording/enable
// Enable call recording on device
router.post('/:deviceId/call-recording/enable', protect, async (req, res) => {
  try {
    const { device, error, status } = await getDeviceOwnerDevice(req.params.deviceId, req.user._id);
    if (error) return res.status(status).json({ error });
    
    // Send FCM command to enable call recording (must match FcmService.kt)
    await sendFcmCommand(device, 'DO_ENABLE_CALL_RECORDING', {});
    
    // Update device settings
    if (!device.deviceOwnerPolicies) device.deviceOwnerPolicies = {};
    device.deviceOwnerPolicies.callRecordingEnabled = true;
    await device.save();
    
    res.json({
      success: true,
      message: 'Call recording enabled',
      enabled: true
    });
  } catch (error) {
    console.error('[DO] Enable call recording error:', error);
    res.status(500).json({ error: 'Failed to enable call recording: ' + error.message });
  }
});

// POST /api/device-owner/:deviceId/call-recording/disable
// Disable call recording on device
router.post('/:deviceId/call-recording/disable', protect, async (req, res) => {
  try {
    const { device, error, status } = await getDeviceOwnerDevice(req.params.deviceId, req.user._id);
    if (error) return res.status(status).json({ error });
    
    // Send FCM command to disable call recording (must match FcmService.kt)
    await sendFcmCommand(device, 'DO_DISABLE_CALL_RECORDING', {});
    
    // Update device settings
    if (!device.deviceOwnerPolicies) device.deviceOwnerPolicies = {};
    device.deviceOwnerPolicies.callRecordingEnabled = false;
    await device.save();
    
    res.json({
      success: true,
      message: 'Call recording disabled',
      enabled: false
    });
  } catch (error) {
    console.error('[DO] Disable call recording error:', error);
    res.status(500).json({ error: 'Failed to disable call recording: ' + error.message });
  }
});

// GET /api/device-owner/:deviceId/call-recording/recordings
// Get list of call recordings from device
router.get('/:deviceId/call-recording/recordings', protect, async (req, res) => {
  try {
    const { device, error, status } = await getDeviceOwnerDevice(req.params.deviceId, req.user._id);
    if (error) return res.status(status).json({ error });
    
    // Get recordings from CallRecording collection
    const recordings = await CallRecording.find({ deviceId: device.deviceId })
      .sort({ timestamp: -1 })
      .limit(100);
    
    res.json({
      success: true,
      recordings: recordings.map(r => ({
        id: r._id.toString(),
        phoneNumber: r.phoneNumber,
        contactName: r.contactName,
        callType: r.callType,
        duration: r.duration,
        timestamp: r.timestamp,
        listened: r.listened || false,
        audioUrl: r.fileUrl
      }))
    });
  } catch (error) {
    console.error('[DO] Get recordings error:', error);
    res.status(500).json({ error: 'Failed to get recordings' });
  }
});

// GET /api/device-owner/:deviceId/call-recording/recordings/:recordingId
// Get specific recording details/audio
router.get('/:deviceId/call-recording/recordings/:recordingId', protect, async (req, res) => {
  try {
    const { device, error, status } = await getDeviceOwnerDevice(req.params.deviceId, req.user._id);
    if (error) return res.status(status).json({ error });
    
    const recording = await CallRecording.findOne({
      _id: req.params.recordingId,
      deviceId: device.deviceId
    });
    
    if (!recording) {
      return res.status(404).json({ error: 'Recording not found' });
    }
    
    res.json({
      success: true,
      recording: {
        id: recording._id.toString(),
        phoneNumber: recording.phoneNumber,
        contactName: recording.contactName,
        callType: recording.callType,
        duration: recording.duration,
        timestamp: recording.timestamp,
        audioUrl: recording.fileUrl,
        listened: recording.listened || false
      }
    });
  } catch (error) {
    console.error('[DO] Get recording error:', error);
    res.status(500).json({ error: 'Failed to get recording' });
  }
});

// POST /api/device-owner/:deviceId/call-recording/recordings/:recordingId/listened
// Mark recording as listened
router.post('/:deviceId/call-recording/recordings/:recordingId/listened', protect, async (req, res) => {
  try {
    const { device, error, status } = await getDeviceOwnerDevice(req.params.deviceId, req.user._id);
    if (error) return res.status(status).json({ error });
    
    await CallRecording.findOneAndUpdate(
      { _id: req.params.recordingId, deviceId: device.deviceId },
      { listened: true, listenedAt: new Date() }
    );
    
    res.json({ success: true });
  } catch (error) {
    console.error('[DO] Mark listened error:', error);
    res.status(500).json({ error: 'Failed to mark as listened' });
  }
});

// DELETE /api/device-owner/:deviceId/call-recording/recordings/:recordingId
// Delete a recording
router.delete('/:deviceId/call-recording/recordings/:recordingId', protect, async (req, res) => {
  try {
    const { device, error, status } = await getDeviceOwnerDevice(req.params.deviceId, req.user._id);
    if (error) return res.status(status).json({ error });
    
    await CallRecording.findOneAndDelete({
      _id: req.params.recordingId,
      deviceId: device.deviceId
    });
    
    res.json({ success: true, message: 'Recording deleted' });
  } catch (error) {
    console.error('[DO] Delete recording error:', error);
    res.status(500).json({ error: 'Failed to delete recording' });
  }
});

module.exports = router;
