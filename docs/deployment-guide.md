# FamilyGuard Pro - Deployment Guide 🚀

Complete guide for deploying FamilyGuard Pro backend on free hosting platforms with UptimeRobot monitoring.

## Table of Contents
1. [MongoDB Atlas Setup](#1-mongodb-atlas-setup)
2. [Firebase Cloud Messaging Setup](#2-firebase-cloud-messaging-setup)
3. [Backend Deployment (Render)](#3-backend-deployment-render)
4. [Alternative: Railway Deployment](#4-alternative-railway-deployment)
5. [UptimeRobot Configuration](#5-uptimerobot-configuration)
6. [Environment Variables](#6-environment-variables)
7. [Android App Configuration](#7-android-app-configuration)
8. [Troubleshooting](#8-troubleshooting)

---

## 1. MongoDB Atlas Setup

### Create Free Cluster

1. Go to [MongoDB Atlas](https://www.mongodb.com/cloud/atlas)
2. Sign up / Sign in
3. Create a new project: `FamilyGuard`
4. Click **Build a Cluster**
5. Select **FREE Shared Cluster (M0)**
6. Choose cloud provider (AWS/GCP/Azure) and region closest to you
7. Cluster name: `familyguard-cluster`
8. Click **Create Cluster**

### Configure Database Access

1. Go to **Database Access** → **Add New Database User**
2. Authentication: Password
3. Username: `familyguard_admin`
4. Password: Generate secure password (save it!)
5. Database User Privileges: **Read and write to any database**
6. Click **Add User**

### Configure Network Access

1. Go to **Network Access** → **Add IP Address**
2. Click **Allow Access from Anywhere** (0.0.0.0/0)
   - Required for cloud hosting platforms
3. Click **Confirm**

### Get Connection String

1. Go to **Clusters** → **Connect**
2. Select **Connect your application**
3. Driver: Node.js, Version: 4.1 or later
4. Copy the connection string:
```
mongodb+srv://familyguard_admin:<password>@familyguard-cluster.xxxxx.mongodb.net/?retryWrites=true&w=majority
```
5. Replace `<password>` with your actual password
6. Add database name before `?`:
```
mongodb+srv://familyguard_admin:YOUR_PASSWORD@familyguard-cluster.xxxxx.mongodb.net/familyguard?retryWrites=true&w=majority
```

---

## 2. Firebase Cloud Messaging Setup

### Create Firebase Project

1. Go to [Firebase Console](https://console.firebase.google.com/)
2. Click **Add project**
3. Project name: `FamilyGuard Pro`
4. Disable Google Analytics (optional)
5. Click **Create project**

### Register Android App

1. Click **Android** icon to add app
2. Package name: `com.familyguard.parental`
3. App nickname: `FamilyGuard Child`
4. Download `google-services.json`
5. Place file in `android/app/` folder

### Get Server Key (FCM Admin SDK)

1. Go to **Project Settings** → **Service accounts**
2. Click **Generate new private key**
3. Download the JSON file (e.g., `familyguard-pro-firebase-adminsdk.json`)
4. This will be used in backend as `FIREBASE_SERVICE_ACCOUNT`

### Legacy Server Key (Alternative)

1. Go to **Project Settings** → **Cloud Messaging**
2. Under **Cloud Messaging API (Legacy)**, copy **Server key**
3. Use as `FCM_SERVER_KEY` environment variable

---

## 3. Backend Deployment (Render)

[Render](https://render.com) offers free web service hosting with easy deployment.

### Step 1: Prepare Repository

1. Create a GitHub/GitLab repository
2. Push the `backend` folder contents to the repo
3. Ensure these files exist:
   - `package.json`
   - `server.js`
   - `models/index.js`
   - `routes/` folder

### Step 2: Create Render Account

1. Go to [Render](https://render.com)
2. Sign up with GitHub
3. Authorize Render to access your repos

### Step 3: Create Web Service

1. Click **New +** → **Web Service**
2. Connect your repository
3. Configure:
   - **Name**: `familyguard-api`
   - **Region**: Choose closest to your users
   - **Branch**: `main`
   - **Root Directory**: `backend` (if backend is in subfolder)
   - **Runtime**: `Node`
   - **Build Command**: `npm install`
   - **Start Command**: `node server.js`
   - **Instance Type**: **Free**

### Step 4: Add Environment Variables

Click **Advanced** → **Add Environment Variable**:

| Key | Value |
|-----|-------|
| `NODE_ENV` | `production` |
| `PORT` | `3000` |
| `MONGODB_URI` | Your MongoDB Atlas connection string |
| `JWT_SECRET` | Generate: `openssl rand -base64 32` |
| `FIREBASE_SERVICE_ACCOUNT` | Paste entire Firebase JSON (escaped) |
| `FCM_SERVER_KEY` | Your FCM server key |

### Step 5: Deploy

1. Click **Create Web Service**
2. Wait for deployment (2-5 minutes)
3. Your API URL: `https://familyguard-api.onrender.com`

### Render Free Tier Notes

⚠️ **Important**: Free tier services spin down after 15 minutes of inactivity
- Cold starts take ~30 seconds
- Use UptimeRobot to keep alive (Section 5)

---

## 4. Alternative: Railway Deployment

[Railway](https://railway.app) offers $5/month free credit.

### Step 1: Create Railway Account

1. Go to [Railway](https://railway.app)
2. Sign up with GitHub

### Step 2: Deploy from GitHub

1. Click **New Project** → **Deploy from GitHub repo**
2. Select your repository
3. Railway auto-detects Node.js

### Step 3: Add Environment Variables

1. Click on your service
2. Go to **Variables** tab
3. Add all environment variables (same as Render)

### Step 4: Generate Domain

1. Go to **Settings** → **Networking**
2. Click **Generate Domain**
3. Your API URL: `https://familyguard-production.up.railway.app`

### Railway Benefits
- No cold starts (always running)
- Built-in PostgreSQL/MongoDB options
- Easy environment variable management

---

## 5. UptimeRobot Configuration

UptimeRobot keeps your free Render service awake by pinging it every 5 minutes.

### Create UptimeRobot Account

1. Go to [UptimeRobot](https://uptimerobot.com)
2. Sign up for free account
3. Verify email

### Create Monitors

#### Monitor 1: API Health Check

1. Click **Add New Monitor**
2. Configure:
   - **Monitor Type**: HTTP(s)
   - **Friendly Name**: `FamilyGuard API Health`
   - **URL**: `https://familyguard-api.onrender.com/api/health`
   - **Monitoring Interval**: 5 minutes
3. Click **Create Monitor**

#### Monitor 2: API Root

1. Click **Add New Monitor**
2. Configure:
   - **Monitor Type**: HTTP(s)
   - **Friendly Name**: `FamilyGuard API Root`
   - **URL**: `https://familyguard-api.onrender.com/`
   - **Monitoring Interval**: 5 minutes
3. Click **Create Monitor**

### Alert Contacts (Optional)

1. Go to **My Settings** → **Alert Contacts**
2. Add email for downtime notifications
3. Can also add Telegram, SMS, Webhook

### Status Page (Optional)

1. Go to **Status Pages** → **Add Status Page**
2. Add your monitors
3. Get public status URL to share

### Pro Tips

- **Free Plan**: 50 monitors, 5-minute intervals
- Create multiple monitors to ensure reliability
- Use keyword monitoring to verify actual response
- Set up status page for transparency

---

## 6. Environment Variables

Complete list of environment variables for production:

```env
# Server
NODE_ENV=production
PORT=3000

# MongoDB Atlas
MONGODB_URI=mongodb+srv://user:pass@cluster.mongodb.net/familyguard?retryWrites=true&w=majority

# JWT Authentication
JWT_SECRET=your-super-secret-jwt-key-min-32-chars
JWT_EXPIRES_IN=7d

# Firebase Cloud Messaging
FCM_SERVER_KEY=AAAA...:APA91b...
# OR use service account (recommended)
FIREBASE_PROJECT_ID=familyguard-pro
FIREBASE_SERVICE_ACCOUNT={"type":"service_account","project_id":"..."}

# Optional: Email notifications
SMTP_HOST=smtp.gmail.com
SMTP_PORT=587
SMTP_USER=your-email@gmail.com
SMTP_PASS=app-password

# Optional: Push notification fallback
ONESIGNAL_APP_ID=xxx
ONESIGNAL_API_KEY=xxx
```

### Security Best Practices

1. **Never commit** `.env` files
2. Use **separate** credentials for dev/production
3. Rotate JWT secrets periodically
4. Use **IP whitelist** in MongoDB for production servers
5. Enable **2FA** on all accounts

---

## 7. Android App Configuration

### Update API Base URL

In `app/src/main/java/com/familyguard/parental/network/ApiClient.kt`:

```kotlin
object ApiClient {
    private const val BASE_URL = "https://familyguard-api.onrender.com/api/"
    
    // ... rest of the code
}
```

### Update WebSocket URL

In `app/src/main/java/com/familyguard/parental/service/ScreenMirrorService.kt`:

```kotlin
private const val WS_URL = "wss://familyguard-api.onrender.com"
```

### Build Release APK

1. Generate signing key:
```bash
keytool -genkey -v -keystore familyguard-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias familyguard
```

2. Configure signing in `app/build.gradle.kts`:
```kotlin
android {
    signingConfigs {
        create("release") {
            storeFile = file("familyguard-key.jks")
            storePassword = "your-store-password"
            keyAlias = "familyguard"
            keyPassword = "your-key-password"
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}
```

3. Build:
```bash
./gradlew assembleRelease
```

APK location: `app/build/outputs/apk/release/app-release.apk`

---

## 8. Troubleshooting

### Common Issues

#### 1. MongoDB Connection Failed

**Error**: `MongooseServerSelectionError: connection timed out`

**Solutions**:
- Check MongoDB Atlas IP whitelist (allow 0.0.0.0/0)
- Verify connection string format
- Check username/password encoding (special chars need URL encoding)

#### 2. Render Service Not Starting

**Error**: `Build failed` or `Port binding error`

**Solutions**:
- Ensure `PORT` env var is set (Render sets this automatically)
- Check `package.json` has correct start script
- View Render logs for specific errors

#### 3. FCM Messages Not Delivered

**Error**: `messaging/registration-token-not-registered`

**Solutions**:
- Device token may be expired - request new token
- Check Firebase project matches `google-services.json`
- Verify FCM server key is correct

#### 4. WebSocket Connection Fails

**Error**: `WebSocket connection to 'wss://...' failed`

**Solutions**:
- Ensure Render web service supports WebSocket
- Check for CORS issues
- Verify wss:// (not ws://) for HTTPS deployments

#### 5. UptimeRobot Shows Down

**Solutions**:
- Check monitor URL is correct
- Verify `/api/health` endpoint returns 200 OK
- Check Render service logs for errors

### Debugging Commands

```bash
# Test API locally
curl http://localhost:3000/api/health

# Test production API
curl https://familyguard-api.onrender.com/api/health

# Check MongoDB connection
mongosh "mongodb+srv://user:pass@cluster.mongodb.net/familyguard"

# View Render logs
# Go to Render Dashboard → Your Service → Logs
```

### Performance Optimization

1. **Enable MongoDB Indexes**:
```javascript
// In models/index.js - already configured with TTL indexes
db.notifications.createIndex({ "timestamp": 1 }, { expireAfterSeconds: 172800 })
```

2. **Use Connection Pooling**:
```javascript
mongoose.connect(MONGODB_URI, {
  maxPoolSize: 10,
  serverSelectionTimeoutMS: 5000,
  socketTimeoutMS: 45000,
})
```

3. **Implement Caching** (optional):
```javascript
// Use Redis for frequently accessed data
const Redis = require('ioredis');
const redis = new Redis(process.env.REDIS_URL);
```

---

## Quick Start Checklist

- [ ] Create MongoDB Atlas cluster
- [ ] Set up Firebase project and get credentials
- [ ] Deploy backend to Render/Railway
- [ ] Configure all environment variables
- [ ] Set up UptimeRobot monitors
- [ ] Update Android app with production URLs
- [ ] Test API endpoints
- [ ] Build and install release APK
- [ ] Pair child device with parent account

---

## Support

For issues and feature requests:
- Check this documentation first
- Review Render/MongoDB Atlas logs
- Verify all environment variables are set

**Your FamilyGuard Pro app is now production-ready! 🎉**
