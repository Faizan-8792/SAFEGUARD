const express = require('express');
const jwt = require('jsonwebtoken');
const { User, Device, PairingCode } = require('../models');
const { sendError } = require('../utils/errorCodes');

const router = express.Router();

// Email validation regex
const isValidEmail = (email) => {
  const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
  return emailRegex.test(email);
};

// Generate JWT token
const signToken = (id) => {
  return jwt.sign({ id }, process.env.JWT_SECRET, {
    expiresIn: process.env.JWT_EXPIRES_IN || '30d'
  });
};

// Middleware to protect routes
const protect = async (req, res, next) => {
  try {
    let token;
    if (req.headers.authorization && req.headers.authorization.startsWith('Bearer')) {
      token = req.headers.authorization.split(' ')[1];
    }

    if (!token) {
      return sendError(res, 'AUTH_TOKEN_MISSING');
    }

    try {
      const decoded = jwt.verify(token, process.env.JWT_SECRET);
      const user = await User.findById(decoded.id);

      if (!user) {
        return sendError(res, 'AUTH_USER_NOT_FOUND');
      }

      req.user = user;
      next();
    } catch (jwtError) {
      if (jwtError.name === 'TokenExpiredError') {
        return sendError(res, 'AUTH_TOKEN_EXPIRED');
      }
      return sendError(res, 'AUTH_TOKEN_INVALID');
    }
  } catch (error) {
    return sendError(res, 'SERVER_ERROR');
  }
};

// Register new parent account
router.post('/register', async (req, res) => {
  try {
    const { email, password, name } = req.body;

    // Validate required fields
    if (!email || !password || !name) {
      return sendError(res, 'AUTH_MISSING_FIELDS');
    }

    // Validate email format
    if (!isValidEmail(email)) {
      return sendError(res, 'AUTH_INVALID_EMAIL');
    }

    // Validate password strength
    if (password.length < 6) {
      return sendError(res, 'AUTH_WEAK_PASSWORD');
    }

    // Check if email already exists
    const existingUser = await User.findOne({ email: email.toLowerCase() });
    if (existingUser) {
      return sendError(res, 'AUTH_EMAIL_EXISTS');
    }

    // Create user
    const user = await User.create({ 
      email: email.toLowerCase(), 
      password, 
      name: name.trim() 
    });
    
    const token = signToken(user._id);

    res.status(201).json({
      success: true,
      token,
      user: {
        id: user._id,
        email: user.email,
        name: user.name
      }
    });
  } catch (error) {
    console.error('Register error:', error);
    return sendError(res, 'SERVER_ERROR', 'Registration failed. Please try again');
  }
});

// Login parent
router.post('/login', async (req, res) => {
  try {
    const { email, password } = req.body;

    // Validate required fields
    if (!email || !password) {
      return sendError(res, 'AUTH_MISSING_LOGIN_FIELDS');
    }

    // Validate email format
    if (!isValidEmail(email)) {
      return sendError(res, 'AUTH_INVALID_EMAIL');
    }

    // Find user
    const user = await User.findOne({ email: email.toLowerCase() });
    
    if (!user) {
      return sendError(res, 'AUTH_USER_NOT_FOUND');
    }

    // Check password
    const isPasswordValid = await user.comparePassword(password);
    if (!isPasswordValid) {
      return sendError(res, 'AUTH_WRONG_PASSWORD');
    }

    user.lastLogin = new Date();
    await user.save();

    const token = signToken(user._id);

    res.json({
      success: true,
      token,
      user: {
        id: user._id,
        email: user.email,
        name: user.name
      }
    });
  } catch (error) {
    console.error('Login error:', error);
    return sendError(res, 'SERVER_ERROR', 'Login failed. Please try again');
  }
});

// Update FCM token (parent)
router.post('/fcm-token', protect, async (req, res) => {
  try {
    const { fcmToken } = req.body;
    req.user.fcmToken = fcmToken;
    await req.user.save();
    res.json({ success: true });
  } catch (error) {
    return sendError(res, 'SERVER_ERROR', 'Failed to update notification settings');
  }
});

// Generate pairing code for adding a child device
router.post('/pairing-code', protect, async (req, res) => {
  try {
    // Generate 6-character alphanumeric code
    const code = Math.random().toString(36).substring(2, 8).toUpperCase();
    
    // Set expiry to 24 hours
    const expiresAt = new Date(Date.now() + 24 * 60 * 60 * 1000);
    
    await PairingCode.create({
      code,
      userId: req.user._id,
      expiresAt
    });

    res.json({
      success: true,
      code,
      expiresAt: expiresAt.toISOString(),
      expiresIn: 24 * 60 * 60  // seconds (24 hours)
    });
  } catch (error) {
    console.error('Pairing code error:', error);
    return sendError(res, 'SERVER_ERROR', 'Failed to generate pairing code. Please try again');
  }
});

// Verify pairing code and register child device
router.post('/pair-device', async (req, res) => {
  try {
    const { code, deviceId, name, model, androidVersion, fcmToken } = req.body;

    // Validate required fields
    if (!code) {
      return sendError(res, 'PAIR_CODE_REQUIRED');
    }

    if (!deviceId) {
      return sendError(res, 'PAIR_DEVICE_ID_REQUIRED');
    }

    // Find pairing code
    const pairingCode = await PairingCode.findOne({ 
      code: code.toUpperCase().trim() 
    });

    if (!pairingCode) {
      return sendError(res, 'PAIR_CODE_INVALID');
    }

    // Check if code is expired
    if (pairingCode.expiresAt && pairingCode.expiresAt < new Date()) {
      await PairingCode.deleteOne({ _id: pairingCode._id });
      return sendError(res, 'PAIR_CODE_EXPIRED');
    }

    // Check if device already exists
    let device = await Device.findOne({ deviceId });
    if (device) {
      // Update existing device
      device.owner = pairingCode.userId;
      device.name = name || device.name;
      device.model = model || device.model;
      device.androidVersion = androidVersion;
      device.fcmToken = fcmToken;
      device.isOnline = true;
      device.lastSeen = new Date();
    } else {
      // Create new device
      device = await Device.create({
        deviceId,
        name: name || 'Child Device',
        model: model || 'Unknown',
        androidVersion: androidVersion || 'Unknown',
        owner: pairingCode.userId,
        fcmToken,
        isOnline: true
      });
    }

    await device.save();

    // Add device to user's devices list
    await User.findByIdAndUpdate(pairingCode.userId, {
      $addToSet: { devices: device._id }
    });

    // Delete the used pairing code
    await PairingCode.deleteOne({ _id: pairingCode._id });

    res.json({
      success: true,
      deviceId: device._id.toString(),
      parentId: pairingCode.userId.toString(),
      message: 'Device paired successfully! You can now monitor this device.'
    });
  } catch (error) {
    console.error('Pair device error:', error);
    return sendError(res, 'PAIR_FAILED');
  }
});

// Get current user profile
router.get('/me', protect, async (req, res) => {
  try {
    const user = await User.findById(req.user._id)
      .select('-password')
      .populate('devices');
    
    res.json({
      success: true,
      user
    });
  } catch (error) {
    return sendError(res, 'SERVER_ERROR', 'Failed to load profile');
  }
});

// Update password
router.put('/password', protect, async (req, res) => {
  try {
    const { currentPassword, newPassword } = req.body;

    if (!currentPassword || !newPassword) {
      return res.status(400).json({ error: 'Please provide current and new password' });
    }

    const user = await User.findById(req.user._id);
    if (!(await user.comparePassword(currentPassword))) {
      return res.status(401).json({ error: 'Current password is incorrect' });
    }

    user.password = newPassword;
    await user.save();

    res.json({ success: true, message: 'Password updated successfully' });
  } catch (error) {
    res.status(500).json({ error: 'Failed to update password' });
  }
});

// Set/Update security PIN
router.post('/security-pin', protect, async (req, res) => {
  try {
    const { pin, currentPin } = req.body;

    if (!pin || pin.length < 4 || pin.length > 8) {
      return res.status(400).json({ 
        success: false,
        error: 'PIN must be between 4 and 8 digits' 
      });
    }

    // If user already has a PIN, verify current PIN
    if (req.user.securityPin && req.user.securityPin !== currentPin) {
      return res.status(401).json({ 
        success: false,
        error: 'Current PIN is incorrect' 
      });
    }

    req.user.securityPin = pin;
    await req.user.save();

    res.json({ 
      success: true, 
      message: 'Security PIN updated successfully',
      hasPinSet: true
    });
  } catch (error) {
    console.error('Security PIN error:', error);
    res.status(500).json({ error: 'Failed to update security PIN' });
  }
});

// Verify security PIN
router.post('/verify-pin', protect, async (req, res) => {
  try {
    const { pin } = req.body;

    if (!req.user.securityPin) {
      return res.json({ 
        success: true, 
        valid: true,
        hasPinSet: false,
        message: 'No PIN set' 
      });
    }

    const isValid = req.user.securityPin === pin;

    res.json({ 
      success: true,
      valid: isValid,
      hasPinSet: true,
      message: isValid ? 'PIN verified' : 'Invalid PIN'
    });
  } catch (error) {
    res.status(500).json({ error: 'Failed to verify PIN' });
  }
});

// Remove security PIN
router.delete('/security-pin', protect, async (req, res) => {
  try {
    const { pin } = req.body;

    // Verify current PIN before removing
    if (req.user.securityPin && req.user.securityPin !== pin) {
      return res.status(401).json({ 
        success: false,
        error: 'Current PIN is incorrect' 
      });
    }

    req.user.securityPin = null;
    await req.user.save();

    res.json({ 
      success: true, 
      message: 'Security PIN removed',
      hasPinSet: false
    });
  } catch (error) {
    res.status(500).json({ error: 'Failed to remove security PIN' });
  }
});

// Check if user has PIN set
router.get('/has-pin', protect, async (req, res) => {
  try {
    res.json({ 
      success: true,
      hasPinSet: !!req.user.securityPin
    });
  } catch (error) {
    res.status(500).json({ error: 'Failed to check PIN status' });
  }
});

module.exports = { router, protect };
