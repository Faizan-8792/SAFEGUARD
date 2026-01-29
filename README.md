# FamilyGuard Pro 🛡️

**Complete Parental Control Solution for Android**

A production-ready, full-featured parental control Android app with remote monitoring capabilities including screen mirroring, remote camera, live listening, call recording, notification monitoring, location tracking, app blocking, and **remote call log deletion**.

![Android](https://img.shields.io/badge/Android-14+-green?logo=android)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-purple?logo=kotlin)
![Node.js](https://img.shields.io/badge/Node.js-18+-green?logo=node.js)
![MongoDB](https://img.shields.io/badge/MongoDB-Atlas-green?logo=mongodb)
![License](https://img.shields.io/badge/License-MIT-blue)

## ✨ Features

### 📱 Child Device (Android App)

| Feature | Description | Specs |
|---------|-------------|-------|
| **Screen Mirroring** | Real-time screen streaming | 360p @ 10fps, 250kbps H.264 |
| **Remote Camera** | Live camera feed from child device | 480p @ 15fps, 400kbps H.264 |
| **Live Listen** | Real-time audio monitoring | 32kbps Opus |
| **Call Recording** | Automatic call recording | 64kbps MP3 |
| **Notification Capture** | Monitor all app notifications | WhatsApp, Instagram, Telegram, etc. |
| **Location Tracking** | Real-time GPS location | Every 15 minutes |
| **App Blocking** | Block/restrict apps remotely | VPN-based filtering |
| **Remote Call Log Delete** | Delete call history from child phone | 🆕 New Feature |
| **Stealth Mode** | Hide app icon and disguise as "System Update" | Activity alias |

### 👨‍👩‍👧 Parent Features

| Feature | Description |
|---------|-------------|
| **Web Dashboard** | Browser-based control panel |
| **Real-time Alerts** | Instant notifications |
| **Multi-device Support** | Monitor multiple children |
| **Remote Commands** | Lock device, ring, sync data |
| **Call History** | View & delete call logs |
| **App Usage Stats** | Screen time analytics |

## 🏗️ Architecture

```
FamilyGuard Pro
├── android/                 # Android App (Kotlin)
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── java/com/familyguard/parental/
│   │   │   │   ├── activity/       # 10 Activities
│   │   │   │   ├── service/        # 14 Services
│   │   │   │   ├── util/           # Utilities
│   │   │   │   ├── model/          # Data models
│   │   │   │   ├── network/        # API client
│   │   │   │   └── adapter/        # RecyclerView adapters
│   │   │   └── res/                # Resources
│   │   └── build.gradle.kts
│   └── settings.gradle.kts
│
├── backend/                 # Node.js Server
│   ├── models/             # MongoDB schemas
│   ├── routes/             # API endpoints
│   ├── server.js           # Express + WebSocket
│   └── package.json
│
├── parent-web/             # Web Dashboard
│   ├── index.html
│   ├── style.css
│   └── dashboard.js
│
└── docs/                   # Documentation
    └── deployment-guide.md
```

## 🚀 Quick Start

### Prerequisites

- **Android Studio** Hedgehog 2023.1.1+
- **Node.js** 18+
- **MongoDB Atlas** account (free)
- **Firebase** account (free)

### 1. Clone Repository

```bash
git clone https://github.com/yourusername/familyguard-pro.git
cd familyguard-pro
```

### 2. Backend Setup

```bash
cd backend
npm install
cp .env.example .env
# Edit .env with your credentials
npm start
```

### 3. Android App Setup

1. Open `android/` folder in Android Studio
2. Add `google-services.json` to `app/` folder
3. Update API URL in `ApiClient.kt`
4. Build & run on device

### 4. Web Dashboard

```bash
cd parent-web
# Open index.html in browser
# Or serve with any static server
npx serve
```

## 📋 API Endpoints

### Authentication

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/auth/register` | Register parent account |
| POST | `/api/auth/login` | Login |
| POST | `/api/auth/pairing-code` | Generate pairing code |
| POST | `/api/auth/pair` | Pair child device |

### Devices

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/devices` | List all devices |
| GET | `/api/devices/:id` | Get device details |
| POST | `/api/devices/:id/command` | Send remote command |
| DELETE | `/api/devices/:id/call-logs` | **Delete call logs** |
| GET | `/api/devices/:id/notifications` | Get notifications |
| GET | `/api/devices/:id/location` | Get location |

### Sync (Child Device)

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/sync/notifications` | Sync notifications |
| POST | `/api/sync/call-logs` | Sync call logs |
| POST | `/api/sync/location` | Sync location |
| POST | `/api/sync/apps` | Sync app usage |

## 🔐 Remote Commands

Commands sent via Firebase Cloud Messaging:

| Command | Description |
|---------|-------------|
| `start_screen_mirror` | Start screen streaming |
| `stop_screen_mirror` | Stop screen streaming |
| `start_camera` | Start camera streaming |
| `stop_camera` | Stop camera streaming |
| `start_live_listen` | Start audio streaming |
| `stop_live_listen` | Stop audio streaming |
| `delete_call_logs` | **Delete all call logs** |
| `lock_device` | Lock child device |
| `ring_device` | Play alert sound |
| `sync_data` | Force data sync |
| `block_app` | Block specific app |
| `unblock_app` | Unblock app |

## 🛠️ Technical Specifications

### Video Encoding

| Stream | Resolution | FPS | Bitrate | Codec |
|--------|------------|-----|---------|-------|
| Camera | 480p (640x480) | 15 | 400 kbps | H.264 Baseline |
| Screen | 360p (640x360) | 10 | 250 kbps | H.264 Baseline |

### Audio Encoding

| Type | Format | Bitrate | Sample Rate |
|------|--------|---------|-------------|
| Live Listen | Opus | 32 kbps | 16 kHz |
| Call Recording | MP3 | 64 kbps | 44.1 kHz |

### Data Retention

- **Notifications**: 48 hours (TTL index)
- **Call Logs**: 48 hours (TTL index)
- **Location History**: 7 days
- **App Usage**: 7 days

## 📦 Android Permissions

The app requires these permissions:

```xml
<!-- Core -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />

<!-- Location -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />

<!-- Media -->
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.CAPTURE_AUDIO_OUTPUT" />

<!-- Phone -->
<uses-permission android:name="android.permission.READ_CALL_LOG" />
<uses-permission android:name="android.permission.WRITE_CALL_LOG" />
<uses-permission android:name="android.permission.READ_PHONE_STATE" />
<uses-permission android:name="android.permission.PROCESS_OUTGOING_CALLS" />

<!-- System -->
<uses-permission android:name="android.permission.PACKAGE_USAGE_STATS" />
<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
```

## 🌐 Deployment

See [Deployment Guide](docs/deployment-guide.md) for:

- MongoDB Atlas setup
- Firebase FCM configuration
- Render/Railway deployment
- UptimeRobot monitoring
- Production configuration

## 🔒 Security Features

- **JWT Authentication** for API access
- **EncryptedSharedPreferences** for local storage
- **HTTPS/WSS** for all communications
- **Firebase Admin SDK** for FCM authentication
- **MongoDB TTL indexes** for automatic data cleanup
- **Device Admin** for enhanced device control

## 📱 Supported Devices

- **Android**: 14+ (API 34)
- **Target SDK**: 34
- **Min SDK**: 34
- **Architecture**: arm64-v8a, armeabi-v7a, x86_64

## 🤝 Contributing

1. Fork the repository
2. Create feature branch (`git checkout -b feature/amazing-feature`)
3. Commit changes (`git commit -m 'Add amazing feature'`)
4. Push to branch (`git push origin feature/amazing-feature`)
5. Open Pull Request

## ⚠️ Legal Disclaimer

**IMPORTANT**: This software is intended for **legal use only**:

- Parents monitoring minor children with proper consent
- Employers monitoring company-owned devices with employee consent
- Personal device backup and recovery

**Unauthorized use for surveillance is illegal**. Users are responsible for complying with all applicable laws including GDPR, COPPA, and local privacy regulations.

## 📄 License

MIT License - see [LICENSE](LICENSE) file for details.

## 🙏 Acknowledgments

- [Firebase](https://firebase.google.com/) - Cloud Messaging
- [MongoDB Atlas](https://www.mongodb.com/cloud/atlas) - Database
- [Render](https://render.com/) - Hosting
- [UptimeRobot](https://uptimerobot.com/) - Monitoring

---

**Made with ❤️ for Family Safety**
