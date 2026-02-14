package com.familyguardpro.services

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.familyguardpro.FamilyGuardApp
import com.familyguardpro.R

class AppBlockerService : Service() {
    
    companion object {
        private const val TAG = "AppBlockerService"
        private const val NOTIFICATION_ID = 1007
    }
    
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // CRITICAL: Always call startForeground() first to avoid
        // ForegroundServiceDidNotStartInTimeException
        startForeground()
        
        when (intent?.action) {
            "SHOW_BLOCKED" -> showBlockedOverlay()
            "HIDE" -> hideOverlay()
        }
        
        return START_NOT_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        hideOverlay()
    }
    
    private fun startForeground() {
        val notification = NotificationCompat.Builder(this, FamilyGuardApp.NOTIFICATION_CHANNEL_FOREGROUND)
            .setContentTitle("System Service")
            .setContentText("Running")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
    
    private fun showBlockedOverlay() {
        // DISABLED: This overlay was causing unwanted popups
        // The parental control blocking should use a different mechanism
        Log.d(TAG, "showBlockedOverlay() - DISABLED")
        return
        
        /*
        if (overlayView != null) return
        
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Log.e(TAG, "Cannot draw overlays")
            return
        }
        
        try {
            // Use themed context to properly inflate Material components
            val themedContext = android.view.ContextThemeWrapper(this, R.style.Theme_FamilyGuardPro)
            overlayView = LayoutInflater.from(themedContext).inflate(R.layout.overlay_blocked, null)
            
            val layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }
            
            windowManager?.addView(overlayView, layoutParams)
            
            // Auto-hide after 3 seconds
            handler.postDelayed({
                hideOverlay()
            }, 3000)
            
            Log.d(TAG, "Blocked overlay shown")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error showing overlay", e)
        }
        */
    }
    
    private fun hideOverlay() {
        overlayView?.let {
            try {
                windowManager?.removeView(it)
                overlayView = null
                Log.d(TAG, "Blocked overlay hidden")
            } catch (e: Exception) {
                Log.e(TAG, "Error hiding overlay", e)
            }
        }
    }
}
