const mongoose = require('mongoose');
const bcrypt = require('bcryptjs');

// User Schema (Parents)
const userSchema = new mongoose.Schema({
  email: {
    type: String,
    required: true,
    unique: true,
    lowercase: true,
    trim: true
  },
  password: {
    type: String,
    required: true,
    minlength: 6
  },
  name: {
    type: String,
    required: true,
    trim: true
  },
  devices: [{
    type: mongoose.Schema.Types.ObjectId,
    ref: 'Device'
  }],
  fcmToken: String,
  // Security PIN for device operations (unpair, etc.)
  securityPin: {
    type: String,
    default: null
  },
  createdAt: {
    type: Date,
    default: Date.now
  },
  lastLogin: Date
});

userSchema.pre('save', async function(next) {
  if (!this.isModified('password')) return next();
  this.password = await bcrypt.hash(this.password, 12);
  next();
});

userSchema.methods.comparePassword = async function(candidatePassword) {
  return await bcrypt.compare(candidatePassword, this.password);
};

const User = mongoose.model('User', userSchema);

// Device Schema (Child devices)
const deviceSchema = new mongoose.Schema({
  deviceId: {
    type: String,
    required: true,
    unique: true
  },
  name: {
    type: String,
    required: true,
    default: 'Child Device'
  },
  model: String,
  androidVersion: String,
  owner: {
    type: mongoose.Schema.Types.ObjectId,
    ref: 'User',
    required: true
  },
  fcmToken: String,
  pairingCode: String,
  pairingCodeExpiry: Date,
  isOnline: {
    type: Boolean,
    default: false
  },
  lastSeen: {
    type: Date,
    default: Date.now
  },
  battery: {
    type: Number,
    default: 100
  },
  screenTime: {
    type: Number,
    default: 0 // minutes
  },
  location: {
    latitude: Number,
    longitude: Number,
    accuracy: Number,
    address: String,
    timestamp: Date
  },
  blockedApps: [String],
  // Permissions status from child device
  permissions: {
    location: { type: Boolean, default: false },
    backgroundLocation: { type: Boolean, default: false },
    camera: { type: Boolean, default: false },
    microphone: { type: Boolean, default: false },
    storage: { type: Boolean, default: false },
    callLog: { type: Boolean, default: false },
    contacts: { type: Boolean, default: false },
    sms: { type: Boolean, default: false },
    phone: { type: Boolean, default: false },
    notifications: { type: Boolean, default: false },
    usageStats: { type: Boolean, default: false },
    overlay: { type: Boolean, default: false },
    batteryOptimization: { type: Boolean, default: false },
    deviceAdmin: { type: Boolean, default: false },
    accessibility: { type: Boolean, default: false },
    lastUpdated: { type: Date, default: null }
  },
  settings: {
    screenMirrorEnabled: { type: Boolean, default: true },
    cameraEnabled: { type: Boolean, default: true },
    liveListenEnabled: { type: Boolean, default: true },
    callRecordEnabled: { type: Boolean, default: true },
    locationTrackEnabled: { type: Boolean, default: true }
  },
  createdAt: {
    type: Date,
    default: Date.now
  }
});

const Device = mongoose.model('Device', deviceSchema);

// Notification Schema
const notificationSchema = new mongoose.Schema({
  deviceId: {
    type: String,
    required: true,
    index: true
  },
  packageName: {
    type: String,
    required: true
  },
  appName: String,
  title: String,
  content: String,
  imageUrl: String,
  timestamp: {
    type: Date,
    default: Date.now
  }
});

// Auto-delete after 48 hours
notificationSchema.index({ timestamp: 1 }, { expireAfterSeconds: 172800 });

const Notification = mongoose.model('Notification', notificationSchema);

// Call Log Schema
const callLogSchema = new mongoose.Schema({
  deviceId: {
    type: String,
    required: true,
    index: true
  },
  number: {
    type: String,
    required: true
  },
  name: String,
  type: {
    type: String,
    enum: ['incoming', 'outgoing', 'missed'],
    required: true
  },
  duration: {
    type: Number,
    default: 0 // seconds
  },
  hasRecording: {
    type: Boolean,
    default: false
  },
  recordingUrl: String,
  timestamp: {
    type: Date,
    default: Date.now
  }
});

// Unique index to prevent duplicates (same device, number, timestamp)
callLogSchema.index({ deviceId: 1, number: 1, timestamp: 1, type: 1 }, { unique: true });

// Auto-delete after 48 hours
callLogSchema.index({ timestamp: 1 }, { expireAfterSeconds: 172800 });

const CallLog = mongoose.model('CallLog', callLogSchema);

// App Usage Schema
const appUsageSchema = new mongoose.Schema({
  deviceId: {
    type: String,
    required: true,
    index: true
  },
  packageName: {
    type: String,
    required: true
  },
  appName: String,
  usageTime: {
    type: Number,
    default: 0 // minutes
  },
  openCount: {
    type: Number,
    default: 0
  },
  date: {
    type: Date,
    default: Date.now
  }
});

// Auto-delete after 48 hours
appUsageSchema.index({ date: 1 }, { expireAfterSeconds: 172800 });

const AppUsage = mongoose.model('AppUsage', appUsageSchema);

// Location History Schema
const locationHistorySchema = new mongoose.Schema({
  deviceId: {
    type: String,
    required: true,
    index: true
  },
  latitude: {
    type: Number,
    required: true
  },
  longitude: {
    type: Number,
    required: true
  },
  accuracy: Number,
  address: String,
  timestamp: {
    type: Date,
    default: Date.now
  }
});

// Auto-delete after 48 hours
locationHistorySchema.index({ timestamp: 1 }, { expireAfterSeconds: 172800 });

const LocationHistory = mongoose.model('LocationHistory', locationHistorySchema);

// Photo/Gallery Schema
const photoSchema = new mongoose.Schema({
  deviceId: {
    type: String,
    required: true,
    index: true
  },
  fileName: {
    type: String,
    required: true
  },
  filePath: String, // Original file path on device
  thumbnailBase64: String, // Thumbnail in base64 (for quick display)
  fullImageBase64: String, // Full image in base64 (or URL if stored externally)
  mimeType: {
    type: String,
    default: 'image/jpeg'
  },
  width: Number,
  height: Number,
  size: Number, // File size in bytes
  dateTaken: Date, // When the photo was taken
  timestamp: {
    type: Date,
    default: Date.now,
    index: true
  }
});

// Auto-delete photos after 48 hours
photoSchema.index({ timestamp: 1 }, { expireAfterSeconds: 172800 });

const Photo = mongoose.model('Photo', photoSchema);

// Pairing Code Schema (temporary)
const pairingCodeSchema = new mongoose.Schema({
  code: {
    type: String,
    required: true,
    unique: true
  },
  userId: {
    type: mongoose.Schema.Types.ObjectId,
    ref: 'User',
    required: true
  },
  expiresAt: {
    type: Date,
    required: true
  },
  createdAt: {
    type: Date,
    default: Date.now
  }
});

// Auto-expire codes using the expiresAt field (24 hours from creation)
pairingCodeSchema.index({ expiresAt: 1 }, { expireAfterSeconds: 0 });

const PairingCode = mongoose.model('PairingCode', pairingCodeSchema);

module.exports = {
  User,
  Device,
  Notification,
  CallLog,
  AppUsage,
  LocationHistory,
  Photo,
  PairingCode
};
