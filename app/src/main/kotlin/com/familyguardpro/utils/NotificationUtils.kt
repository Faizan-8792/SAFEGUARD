package com.familyguardpro.utils

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Utility for handling notification suppression in Device Owner mode.
 * 
 * When the app is provisioned as Device Owner, we want to hide all
 * FamilyGuard notifications from the notification shade to make
 * the app completely invisible to the child.
 */
object NotificationUtils {
    private const val TAG = "NotificationUtils"
    
    /**
     * Suppress a foreground service's notification in Device Owner mode.
     * 
     * Call this AFTER startForeground() to remove the notification from
     * the shade while keeping the service running.
     * 
     * @param service The foreground service
     * @param notificationId The notification ID used in startForeground()
     * @param delayMs Delay before removing notification (default 2000ms)
     */
    fun suppressForegroundNotificationIfDeviceOwner(
        service: Service,
        notificationId: Int,
        delayMs: Long = 2000
    ) {
        try {
            val doManager = com.familyguardpro.deviceowner.DeviceOwnerManager.getInstance(service)
            if (doManager.isDeviceOwner()) {
                // Use longer delay to ensure Android registers the foreground start
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        // Use STOP_FOREGROUND_DETACH - keeps service as foreground but 
                        // detaches the notification so it can be cancelled
                        service.stopForeground(Service.STOP_FOREGROUND_DETACH)
                        
                        // Also explicitly cancel the notification
                        val nm = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        nm.cancel(notificationId)
                        
                        Log.d(TAG, "Suppressed notification $notificationId for ${service.javaClass.simpleName}")
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not suppress notification: ${e.message}")
                    }
                }, delayMs)
            }
        } catch (e: Exception) {
            // DeviceOwnerManager not available or not DO mode
            Log.d(TAG, "Not in DO mode or error: ${e.message}")
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
}
