package com.familyguardpro.services

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.os.Handler
import android.os.Looper

/**
 * Dedicated service to cancel Device Management notifications from SystemUI.
 * This runs continuously and cancels any "This device belongs to..." notifications
 * that Android shows when Device Owner mode is active.
 */
class DeviceManagementNotificationCanceller : NotificationListenerService() {
    
    companion object {
        private const val TAG = "DeviceMgmtCanceller"
        
        // Keywords that indicate device management notification
        private val KEYWORDS = listOf(
            "device management",
            "organisation",
            "organization",
            "it admin",
            "managed",
            "device policy",
            "work profile",
            "belongs to",
            "your admin",
            "corporate",
            "enterprise"
        )
        
        // System packages that post device management notifications
        private val SYSTEM_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.vivo.systemuiplugin",
            "com.miui.securitycenter",
            "com.samsung.android.app.dofmanager",
            "com.google.android.gms"
        )
        
        // Channel IDs related to device management
        private val DEVICE_MANAGEMENT_CHANNELS = listOf(
            "device",
            "work",
            "admin",
            "policy",
            "enterprise",
            "managed",
            "mdm"
        )
    }
    
    private val handler = Handler(Looper.getMainLooper())
    private val periodicCancelRunnable = object : Runnable {
        override fun run() {
            cancelExistingDeviceManagementNotifications()
            // Run every 500ms to catch any new notifications quickly
            handler.postDelayed(this, 500)
        }
    }
    
    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "✅ Device Management Notification Canceller connected")
        
        // Cancel existing device management notifications immediately
        cancelExistingDeviceManagementNotifications()
        
        // Start periodic checking
        handler.postDelayed(periodicCancelRunnable, 500)
    }
    
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(TAG, "❌ Device Management Notification Canceller disconnected")
        handler.removeCallbacks(periodicCancelRunnable)
    }
    
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let { notification ->
            // Check if it's device management notification
            if (isDeviceManagementNotification(notification)) {
                Log.d(TAG, "⚠️ Device Management notification detected from ${notification.packageName}")
                
                // Cancel it immediately
                try {
                    cancelNotification(notification.key)
                    Log.d(TAG, "✅ Device Management notification cancelled: ${notification.key}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to cancel notification: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Check if a notification is a device management notification
     */
    private fun isDeviceManagementNotification(sbn: StatusBarNotification): Boolean {
        // First check if it's from a system package
        if (!SYSTEM_PACKAGES.contains(sbn.packageName)) {
            return false
        }
        
        try {
            val notification = sbn.notification
            val extras = notification.extras
            
            // Get notification text content
            val title = extras.getCharSequence("android.title")?.toString() ?: ""
            val text = extras.getCharSequence("android.text")?.toString() ?: ""
            val bigText = extras.getCharSequence("android.bigText")?.toString() ?: ""
            val subText = extras.getCharSequence("android.subText")?.toString() ?: ""
            val infoText = extras.getCharSequence("android.infoText")?.toString() ?: ""
            val summaryText = extras.getCharSequence("android.summaryText")?.toString() ?: ""
            
            // Combine all text for checking
            val allText = "$title $text $bigText $subText $infoText $summaryText".lowercase()
            
            // Check for keywords
            for (keyword in KEYWORDS) {
                if (allText.contains(keyword)) {
                    Log.d(TAG, "Matched keyword: $keyword in: ${allText.take(100)}")
                    return true
                }
            }
            
            // Check channel ID (Android 8+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channelId = notification.channelId?.lowercase() ?: ""
                
                for (channel in DEVICE_MANAGEMENT_CHANNELS) {
                    if (channelId.contains(channel)) {
                        Log.d(TAG, "Matched channel: $channelId")
                        return true
                    }
                }
            }
            
            // Check notification category
            val category = notification.category?.lowercase() ?: ""
            if (category.contains("system") || category.contains("device") || category.contains("service")) {
                // Additional check for system notifications
                if (sbn.packageName == "android" && allText.isNotEmpty()) {
                    // If it's from android package and has text, likely device management
                    return true
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error checking notification: ${e.message}")
        }
        
        return false
    }
    
    /**
     * Cancel all existing device management notifications
     */
    private fun cancelExistingDeviceManagementNotifications() {
        try {
            val notifications = activeNotifications ?: return
            
            var cancelledCount = 0
            for (sbn in notifications) {
                if (isDeviceManagementNotification(sbn)) {
                    try {
                        cancelNotification(sbn.key)
                        cancelledCount++
                        Log.d(TAG, "✅ Cancelled existing notification: ${sbn.packageName}/${sbn.id}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to cancel: ${e.message}")
                    }
                }
            }
            
            if (cancelledCount > 0) {
                Log.d(TAG, "✅ Cancelled $cancelledCount device management notifications")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get active notifications: ${e.message}")
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(periodicCancelRunnable)
    }
}
