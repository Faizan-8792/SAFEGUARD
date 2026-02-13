require('dotenv').config();

// Use Google DNS for MongoDB SRV resolution (fixes local router DNS issues)
const dns = require('dns');
dns.setServers(['8.8.8.8', '8.8.4.4']);

const express = require('express');
const mongoose = require('mongoose');
const cors = require('cors');
const helmet = require('helmet');
const compression = require('compression');
const rateLimit = require('express-rate-limit');
const http = require('http');
const WebSocket = require('ws');
const cron = require('node-cron');
const admin = require('firebase-admin');

const { router: authRoutes } = require('./routes/auth');
const deviceRoutes = require('./routes/devices');
const syncRoutes = require('./routes/sync');
const { Notification, CallLog, AppUsage, LocationHistory, Device, Photo, BrowserHistory } = require('./models');

const app = express();

// Trust proxy for Replit (needed for rate limiter with X-Forwarded-For header)
app.set('trust proxy', 1);

const server = http.createServer(app);

// WebSocket server for streaming - handle both /ws and //ws paths
const wss = new WebSocket.Server({ noServer: true });

// WebRTC signaling sessions
const webrtcSessions = new Map();

// Handle WebSocket upgrade requests
server.on('upgrade', (request, socket, head) => {
  const pathname = request.url.split('?')[0];
  console.log(`[WS Upgrade] Received upgrade request for path: ${pathname}`);
  
  // Accept /ws, //ws, /ws/webrtc paths
  if (pathname === '/ws' || pathname === '//ws' || pathname === '/ws/webrtc' || pathname === '//ws/webrtc') {
    wss.handleUpgrade(request, socket, head, (ws) => {
      wss.emit('connection', ws, request);
    });
  } else {
    console.log(`[WS Upgrade] Rejecting connection for unknown path: ${pathname}`);
    socket.destroy();
  }
});

// Initialize Firebase Admin SDK
let firebaseInitialized = false;
if (process.env.FIREBASE_PROJECT_ID && process.env.FIREBASE_CLIENT_EMAIL && process.env.FIREBASE_PRIVATE_KEY) {
  try {
    // Handle private key format - support multiple formats
    let privateKey = process.env.FIREBASE_PRIVATE_KEY;
    
    // Log original for debugging
    console.log('Original private key length:', privateKey.length);
    
    // Method 1: If it contains literal backslash-n sequences, replace them
    if (privateKey.includes('\\n')) {
      privateKey = privateKey.split('\\n').join('\n');
    }
    
    // Method 2: If it's JSON stringified (has quotes), parse it
    if (privateKey.startsWith('"') && privateKey.endsWith('"')) {
      try {
        privateKey = JSON.parse(privateKey);
      } catch (e) {}
    }
    
    // Ensure proper PEM format
    if (!privateKey.includes('\n') && privateKey.includes('-----BEGIN')) {
      // Key might be all on one line, need to add newlines
      privateKey = privateKey
        .replace('-----BEGIN PRIVATE KEY-----', '-----BEGIN PRIVATE KEY-----\n')
        .replace('-----END PRIVATE KEY-----', '\n-----END PRIVATE KEY-----');
    }
    
    console.log('Processed private key length:', privateKey.length);
    console.log('Private key starts with:', privateKey.substring(0, 35));
    console.log('Private key has newlines:', privateKey.includes('\n'));
    
    admin.initializeApp({
      credential: admin.credential.cert({
        projectId: process.env.FIREBASE_PROJECT_ID,
        clientEmail: process.env.FIREBASE_CLIENT_EMAIL,
        privateKey: privateKey
      })
    });
    firebaseInitialized = true;
    console.log('✅ Firebase Admin initialized successfully');
  } catch (err) {
    console.error('❌ Firebase init error:', err.message);
    console.error('Stack:', err.stack);
  }
} else {
  console.warn('⚠️ Firebase credentials not set - push notifications disabled');
  console.log('FIREBASE_PROJECT_ID:', process.env.FIREBASE_PROJECT_ID ? 'SET' : 'NOT SET');
  console.log('FIREBASE_CLIENT_EMAIL:', process.env.FIREBASE_CLIENT_EMAIL ? 'SET' : 'NOT SET');
  console.log('FIREBASE_PRIVATE_KEY:', process.env.FIREBASE_PRIVATE_KEY ? 'SET' : 'NOT SET');
}

// Export for use in routes
app.set('firebaseInitialized', firebaseInitialized);
const path = require('path');

// Middleware
app.use(helmet({
  contentSecurityPolicy: false // Allow inline scripts for dashboard
}));
app.use(compression());
app.use(cors({
  origin: process.env.CORS_ORIGIN || '*',
  methods: ['GET', 'POST', 'PUT', 'DELETE'],
  allowedHeaders: ['Content-Type', 'Authorization', 'X-Device-ID']
}));
app.use(express.json({ limit: '10mb' }));
app.use(express.urlencoded({ extended: true }));

// Serve parent-web dashboard static files
app.use('/dashboard', express.static(path.join(__dirname, 'parent-web')));

// Serve icon.png at root level for notifications
app.use('/icon.png', express.static(path.join(__dirname, 'parent-web', 'icon.png')));

// Health check endpoint with Firebase status
app.get('/api/health', (req, res) => {
  res.json({
    status: 'ok',
    timestamp: new Date().toISOString(),
    firebase: {
      initialized: admin.apps.length > 0,
      projectId: process.env.FIREBASE_PROJECT_ID ? 'set' : 'not set'
    }
  });
});

// Redirect root to dashboard
app.get('/', (req, res) => {
  res.redirect('/dashboard');
});

// Also serve at root for convenience
app.get('/index.html', (req, res) => {
  res.redirect('/dashboard');
});

// Rate limiting - More lenient for mobile apps
const generalLimiter = rateLimit({
  windowMs: parseInt(process.env.RATE_LIMIT_WINDOW_MS) || 1 * 60 * 1000, // 1 minute window
  max: parseInt(process.env.RATE_LIMIT_MAX_REQUESTS) || 200, // 200 requests per minute
  message: { error: 'Too many requests, please try again later' },
  standardHeaders: true,
  legacyHeaders: false,
  skip: (req) => {
    // Skip rate limiting for sync endpoints (they have their own throttling)
    return req.path.includes('/sync/') || req.path.includes('/social-media/');
  }
});

// Stricter rate limit for auth endpoints only (prevent brute force)
const authLimiter = rateLimit({
  windowMs: 5 * 60 * 1000, // 5 minute window
  max: 20, // 20 login attempts per 5 minutes
  message: { error: 'Too many login attempts, please try again in 5 minutes' },
  standardHeaders: true,
  legacyHeaders: false,
});

// Apply general limiter to all API routes
app.use('/api/', generalLimiter);

// Apply stricter limiter only to auth routes
app.use('/api/auth/login', authLimiter);
app.use('/api/auth/register', authLimiter);

// Direct browser history sync route (MUST be before syncRoutes to take precedence)
app.post('/api/sync/browser-history', async (req, res) => {
  try {
    const deviceIdHeader = req.headers['x-device-id'];
    console.log(`[Browser-History] Received X-Device-ID: ${deviceIdHeader}`);
    
    if (!deviceIdHeader) {
      return res.status(401).json({ error: 'Device ID required' });
    }
    
    // Find device by deviceId
    const device = await Device.findOne({ deviceId: deviceIdHeader });
    if (!device) {
      console.log(`[Browser-History] Device not found for ID: ${deviceIdHeader}`);
      return res.status(404).json({ error: 'Device not registered', success: false });
    }
    
    const { history } = req.body;
    if (!Array.isArray(history)) {
      return res.status(400).json({ error: 'History must be an array' });
    }
    
    if (history.length === 0) {
      return res.json({ success: true, count: 0 });
    }
    
    const docs = history.map(h => ({
      deviceId: device.deviceId,
      url: h.url,
      title: h.title || 'Untitled',
      browser: h.browser || 'Unknown',
      visitCount: h.visitCount || 1,
      visitedAt: h.visitedAt ? new Date(h.visitedAt) : new Date(),
      timestamp: new Date()
    }));
    
    // Use insertMany with ordered: false to skip duplicates
    try {
      await BrowserHistory.insertMany(docs, { ordered: false });
    } catch (err) {
      // Ignore duplicate key errors
      if (err.code !== 11000) {
        console.error('[Browser-History] Insert error:', err);
      }
    }
    
    console.log(`[Browser-History] Device ${device.name} - Synced ${docs.length} history entries`);
    
    res.json({
      success: true,
      count: docs.length
    });
  } catch (error) {
    console.error('[Browser-History] Error:', error);
    res.status(500).json({ error: 'Failed to sync browser history', success: false });
  }
});

// Risk keywords for keystroke analysis
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
  
  for (const keyword of RISK_KEYWORDS.HIGH) {
    if (lowerText.includes(keyword.toLowerCase())) {
      flaggedKeywords.push(keyword);
    }
  }
  if (flaggedKeywords.length > 0) {
    return { riskLevel: 'HIGH', flaggedKeywords, sentiment: 'Negative' };
  }
  
  for (const keyword of RISK_KEYWORDS.MEDIUM) {
    if (lowerText.includes(keyword.toLowerCase())) {
      flaggedKeywords.push(keyword);
    }
  }
  if (flaggedKeywords.length > 0) {
    return { riskLevel: 'MEDIUM', flaggedKeywords, sentiment: 'Negative' };
  }
  
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

// Direct keystroke sync route (MUST be before syncRoutes to take precedence)
app.post('/api/sync/keystrokes', async (req, res) => {
  try {
    const { deviceId, keystrokes } = req.body;
    
    if (!deviceId || !keystrokes || !Array.isArray(keystrokes)) {
      console.log('[Keystrokes] Invalid request:', { deviceId: !!deviceId, keystrokes: Array.isArray(keystrokes) });
      return res.status(400).json({ error: 'deviceId and keystrokes array required' });
    }
    
    console.log(`[Keystrokes] Received ${keystrokes.length} keystrokes from device ${deviceId.substring(0, 8)}...`);
    
    // Verify device exists
    const device = await Device.findOne({ deviceId: deviceId });
    if (!device) {
      console.log(`[Keystrokes] Device not found: ${deviceId}`);
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
      
      const msgTime = new Date(timestamp);
      if (msgTime < session.firstMessageTime) session.firstMessageTime = msgTime;
      if (msgTime > session.lastMessageTime) session.lastMessageTime = msgTime;
    }
    
    // Process and save/update sessions
    let savedCount = 0;
    const KeystrokeSession = require('./models').KeystrokeSession;
    
    for (const [sessionId, sessionData] of sessionMap) {
      try {
        const allText = sessionData.messages.map(m => m.text).join(' ');
        const { riskLevel, flaggedKeywords, sentiment } = analyzeRisk(allText);
        
        const existingSession = await KeystrokeSession.findOne({ sessionId });
        
        if (existingSession) {
          existingSession.messages.push(...sessionData.messages);
          existingSession.messageCount = existingSession.messages.length;
          existingSession.lastMessageTime = sessionData.lastMessageTime;
          
          const combinedText = existingSession.messages.map(m => m.text).join(' ');
          const analysis = analyzeRisk(combinedText);
          existingSession.riskLevel = analysis.riskLevel;
          existingSession.flaggedKeywords = analysis.flaggedKeywords;
          existingSession.sentiment = analysis.sentiment;
          
          await existingSession.save();
        } else {
          await KeystrokeSession.create({
            ...sessionData,
            messageCount: sessionData.messages.length,
            riskLevel,
            flaggedKeywords,
            sentiment
          });
        }
        savedCount++;
      } catch (err) {
        console.error(`[Keystrokes] Error saving session ${sessionId}:`, err.message);
      }
    }
    
    console.log(`[Keystrokes] Device ${device.name} - Saved ${savedCount} sessions`);
    
    res.json({
      success: true,
      sessionsProcessed: savedCount,
      keystrokesReceived: keystrokes.length
    });
  } catch (error) {
    console.error('[Keystrokes] Error:', error);
    res.status(500).json({ error: 'Failed to sync keystrokes', success: false });
  }
});

// Routes
app.use('/api/auth', authRoutes);
app.use('/api/devices', deviceRoutes);
app.use('/api/sync', syncRoutes);

// Social Media Routes
const socialMediaRoutes = require('./routes/social-media');
app.use('/api/social-media', socialMediaRoutes);

// Device Owner Mode Routes
const deviceOwnerRoutes = require('./routes/deviceOwner');
app.use('/api/device-owner', deviceOwnerRoutes);

// ==== ALIAS ROUTES FOR BACKWARD COMPATIBILITY ====


// Redirect /api/notifications to /api/sync/notifications  
app.post('/api/notifications', (req, res, next) => {
  req.url = '/api/sync/notifications';
  next('route');
});
app.use('/api/notifications', syncRoutes);

// Redirect /api/call-logs/:deviceId to /api/devices/:deviceId/call-logs
app.get('/api/call-logs/:deviceId', async (req, res) => {
  const { protect } = require('./routes/auth');
  protect(req, res, async () => {
    const device = await Device.findOne({
      _id: req.params.deviceId,
      owner: req.user._id
    });
    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }
    const CallLog = require('./models').CallLog;
    const logs = await CallLog.find({ deviceId: device.deviceId })
      .sort({ timestamp: -1 })
      .limit(100);
    res.json(logs);
  });
});

app.delete('/api/call-logs/:deviceId', async (req, res) => {
  const { protect } = require('./routes/auth');
  protect(req, res, async () => {
    const device = await Device.findOne({
      _id: req.params.deviceId,
      owner: req.user._id
    });
    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }
    const CallLog = require('./models').CallLog;
    await CallLog.deleteMany({ deviceId: device.deviceId });
    res.json({ success: true });
  });
});

// Redirect /api/app-usage/:deviceId to /api/devices/:deviceId/apps
app.get('/api/app-usage/:deviceId', async (req, res) => {
  const { protect } = require('./routes/auth');
  protect(req, res, async () => {
    const device = await Device.findOne({
      _id: req.params.deviceId,
      owner: req.user._id
    });
    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }
    const AppUsage = require('./models').AppUsage;
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    const usage = await AppUsage.aggregate([
      { $match: { deviceId: device.deviceId, date: { $gte: today } } },
      { $group: { _id: '$packageName', appName: { $first: '$appName' }, totalTime: { $sum: '$usageTime' }, openCount: { $sum: '$openCount' } } },
      { $sort: { totalTime: -1 } }
    ]);
    res.json({ success: true, blockedApps: device.blockedApps, usage });
  });
});

// Redirect /api/location/:deviceId to /api/devices/:deviceId/location
app.get('/api/location/:deviceId', async (req, res) => {
  const { protect } = require('./routes/auth');
  protect(req, res, async () => {
    const device = await Device.findOne({
      _id: req.params.deviceId,
      owner: req.user._id
    });
    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }
    res.json({ success: true, location: device.location || null });
  });
});

// Redirect /api/gallery/:deviceId to /api/devices/:deviceId/photos
app.get('/api/gallery/:deviceId', async (req, res) => {
  const { protect } = require('./routes/auth');
  protect(req, res, async () => {
    const device = await Device.findOne({
      _id: req.params.deviceId,
      owner: req.user._id
    });
    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }
    const Photo = require('./models').Photo;
    const page = parseInt(req.query.page) || 1;
    const limit = parseInt(req.query.limit) || 50;
    const photos = await Photo.find({ deviceId: device.deviceId })
      .sort({ capturedAt: -1 })
      .skip((page - 1) * limit)
      .limit(limit);
    res.json({ success: true, photos });
  });
});

// Redirect /api/commands to /api/devices/:deviceId/command
app.post('/api/commands', async (req, res) => {
  const { deviceId, command, ...params } = req.body;
  if (!deviceId) {
    return res.status(400).json({ error: 'deviceId is required' });
  }
  // Forward to the proper endpoint
  req.url = `/api/devices/${deviceId}/command`;
  req.body = { command, ...params };
  // Use the device routes
  const { protect } = require('./routes/auth');
  protect(req, res, async () => {
    const device = await Device.findOne({
      _id: deviceId,
      owner: req.user._id
    });
    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }
    // Send FCM command
    if (!device.fcmToken) {
      return res.status(400).json({ 
        success: false, 
        error: 'Device not registered for push notifications. Please open the app on the child device.'
      });
    }
    try {
      await admin.messaging().send({
        token: device.fcmToken,
        data: { command, params: JSON.stringify(params || {}) }
      });
      res.json({ success: true, message: 'Command sent' });
    } catch (fcmError) {
      console.error('FCM error:', fcmError);
      res.status(500).json({ success: false, error: 'Failed to send command to device' });
    }
  });
});

// Redirect /api/blocked-apps/:deviceId to /api/devices/:deviceId/settings
app.get('/api/blocked-apps/:deviceId', async (req, res) => {
  const { protect } = require('./routes/auth');
  protect(req, res, async () => {
    const device = await Device.findOne({
      _id: req.params.deviceId,
      owner: req.user._id
    });
    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }
    res.json(device.blockedApps || []);
  });
});

app.post('/api/blocked-apps/:deviceId', async (req, res) => {
  const { protect } = require('./routes/auth');
  protect(req, res, async () => {
    const device = await Device.findOne({
      _id: req.params.deviceId,
      owner: req.user._id
    });
    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }
    device.blockedApps = req.body.blockedApps || [];
    await device.save();
    res.json({ success: true, blockedApps: device.blockedApps });
  });
});

// ==== END ALIAS ROUTES ====

// Debug endpoint to check database status (remove in production)
app.get('/api/debug/devices', async (req, res) => {
  try {
    const allDevices = await Device.find({}).select('_id deviceId name battery screenTime lastSeen isOnline');
    res.json({
      count: allDevices.length,
      devices: allDevices
    });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

// Health check endpoint for UptimeRobot
app.get('/health', (req, res) => {
  res.json({
    status: 'ok',
    timestamp: new Date().toISOString(),
    uptime: process.uptime()
  });
});

// Device heartbeat endpoint - updates device online status
// Called by PersistentService every 2 minutes as WebSocket fallback
app.post('/api/devices/:deviceId/heartbeat', async (req, res) => {
  try {
    const { deviceId } = req.params;
    const { battery, timestamp } = req.body;
    
    const device = await Device.findOne({ deviceId });
    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }
    
    // Update device status
    device.isOnline = true;
    device.lastSeen = new Date();
    if (battery !== undefined && battery > 0) {
      device.battery = battery;
    }
    await device.save();
    
    console.log(`[Heartbeat] Device ${deviceId} is online (battery: ${battery}%)`);
    
    res.json({ 
      success: true, 
      deviceId,
      isOnline: true,
      timestamp: new Date().toISOString()
    });
  } catch (error) {
    console.error('[Heartbeat] Error:', error);
    res.status(500).json({ error: error.message });
  }
});

app.get('/', (req, res) => {
  res.json({
    name: 'FamilyGuard Pro API',
    version: '1.0.0',
    status: 'running'
  });
});

// WebSocket handling for real-time streaming
const streamSessions = new Map();

// === REAL-TIME SYNC CONNECTIONS ===
// Maps for instant parent-child communication (WhatsApp-like)
const syncChildConnections = new Map();  // deviceId -> WebSocket
const syncParentConnections = new Map(); // userId -> { ws, devices[] }

wss.on('connection', (ws, req) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const pathname = url.pathname;
  
  // Support both 'session' and 'device_id' for backward compatibility
  const sessionId = url.searchParams.get('session');
  const role = url.searchParams.get('role'); // 'sender', 'receiver', 'sync'
  const deviceId = url.searchParams.get('deviceId') || url.searchParams.get('device_id');
  const type = url.searchParams.get('type'); // 'screen', 'camera', 'audio'
  const deviceType = url.searchParams.get('device_type'); // 'child' or 'parent'

  console.log(`WebSocket connection: role=${role} type=${type} device_type=${deviceType} deviceId=${deviceId}`);

  // Handle real-time sync connections (from WebSocketSyncService)
  if (role === 'sync' && deviceId) {
    handleRealtimeSync(ws, deviceId, deviceType);
    return;
  }

  if (!sessionId || !role || !deviceId) {
    ws.close(1008, 'Missing parameters');
    return;
  }

  // Check if this is a WebRTC signaling connection
  const isWebRTC = pathname === '/ws/webrtc' || pathname === '//ws/webrtc' || sessionId.includes('webrtc');
  
  if (isWebRTC) {
    handleWebRTCSignaling(ws, deviceId, type, role);
    return;
  }

  const sessionKey = `${deviceId}-${type}`;

  if (role === 'sender') {
    // Child device sending stream
    // Check if session already exists (receiver might be waiting)
    let session = streamSessions.get(sessionKey);
    if (session) {
      // Session exists, add sender to it
      session.sender = ws;
      // Notify waiting receivers
      session.receivers.forEach(receiver => {
        if (receiver.readyState === WebSocket.OPEN) {
          receiver.send(JSON.stringify({ type: 'stream_started', message: 'Stream started' }));
        }
      });
    } else {
      // Create new session
      session = { sender: ws, receivers: new Set() };
      streamSessions.set(sessionKey, session);
    }
    
    ws.on('message', (data) => {
      const currentSession = streamSessions.get(sessionKey);
      if (currentSession && currentSession.receivers.size > 0) {
        currentSession.receivers.forEach(receiver => {
          if (receiver.readyState === WebSocket.OPEN) {
            receiver.send(data);
          }
        });
      }
    });

    ws.on('close', () => {
      const currentSession = streamSessions.get(sessionKey);
      if (currentSession) {
        currentSession.sender = null;
        // Notify receivers that stream ended
        currentSession.receivers.forEach(receiver => {
          if (receiver.readyState === WebSocket.OPEN) {
            receiver.send(JSON.stringify({ type: 'stream_ended', message: 'Stream ended' }));
          }
        });
        // Only delete if no receivers
        if (currentSession.receivers.size === 0) {
          streamSessions.delete(sessionKey);
        }
      }
      console.log(`Sender disconnected: ${sessionKey}`);
    });

  } else if (role === 'receiver') {
    // Parent device receiving stream
    let session = streamSessions.get(sessionKey);
    if (session) {
      session.receivers.add(ws);
      if (session.sender) {
        ws.send(JSON.stringify({ type: 'connected', message: 'Stream connected' }));
      } else {
        ws.send(JSON.stringify({ type: 'waiting', message: 'Waiting for device to start streaming' }));
      }
    } else {
      // Create session to wait for sender
      session = { sender: null, receivers: new Set([ws]) };
      streamSessions.set(sessionKey, session);
      ws.send(JSON.stringify({ type: 'waiting', message: 'Waiting for device to start streaming' }));
    }

    ws.on('close', () => {
      const currentSession = streamSessions.get(sessionKey);
      if (currentSession) {
        currentSession.receivers.delete(ws);
        if (currentSession.receivers.size === 0 && !currentSession.sender) {
          streamSessions.delete(sessionKey);
        }
      }
      console.log(`Receiver disconnected: ${sessionKey}`);
    });
  }

  ws.on('error', (error) => {
    console.error('WebSocket error:', error);
  });
});

// WebRTC Signaling Handler
function handleWebRTCSignaling(ws, deviceId, type, role) {
  const sessionKey = `${deviceId}-${type}-webrtc`;
  
  console.log(`[WebRTC] ${role} connecting for ${type} - Session: ${sessionKey}`);
  
  // Initialize session if not exists
  if (!webrtcSessions.has(sessionKey)) {
    webrtcSessions.set(sessionKey, {
      sender: null,
      receiver: null,
      senderIceCandidates: [],
      receiverIceCandidates: []
    });
  }
  
  const session = webrtcSessions.get(sessionKey);
  
  if (role === 'sender') {
    session.sender = ws;
    
    // If receiver is waiting, notify them
    if (session.receiver && session.receiver.readyState === WebSocket.OPEN) {
      session.receiver.send(JSON.stringify({ 
        type: 'sender_joined',
        deviceId 
      }));
    }
    
    // Send any pending ICE candidates from receiver
    session.receiverIceCandidates.forEach(candidate => {
      ws.send(JSON.stringify(candidate));
    });
    session.receiverIceCandidates = [];
    
    ws.on('message', (data) => {
      try {
        const message = JSON.parse(data.toString());
        console.log(`[WebRTC] Sender message: ${message.type}`);
        
        // Forward to receiver
        if (session.receiver && session.receiver.readyState === WebSocket.OPEN) {
          session.receiver.send(JSON.stringify(message));
        } else if (message.type === 'ice_candidate') {
          // Store ICE candidates if receiver not connected yet
          session.senderIceCandidates.push(message);
        }
      } catch (e) {
        console.error('[WebRTC] Error parsing sender message:', e);
      }
    });
    
    ws.on('close', () => {
      console.log(`[WebRTC] Sender disconnected: ${sessionKey}`);
      session.sender = null;
      
      // Notify receiver
      if (session.receiver && session.receiver.readyState === WebSocket.OPEN) {
        session.receiver.send(JSON.stringify({ 
          type: 'sender_left',
          deviceId 
        }));
      }
      
      // Clean up session if both disconnected
      if (!session.receiver) {
        webrtcSessions.delete(sessionKey);
      }
    });
    
  } else if (role === 'receiver') {
    session.receiver = ws;
    
    // If sender is already connected, notify receiver
    if (session.sender && session.sender.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ 
        type: 'sender_joined',
        deviceId 
      }));
    } else {
      ws.send(JSON.stringify({ 
        type: 'waiting',
        message: 'Waiting for device to connect...' 
      }));
    }
    
    // Send any pending ICE candidates from sender
    session.senderIceCandidates.forEach(candidate => {
      ws.send(JSON.stringify(candidate));
    });
    session.senderIceCandidates = [];
    
    ws.on('message', (data) => {
      try {
        const message = JSON.parse(data.toString());
        console.log(`[WebRTC] Receiver message: ${message.type}`);
        
        // Forward to sender
        if (session.sender && session.sender.readyState === WebSocket.OPEN) {
          session.sender.send(JSON.stringify(message));
        } else if (message.type === 'ice_candidate') {
          // Store ICE candidates if sender not connected yet
          session.receiverIceCandidates.push(message);
        }
      } catch (e) {
        console.error('[WebRTC] Error parsing receiver message:', e);
      }
    });
    
    ws.on('close', () => {
      console.log(`[WebRTC] Receiver disconnected: ${sessionKey}`);
      session.receiver = null;
      
      // Notify sender
      if (session.sender && session.sender.readyState === WebSocket.OPEN) {
        session.sender.send(JSON.stringify({ 
          type: 'parent_left',
          deviceId 
        }));
      }
      
      // Clean up session if both disconnected
      if (!session.sender) {
        webrtcSessions.delete(sessionKey);
      }
    });
  }
  
  ws.on('error', (error) => {
    console.error('[WebRTC] WebSocket error:', error);
  });
  
  // Send ping every 30 seconds to keep connection alive
  const pingInterval = setInterval(() => {
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: 'ping' }));
    } else {
      clearInterval(pingInterval);
    }
  }, 30000);
}

// === REAL-TIME SYNC HANDLER ===
// Handles instant notifications and commands between child device and parent dashboard
function handleRealtimeSync(ws, deviceId, deviceType) {
  console.log(`[Sync] ${deviceType} device ${deviceId} connecting for real-time sync`);
  
  let authenticated = false;
  let parentId = null;
  
  // Send ping every 20 seconds to keep MIUI connections alive
  const pingInterval = setInterval(() => {
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: 'ping', timestamp: Date.now() }));
    } else {
      clearInterval(pingInterval);
    }
  }, 20000);
  
  ws.on('message', async (data) => {
    try {
      const message = JSON.parse(data.toString());
      const type = message.type;
      
      switch (type) {
        case 'auth':
          // Child or parent authenticating
          if (message.device_type === 'child') {
            syncChildConnections.set(deviceId, ws);
            authenticated = true;
            
            // Get parent ID from database
            const device = await Device.findOne({ deviceId });
            if (device && device.owner) {
              parentId = device.owner.toString();
              
              // Notify parent that child is online
              const parentWs = syncParentConnections.get(parentId);
              if (parentWs && parentWs.ws && parentWs.ws.readyState === WebSocket.OPEN) {
                parentWs.ws.send(JSON.stringify({
                  type: 'device_online',
                  device_id: deviceId,
                  timestamp: Date.now()
                }));
              }
              
              // Mark device as online
              await Device.updateOne({ deviceId }, { isOnline: true, lastSeen: new Date() });
            }
            
            ws.send(JSON.stringify({ type: 'auth_success', timestamp: Date.now() }));
            console.log(`[Sync] Child ${deviceId} authenticated`);
            
          } else if (message.device_type === 'parent') {
            // Parent connecting
            const userId = message.user_id || message.parent_id;
            if (userId) {
              syncParentConnections.set(userId, { ws, devices: [] });
              authenticated = true;
              parentId = userId;
              
              // Check which of their devices are online
              const devices = await Device.find({ owner: userId });
              const onlineDevices = [];
              for (const device of devices) {
                if (syncChildConnections.has(device.deviceId)) {
                  onlineDevices.push(device.deviceId);
                }
              }
              
              ws.send(JSON.stringify({
                type: 'auth_success',
                online_devices: onlineDevices,
                timestamp: Date.now()
              }));
              console.log(`[Sync] Parent ${userId} authenticated`);
            }
          }
          break;
          
        case 'notification':
          // Child sending notification - forward to parent INSTANTLY
          if (deviceType === 'child' && parentId) {
            const parentConn = syncParentConnections.get(parentId);
            if (parentConn && parentConn.ws && parentConn.ws.readyState === WebSocket.OPEN) {
              parentConn.ws.send(JSON.stringify({
                type: 'child_notification',
                device_id: deviceId,
                notification: message.data,
                timestamp: message.timestamp
              }));
              console.log(`[Sync] Notification forwarded to parent ${parentId}`);
            }
            
            // Save to database (async)
            try {
              const notification = new Notification({
                deviceId,
                packageName: message.data?.app || 'unknown',
                appName: message.data?.appName || 'Unknown',
                title: message.data?.title || '',
                content: message.data?.text || '',
                timestamp: new Date(message.data?.time || Date.now())
              });
              await notification.save();
            } catch (dbErr) {
              console.error('[Sync] Error saving notification:', dbErr);
            }
            
            // Send acknowledgment
            ws.send(JSON.stringify({
              type: 'ack',
              message_id: message.message_id || Date.now(),
              timestamp: Date.now()
            }));
          }
          break;
          
        case 'social_message':
          // Child sending social media message - forward to parent AND save
          if (deviceType === 'child' && parentId) {
            const msgData = message.data;
            
            // Forward to parent in real-time
            const parentConn = syncParentConnections.get(parentId);
            if (parentConn && parentConn.ws && parentConn.ws.readyState === WebSocket.OPEN) {
              parentConn.ws.send(JSON.stringify({
                type: 'social_message',
                device_id: deviceId,
                message: msgData,
                timestamp: message.timestamp
              }));
              console.log(`[Sync] Social message forwarded: ${msgData?.app_name} - ${msgData?.contact_name}`);
            }
            
            // Save to database with CONTENT-BASED deduplication
            try {
              const { SocialMessage, SocialContact } = require('./models');
              
              // Create a time window for duplicate detection (1 second)
              const timestampWindow = 1000; // 1 second
              const minTime = msgData.timestamp - timestampWindow;
              const maxTime = msgData.timestamp + timestampWindow;
              
              // Check for duplicate based on CONTENT, not just message_id
              const existing = await SocialMessage.findOne({ 
                device_id: deviceId,
                app_package: msgData.app_package,
                contact_name: msgData.contact_name,
                message_text: msgData.message_text,
                timestamp: { $gte: minTime, $lte: maxTime }
              });
              
              if (!existing) {
                const socialMsg = new SocialMessage({
                  message_id: msgData.message_id,
                  device_id: deviceId,
                  app_package: msgData.app_package,
                  app_name: msgData.app_name,
                  contact_name: msgData.contact_name,
                  contact_identifier: msgData.contact_identifier,
                  message_text: msgData.message_text,
                  timestamp: msgData.timestamp,
                  message_type: msgData.message_type,
                  media_type: msgData.media_type,
                  is_group_chat: msgData.is_group_chat,
                  group_name: msgData.group_name,
                  sender_in_group: msgData.sender_in_group
                });
                await socialMsg.save();
                
                // Determine if this is a RECEIVED message (should increment unread count)
                const isReceived = msgData.message_type === 'RECEIVED';
                
                // Update contact
                await SocialContact.findOneAndUpdate(
                  {
                    device_id: deviceId,
                    app_package: msgData.app_package,
                    contact_name: msgData.contact_name
                  },
                  {
                    $set: {
                      contact_identifier: msgData.contact_identifier,
                      last_message_time: msgData.timestamp,
                      last_message_text: msgData.message_text,
                      last_message_type: msgData.message_type,
                      updated_at: new Date()
                    },
                    $inc: { 
                      message_count: 1,
                      // Only increment unread_count for RECEIVED messages
                      ...(isReceived ? { unread_count: 1 } : {})
                    },
                    $setOnInsert: {
                      profile_photo: msgData.profile_photo
                    }
                  },
                  { upsert: true }
                );
                
                console.log(`💬 Saved: ${msgData.app_name} - ${msgData.contact_name}`);
              } else {
                console.log(`⏭️ Duplicate skipped: ${msgData.app_name} - ${msgData.message_text?.substring(0, 20)}`);
              }
            } catch (dbErr) {
              console.error('[Sync] Error saving social message:', dbErr);
            }
            
            // Acknowledge
            ws.send(JSON.stringify({
              type: 'ack',
              message_id: message.message_id || Date.now(),
              timestamp: Date.now()
            }));
          }
          break;
          
        case 'location':
          // Child sending location update - forward to parent INSTANTLY
          if (deviceType === 'child' && parentId) {
            const parentConn = syncParentConnections.get(parentId);
            if (parentConn && parentConn.ws && parentConn.ws.readyState === WebSocket.OPEN) {
              parentConn.ws.send(JSON.stringify({
                type: 'location_update',
                device_id: deviceId,
                location: message.data,
                timestamp: message.timestamp
              }));
            }
            
            // Update device location in database
            try {
              await Device.updateOne({ deviceId }, {
                location: {
                  latitude: message.data.latitude,
                  longitude: message.data.longitude,
                  accuracy: message.data.accuracy,
                  timestamp: new Date()
                },
                lastSeen: new Date()
              });
            } catch (dbErr) {
              console.error('[Sync] Error updating location:', dbErr);
            }
          }
          break;
          
        case 'command':
          // Parent sending command - forward to child INSTANTLY
          if (deviceType === 'parent') {
            const targetDeviceId = message.target_device_id;
            const childWs = syncChildConnections.get(targetDeviceId);
            
            if (childWs && childWs.readyState === WebSocket.OPEN) {
              childWs.send(JSON.stringify({
                type: 'command',
                command: message.command,
                params: message.params,
                message_id: message.message_id,
                timestamp: Date.now()
              }));
              console.log(`[Sync] Command ${message.command} sent to ${targetDeviceId}`);
              
              ws.send(JSON.stringify({
                type: 'command_sent',
                message_id: message.message_id,
                timestamp: Date.now()
              }));
            } else {
              // Child offline - try FCM
              console.log(`[Sync] Child ${targetDeviceId} offline - using FCM`);
              
              try {
                const device = await Device.findOne({ deviceId: targetDeviceId });
                if (device && device.fcmToken && firebaseInitialized) {
                  await admin.messaging().send({
                    token: device.fcmToken,
                    data: {
                      command: message.command,
                      params: JSON.stringify(message.params || {})
                    }
                  });
                  ws.send(JSON.stringify({
                    type: 'command_sent_fcm',
                    message_id: message.message_id,
                    timestamp: Date.now()
                  }));
                } else {
                  ws.send(JSON.stringify({
                    type: 'command_failed',
                    error: 'Device offline and no FCM token',
                    message_id: message.message_id,
                    timestamp: Date.now()
                  }));
                }
              } catch (fcmErr) {
                console.error('[Sync] FCM error:', fcmErr);
                ws.send(JSON.stringify({
                  type: 'command_failed',
                  error: 'Failed to send command',
                  message_id: message.message_id,
                  timestamp: Date.now()
                }));
              }
            }
          }
          break;
          
        case 'pong':
        case 'ping':
          // Child/parent responded to ping - process health metrics
          if (deviceType === 'child' && message.health) {
            const health = message.health;
            
            // Alert parent if critical issues detected
            if (parentId) {
              const parentConn = syncParentConnections.get(parentId);
              if (parentConn && parentConn.ws && parentConn.ws.readyState === WebSocket.OPEN) {
                
                // Low battery alert (< 15%)
                if (health.battery < 15) {
                  parentConn.ws.send(JSON.stringify({
                    type: 'child_alert',
                    device_id: deviceId,
                    alert_type: 'low_battery',
                    message: `Child device battery low: ${health.battery}%`,
                    health: health,
                    timestamp: Date.now()
                  }));
                }
                
                // Accessibility disabled alert
                if (health.accessibility_enabled === false) {
                  parentConn.ws.send(JSON.stringify({
                    type: 'child_alert',
                    device_id: deviceId,
                    alert_type: 'accessibility_disabled',
                    message: 'Accessibility service was disabled on child device - monitoring limited',
                    health: health,
                    timestamp: Date.now()
                  }));
                }
                
                // Connection issues alert
                if (health.consecutive_failures > 3) {
                  parentConn.ws.send(JSON.stringify({
                    type: 'child_alert',
                    device_id: deviceId,
                    alert_type: 'connection_issues',
                    message: `Child device having connection issues (${health.consecutive_failures} failures)`,
                    health: health,
                    timestamp: Date.now()
                  }));
                }
              }
            }
            
            // Update device health in database (non-blocking)
            Device.updateOne({ deviceId }, {
              lastSeen: new Date(),
              isOnline: true,
              health: {
                battery: health.battery,
                charging: health.charging,
                network: health.network,
                accessibility: health.accessibility_enabled
              }
            }).catch(err => console.error('[Sync] Health update error:', err));
          }
          
          console.log(`[Sync] Ping/Pong from ${deviceId || parentId} (battery: ${message.health?.battery || 'N/A'}%)`);
          break;
          
        case 'notification_batch':
          // Child sending batched notifications - forward to parent INSTANTLY
          if (deviceType === 'child' && parentId) {
            const parentConn = syncParentConnections.get(parentId);
            if (parentConn && parentConn.ws && parentConn.ws.readyState === WebSocket.OPEN) {
              // Forward entire batch
              parentConn.ws.send(JSON.stringify({
                type: 'child_notification_batch',
                device_id: deviceId,
                notifications: message.data,
                count: message.count,
                timestamp: message.timestamp
              }));
              console.log(`[Sync] Batch of ${message.count} notifications forwarded to parent ${parentId}`);
            }
            
            // Save to database (async)
            if (Array.isArray(message.data)) {
              for (const notif of message.data) {
                try {
                  const notification = new Notification({
                    deviceId,
                    packageName: notif.app || 'unknown',
                    appName: notif.appName || 'Unknown',
                    title: notif.title || '',
                    content: notif.text || '',
                    timestamp: new Date(notif.time || Date.now())
                  });
                  await notification.save();
                } catch (dbErr) {
                  console.error('[Sync] Error saving batch notification:', dbErr);
                }
              }
            }
            
            // Send acknowledgment
            ws.send(JSON.stringify({
              type: 'ack',
              message_id: message.message_id || Date.now(),
              timestamp: Date.now()
            }));
          }
          break;
          
        case 'ack':
          // Acknowledgment received
          break;
          
        default:
          console.log(`[Sync] Unknown message type: ${type}`);
      }
      
    } catch (error) {
      console.error('[Sync] Error handling message:', error);
    }
  });
  
  ws.on('close', async () => {
    clearInterval(pingInterval);
    
    if (deviceType === 'child') {
      syncChildConnections.delete(deviceId);
      console.log(`[Sync] Child ${deviceId} WebSocket closed (will use HTTP heartbeat fallback)`);
      
      // DON'T immediately mark as offline - this causes status flickering!
      // The device will send HTTP heartbeats every 2 minutes.
      // The cron job will mark offline after 5 minutes of no activity.
      // This prevents rapid online/offline/online status changes on B1 tier.
      
      // Only notify parent of WebSocket disconnect (not offline status)
      if (parentId) {
        const parentConn = syncParentConnections.get(parentId);
        if (parentConn && parentConn.ws && parentConn.ws.readyState === WebSocket.OPEN) {
          parentConn.ws.send(JSON.stringify({
            type: 'device_ws_disconnected',
            device_id: deviceId,
            timestamp: Date.now(),
            message: 'WebSocket disconnected, using HTTP fallback'
          }));
        }
      }
    } else if (deviceType === 'parent' && parentId) {
      syncParentConnections.delete(parentId);
      console.log(`[Sync] Parent ${parentId} disconnected`);
    }
  });
  
  ws.on('error', (error) => {
    console.error('[Sync] WebSocket error:', error);
  });
}

// Cron job - Mark devices offline after 10 minutes of no heartbeat
// Extended from 5 mins to 10 mins to handle Azure B1 tier latency and WebSocket reconnection delays
cron.schedule('*/2 * * * *', async () => {
  try {
    const tenMinutesAgo = new Date(Date.now() - 10 * 60 * 1000);
    await Device.updateMany(
      { lastSeen: { $lt: tenMinutesAgo }, isOnline: true },
      { isOnline: false }
    );
  } catch (error) {
    console.error('Offline check cron error:', error);
  }
});

// Cron job - Cleanup old data (runs daily at 3 AM)
cron.schedule('0 3 * * *', async () => {
  try {
    const retentionHours = parseInt(process.env.DATA_RETENTION_HOURS) || 48;
    const cutoffDate = new Date(Date.now() - retentionHours * 60 * 60 * 1000);

    const results = await Promise.all([
      Notification.deleteMany({ timestamp: { $lt: cutoffDate } }),
      CallLog.deleteMany({ timestamp: { $lt: cutoffDate } }),
      AppUsage.deleteMany({ date: { $lt: cutoffDate } }),
      LocationHistory.deleteMany({ timestamp: { $lt: cutoffDate } })
    ]);

    console.log('Data cleanup completed:', {
      notifications: results[0].deletedCount,
      callLogs: results[1].deletedCount,
      appUsage: results[2].deletedCount,
      locations: results[3].deletedCount
    });
  } catch (error) {
    console.error('Cleanup cron error:', error);
  }
});

// Error handling middleware
app.use((err, req, res, next) => {
  console.error('Error:', err);
  res.status(err.status || 500).json({
    error: err.message || 'Internal server error'
  });
});

// 404 handler
app.use((req, res) => {
  res.status(404).json({ error: 'Not found' });
});

// Connect to MongoDB and start server
const PORT = parseInt(process.env.PORT, 10) || 8080;

const startServer = () => {
  console.log(`Starting server on port ${PORT}...`);
  server.listen(PORT, () => {
    console.log(`Server running on port ${PORT}`);
    console.log(`WebSocket server running on ws://localhost:${PORT}/ws`);
  });
};

mongoose.connect(process.env.MONGODB_URI || 'mongodb://localhost:27017/familyguard')
.then(async () => {
  console.log('Connected to MongoDB');
  startServer();
  
  // Run social media duplicate cleanup on startup
  try {
    const { SocialMessage } = require('./models');
    const pipeline = [
      {
        $group: {
          _id: {
            device_id: '$device_id',
            app_package: '$app_package',
            contact_name: '$contact_name',
            message_text: '$message_text',
            timestamp_second: { $subtract: ['$timestamp', { $mod: ['$timestamp', 2000] }] }
          },
          count: { $sum: 1 },
          docs: { $push: '$_id' },
          firstDoc: { $first: '$_id' }
        }
      },
      { $match: { count: { $gt: 1 } } }
    ];
    
    const duplicates = await SocialMessage.aggregate(pipeline).allowDiskUse(true);
    
    if (duplicates.length > 0) {
      let totalDeleted = 0;
      for (const dup of duplicates) {
        const idsToDelete = dup.docs.filter(id => !id.equals(dup.firstDoc));
        if (idsToDelete.length > 0) {
          const result = await SocialMessage.deleteMany({ _id: { $in: idsToDelete } });
          totalDeleted += result.deletedCount;
        }
      }
      console.log(`🧹 Startup cleanup: removed ${totalDeleted} duplicate social messages from ${duplicates.length} groups`);
    } else {
      console.log('✅ No duplicate social messages found');
    }
  } catch (cleanupErr) {
    console.error('Startup duplicate cleanup error (non-fatal):', cleanupErr.message);
  }
})
.catch((error) => {
  console.error('MongoDB connection error:', error);
  console.warn('⚠️ Starting server WITHOUT MongoDB - some features will be unavailable');
  startServer();
});

// Graceful shutdown
process.on('SIGTERM', () => {
  console.log('SIGTERM received. Shutting down gracefully...');
  server.close(() => {
    mongoose.connection.close(false, () => {
      console.log('Server closed');
      process.exit(0);
    });
  });
});

module.exports = app;
