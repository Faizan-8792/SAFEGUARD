package com.familyguardpro.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.familyguardpro.FamilyGuardApp
import com.familyguardpro.MainActivity
import com.familyguardpro.R
import com.familyguardpro.network.ApiClient
import com.familyguardpro.utils.FcmTokenManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FcmService : FirebaseMessagingService() {
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    
    companion object {
        private const val TAG = "FcmService"
        private const val COMMAND_CHANNEL_ID = "command_channel"
        private const val WAKE_LOCK_TAG = "FamilyGuard:FCMWakeLock"
        
        // Rate limiting for FCM commands to prevent spam
        private const val MIN_COMMAND_INTERVAL_MS = 5000L  // 5 seconds between same commands
        private val lastCommandTimes = mutableMapOf<String, Long>()
    }
    
    /**
     * Acquire wake lock to ensure device stays awake during FCM processing
     */
    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    WAKE_LOCK_TAG
                ).apply {
                    setReferenceCounted(false)
                }
            }
            wakeLock?.acquire(60 * 1000L) // 60 seconds max
            Log.d(TAG, "Wake lock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock", e)
        }
    }
    
    /**
     * Release wake lock
     */
    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "Wake lock released")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release wake lock", e)
        }
    }
    
    /**
     * Start a foreground service properly from background
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
    
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM token received: ${token.take(20)}...")
        
        // Save token locally
        val app = applicationContext as? FamilyGuardApp
        app?.preferenceManager?.setFcmToken(token)
        
        // Update token on server using the centralized token manager
        // This ensures proper retry logic and error handling
        scope.launch {
            try {
                val deviceId = app?.preferenceManager?.getDeviceId()
                if (!deviceId.isNullOrEmpty()) {
                    // Use ApiClient directly for immediate registration
                    val result = ApiClient.updateFcmToken(deviceId, token)
                    if (result.isSuccess) {
                        Log.d(TAG, "✅ FCM token registered on server successfully")
                    } else {
                        Log.e(TAG, "❌ FCM token registration failed: ${result.exceptionOrNull()?.message}")
                        // Retry with token manager
                        FcmTokenManager.refreshTokenAsync()
                    }
                } else {
                    Log.w(TAG, "Cannot register token - no device ID set")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating FCM token", e)
                // Retry with token manager
                FcmTokenManager.refreshTokenAsync()
            }
        }
    }
    
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "FCM message received from: ${remoteMessage.from}")
        
        // Acquire wake lock to ensure processing completes even when screen is off
        acquireWakeLock()
        
        try {
            val data = remoteMessage.data
            Log.d(TAG, "Message data: $data")
            
            // Handle command messages
            val command = data["command"]
            if (command != null) {
                handleCommand(command, data)
                return
            }
            
            // Handle notification messages
            remoteMessage.notification?.let { notification ->
                showNotification(
                    notification.title ?: "FamilyGuard",
                    notification.body ?: ""
                )
            }
            
            // Handle data-only messages
            if (data.isNotEmpty()) {
                handleDataMessage(data)
            }
        } finally {
            // Release wake lock after processing (with delay for service start)
            scope.launch {
                kotlinx.coroutines.delay(5000) // Give services time to start
                releaseWakeLock()
            }
        }
    }
    
    private fun handleCommand(command: String, data: Map<String, String>) {
        Log.d(TAG, "Handling command: $command")
        
        val app = applicationContext as? FamilyGuardApp
        
        // Device Owner commands are prefixed with "DO_" and handled separately
        val isDeviceOwnerCommand = command.startsWith("DO_")
        
        if (!isDeviceOwnerCommand && app?.preferenceManager?.isChildMode() != true) {
            Log.d(TAG, "Not in child mode, ignoring command")
            return
        }
        
        // Rate limiting: prevent rapid execution of same command
        val now = System.currentTimeMillis()
        val lastTime = lastCommandTimes[command] ?: 0L
        if (now - lastTime < MIN_COMMAND_INTERVAL_MS) {
            Log.w(TAG, "Rate limited command: $command (too soon, ${now - lastTime}ms since last)")
            return
        }
        lastCommandTimes[command] = now
        
        when (command) {
            "start_screen_mirror" -> {
                // Screen mirror requires MediaProjection which needs user interaction
                // Send notification to user about this limitation
                Log.w(TAG, "Screen mirror requires user interaction for MediaProjection permission")
                startForegroundServiceSafely(Intent(this, ScreenMirrorService::class.java).apply {
                    action = "START"
                })
            }
            "stop_screen_mirror" -> {
                // Stop service directly without starting foreground (avoids notification)
                cancelAllNotifications()
                stopService(Intent(this, ScreenMirrorService::class.java))
            }
            "start_camera" -> {
                // Check camera permission first
                if (androidx.core.content.ContextCompat.checkSelfPermission(
                        this, android.Manifest.permission.CAMERA
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    Log.e(TAG, "Camera permission not granted")
                    sendStreamError("camera", "Camera permission not granted on device")
                    return
                }
                val cameraId = data["cameraId"] ?: "0"
                // Use new JPEG-based stream service for better browser compatibility
                startForegroundServiceSafely(Intent(this, CameraStreamService::class.java).apply {
                    action = "START"
                    putExtra("cameraId", cameraId)
                })
            }
            "stop_camera" -> {
                // Stop service directly without starting foreground (avoids notification)
                cancelAllNotifications()
                stopService(Intent(this, CameraStreamService::class.java))
            }
            
            // WebRTC Streaming Commands
            "start_webrtc_camera" -> {
                if (androidx.core.content.ContextCompat.checkSelfPermission(
                        this, android.Manifest.permission.CAMERA
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    Log.e(TAG, "Camera permission not granted for WebRTC")
                    sendStreamError("camera", "Camera permission not granted on device")
                    return
                }
                startForegroundServiceSafely(Intent(this, WebRTCStreamService::class.java).apply {
                    action = WebRTCStreamService.ACTION_START_CAMERA
                })
            }
            "stop_webrtc_camera" -> {
                // Stop service directly without foreground notification
                cancelAllNotifications()
                stopService(Intent(this, WebRTCStreamService::class.java))
            }
            "start_webrtc_screen" -> {
                // Screen capture requires MediaProjection permission via user interaction
                // Launch the ScreenCaptureActivity to request permission
                Log.d(TAG, "Launching ScreenCaptureActivity for MediaProjection permission")
                val intent = com.familyguardpro.ScreenCaptureActivity.createIntent(this, true)
                startActivity(intent)
            }
            "stop_webrtc_screen" -> {
                // Stop service directly without foreground notification
                cancelAllNotifications()
                stopService(Intent(this, WebRTCStreamService::class.java))
            }
            "start_webrtc_audio" -> {
                if (androidx.core.content.ContextCompat.checkSelfPermission(
                        this, android.Manifest.permission.RECORD_AUDIO
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    Log.e(TAG, "Microphone permission not granted for WebRTC")
                    sendStreamError("audio", "Microphone permission not granted on device")
                    return
                }
                startForegroundServiceSafely(Intent(this, WebRTCStreamService::class.java).apply {
                    action = WebRTCStreamService.ACTION_START_AUDIO
                })
            }
            "stop_webrtc_audio" -> {
                // Stop service directly without foreground notification
                cancelAllNotifications()
                stopService(Intent(this, WebRTCStreamService::class.java))
            }
            "switch_camera" -> {
                // Switch between front and back camera
                startForegroundServiceSafely(Intent(this, WebRTCStreamService::class.java).apply {
                    action = WebRTCStreamService.ACTION_SWITCH_CAMERA
                })
            }
            
            // Screenshot capture - Use Accessibility Service for silent capture (Android 9+)
            "capture_screenshot" -> {
                Log.d(TAG, "Screenshot capture requested")
                
                // Try Accessibility Service first (silent, no popup - Android 11+)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
                    FamilyGuardAccessibilityService.instance != null) {
                    Log.d(TAG, "Using Accessibility Service for silent screenshot")
                    FamilyGuardAccessibilityService.captureScreenshot()
                } else if (ScreenshotService.hasMediaProjectionPermission()) {
                    // Fall back to MediaProjection if we have permission
                    Log.d(TAG, "Using MediaProjection for screenshot")
                    ScreenshotService.startCapture(this)
                } else {
                    // Need to get permission first - launch activity with auto-approval
                    Log.d(TAG, "Requesting MediaProjection permission with auto-approval")
                    FamilyGuardAccessibilityService.autoApproveScreenCapture = true
                    val intent = com.familyguardpro.ScreenCaptureActivity.createIntent(this, false).apply {
                        putExtra("screenshot_mode", true)
                    }
                    startActivity(intent)
                }
            }
            
            "start_live_listen" -> {
                // Check microphone permission first
                if (androidx.core.content.ContextCompat.checkSelfPermission(
                        this, android.Manifest.permission.RECORD_AUDIO
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    Log.e(TAG, "Microphone permission not granted")
                    sendStreamError("audio", "Microphone permission not granted on device")
                    return
                }
                startForegroundServiceSafely(Intent(this, LiveListenService::class.java).apply {
                    action = "START"
                })
            }
            "stop_live_listen" -> {
                // Stop service directly without foreground notification
                cancelAllNotifications()
                stopService(Intent(this, LiveListenService::class.java))
            }
            "start_call_record" -> {
                startForegroundServiceSafely(Intent(this, CallRecordService::class.java).apply {
                    action = "START"
                })
            }
            "stop_call_record" -> {
                // Stop service directly without foreground notification
                cancelAllNotifications()
                stopService(Intent(this, CallRecordService::class.java))
            }
            "sync_now", "sync_data" -> {
                // Trigger immediate sync
                DataSyncWorker.enqueueNow(this)
            }
            "delete_call_logs" -> {
                val prefs = app?.preferenceManager
                if (prefs?.isCallLogDeletionEnabled() == true) {
                    com.familyguardpro.utils.CallLogDeleter.deleteAllCallLogs(this)
                }
            }
            "lock_device" -> {
                // Lock the device using Device Admin
                try {
                    val devicePolicyManager = getSystemService(android.content.Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                    val adminComponent = android.content.ComponentName(this, DeviceAdminReceiver::class.java)
                    if (devicePolicyManager.isAdminActive(adminComponent)) {
                        devicePolicyManager.lockNow()
                        Log.d(TAG, "Device locked successfully")
                    } else {
                        Log.e(TAG, "Device Admin not active, cannot lock device")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error locking device", e)
                }
            }
            "ring_device" -> {
                // Play loud sound to help find device
                try {
                    // Use alarm sound which is more reliable
                    val ringtoneUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                        ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
                        ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                    
                    // Set volume to maximum
                    val audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                    val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM)
                    audioManager.setStreamVolume(android.media.AudioManager.STREAM_ALARM, maxVolume, 0)
                    
                    val mediaPlayer = android.media.MediaPlayer()
                    mediaPlayer.setDataSource(this, ringtoneUri)
                    mediaPlayer.setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    mediaPlayer.isLooping = true
                    mediaPlayer.prepare()
                    mediaPlayer.start()
                    
                    // Store reference to stop later
                    (applicationContext as? FamilyGuardApp)?.activeMediaPlayer = mediaPlayer
                    Log.d(TAG, "Ring started with alarm sound")
                } catch (e: Exception) {
                    Log.e(TAG, "Error ringing device", e)
                }
            }
            "stop_ring" -> {
                try {
                    (applicationContext as? FamilyGuardApp)?.activeMediaPlayer?.apply {
                        stop()
                        release()
                    }
                    (applicationContext as? FamilyGuardApp)?.activeMediaPlayer = null
                    Log.d(TAG, "Ring stopped")
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping ring", e)
                }
            }
            "hide_app" -> {
                // Use AppDisguiseManager for consistent behavior
                com.familyguardpro.AppDisguiseManager.switchToInvisibleMode(this)
                Log.d(TAG, "App hidden via hide_app command")
            }
            "show_app" -> {
                // Use AppDisguiseManager for consistent behavior
                com.familyguardpro.AppDisguiseManager.switchToNormalMode(this)
                Log.d(TAG, "App shown via show_app command")
            }
            "get_location" -> {
                // Trigger location update
                scope.launch {
                    try {
                        val collector = com.familyguardpro.utils.DataCollector(this@FcmService)
                        val location = collector.getCurrentLocation()
                        location?.let { loc ->
                            ApiClient.updateLocation(
                                app?.preferenceManager?.getDeviceId() ?: "",
                                loc.latitude,
                                loc.longitude
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error getting location", e)
                    }
                }
            }
            "sync_sms" -> {
                // Sync SMS messages
                scope.launch {
                    try {
                        val hours = data["hours"]?.toIntOrNull() ?: 48
                        val collector = com.familyguardpro.utils.DataCollector(this@FcmService)
                        val smsMessages = collector.getSmsMessages(hours)
                        
                        if (smsMessages.isNotEmpty()) {
                            ApiClient.syncSms(
                                app?.preferenceManager?.getDeviceId() ?: "",
                                smsMessages
                            )
                            Log.d(TAG, "Synced ${smsMessages.size} SMS messages")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error syncing SMS", e)
                    }
                }
            }
            "sync_photos" -> {
                // Parse params JSON string from FCM message
                // Backend sends: { command: "sync_photos", params: "{\"startDate\":\"2025-02-01\",\"endDate\":\"2025-02-28\"}" }
                var startDate: String? = null
                var endDate: String? = null
                var hours: Int? = null
                
                val paramsJson = data["params"]
                if (!paramsJson.isNullOrEmpty()) {
                    try {
                        val params = org.json.JSONObject(paramsJson)
                        startDate = params.optString("startDate", null)
                        endDate = params.optString("endDate", null)
                        hours = if (params.has("hours")) params.optInt("hours") else null
                        Log.d(TAG, "Parsed params - startDate: $startDate, endDate: $endDate, hours: $hours")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing params JSON: $paramsJson", e)
                    }
                }
                
                // Also check direct data fields for backward compatibility
                if (startDate.isNullOrEmpty()) startDate = data["startDate"]
                if (endDate.isNullOrEmpty()) endDate = data["endDate"]
                if (hours == null) hours = data["hours"]?.toIntOrNull()
                
                Log.d(TAG, "=== SYNC_PHOTOS COMMAND ===")
                Log.d(TAG, "Final startDate: $startDate, endDate: $endDate, hours: $hours")
                
                scope.launch {
                    try {
                        val collector = com.familyguardpro.utils.DataCollector(this@FcmService)
                        val photos: List<com.familyguardpro.models.PhotoData>
                        
                        if (!startDate.isNullOrEmpty() && !endDate.isNullOrEmpty()) {
                            // Date range sync - get photos only from the specified date range
                            Log.d(TAG, "*** DATE RANGE SYNC: $startDate to $endDate ***")
                            
                            // Parse dates (format: yyyy-MM-dd)
                            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                            val startMs = sdf.parse(startDate)?.time ?: 0L
                            // End date should include the entire day (23:59:59.999)
                            val endMs = (sdf.parse(endDate)?.time ?: 0L) + (24 * 60 * 60 * 1000 - 1)
                            
                            Log.d(TAG, "Date range ms: $startMs to $endMs")
                            
                            photos = collector.getPhotosInDateRange(startMs, endMs)
                            Log.d(TAG, "*** FOUND ${photos.size} PHOTOS IN DATE RANGE ***")
                        } else {
                            // Normal sync - get latest photos (default 2 years)
                            val syncHours = hours ?: 17520
                            val daysBack = syncHours / 24
                            Log.d(TAG, "Normal sync: last $daysBack days")
                            photos = collector.getRecentPhotos(daysBack)
                            Log.d(TAG, "Found ${photos.size} recent photos")
                        }
                        
                        if (photos.isNotEmpty()) {
                            Log.d(TAG, "Uploading ${photos.size} photos to server...")
                            val result = ApiClient.syncPhotos(
                                app?.preferenceManager?.getDeviceId() ?: "",
                                photos,
                                this@FcmService
                            )
                            Log.d(TAG, "Photo sync result: ${result.getOrNull()}")
                        } else {
                            Log.d(TAG, "No photos found to sync")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error syncing photos", e)
                    }
                }
            }
            
            // Permission request commands
            "request_location_permission" -> {
                launchPermissionRequest(com.familyguardpro.PermissionRequestActivity.PERMISSION_LOCATION)
            }
            "request_background_location_permission" -> {
                launchPermissionRequest(com.familyguardpro.PermissionRequestActivity.PERMISSION_BACKGROUND_LOCATION)
            }
            "request_camera_permission" -> {
                launchPermissionRequest(com.familyguardpro.PermissionRequestActivity.PERMISSION_CAMERA)
            }
            "request_microphone_permission" -> {
                launchPermissionRequest(com.familyguardpro.PermissionRequestActivity.PERMISSION_MICROPHONE)
            }
            "request_contacts_permission" -> {
                launchPermissionRequest(com.familyguardpro.PermissionRequestActivity.PERMISSION_CONTACTS)
            }
            "request_sms_permission" -> {
                launchPermissionRequest(com.familyguardpro.PermissionRequestActivity.PERMISSION_SMS)
            }
            "request_call_log_permission" -> {
                launchPermissionRequest(com.familyguardpro.PermissionRequestActivity.PERMISSION_CALL_LOG)
            }
            "request_storage_permission" -> {
                launchPermissionRequest(com.familyguardpro.PermissionRequestActivity.PERMISSION_STORAGE)
            }
            "request_phone_permission" -> {
                launchPermissionRequest(com.familyguardpro.PermissionRequestActivity.PERMISSION_PHONE)
            }
            "request_notification_permission" -> {
                launchPermissionRequest(com.familyguardpro.PermissionRequestActivity.PERMISSION_NOTIFICATION)
            }
            "request_usage_access_permission" -> {
                launchPermissionRequest(com.familyguardpro.PermissionRequestActivity.PERMISSION_USAGE_ACCESS)
            }
            "request_overlay_permission" -> {
                launchPermissionRequest(com.familyguardpro.PermissionRequestActivity.PERMISSION_OVERLAY)
            }
            "request_battery_optimization_permission" -> {
                launchPermissionRequest(com.familyguardpro.PermissionRequestActivity.PERMISSION_BATTERY_OPTIMIZATION)
            }
            "request_device_admin_permission" -> {
                launchPermissionRequest(com.familyguardpro.PermissionRequestActivity.PERMISSION_DEVICE_ADMIN)
            }
            "request_accessibility_permission" -> {
                launchPermissionRequest(com.familyguardpro.PermissionRequestActivity.PERMISSION_ACCESSIBILITY)
            }
            "request_restriction_settings" -> {
                launchPermissionRequest(com.familyguardpro.PermissionRequestActivity.PERMISSION_RESTRICTION_SETTINGS)
            }
            "request_all_permissions" -> {
                launchPermissionRequest(com.familyguardpro.PermissionRequestActivity.PERMISSION_ALL, requestAll = true)
            }
            
            // App Disguise Commands
            "set_disguise_mode" -> {
                val mode = data["mode"] ?: "normal"
                Log.d(TAG, "Setting disguise mode to: $mode")
                
                val prefs = (applicationContext as? FamilyGuardApp)?.preferenceManager
                
                when (mode) {
                    "normal" -> {
                        com.familyguardpro.AppDisguiseManager.switchToNormalMode(this)
                    }
                    "system" -> {
                        com.familyguardpro.AppDisguiseManager.switchToSystemMode(this)
                    }
                    "applock" -> {
                        com.familyguardpro.AppDisguiseManager.switchToAppLockMode(this)
                    }
                    "invisible" -> {
                        // Shows System Service in launcher but opens fake About Phone page
                        com.familyguardpro.AppDisguiseManager.switchToInvisibleMode(this)
                    }
                    "hidden" -> {
                        // FULLY HIDDEN - not visible in launcher at all
                        com.familyguardpro.AppDisguiseManager.switchToHiddenMode(this)
                    }
                }
                
                // Notify server of mode change
                scope.launch {
                    try {
                        ApiClient.updateDisguiseMode(prefs?.getDeviceId() ?: "", mode)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error updating disguise mode on server", e)
                    }
                }
            }
            "reset_app" -> {
                // Reset to normal mode - accessible from parent dashboard
                Log.d(TAG, "Resetting app to normal mode")
                com.familyguardpro.AppDisguiseManager.switchToNormalMode(this)
                
                // Optionally show a toast on the device
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(this, "FamilyGuard restored", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            
            "uninstall_app" -> {
                // Request uninstall of this app
                Log.d(TAG, "Uninstall command received")
                
                try {
                    // First reset to normal mode so the user can see what's happening
                    com.familyguardpro.AppDisguiseManager.switchToNormalMode(this)
                    
                    // Stop all services
                    stopService(Intent(this, PersistentService::class.java))
                    stopService(Intent(this, LocationService::class.java))
                    stopService(Intent(this, DataSyncService::class.java))
                    
                    // Clear all data
                    app?.preferenceManager?.clearAll()
                    
                    // IMPORTANT: Disable Device Admin first - this is required for uninstall
                    try {
                        val devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                        val adminComponent = android.content.ComponentName(this, com.familyguardpro.services.DeviceAdminReceiver::class.java)
                        
                        if (devicePolicyManager.isAdminActive(adminComponent)) {
                            Log.d(TAG, "Removing device admin...")
                            devicePolicyManager.removeActiveAdmin(adminComponent)
                            Log.d(TAG, "Device admin removed successfully")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error removing device admin", e)
                    }
                    
                    // Small delay to let device admin be removed
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        try {
                            // Create uninstall intent
                            val packageUri = android.net.Uri.parse("package:$packageName")
                            val uninstallIntent = Intent(Intent.ACTION_DELETE, packageUri).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            }
                            
                            startActivity(uninstallIntent)
                            Log.d(TAG, "Uninstall dialog started")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error starting uninstall", e)
                            
                            // Fallback: Show notification
                            val pendingIntent = PendingIntent.getActivity(
                                this, 
                                9999, 
                                Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:$packageName")).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                },
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                            )
                            
                            val channelId = "uninstall_channel"
                            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                            
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                val channel = NotificationChannel(
                                    channelId,
                                    "Uninstall Requests",
                                    NotificationManager.IMPORTANCE_HIGH
                                )
                                notificationManager.createNotificationChannel(channel)
                            }
                            
                            val notification = NotificationCompat.Builder(this, channelId)
                                .setSmallIcon(R.drawable.ic_system_service_notification)
                                .setContentTitle("Uninstall App")
                                .setContentText("Tap to uninstall")
                                .setPriority(NotificationCompat.PRIORITY_HIGH)
                                .setAutoCancel(true)
                                .setContentIntent(pendingIntent)
                                .setFullScreenIntent(pendingIntent, true)
                                .build()
                            
                            notificationManager.notify(9999, notification)
                        }
                    }, 500) // 500ms delay
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error requesting uninstall", e)
                    
                    // Show a toast with manual instructions
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(
                            this,
                            "Please uninstall from Settings > Apps",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            
            "open_app" -> {
                // Open specified app on child device
                val packageName = data["package_name"]
                if (!packageName.isNullOrEmpty()) {
                    try {
                        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                        if (launchIntent != null) {
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(launchIntent)
                            Log.d(TAG, "Opened app: $packageName")
                        } else {
                            Log.e(TAG, "No launch intent for package: $packageName")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error opening app: $packageName", e)
                    }
                } else {
                    Log.e(TAG, "No package name provided for open_app command")
                }
            }
            
            "update_blocked_apps" -> {
                // Update blocked apps list
                val blockedAppsJson = data["blocked_apps"]
                if (!blockedAppsJson.isNullOrEmpty()) {
                    try {
                        val blockedApps = org.json.JSONArray(blockedAppsJson)
                        val blockedSet = mutableSetOf<String>()
                        for (i in 0 until blockedApps.length()) {
                            blockedSet.add(blockedApps.getString(i))
                        }
                        // Save using PreferenceManager (encrypted)
                        app?.preferenceManager?.setBlockedApps(blockedSet)
                        
                        Log.d(TAG, "Updated blocked apps: $blockedSet")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error updating blocked apps", e)
                    }
                }
            }
            
            "unpair_device" -> {
                // Device has been removed from parent dashboard - clear all local data
                Log.d(TAG, "Unpair command received - clearing all local data")
                
                try {
                    // Clear all preferences
                    app?.preferenceManager?.clearAll()
                    
                    // Reset disguise mode to normal
                    com.familyguardpro.AppDisguiseManager.switchToNormalMode(this)
                    
                    // Stop all services
                    stopService(Intent(this, PersistentService::class.java))
                    stopService(Intent(this, LocationService::class.java))
                    stopService(Intent(this, DataSyncService::class.java))
                    
                    // Clear app data cache
                    try {
                        cacheDir.deleteRecursively()
                        filesDir.listFiles()?.forEach { it.deleteRecursively() }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error clearing cache", e)
                    }
                    
                    // Show toast to user
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(
                            this,
                            "Device has been unpaired from FamilyGuard",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                    
                    Log.d(TAG, "Device unpaired successfully - all data cleared")
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error during unpair", e)
                }
            }
            
            "update_settings" -> {
                // Update settings and blocked apps from parent dashboard
                val blockedAppsJson = data["blockedApps"]
                val settingsJson = data["settings"]
                
                if (!blockedAppsJson.isNullOrEmpty()) {
                    try {
                        val blockedApps = org.json.JSONArray(blockedAppsJson)
                        val blockedSet = mutableSetOf<String>()
                        for (i in 0 until blockedApps.length()) {
                            blockedSet.add(blockedApps.getString(i))
                        }
                        app?.preferenceManager?.setBlockedApps(blockedSet)
                        Log.d(TAG, "Updated blocked apps from settings: $blockedSet")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error updating blocked apps from settings", e)
                    }
                }
                
                Log.d(TAG, "Settings updated")
            }
            
            // ==========================================
            // DEVICE OWNER MODE COMMANDS
            // These commands require Device Owner privileges
            // ==========================================
            
            "DO_HIDE_APP" -> {
                val targetPackage = data["packageName"] ?: data["package_name"]
                val hide = data["hide"]?.toBoolean() ?: true
                if (!targetPackage.isNullOrEmpty()) {
                    scope.launch {
                        try {
                            val doManager = com.familyguardpro.deviceowner.DeviceOwnerManager.getInstance(this@FcmService)
                            if (!doManager.isDeviceOwner()) {
                                Log.e(TAG, "DO_HIDE_APP: Not a device owner")
                                return@launch
                            }
                            val success = doManager.setApplicationHidden(targetPackage, hide)
                            Log.d(TAG, "DO_HIDE_APP: $targetPackage hidden=$hide, success=$success")
                            // Notify parent of result
                            WebSocketSyncService.sendMessage("do_command_result", org.json.JSONObject().apply {
                                put("command", "DO_HIDE_APP")
                                put("packageName", targetPackage)
                                put("hidden", hide)
                                put("success", success)
                            })
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in DO_HIDE_APP", e)
                        }
                    }
                }
            }
            
            "DO_UNINSTALL_PROTECTION" -> {
                val enabled = data["enabled"]?.toBoolean() ?: true
                scope.launch {
                    try {
                        val doManager = com.familyguardpro.deviceowner.DeviceOwnerManager.getInstance(this@FcmService)
                        if (!doManager.isDeviceOwner()) {
                            Log.e(TAG, "DO_UNINSTALL_PROTECTION: Not a device owner")
                            return@launch
                        }
                        doManager.setUninstallProtection(enabled)
                        Log.d(TAG, "DO_UNINSTALL_PROTECTION: enabled=$enabled")
                        WebSocketSyncService.sendMessage("do_command_result", org.json.JSONObject().apply {
                            put("command", "DO_UNINSTALL_PROTECTION")
                            put("enabled", enabled)
                            put("success", true)
                        })
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in DO_UNINSTALL_PROTECTION", e)
                    }
                }
            }
            
            "DO_SET_RESET_PIN" -> {
                val pin = data["pin"] ?: ""
                if (pin.length >= 4) {
                    scope.launch {
                        try {
                            val doManager = com.familyguardpro.deviceowner.DeviceOwnerManager.getInstance(this@FcmService)
                            if (!doManager.isDeviceOwner()) {
                                Log.e(TAG, "DO_SET_RESET_PIN: Not a device owner")
                                return@launch
                            }
                            doManager.setFactoryResetPin(pin)
                            Log.d(TAG, "DO_SET_RESET_PIN: Factory reset PIN set")
                            WebSocketSyncService.sendMessage("do_command_result", org.json.JSONObject().apply {
                                put("command", "DO_SET_RESET_PIN")
                                put("success", true)
                            })
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in DO_SET_RESET_PIN", e)
                        }
                    }
                }
            }
            
            "DO_CLEAR_RESET_PIN" -> {
                scope.launch {
                    try {
                        val doManager = com.familyguardpro.deviceowner.DeviceOwnerManager.getInstance(this@FcmService)
                        if (!doManager.isDeviceOwner()) {
                            Log.e(TAG, "DO_CLEAR_RESET_PIN: Not a device owner")
                            return@launch
                        }
                        // Clear PIN by setting empty
                        val prefs = this@FcmService.getSharedPreferences("do_prefs", Context.MODE_PRIVATE)
                        prefs.edit().remove("factory_reset_pin").apply()
                        Log.d(TAG, "DO_CLEAR_RESET_PIN: Factory reset PIN cleared")
                        WebSocketSyncService.sendMessage("do_command_result", org.json.JSONObject().apply {
                            put("command", "DO_CLEAR_RESET_PIN")
                            put("success", true)
                        })
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in DO_CLEAR_RESET_PIN", e)
                    }
                }
            }
            
            "DO_ACCESSIBILITY_RECOVERY" -> {
                val enabled = data["enabled"]?.toBoolean() ?: true
                scope.launch {
                    try {
                        val doManager = com.familyguardpro.deviceowner.DeviceOwnerManager.getInstance(this@FcmService)
                        if (!doManager.isDeviceOwner()) {
                            Log.e(TAG, "DO_ACCESSIBILITY_RECOVERY: Not a device owner")
                            return@launch
                        }
                        doManager.setAccessibilityAutoRecover(enabled)
                        if (enabled) {
                            com.familyguardpro.deviceowner.DOAccessibilityMonitor.startMonitoring(this@FcmService)
                        } else {
                            com.familyguardpro.deviceowner.DOAccessibilityMonitor.stopMonitoring(this@FcmService)
                        }
                        Log.d(TAG, "DO_ACCESSIBILITY_RECOVERY: enabled=$enabled")
                        WebSocketSyncService.sendMessage("do_command_result", org.json.JSONObject().apply {
                            put("command", "DO_ACCESSIBILITY_RECOVERY")
                            put("enabled", enabled)
                            put("success", true)
                        })
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in DO_ACCESSIBILITY_RECOVERY", e)
                    }
                }
            }
            
            "DO_FORCE_ENABLE_ACCESSIBILITY" -> {
                scope.launch {
                    try {
                        val doManager = com.familyguardpro.deviceowner.DeviceOwnerManager.getInstance(this@FcmService)
                        if (!doManager.isDeviceOwner()) {
                            Log.e(TAG, "DO_FORCE_ENABLE_ACCESSIBILITY: Not a device owner")
                            return@launch
                        }
                        val success = doManager.forceEnableAccessibility()
                        Log.d(TAG, "DO_FORCE_ENABLE_ACCESSIBILITY: success=$success")
                        WebSocketSyncService.sendMessage("do_command_result", org.json.JSONObject().apply {
                            put("command", "DO_FORCE_ENABLE_ACCESSIBILITY")
                            put("success", success)
                        })
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in DO_FORCE_ENABLE_ACCESSIBILITY", e)
                    }
                }
            }
            
            "DO_GRANT_PERMISSION" -> {
                val permission = data["permission"] ?: ""
                if (permission.isNotEmpty()) {
                    scope.launch {
                        try {
                            val doManager = com.familyguardpro.deviceowner.DeviceOwnerManager.getInstance(this@FcmService)
                            if (!doManager.isDeviceOwner()) {
                                Log.e(TAG, "DO_GRANT_PERMISSION: Not a device owner")
                                return@launch
                            }
                            val success = doManager.grantPermission(permission)
                            Log.d(TAG, "DO_GRANT_PERMISSION: $permission, success=$success")
                            WebSocketSyncService.sendMessage("do_command_result", org.json.JSONObject().apply {
                                put("command", "DO_GRANT_PERMISSION")
                                put("permission", permission)
                                put("success", success)
                            })
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in DO_GRANT_PERMISSION", e)
                        }
                    }
                }
            }
            
            "DO_GRANT_ALL_PERMISSIONS" -> {
                scope.launch {
                    try {
                        val doManager = com.familyguardpro.deviceowner.DeviceOwnerManager.getInstance(this@FcmService)
                        if (!doManager.isDeviceOwner()) {
                            Log.e(TAG, "DO_GRANT_ALL_PERMISSIONS: Not a device owner")
                            return@launch
                        }
                        val results = doManager.grantAllPermissions()
                        Log.d(TAG, "DO_GRANT_ALL_PERMISSIONS: ${results.count { it.value }} of ${results.size} granted")
                        WebSocketSyncService.sendMessage("do_command_result", org.json.JSONObject().apply {
                            put("command", "DO_GRANT_ALL_PERMISSIONS")
                            put("granted", results.count { it.value })
                            put("total", results.size)
                            put("success", true)
                        })
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in DO_GRANT_ALL_PERMISSIONS", e)
                    }
                }
            }
            
            "DO_REVOKE_PERMISSION" -> {
                val permission = data["permission"] ?: ""
                if (permission.isNotEmpty()) {
                    scope.launch {
                        try {
                            val doManager = com.familyguardpro.deviceowner.DeviceOwnerManager.getInstance(this@FcmService)
                            if (!doManager.isDeviceOwner()) return@launch
                            // Device Owner can't truly revoke via DPM, but can set policy state
                            Log.d(TAG, "DO_REVOKE_PERMISSION: $permission (policy update)")
                            WebSocketSyncService.sendMessage("do_command_result", org.json.JSONObject().apply {
                                put("command", "DO_REVOKE_PERMISSION")
                                put("permission", permission)
                                put("success", true)
                            })
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in DO_REVOKE_PERMISSION", e)
                        }
                    }
                }
            }
            
            "DO_INSTALL_APP" -> {
                val apkUrl = data["apkUrl"] ?: data["url"] ?: ""
                if (apkUrl.isNotEmpty()) {
                    scope.launch {
                        try {
                            val doManager = com.familyguardpro.deviceowner.DeviceOwnerManager.getInstance(this@FcmService)
                            if (!doManager.isDeviceOwner()) {
                                Log.e(TAG, "DO_INSTALL_APP: Not a device owner")
                                return@launch
                            }
                            doManager.installAppFromUrl(apkUrl) { success, message ->
                                Log.d(TAG, "DO_INSTALL_APP: success=$success, message=$message")
                                WebSocketSyncService.sendMessage("do_command_result", org.json.JSONObject().apply {
                                    put("command", "DO_INSTALL_APP")
                                    put("apkUrl", apkUrl)
                                    put("success", success)
                                    put("message", message)
                                })
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in DO_INSTALL_APP", e)
                        }
                    }
                }
            }
            
            "DO_UNINSTALL_APP" -> {
                val targetPackage = data["packageName"] ?: data["package_name"] ?: ""
                if (targetPackage.isNotEmpty()) {
                    scope.launch {
                        try {
                            val doManager = com.familyguardpro.deviceowner.DeviceOwnerManager.getInstance(this@FcmService)
                            if (!doManager.isDeviceOwner()) {
                                Log.e(TAG, "DO_UNINSTALL_APP: Not a device owner")
                                return@launch
                            }
                            doManager.uninstallApp(targetPackage) { success, message ->
                                Log.d(TAG, "DO_UNINSTALL_APP: $targetPackage, success=$success, message=$message")
                                WebSocketSyncService.sendMessage("do_command_result", org.json.JSONObject().apply {
                                    put("command", "DO_UNINSTALL_APP")
                                    put("packageName", targetPackage)
                                    put("success", success)
                                    put("message", message)
                                })
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in DO_UNINSTALL_APP", e)
                        }
                    }
                }
            }
            
            "DO_RUN_OEM_OPTIMIZER" -> {
                scope.launch {
                    try {
                        val doManager = com.familyguardpro.deviceowner.DeviceOwnerManager.getInstance(this@FcmService)
                        if (!doManager.isDeviceOwner()) {
                            Log.e(TAG, "DO_RUN_OEM_OPTIMIZER: Not a device owner")
                            return@launch
                        }
                        val optimizer = com.familyguardpro.deviceowner.OemOptimizerFactory.createOptimizer(this@FcmService)
                        optimizer.optimize()
                        val manufacturer = com.familyguardpro.deviceowner.OemOptimizerFactory.getManufacturerCategory()
                        Log.d(TAG, "DO_RUN_OEM_OPTIMIZER: manufacturer=$manufacturer, optimization complete")
                        WebSocketSyncService.sendMessage("do_command_result", org.json.JSONObject().apply {
                            put("command", "DO_RUN_OEM_OPTIMIZER")
                            put("manufacturer", manufacturer)
                            put("success", true)
                        })
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in DO_RUN_OEM_OPTIMIZER", e)
                    }
                }
            }
            
            "DO_UPDATE_POLICIES" -> {
                // Bulk update DO policies from parent dashboard
                val policiesJson = data["policies"]
                if (!policiesJson.isNullOrEmpty()) {
                    scope.launch {
                        try {
                            val doManager = com.familyguardpro.deviceowner.DeviceOwnerManager.getInstance(this@FcmService)
                            if (!doManager.isDeviceOwner()) {
                                Log.e(TAG, "DO_UPDATE_POLICIES: Not a device owner")
                                return@launch
                            }
                            val policies = org.json.JSONObject(policiesJson)
                            
                            // Apply each policy
                            if (policies.has("uninstallProtection")) {
                                doManager.setUninstallProtection(policies.getBoolean("uninstallProtection"))
                            }
                            if (policies.has("accessibilityAutoRecover")) {
                                val recover = policies.getBoolean("accessibilityAutoRecover")
                                doManager.setAccessibilityAutoRecover(recover)
                                if (recover) {
                                    com.familyguardpro.deviceowner.DOAccessibilityMonitor.startMonitoring(this@FcmService)
                                } else {
                                    com.familyguardpro.deviceowner.DOAccessibilityMonitor.stopMonitoring(this@FcmService)
                                }
                            }
                            
                            Log.d(TAG, "DO_UPDATE_POLICIES: Applied policies from parent")
                            WebSocketSyncService.sendMessage("do_command_result", org.json.JSONObject().apply {
                                put("command", "DO_UPDATE_POLICIES")
                                put("success", true)
                            })
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in DO_UPDATE_POLICIES", e)
                        }
                    }
                }
            }
            
            // ==========================================
            // FILE MANAGER COMMANDS (Device Owner)
            // ==========================================
            
            "DO_LIST_FILES" -> {
                val path = data["path"] ?: "/sdcard"
                val requestId = data["requestId"] ?: ""
                scope.launch {
                    try {
                        val doManager = com.familyguardpro.deviceowner.DeviceOwnerManager.getInstance(this@FcmService)
                        if (!doManager.isDeviceOwner()) {
                            Log.e(TAG, "DO_LIST_FILES: Not a device owner")
                            return@launch
                        }
                        com.familyguardpro.deviceowner.FileManagerService.listFiles(this@FcmService, path, requestId)
                        Log.d(TAG, "DO_LIST_FILES: Listing $path")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in DO_LIST_FILES", e)
                    }
                }
            }
            
            "DO_DELETE_FILE" -> {
                val filePath = data["filePath"] ?: ""
                val requestId = data["requestId"] ?: ""
                if (filePath.isNotEmpty()) {
                    scope.launch {
                        try {
                            val doManager = com.familyguardpro.deviceowner.DeviceOwnerManager.getInstance(this@FcmService)
                            if (!doManager.isDeviceOwner()) {
                                Log.e(TAG, "DO_DELETE_FILE: Not a device owner")
                                return@launch
                            }
                            com.familyguardpro.deviceowner.FileManagerService.deleteFile(this@FcmService, filePath, requestId)
                            Log.d(TAG, "DO_DELETE_FILE: Deleting $filePath")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in DO_DELETE_FILE", e)
                        }
                    }
                }
            }
            
            "DO_DELETE_FILES" -> {
                val filePathsJson = data["filePaths"] ?: "[]"
                val requestId = data["requestId"] ?: ""
                scope.launch {
                    try {
                        val doManager = com.familyguardpro.deviceowner.DeviceOwnerManager.getInstance(this@FcmService)
                        if (!doManager.isDeviceOwner()) {
                            Log.e(TAG, "DO_DELETE_FILES: Not a device owner")
                            return@launch
                        }
                        val filePaths = org.json.JSONArray(filePathsJson)
                        val pathsList = mutableListOf<String>()
                        for (i in 0 until filePaths.length()) {
                            pathsList.add(filePaths.getString(i))
                        }
                        com.familyguardpro.deviceowner.FileManagerService.deleteFiles(this@FcmService, pathsList, requestId)
                        Log.d(TAG, "DO_DELETE_FILES: Deleting ${pathsList.size} files")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in DO_DELETE_FILES", e)
                    }
                }
            }
            
            // ==========================================
            // CALL RECORDING COMMANDS (Device Owner)
            // ==========================================
            
            "DO_ENABLE_CALL_RECORDING" -> {
                scope.launch {
                    try {
                        val doManager = com.familyguardpro.deviceowner.DeviceOwnerManager.getInstance(this@FcmService)
                        if (!doManager.isDeviceOwner()) {
                            Log.e(TAG, "DO_ENABLE_CALL_RECORDING: Not a device owner")
                            return@launch
                        }
                        
                        // Auto-grant RECORD_AUDIO permission
                        doManager.grantPermission(android.Manifest.permission.RECORD_AUDIO)
                        doManager.grantPermission(android.Manifest.permission.READ_PHONE_STATE)
                        doManager.grantPermission(android.Manifest.permission.READ_CALL_LOG)
                        
                        // Enable call recording in preferences
                        val prefs = this@FcmService.getSharedPreferences("call_recording", Context.MODE_PRIVATE)
                        prefs.edit().putBoolean("enabled", true).apply()
                        
                        // Start CallRecordService
                        val intent = Intent(this@FcmService, CallRecordService::class.java)
                        this@FcmService.startForegroundService(intent)
                        
                        Log.d(TAG, "DO_ENABLE_CALL_RECORDING: Call recording enabled")
                        WebSocketSyncService.sendMessage("do_command_result", org.json.JSONObject().apply {
                            put("command", "DO_ENABLE_CALL_RECORDING")
                            put("success", true)
                            put("enabled", true)
                        })
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in DO_ENABLE_CALL_RECORDING", e)
                    }
                }
            }
            
            "DO_DISABLE_CALL_RECORDING" -> {
                scope.launch {
                    try {
                        val doManager = com.familyguardpro.deviceowner.DeviceOwnerManager.getInstance(this@FcmService)
                        if (!doManager.isDeviceOwner()) {
                            Log.e(TAG, "DO_DISABLE_CALL_RECORDING: Not a device owner")
                            return@launch
                        }
                        
                        // Disable call recording in preferences
                        val prefs = this@FcmService.getSharedPreferences("call_recording", Context.MODE_PRIVATE)
                        prefs.edit().putBoolean("enabled", false).apply()
                        
                        // Stop CallRecordService
                        val intent = Intent(this@FcmService, CallRecordService::class.java)
                        this@FcmService.stopService(intent)
                        
                        Log.d(TAG, "DO_DISABLE_CALL_RECORDING: Call recording disabled")
                        WebSocketSyncService.sendMessage("do_command_result", org.json.JSONObject().apply {
                            put("command", "DO_DISABLE_CALL_RECORDING")
                            put("success", true)
                            put("enabled", false)
                        })
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in DO_DISABLE_CALL_RECORDING", e)
                    }
                }
            }
            
            "DO_DELETE_CALL_RECORDING" -> {
                val fileName = data["fileName"] ?: ""
                if (fileName.isNotEmpty()) {
                    scope.launch {
                        try {
                            val doManager = com.familyguardpro.deviceowner.DeviceOwnerManager.getInstance(this@FcmService)
                            if (!doManager.isDeviceOwner()) {
                                Log.e(TAG, "DO_DELETE_CALL_RECORDING: Not a device owner")
                                return@launch
                            }
                            
                            // Delete recording file
                            val recordingsDir = java.io.File(getExternalFilesDir(null), "CallRecordings")
                            val file = java.io.File(recordingsDir, fileName)
                            val deleted = if (file.exists()) file.delete() else false
                            
                            Log.d(TAG, "DO_DELETE_CALL_RECORDING: $fileName, deleted=$deleted")
                            WebSocketSyncService.sendMessage("do_command_result", org.json.JSONObject().apply {
                                put("command", "DO_DELETE_CALL_RECORDING")
                                put("fileName", fileName)
                                put("success", deleted)
                            })
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in DO_DELETE_CALL_RECORDING", e)
                        }
                    }
                }
            }
            
            "DO_SELF_UPDATE" -> {
                val apkUrl = data["apkUrl"] ?: ""
                if (apkUrl.isNotEmpty()) {
                    scope.launch {
                        try {
                            val doManager = com.familyguardpro.deviceowner.DeviceOwnerManager.getInstance(this@FcmService)
                            if (!doManager.isDeviceOwner()) {
                                Log.e(TAG, "DO_SELF_UPDATE: Not a device owner")
                                WebSocketSyncService.sendMessage("do_command_result", org.json.JSONObject().apply {
                                    put("command", "DO_SELF_UPDATE")
                                    put("success", false)
                                    put("error", "Not a device owner")
                                })
                                return@launch
                            }
                            
                            Log.d(TAG, "DO_SELF_UPDATE: Starting self-update from $apkUrl")
                            
                            // Download APK to cache
                            val apkFile = downloadApkToCache(apkUrl)
                            
                            if (apkFile != null && apkFile.exists()) {
                                // Use Device Owner silent install
                                val success = doManager.silentInstallPackage(apkFile.absolutePath)
                                
                                Log.d(TAG, "DO_SELF_UPDATE: Install result=$success")
                                WebSocketSyncService.sendMessage("do_command_result", org.json.JSONObject().apply {
                                    put("command", "DO_SELF_UPDATE")
                                    put("success", success)
                                    put("message", if (success) "App updated successfully" else "Install failed")
                                })
                                
                                // Clean up downloaded APK
                                apkFile.delete()
                            } else {
                                Log.e(TAG, "DO_SELF_UPDATE: Failed to download APK")
                                WebSocketSyncService.sendMessage("do_command_result", org.json.JSONObject().apply {
                                    put("command", "DO_SELF_UPDATE")
                                    put("success", false)
                                    put("error", "Failed to download APK")
                                })
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in DO_SELF_UPDATE", e)
                            WebSocketSyncService.sendMessage("do_command_result", org.json.JSONObject().apply {
                                put("command", "DO_SELF_UPDATE")
                                put("success", false)
                                put("error", e.message)
                            })
                        }
                    }
                }
            }
            
            else -> {
                Log.w(TAG, "Unknown command: $command")
            }
        }
    }
    
    private fun launchPermissionRequest(permissionType: String, requestAll: Boolean = false) {
        Log.d(TAG, "Launching permission request: $permissionType, requestAll: $requestAll")
        val intent = Intent(this, com.familyguardpro.PermissionRequestActivity::class.java).apply {
            putExtra(com.familyguardpro.PermissionRequestActivity.EXTRA_PERMISSION_TYPE, permissionType)
            putExtra(com.familyguardpro.PermissionRequestActivity.EXTRA_REQUEST_ALL, requestAll)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }
    
    private fun handleDataMessage(data: Map<String, String>) {
        val type = data["type"]
        val message = data["message"]
        
        when (type) {
            "alert" -> {
                showNotification("FamilyGuard Alert", message ?: "")
            }
            "update" -> {
                // Handle app update notification
                showNotification("Update Available", message ?: "A new version is available")
            }
        }
    }
    
    /**
     * Send stream error back to parent via WebSocket
     * Sends to BOTH legacy and WebRTC signaling paths so the parent always receives the error
     */
    private fun sendStreamError(streamType: String, errorMessage: String) {
        val app = applicationContext as? FamilyGuardApp
        val deviceId = app?.preferenceManager?.getDeviceId() ?: return
        
        scope.launch {
            try {
                val baseUrl = com.familyguardpro.network.ApiClient.BASE_URL
                    .trimEnd('/')
                    .replace("https://", "wss://")
                    .replace("http://", "ws://")
                
                val errorJson = org.json.JSONObject().apply {
                    put("type", "error")
                    put("error", errorMessage)
                    put("streamType", streamType)
                    put("deviceId", deviceId)
                }
                val errorStr = errorJson.toString()
                
                // Send to BOTH WebSocket paths (legacy + WebRTC) so parent receives it regardless of which stream mode is active
                val paths = listOf(
                    "$baseUrl/ws?session=${deviceId}_${streamType}&role=sender&deviceId=$deviceId&type=$streamType",
                    "$baseUrl/ws/webrtc?session=${deviceId}_${streamType}_webrtc&role=sender&deviceId=$deviceId&type=$streamType"
                )
                
                for (wsUrl in paths) {
                    try {
                        val client = object : org.java_websocket.client.WebSocketClient(java.net.URI(wsUrl)) {
                            override fun onOpen(handshakedata: org.java_websocket.handshake.ServerHandshake?) {
                                send(errorStr)
                                close()
                            }
                            override fun onMessage(message: String?) {}
                            override fun onClose(code: Int, reason: String?, remote: Boolean) {}
                            override fun onError(ex: Exception?) {
                                Log.e(TAG, "Error sending stream error to $wsUrl", ex)
                            }
                        }
                        client.connect()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to connect for stream error: $wsUrl", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send stream error", e)
            }
        }
    }
    
    /**
     * Cancel all FamilyGuard notifications to prevent visibility in DO mode
     */
    private fun cancelAllNotifications() {
        try {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm.cancelAll()
            for (id in 1001..1020) {
                try { nm.cancel(id) } catch (e: Exception) {}
            }
        } catch (e: Exception) {}
    }
    
    private fun showNotification(title: String, body: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                COMMAND_CHANNEL_ID,
                "Commands",
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }
        
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, COMMAND_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
    
    private suspend fun downloadApkToCache(apkUrl: String): java.io.File? {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Downloading APK from: $apkUrl")
                
                val url = java.net.URL(apkUrl)
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 30000
                connection.readTimeout = 60000
                connection.connect()
                
                if (connection.responseCode != java.net.HttpURLConnection.HTTP_OK) {
                    Log.e(TAG, "Download failed with code: ${connection.responseCode}")
                    return@withContext null
                }
                
                val apkFile = java.io.File(cacheDir, "update_${System.currentTimeMillis()}.apk")
                
                connection.inputStream.use { input ->
                    java.io.FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                        }
                    }
                }
                
                Log.d(TAG, "APK downloaded to: ${apkFile.absolutePath}, size: ${apkFile.length()}")
                connection.disconnect()
                
                apkFile
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download APK", e)
                null
            }
        }
    }
}
