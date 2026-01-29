package com.familyguardpro.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.familyguardpro.R
import com.familyguardpro.utils.PreferenceManager

/**
 * Service to display block overlay when app is blocked
 * Works in conjunction with FamilyGuardAccessibilityService
 */
class AppBlockerService : Service() {

    companion object {
        private const val TAG = "AppBlockerService"
        private const val CHANNEL_ID = "app_blocker_channel"
        private const val NOTIFICATION_ID = 2001
    }

    private lateinit var preferenceManager: PreferenceManager
    private var windowManager: WindowManager? = null
    private var blockOverlay: android.view.View? = null
    private var currentlyBlocking: String? = null

    override fun onCreate() {
        super.onCreate()
        preferenceManager = PreferenceManager(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val packageName = intent?.getStringExtra("blocked_package")
        
        if (packageName != null) {
            showBlockOverlay(packageName)
        } else {
            hideBlockOverlay()
        }
        
        // Start as foreground to prevent being killed
        startForeground(NOTIFICATION_ID, createNotification())
        
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "App Blocker",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "App blocking notifications"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FamilyGuard Active")
            .setContentText("App protection is running")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun showBlockOverlay(packageName: String) {
        if (currentlyBlocking == packageName) return
        
        hideBlockOverlay()
        currentlyBlocking = packageName
        
        try {
            blockOverlay = LayoutInflater.from(this).inflate(R.layout.overlay_blocked, null)
            
            val appName = try {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(packageName, 0)
                ).toString()
            } catch (e: Exception) {
                packageName
            }
            
            blockOverlay?.findViewById<TextView>(R.id.tvBlockedAppName)?.text = appName
            
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }
            
            windowManager?.addView(blockOverlay, params)
            
            Log.d(TAG, "Blocked app: $appName")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error showing block overlay", e)
        }
    }

    private fun hideBlockOverlay() {
        try {
            blockOverlay?.let {
                windowManager?.removeView(it)
            }
        } catch (e: Exception) {
            // Ignore
        }
        blockOverlay = null
        currentlyBlocking = null
    }

    override fun onDestroy() {
        super.onDestroy()
        hideBlockOverlay()
    }
}
