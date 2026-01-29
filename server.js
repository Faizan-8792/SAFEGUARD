require('dotenv').config();
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
const { Notification, CallLog, AppUsage, LocationHistory, Device, Photo } = require('./models');

const app = express();

// Trust proxy for Replit (needed for rate limiter with X-Forwarded-For header)
app.set('trust proxy', 1);

const server = http.createServer(app);

// WebSocket server for streaming - handle both /ws and //ws paths
const wss = new WebSocket.Server({ noServer: true });

// Handle WebSocket upgrade requests
server.on('upgrade', (request, socket, head) => {
  const pathname = request.url.split('?')[0];
  console.log(`[WS Upgrade] Received upgrade request for path: ${pathname}`);
  
  // Accept both /ws and //ws paths (some clients send double slash)
  if (pathname === '/ws' || pathname === '//ws') {
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

// Redirect root to dashboard
app.get('/', (req, res) => {
  res.redirect('/dashboard');
});

// Also serve at root for convenience
app.get('/index.html', (req, res) => {
  res.redirect('/dashboard');
});

// Rate limiting
const limiter = rateLimit({
  windowMs: parseInt(process.env.RATE_LIMIT_WINDOW_MS) || 15 * 60 * 1000,
  max: parseInt(process.env.RATE_LIMIT_MAX_REQUESTS) || 100,
  message: { error: 'Too many requests, please try again later' }
});
app.use('/api/', limiter);

// Routes
app.use('/api/auth', authRoutes);
app.use('/api/devices', deviceRoutes);
app.use('/api/sync', syncRoutes);

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

app.get('/', (req, res) => {
  res.json({
    name: 'FamilyGuard Pro API',
    version: '1.0.0',
    status: 'running'
  });
});

// WebSocket handling for real-time streaming
const streamSessions = new Map();

wss.on('connection', (ws, req) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const sessionId = url.searchParams.get('session');
  const role = url.searchParams.get('role'); // 'sender' (child) or 'receiver' (parent)
  const deviceId = url.searchParams.get('deviceId');
  const type = url.searchParams.get('type'); // 'screen', 'camera', 'audio'

  console.log(`WebSocket connection: ${role} for ${type} - Device: ${deviceId}`);

  if (!sessionId || !role || !deviceId) {
    ws.close(1008, 'Missing parameters');
    return;
  }

  const sessionKey = `${deviceId}-${type}`;

  if (role === 'sender') {
    // Child device sending stream
    streamSessions.set(sessionKey, { sender: ws, receivers: new Set() });
    
    ws.on('message', (data) => {
      const session = streamSessions.get(sessionKey);
      if (session && session.receivers.size > 0) {
        session.receivers.forEach(receiver => {
          if (receiver.readyState === WebSocket.OPEN) {
            receiver.send(data);
          }
        });
      }
    });

    ws.on('close', () => {
      const session = streamSessions.get(sessionKey);
      if (session) {
        session.receivers.forEach(receiver => {
          receiver.close(1000, 'Stream ended');
        });
        streamSessions.delete(sessionKey);
      }
      console.log(`Sender disconnected: ${sessionKey}`);
    });

  } else if (role === 'receiver') {
    // Parent device receiving stream
    const session = streamSessions.get(sessionKey);
    if (session) {
      session.receivers.add(ws);
      ws.send(JSON.stringify({ type: 'connected', message: 'Stream connected' }));
    } else {
      ws.send(JSON.stringify({ type: 'waiting', message: 'Waiting for stream' }));
      // Create session to wait for sender
      streamSessions.set(sessionKey, { sender: null, receivers: new Set([ws]) });
    }

    ws.on('close', () => {
      const session = streamSessions.get(sessionKey);
      if (session) {
        session.receivers.delete(ws);
        if (session.receivers.size === 0 && !session.sender) {
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

// Cron job - Mark devices offline after 5 minutes of no heartbeat
cron.schedule('*/2 * * * *', async () => {
  try {
    const fiveMinutesAgo = new Date(Date.now() - 5 * 60 * 1000);
    await Device.updateMany(
      { lastSeen: { $lt: fiveMinutesAgo }, isOnline: true },
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
const PORT = process.env.PORT || 5000;

mongoose.connect(process.env.MONGODB_URI || 'mongodb://localhost:27017/familyguard')
.then(() => {
  console.log('Connected to MongoDB');
  server.listen(PORT, '0.0.0.0', () => {
    console.log(`Server running on port ${PORT}`);
    console.log(`WebSocket server running on ws://localhost:${PORT}/ws`);
  });
})
.catch((error) => {
  console.error('MongoDB connection error:', error);
  process.exit(1);
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
