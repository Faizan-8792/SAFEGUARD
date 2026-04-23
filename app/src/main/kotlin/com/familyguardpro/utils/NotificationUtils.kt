package com.familyguardpro.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Utility for handling notification suppression in Device Owner mode.
 * 
 * When the app is provisioned as Device Owner, we want to hide all
 * FamilyGuard notifications from the notification shade to make
 * the app completely invisible to the child.
 */
object NotificationUtils {
    private const val TAG = "NotificationUtils"
    const val INVISIBLE_CHANNEL_ID = "hidden_system_service"

    /**
     * Create an invisible notification for foreground services in DO mode.
     * This notification won't appear in the status bar.
     */
    fun createInvisibleNotification(context: Context): Notification {
        // Ensure invisible channel exists
        ensureInvisibleChannel(context)
        
        return NotificationCompat.Builder(context, INVISIBLE_CHANNEL_ID)
            .setContentTitle("")
            .setContentText("")
            .setSmallIcon(android.R.drawable.screen_background_dark_transparent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .setSound(null)
            .setVibrate(null)
            .setSilent(true)
            .build()
    }

    /**
     * Ensure invisible notification channel exists
     */
    fun ensureInvisibleChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // Check if channel already exists
            val existingChannel = nm.getNotificationChannel(INVISIBLE_CHANNEL_ID)
            if (existingChannel != null && existingChannel.importance == NotificationManager.IMPORTANCE_NONE) {
                return // Already set up correctly
            }
            
            // Delete and recreate with IMPORTANCE_NONE
            try {
                nm.deleteNotificationChannel(INVISIBLE_CHANNEL_ID)
            } catch (e: Exception) { /* ignore */ }
            
            val channel = NotificationChannel(
                INVISIBLE_CHANNEL_ID,
                "System Services",
                NotificationManager.IMPORTANCE_NONE
            ).apply {
                description = "Background services"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            
            nm.createNotificationChannel(channel)
            Log.d(TAG, "Created invisible notification channel")
        }
    }
    
    /**
     * Suppress a foreground service's notification in Device Owner mode.
     * 
     * Call this AFTER startForeground() to remove the notification from
     * the shade while keeping the service running.
     * 
     * @param service The foreground service
     * @param notificationId The notification ID used in startForeground()
     * @param delayMs Delay before removing notification (default 500ms)
     */
    fun suppressForegroundNotificationIfDeviceOwner(
        service: Service,
        notificationId: Int,
        delayMs: Long = 500
    ) {
        try {
            val doManager = com.familyguardpro.deviceowner.DeviceOwnerManager.getInstance(service)
            if (doManager.isDeviceOwner()) {
                // Ensure invisible channel exists
                ensureInvisibleChannel(service)
                
                val handler = Handler(Looper.getMainLooper())
                val nm = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                
                // Suppress action - run multiple times to ensure notification is removed
                val suppressAction = Runnable {
                    try {
                        // Cancel the specific notification
                        nm.cancel(notificationId)
                        
                        // Also try canceling common notification IDs
                        listOf(1001, 1002, 1003, 1004, 1005, 1006, 1007, 1008).forEach { id ->
                            nm.cancel(id)
                        }
                        
                        // Cancel all notifications from this app
                        nm.cancelAll()
                        
                        Log.d(TAG, "Suppressed notification $notificationId for ${service.javaClass.simpleName}")
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not suppress notification: ${e.message}")
                    }
                }
                
                // Run suppression multiple times to catch any re-appearing notifications
                handler.postDelayed(suppressAction, delayMs)
                handler.postDelayed(suppressAction, delayMs + 500)
                handler.postDelayed(suppressAction, delayMs + 1500)
                handler.postDelayed(suppressAction, delayMs + 3000)
                
                // Disable all notification channels
                disableAllNotificationChannels(service)
            }
        } catch (e: Exception) {
            // DeviceOwnerManager not available or not DO mode
            Log.d(TAG, "Not in DO mode or error: ${e.message}")
        }
    }

    /**
     * Disable all notification channels (set to IMPORTANCE_NONE)
     */
    fun disableAllNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notificationChannels.forEach { channel ->
                    // Don't change invisible channel
                    if (channel.id != INVISIBLE_CHANNEL_ID) {
                        channel.importance = NotificationManager.IMPORTANCE_NONE
                        nm.createNotificationChannel(channel)
                    }
                }
                Log.d(TAG, "Disabled all notification channels")
            } catch (e: Exception) {
                Log.w(TAG, "Could not disable notification channels: ${e.message}")
            }
        }
    }
    
    /**
     * Cancel all FamilyGuard notifications.
     * Used to ensure no notifications are visible in Device Owner mode.
     */
    fun cancelAllNotificationsIfDeviceOwner(context: Context) {
        try {
            val doManager = com.familyguardpro.deviceowner.DeviceOwnerManager.getInstance(context)
            if (doManager.isDeviceOwner()) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                
                // Cancel all notifications
                nm.cancelAll()
                
                // Also explicitly cancel known notification IDs that might be stale
                // These are the NOTIFICATION_IDs used by various services
                val knownNotificationIds = listOf(
                    1001, // PersistentService
                    1002, // WebSocketSyncService
                    1003, // CallRecordService
                    1004, // ScreenMirrorService
                    1005, // ScreenMirrorService/CallRecordService/LocationService (old)
                    1006, // LiveListenService
                    1007, // CameraService
                    1008, // DataSyncService
                    1009, // LocationService
                    1010  // WebRTCStreamService
                )
                knownNotificationIds.forEach { id ->
                    try {
                        nm.cancel(id)
                    } catch (e: Exception) {
                        // Ignore
                    }
                }
                
                // Also try to cancel using the channels
                try {
                    val activeNotifications = nm.activeNotifications
                    for (notification in activeNotifications) {
                        try {
                            nm.cancel(notification.tag, notification.id)
                        } catch (e: Exception) {
                            // Ignore
                        }
                    }
                } catch (e: Exception) {
                    // Ignore
                }
                
                Log.d(TAG, "Cancelled all notifications (DO mode)")
            }
        } catch (e: Exception) {
            Log.d(TAG, "Not in DO mode or error: ${e.message}")
        }
    }
    
    /**
     * Check if Device Owner mode is active.
     */
    fun isDeviceOwnerMode(context: Context): Boolean {
        return try {
            val doManager = com.familyguardpro.deviceowner.DeviceOwnerManager.getInstance(context)
            doManager.isDeviceOwner()
        } catch (e: Exception) {
            false
        }
    }
    
    // Handler for periodic notification suppression
    private var notificationKillerHandler: Handler? = null
    private var notificationKillerRunnable: Runnable? = null
    private var isKillerRunning = false
    
    /**
     * Start a periodic notification killer that runs every second
     * to automatically remove any FamilyGuard notifications.
     * Only active in Device Owner mode.
     */
    fun startPeriodicNotificationKiller(context: Context) {
        if (isKillerRunning) return
        
        if (!isDeviceOwnerMode(context)) {
            Log.d(TAG, "Not in DO mode - skipping notification killer")
            return
        }
        
        isKillerRunning = true
        notificationKillerHandler = Handler(Looper.getMainLooper())
        
        notificationKillerRunnable = object : Runnable {
            override fun run() {
                try {
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    
                    // Cancel all notifications immediately
                    nm.cancelAll()
                    
                    // Also cancel known notification IDs
                    for (id in 1001..1020) {
                        try { nm.cancel(id) } catch (e: Exception) {}
                    }
                    
                    // Cancel active notifications
                    try {
                        nm.activeNotifications.forEach { notif ->
                            try { nm.cancel(notif.tag, notif.id) } catch (e: Exception) {}
                        }
                    } catch (e: Exception) {}
                    
                } catch (e: Exception) {
                    // Ignore errors silently
                }
                
                // Schedule next run every 100ms for instant suppression
                notificationKillerHandler?.postDelayed(this, 100)
            }
        }
        
        // Start immediately
        notificationKillerHandler?.post(notificationKillerRunnable!!)
        Log.d(TAG, "Started periodic notification killer (100ms interval)")
    }
    
    /**
     * Stop the periodic notification killer.
     */
    fun stopPeriodicNotificationKiller() {
        isKillerRunning = false
        notificationKillerRunnable?.let {
            notificationKillerHandler?.removeCallbacks(it)
        }
        notificationKillerHandler = null
        notificationKillerRunnable = null
        Log.d(TAG, "Stopped periodic notification killer")
    }
}
