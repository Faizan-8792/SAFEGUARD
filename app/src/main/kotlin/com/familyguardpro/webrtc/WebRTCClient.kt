package com.familyguardpro.webrtc

import android.content.Context
import android.media.projection.MediaProjection
import android.util.Log
import org.json.JSONObject
import org.webrtc.*
import java.util.concurrent.Executors

/**
 * WebRTC Client for real-time streaming of camera, screen, and audio
 * Features: Adaptive bitrate, reconnection, multiple TURN servers
 */
class WebRTCClient(
    private val context: Context,
    private val listener: WebRTCListener
) {
    companion object {
        private const val TAG = "WebRTCClient"
        
        // Video constraints - Standard quality (balanced)
        private const val VIDEO_WIDTH = 640
        private const val VIDEO_HEIGHT = 480
        private const val VIDEO_FPS = 15
        
        // Screen capture constraints
        private const val SCREEN_WIDTH = 720
        private const val SCREEN_HEIGHT = 1280
        private const val SCREEN_FPS = 10
        
        // Audio constraints
        private const val AUDIO_SAMPLE_RATE = 16000
        private const val AUDIO_CHANNELS = 1
        
        // Adaptive Bitrate settings (bps)
        private const val MIN_BITRATE = 100_000  // 100 kbps
        private const val MAX_BITRATE = 1_500_000 // 1.5 Mbps
        private const val START_BITRATE = 500_000 // 500 kbps
        
        // Quality presets
        enum class StreamQuality {
            LOW,     // 320x240 @ 10fps, 200kbps
            MEDIUM,  // 640x480 @ 15fps, 500kbps
            HIGH,    // 1280x720 @ 24fps, 1.5Mbps
            AUTO     // Adaptive based on network
        }
    }
    
    private val executor = Executors.newSingleThreadExecutor()
    
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localVideoSource: VideoSource? = null
    private var localAudioSource: AudioSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var screenCapturer: ScreenCapturerAndroid? = null
    
    private var eglBase: EglBase? = null
    
    // Adaptive bitrate state
    private var currentQuality = StreamQuality.AUTO
    private var currentBitrate = START_BITRATE
    private var lastBandwidthCheckTime = 0L
    private var consecutiveLowQualityCount = 0
    private var consecutiveHighQualityCount = 0
    
    // Adaptive resolution state
    private var currentVideoWidth = VIDEO_WIDTH
    private var currentVideoHeight = VIDEO_HEIGHT
    private var currentVideoFps = VIDEO_FPS
    private var isScreenStream = false
    private var adaptiveMonitorRunning = false
    private var lastBytesSent = 0L
    private var lastStatsTimestamp = 0L
    private var currentActualBitrateBps = 0
    private var reconnectAttempts = 0
    private val MAX_RECONNECT_ATTEMPTS = 10
    private var connectionLostTime = 0L
    
    // Quality tiers for resolution scaling
    data class QualityTier(
        val width: Int,
        val height: Int,
        val fps: Int,
        val maxBitrate: Int,
        val label: String
    )
    
    private val cameraQualityTiers = listOf(
        QualityTier(160, 120, 8, 80_000, "VERY_LOW"),      // ~80 kbps - survival mode
        QualityTier(240, 180, 10, 150_000, "LOW"),           // ~150 kbps
        QualityTier(320, 240, 12, 250_000, "MEDIUM_LOW"),    // ~250 kbps
        QualityTier(480, 360, 15, 400_000, "MEDIUM"),        // ~400 kbps
        QualityTier(640, 480, 15, 600_000, "MEDIUM_HIGH"),   // ~600 kbps
        QualityTier(960, 720, 20, 1_000_000, "HIGH"),        // ~1 Mbps
        QualityTier(1280, 720, 24, 1_500_000, "VERY_HIGH")   // ~1.5 Mbps
    )
    
    private val screenQualityTiers = listOf(
        QualityTier(360, 640, 5, 100_000, "VERY_LOW"),       // survival
        QualityTier(480, 854, 8, 200_000, "LOW"),
        QualityTier(540, 960, 10, 350_000, "MEDIUM_LOW"),
        QualityTier(720, 1280, 10, 500_000, "MEDIUM"),
        QualityTier(720, 1280, 15, 800_000, "MEDIUM_HIGH"),
        QualityTier(1080, 1920, 10, 1_200_000, "HIGH"),
        QualityTier(1080, 1920, 15, 1_800_000, "VERY_HIGH")
    )
    
    private var currentTierIndex = 3 // Start at MEDIUM
    
    // ICE servers for STUN/TURN - TURN is essential for NAT traversal on mobile networks
    // Multiple TURN providers for reliability
    private val iceServers = listOf(
        // Google STUN servers
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
        
        // Metered.ca TURN servers
        PeerConnection.IceServer.builder("turn:a.relay.metered.ca:80")
            .setUsername("83eebabf8b4cce9d5dbcbbb4")
            .setPassword("2D7JvfkOQtBdYW3R")
            .createIceServer(),
        PeerConnection.IceServer.builder("turn:a.relay.metered.ca:80?transport=tcp")
            .setUsername("83eebabf8b4cce9d5dbcbbb4")
            .setPassword("2D7JvfkOQtBdYW3R")
            .createIceServer(),
        PeerConnection.IceServer.builder("turn:a.relay.metered.ca:443")
            .setUsername("83eebabf8b4cce9d5dbcbbb4")
            .setPassword("2D7JvfkOQtBdYW3R")
            .createIceServer(),
        PeerConnection.IceServer.builder("turn:a.relay.metered.ca:443?transport=tcp")
            .setUsername("83eebabf8b4cce9d5dbcbbb4")
            .setPassword("2D7JvfkOQtBdYW3R")
            .createIceServer(),
        PeerConnection.IceServer.builder("turns:a.relay.metered.ca:443")
            .setUsername("83eebabf8b4cce9d5dbcbbb4")
            .setPassword("2D7JvfkOQtBdYW3R")
            .createIceServer(),
            
        // OpenRelay TURN servers (free, no auth required)
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
            .setUsername("openrelayproject")
            .setPassword("openrelayproject")
            .createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
            .setUsername("openrelayproject")
            .setPassword("openrelayproject")
            .createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=tcp")
            .setUsername("openrelayproject")
            .setPassword("openrelayproject")
            .createIceServer()
    )
    
    interface WebRTCListener {
        fun onLocalDescription(sdp: SessionDescription)
        fun onIceCandidate(candidate: IceCandidate)
        fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState)
        fun onError(error: String)
    }
    
    fun initialize() {
        executor.execute {
            try {
                // Initialize EGL context for video
                eglBase = EglBase.create()
                
                // Initialize WebRTC
                val options = PeerConnectionFactory.InitializationOptions.builder(context)
                    .setEnableInternalTracer(true)
                    .createInitializationOptions()
                PeerConnectionFactory.initialize(options)
                
                // Create peer connection factory
                val encoderFactory = DefaultVideoEncoderFactory(
                    eglBase?.eglBaseContext,
                    true, // enableIntelVp8Encoder
                    true  // enableH264HighProfile
                )
                val decoderFactory = DefaultVideoDecoderFactory(eglBase?.eglBaseContext)
                
                peerConnectionFactory = PeerConnectionFactory.builder()
                    .setVideoEncoderFactory(encoderFactory)
                    .setVideoDecoderFactory(decoderFactory)
                    .setOptions(PeerConnectionFactory.Options())
                    .createPeerConnectionFactory()
                
                Log.d(TAG, "WebRTC initialized successfully")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize WebRTC", e)
                listener.onError("Failed to initialize WebRTC: ${e.message}")
            }
        }
    }
    
    fun createPeerConnection() {
        executor.execute {
            try {
                val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                    continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                    bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                    rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                    // Allow both direct and relay connections
                    iceTransportsType = PeerConnection.IceTransportsType.ALL
                    // Enable TCP candidates for firewall traversal
                    tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
                    // Enable candidate pair preemption for faster connection
                    candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL
                }
                
                Log.d(TAG, "Creating peer connection with ${iceServers.size} ICE servers")
                
                peerConnection = peerConnectionFactory?.createPeerConnection(
                    rtcConfig,
                    object : PeerConnection.Observer {
                        override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                            Log.d(TAG, "Signaling state: $state")
                        }
                        
                        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                            Log.d(TAG, "========== ICE CONNECTION: $state ==========")
                            when (state) {
                                PeerConnection.IceConnectionState.CHECKING -> 
                                    Log.d(TAG, "ICE: Checking connectivity...")
                                PeerConnection.IceConnectionState.CONNECTED -> {
                                    Log.d(TAG, "ICE: Connected! ✅")
                                    reconnectAttempts = 0
                                    connectionLostTime = 0L
                                    // Auto-start adaptive bitrate on connection
                                    ensureAdaptiveQualityRunning()
                                }
                                PeerConnection.IceConnectionState.COMPLETED -> {
                                    Log.d(TAG, "ICE: Completed! All candidates checked ✅")
                                    reconnectAttempts = 0
                                    connectionLostTime = 0L
                                    ensureAdaptiveQualityRunning()
                                }
                                PeerConnection.IceConnectionState.FAILED -> {
                                    Log.e(TAG, "ICE: FAILED! ❌ Attempting recovery...")
                                    handleConnectionDegraded()
                                }
                                PeerConnection.IceConnectionState.DISCONNECTED -> {
                                    Log.w(TAG, "ICE: Disconnected - dropping to minimum quality")
                                    if (connectionLostTime == 0L) connectionLostTime = System.currentTimeMillis()
                                    // Immediately drop to lowest quality to survive reconnection
                                    dropToMinimumQuality()
                                    handleConnectionDegraded()
                                }
                                PeerConnection.IceConnectionState.CLOSED -> 
                                    Log.d(TAG, "ICE: Closed")
                                else -> {}
                            }
                        }
                        
                        override fun onIceConnectionReceivingChange(receiving: Boolean) {
                            Log.d(TAG, "ICE receiving: $receiving")
                        }
                        
                        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                            Log.d(TAG, "ICE gathering state: $state")
                            if (state == PeerConnection.IceGatheringState.COMPLETE) {
                                Log.d(TAG, "========== ICE GATHERING COMPLETE ==========")
                            }
                        }
                        
                        override fun onIceCandidate(candidate: IceCandidate?) {
                            candidate?.let { 
                                // Log candidate type for debugging
                                val candidateType = when {
                                    it.sdp.contains("typ relay") -> "RELAY/TURN ✅"
                                    it.sdp.contains("typ srflx") -> "SRFLX/STUN"
                                    it.sdp.contains("typ prflx") -> "PRFLX/Peer"
                                    it.sdp.contains("typ host") -> "HOST/Local"
                                    else -> "UNKNOWN"
                                }
                                Log.d(TAG, "ICE candidate [$candidateType]: ${it.sdp.take(100)}")
                                listener.onIceCandidate(it) 
                            }
                        }
                        
                        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
                            Log.d(TAG, "ICE candidates removed")
                        }
                        
                        override fun onAddStream(stream: MediaStream?) {
                            Log.d(TAG, "Stream added: ${stream?.id}")
                        }
                        
                        override fun onRemoveStream(stream: MediaStream?) {
                            Log.d(TAG, "Stream removed: ${stream?.id}")
                        }
                        
                        override fun onDataChannel(channel: DataChannel?) {
                            Log.d(TAG, "Data channel: ${channel?.label()}")
                        }
                        
                        override fun onRenegotiationNeeded() {
                            Log.d(TAG, "Renegotiation needed")
                        }
                        
                        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                            Log.d(TAG, "Track added")
                        }
                        
                        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                            Log.d(TAG, "Connection state: $newState")
                            when (newState) {
                                PeerConnection.PeerConnectionState.CONNECTED -> {
                                    reconnectAttempts = 0
                                    connectionLostTime = 0L
                                    ensureAdaptiveQualityRunning()
                                }
                                PeerConnection.PeerConnectionState.DISCONNECTED -> {
                                    if (connectionLostTime == 0L) connectionLostTime = System.currentTimeMillis()
                                    dropToMinimumQuality()
                                    handleConnectionDegraded()
                                }
                                PeerConnection.PeerConnectionState.FAILED -> {
                                    handleConnectionDegraded()
                                }
                                else -> {}
                            }
                            newState?.let { listener.onConnectionStateChanged(it) }
                        }
                        
                        override fun onTrack(transceiver: RtpTransceiver?) {
                            Log.d(TAG, "Track received: ${transceiver?.mediaType}")
                        }
                    }
                )
                
                Log.d(TAG, "Peer connection created")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create peer connection", e)
                listener.onError("Failed to create peer connection: ${e.message}")
            }
        }
    }
    
    fun startCameraCapture() {
        isScreenStream = false
        executor.execute {
            try {
                Log.d(TAG, "Starting camera capture...")
                
                // Create video source - isScreencast=false for camera
                localVideoSource = peerConnectionFactory?.createVideoSource(false)
                Log.d(TAG, "Video source created: ${localVideoSource != null}")
                
                // Create video capturer (prefer front camera)
                videoCapturer = createCameraCapturer()
                
                if (videoCapturer == null) {
                    Log.e(TAG, "No camera capturer available")
                    listener.onError("No camera available")
                    return@execute
                }
                Log.d(TAG, "Camera capturer created")
                
                // Initialize capturer with EGL context
                if (eglBase?.eglBaseContext == null) {
                    Log.e(TAG, "EGL context is null - reinitializing")
                    eglBase = EglBase.create()
                }
                
                surfaceTextureHelper = SurfaceTextureHelper.create(
                    "CaptureThread",
                    eglBase?.eglBaseContext
                )
                Log.d(TAG, "SurfaceTextureHelper created: ${surfaceTextureHelper != null}")
                
                videoCapturer?.initialize(
                    surfaceTextureHelper,
                    context,
                    localVideoSource?.capturerObserver
                )
                Log.d(TAG, "Capturer initialized")
                
                // Start capture - use current tier resolution
                val tier = cameraQualityTiers[currentTierIndex]
                currentVideoWidth = tier.width
                currentVideoHeight = tier.height
                currentVideoFps = tier.fps
                videoCapturer?.startCapture(currentVideoWidth, currentVideoHeight, currentVideoFps)
                Log.d(TAG, "Capture started: ${currentVideoWidth}x${currentVideoHeight}@${currentVideoFps}fps")
                
                // Give camera time to start producing frames
                Thread.sleep(500)
                
                // Create video track
                localVideoTrack = peerConnectionFactory?.createVideoTrack(
                    "video_track",
                    localVideoSource
                )?.apply {
                    setEnabled(true)
                }
                Log.d(TAG, "Video track created and enabled: ${localVideoTrack != null}")
                
                // Add track to peer connection
                localVideoTrack?.let { track ->
                    val sender = peerConnection?.addTrack(track, listOf("stream"))
                    Log.d(TAG, "Video track added to peer connection, sender: ${sender != null}")
                } ?: Log.e(TAG, "Video track is null")
                
                Log.d(TAG, "Camera capture started successfully")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start camera capture", e)
                listener.onError("Failed to start camera: ${e.message}")
            }
        }
    }
    
    fun startScreenCapture(mediaProjectionIntent: android.content.Intent) {
        isScreenStream = true
        executor.execute {
            try {
                val tier = screenQualityTiers[currentTierIndex]
                currentVideoWidth = tier.width
                currentVideoHeight = tier.height
                currentVideoFps = tier.fps
                Log.d(TAG, "Starting screen capture with dimensions: ${currentVideoWidth}x${currentVideoHeight}@${currentVideoFps}fps")
                
                // Create video source for screen
                localVideoSource = peerConnectionFactory?.createVideoSource(true)
                Log.d(TAG, "Video source created for screen: ${localVideoSource != null}")
                
                // Create screen capturer
                screenCapturer = ScreenCapturerAndroid(
                    mediaProjectionIntent,
                    object : MediaProjection.Callback() {
                        override fun onStop() {
                            Log.d(TAG, "MediaProjection stopped")
                        }
                    }
                )
                Log.d(TAG, "ScreenCapturerAndroid created")
                
                // Initialize capturer
                surfaceTextureHelper = SurfaceTextureHelper.create(
                    "ScreenCaptureThread",
                    eglBase?.eglBaseContext
                )
                Log.d(TAG, "SurfaceTextureHelper created: ${surfaceTextureHelper != null}")
                
                screenCapturer?.initialize(
                    surfaceTextureHelper,
                    context,
                    localVideoSource?.capturerObserver
                )
                Log.d(TAG, "Screen capturer initialized")
                
                // Start capture with adaptive resolution
                screenCapturer?.startCapture(currentVideoWidth, currentVideoHeight, currentVideoFps)
                Log.d(TAG, "Screen capture startCapture called at ${currentVideoWidth}x${currentVideoHeight}@${currentVideoFps}fps")
                
                // CRITICAL: Give screen capturer time to start producing frames
                Thread.sleep(1000)
                
                // Create video track
                localVideoTrack = peerConnectionFactory?.createVideoTrack(
                    "screen_track",
                    localVideoSource
                )?.apply {
                    setEnabled(true)
                }
                Log.d(TAG, "Screen video track created and enabled: ${localVideoTrack != null}")
                
                // Add track to peer connection
                localVideoTrack?.let {
                    val sender = peerConnection?.addTrack(it, listOf("screen_stream"))
                    Log.d(TAG, "Screen video track added to peer connection, sender: ${sender != null}")
                } ?: Log.e(TAG, "Screen video track is null!")
                
                Log.d(TAG, "Screen capture started successfully")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start screen capture", e)
                listener.onError("Failed to start screen capture: ${e.message}")
            }
        }
    }
    
    fun startAudioCapture() {
        executor.execute {
            try {
                Log.d(TAG, "Starting audio capture...")
                
                // Create audio constraints - enable processing for cleaner audio
                val audioConstraints = MediaConstraints().apply {
                    // Enable some processing for better quality
                    mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                }
                Log.d(TAG, "Audio constraints set")
                
                // Create audio source
                localAudioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
                Log.d(TAG, "Audio source created: ${localAudioSource != null}")
                
                // Create audio track
                localAudioTrack = peerConnectionFactory?.createAudioTrack(
                    "audio_track",
                    localAudioSource
                )?.apply {
                    setEnabled(true)
                    // Ensure volume is at max
                    setVolume(10.0) // Boost volume
                }
                Log.d(TAG, "Audio track created and enabled: ${localAudioTrack != null}")
                
                // Add track to peer connection
                localAudioTrack?.let { track ->
                    val sender = peerConnection?.addTrack(track, listOf("stream"))
                    Log.d(TAG, "Audio track added to peer connection, sender: ${sender != null}")
                } ?: Log.e(TAG, "Audio track is null")
                
                Log.d(TAG, "Audio capture started successfully")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start audio capture", e)
                listener.onError("Failed to start audio: ${e.message}")
            }
        }
    }
    
    fun createOffer() {
        executor.execute {
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            }
            
            peerConnection?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    sdp?.let {
                        Log.d(TAG, "Offer created")
                        setLocalDescription(it)
                    }
                }
                
                override fun onSetSuccess() {}
                
                override fun onCreateFailure(error: String?) {
                    Log.e(TAG, "Failed to create offer: $error")
                    listener.onError("Failed to create offer: $error")
                }
                
                override fun onSetFailure(error: String?) {}
            }, constraints)
        }
    }
    
    fun setRemoteDescription(sdp: SessionDescription) {
        executor.execute {
            peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {}
                
                override fun onSetSuccess() {
                    Log.d(TAG, "Remote description set")
                }
                
                override fun onCreateFailure(error: String?) {}
                
                override fun onSetFailure(error: String?) {
                    Log.e(TAG, "Failed to set remote description: $error")
                    listener.onError("Failed to set remote description: $error")
                }
            }, sdp)
        }
    }
    
    fun addIceCandidate(candidate: IceCandidate) {
        executor.execute {
            peerConnection?.addIceCandidate(candidate)
            Log.d(TAG, "ICE candidate added")
        }
    }
    
    private fun setLocalDescription(sdp: SessionDescription) {
        peerConnection?.setLocalDescription(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {}
            
            override fun onSetSuccess() {
                Log.d(TAG, "Local description set")
                listener.onLocalDescription(sdp)
            }
            
            override fun onCreateFailure(error: String?) {}
            
            override fun onSetFailure(error: String?) {
                Log.e(TAG, "Failed to set local description: $error")
                listener.onError("Failed to set local description: $error")
            }
        }, sdp)
    }
    
    private fun createCameraCapturer(): VideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        
        // Try front camera first
        for (deviceName in enumerator.deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                val capturer = enumerator.createCapturer(deviceName, null)
                if (capturer != null) {
                    Log.d(TAG, "Using front camera: $deviceName")
                    return capturer
                }
            }
        }
        
        // Fall back to back camera
        for (deviceName in enumerator.deviceNames) {
            if (enumerator.isBackFacing(deviceName)) {
                val capturer = enumerator.createCapturer(deviceName, null)
                if (capturer != null) {
                    Log.d(TAG, "Using back camera: $deviceName")
                    return capturer
                }
            }
        }
        
        return null
    }
    
    fun switchCamera() {
        executor.execute {
            (videoCapturer as? CameraVideoCapturer)?.switchCamera(null)
        }
    }
    
    fun stopCapture() {
        executor.execute {
            try {
                videoCapturer?.stopCapture()
                screenCapturer?.stopCapture()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping capture", e)
            }
        }
    }
    
    fun release() {
        executor.execute {
            try {
                // Stop capture
                videoCapturer?.stopCapture()
                videoCapturer?.dispose()
                videoCapturer = null
                
                screenCapturer?.stopCapture()
                screenCapturer?.dispose()
                screenCapturer = null
                
                // Dispose tracks
                localVideoTrack?.dispose()
                localVideoTrack = null
                
                localAudioTrack?.dispose()
                localAudioTrack = null
                
                // Dispose sources
                localVideoSource?.dispose()
                localVideoSource = null
                
                localAudioSource?.dispose()
                localAudioSource = null
                
                // Dispose helper
                surfaceTextureHelper?.dispose()
                surfaceTextureHelper = null
                
                // Close peer connection
                peerConnection?.close()
                peerConnection = null
                
                // Dispose factory
                peerConnectionFactory?.dispose()
                peerConnectionFactory = null
                
                // Release EGL
                eglBase?.release()
                eglBase = null
                
                Log.d(TAG, "WebRTC released")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing WebRTC", e)
            }
        }
    }
    
    // ============== Adaptive Bitrate + Resolution Control ==============
    
    /**
     * Ensure adaptive quality monitor is running
     */
    private fun ensureAdaptiveQualityRunning() {
        if (!adaptiveMonitorRunning) {
            currentQuality = StreamQuality.AUTO
            startAdaptiveBitrate()
        }
    }
    
    /**
     * Drop to minimum quality immediately (for disconnected/failing connections)
     */
    private fun dropToMinimumQuality() {
        Log.w(TAG, "⚠️ Dropping to MINIMUM quality to keep stream alive")
        val tiers = if (isScreenStream) screenQualityTiers else cameraQualityTiers
        currentTierIndex = 0
        val tier = tiers[0]
        setBitrate(tier.maxBitrate)
        // Don't change resolution mid-stream for camera (causes restart), just reduce bitrate + fps
        applyFpsReduction(tier.fps)
    }
    
    /**
     * Handle degraded connection - try ICE restart, don't die
     */
    private fun handleConnectionDegraded() {
        executor.execute {
            try {
                if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                    Log.e(TAG, "Max reconnects reached, but keeping stream running at minimum")
                    return@execute
                }
                
                reconnectAttempts++
                val delay = minOf(reconnectAttempts * 2000L, 15000L) // Exponential backoff, max 15s
                Log.d(TAG, "Connection degraded - ICE restart attempt $reconnectAttempts in ${delay}ms")
                
                Thread.sleep(delay)
                
                peerConnection?.let {
                    Log.d(TAG, "Attempting ICE restart...")
                    it.restartIce()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in connection recovery", e)
            }
        }
    }
    
    /**
     * Apply FPS reduction without restarting capture
     */
    private fun applyFpsReduction(targetFps: Int) {
        try {
            peerConnection?.senders?.forEach { sender ->
                if (sender.track()?.kind() == "video") {
                    val params = sender.parameters
                    if (params.encodings.isNotEmpty()) {
                        params.encodings[0].maxFramerate = targetFps
                        sender.parameters = params
                        currentVideoFps = targetFps
                        Log.d(TAG, "FPS reduced to $targetFps")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reducing FPS", e)
        }
    }
    
    /**
     * Apply resolution scaling via scaleResolutionDownBy
     */
    private fun applyResolutionScale(tierIndex: Int) {
        try {
            val tiers = if (isScreenStream) screenQualityTiers else cameraQualityTiers
            val baseTier = tiers.last() // Highest tier = base resolution
            val targetTier = tiers[tierIndex]
            
            // Calculate scale factor relative to capture resolution
            val scaleW = currentVideoWidth.toDouble() / targetTier.width.toDouble()
            val scale = maxOf(1.0, scaleW)
            
            peerConnection?.senders?.forEach { sender ->
                if (sender.track()?.kind() == "video") {
                    val params = sender.parameters
                    if (params.encodings.isNotEmpty()) {
                        params.encodings[0].scaleResolutionDownBy = scale
                        params.encodings[0].maxFramerate = targetTier.fps
                        params.encodings[0].maxBitrateBps = targetTier.maxBitrate
                        sender.parameters = params
                        Log.d(TAG, "📐 Resolution scale=${"%.1f".format(scale)}x, FPS=${targetTier.fps}, Bitrate=${targetTier.maxBitrate/1000}kbps [${targetTier.label}]")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error applying resolution scale", e)
        }
    }
    
    /**
     * Set stream quality preset
     */
    fun setQuality(quality: StreamQuality) {
        currentQuality = quality
        when (quality) {
            StreamQuality.LOW -> {
                currentTierIndex = 1
                applyQualityTier(1)
            }
            StreamQuality.MEDIUM -> {
                currentTierIndex = 3
                applyQualityTier(3)
            }
            StreamQuality.HIGH -> {
                currentTierIndex = 5
                applyQualityTier(5)
            }
            StreamQuality.AUTO -> startAdaptiveBitrate()
        }
    }
    
    /**
     * Apply a quality tier (bitrate + resolution scale + fps)
     */
    private fun applyQualityTier(tierIndex: Int) {
        val tiers = if (isScreenStream) screenQualityTiers else cameraQualityTiers
        val safeIndex = tierIndex.coerceIn(0, tiers.size - 1)
        currentTierIndex = safeIndex
        val tier = tiers[safeIndex]
        
        setBitrate(tier.maxBitrate)
        applyResolutionScale(safeIndex)
        
        Log.d(TAG, "📊 Quality tier: ${tier.label} (${tier.width}x${tier.height}@${tier.fps}fps, ${tier.maxBitrate/1000}kbps)")
    }
    
    /**
     * Set video bitrate
     */
    fun setBitrate(bitrateBps: Int) {
        executor.execute {
            try {
                peerConnection?.senders?.forEach { sender ->
                    if (sender.track()?.kind() == "video") {
                        val params = sender.parameters
                        if (params.encodings.isNotEmpty()) {
                            params.encodings[0].maxBitrateBps = bitrateBps
                            sender.parameters = params
                            currentBitrate = bitrateBps
                            Log.d(TAG, "Bitrate set to ${bitrateBps / 1000} kbps")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting bitrate", e)
            }
        }
    }
    
    /**
     * Start adaptive bitrate monitoring
     */
    private fun startAdaptiveBitrate() {
        if (adaptiveMonitorRunning) return
        adaptiveMonitorRunning = true
        
        executor.execute {
            try {
                // Set initial quality tier
                applyQualityTier(currentTierIndex)
                
                Log.d(TAG, "🔄 Adaptive quality monitor STARTED")
                
                // Monitor every 2 seconds for faster reaction
                Thread {
                    while (peerConnection != null && adaptiveMonitorRunning) {
                        try {
                            Thread.sleep(2000)
                            checkConnectionStats()
                        } catch (e: InterruptedException) {
                            break
                        } catch (e: Exception) {
                            Log.e(TAG, "Stats check error", e)
                        }
                    }
                    adaptiveMonitorRunning = false
                    Log.d(TAG, "🔄 Adaptive quality monitor STOPPED")
                }.start()
                
            } catch (e: Exception) {
                Log.e(TAG, "Error starting adaptive bitrate", e)
                adaptiveMonitorRunning = false
            }
        }
    }
    
    /**
     * Check connection statistics and adjust quality tier
     */
    private fun checkConnectionStats() {
        peerConnection?.getStats { report ->
            var packetsLost = 0L
            var packetsSent = 0L
            var bytesSent = 0L
            var roundTripTime = 0.0
            var jitter = 0.0
            var timestamp = 0.0
            var framesEncoded = 0L
            var framesDropped = 0L
            var qualityLimitReason = ""
            
            report.statsMap.values.forEach { stats ->
                val members = stats.members
                when {
                    stats.type == "outbound-rtp" && members.containsKey("kind") && members["kind"] == "video" -> {
                        try {
                            packetsLost = (members["packetsLost"] as? Number)?.toLong() ?: packetsLost
                            packetsSent = (members["packetsSent"] as? Number)?.toLong() ?: packetsSent
                            bytesSent = (members["bytesSent"] as? Number)?.toLong() ?: bytesSent
                            timestamp = (members["timestamp"] as? Number)?.toDouble() ?: timestamp
                            framesEncoded = (members["framesEncoded"] as? Number)?.toLong() ?: framesEncoded
                            qualityLimitReason = (members["qualityLimitationReason"] as? String) ?: qualityLimitReason
                        } catch (e: Exception) { }
                    }
                    stats.type == "outbound-rtp" && stats.toString().contains("video") -> {
                        try {
                            packetsLost = (members["packetsLost"] as? Number)?.toLong() ?: packetsLost
                            packetsSent = (members["packetsSent"] as? Number)?.toLong() ?: packetsSent
                            bytesSent = (members["bytesSent"] as? Number)?.toLong() ?: bytesSent
                            timestamp = (members["timestamp"] as? Number)?.toDouble() ?: timestamp
                        } catch (e: Exception) { }
                    }
                    stats.type == "remote-inbound-rtp" -> {
                        try {
                            roundTripTime = (members["roundTripTime"] as? Number)?.toDouble() ?: roundTripTime
                            jitter = (members["jitter"] as? Number)?.toDouble() ?: jitter
                        } catch (e: Exception) { }
                    }
                }
            }
            
            // Calculate actual bitrate
            if (lastStatsTimestamp > 0 && timestamp > lastStatsTimestamp) {
                val timeDelta = (timestamp - lastStatsTimestamp) / 1000.0 // seconds
                val bytesDelta = bytesSent - lastBytesSent
                if (timeDelta > 0) {
                    currentActualBitrateBps = ((bytesDelta * 8) / timeDelta).toInt()
                }
            }
            lastBytesSent = bytesSent
            lastStatsTimestamp = timestamp
            
            // Calculate packet loss percentage
            val totalPackets = packetsLost + packetsSent
            val lossRate = if (totalPackets > 0) {
                (packetsLost.toDouble() / totalPackets) * 100
            } else {
                0.0
            }
            
            val rttMs = (roundTripTime * 1000).toInt()
            val tiers = if (isScreenStream) screenQualityTiers else cameraQualityTiers
            
            Log.d(TAG, "📊 Stats - Loss:${"%.1f".format(lossRate)}% RTT:${rttMs}ms Bitrate:${currentActualBitrateBps/1000}/${currentBitrate/1000}kbps Tier:${currentTierIndex}/${tiers.size-1} QLimit:$qualityLimitReason")
            
            // AGGRESSIVE quality adjustment based on multiple signals
            val shouldDowngrade = when {
                lossRate > 10.0 -> true   // Very high loss - IMMEDIATE downgrade
                lossRate > 5.0 -> {
                    consecutiveLowQualityCount++
                    consecutiveLowQualityCount >= 1 // Downgrade after 1 bad reading (2s)
                }
                lossRate > 2.0 && rttMs > 500 -> {
                    consecutiveLowQualityCount++
                    consecutiveLowQualityCount >= 2 // Loss + high latency
                }
                rttMs > 1000 -> true  // Very high latency
                qualityLimitReason == "bandwidth" -> {
                    consecutiveLowQualityCount++
                    consecutiveLowQualityCount >= 2
                }
                currentActualBitrateBps > 0 && currentActualBitrateBps < currentBitrate * 0.4 -> {
                    // Actual bitrate is less than 40% of target - bandwidth constrained
                    consecutiveLowQualityCount++
                    consecutiveLowQualityCount >= 2
                }
                else -> false
            }
            
            val shouldUpgrade = lossRate < 0.5 && rttMs < 200 && 
                (currentActualBitrateBps == 0 || currentActualBitrateBps > currentBitrate * 0.8)
            
            when {
                shouldDowngrade -> {
                    consecutiveHighQualityCount = 0
                    if (currentTierIndex > 0) {
                        // Drop by 1 tier normally, 2 tiers if very bad
                        val drop = if (lossRate > 10.0 || rttMs > 1000) 2 else 1
                        val newTier = maxOf(0, currentTierIndex - drop)
                        if (newTier != currentTierIndex) {
                            Log.w(TAG, "⬇️ DOWNGRADE: Tier $currentTierIndex → $newTier (loss=${"%.1f".format(lossRate)}%, rtt=${rttMs}ms)")
                            applyQualityTier(newTier)
                        }
                    } else {
                        Log.w(TAG, "⚠️ Already at minimum tier - keeping stream alive at lowest quality")
                    }
                    consecutiveLowQualityCount = 0
                }
                shouldUpgrade -> {
                    consecutiveHighQualityCount++
                    consecutiveLowQualityCount = 0
                    
                    // Upgrade slowly - need 5 consecutive good readings (10 seconds)
                    if (consecutiveHighQualityCount >= 5 && currentTierIndex < tiers.size - 1) {
                        val newTier = minOf(tiers.size - 1, currentTierIndex + 1)
                        Log.d(TAG, "⬆️ UPGRADE: Tier $currentTierIndex → $newTier (loss=${"%.1f".format(lossRate)}%, rtt=${rttMs}ms)")
                        applyQualityTier(newTier)
                        consecutiveHighQualityCount = 0
                    }
                }
                else -> {
                    // Stable - slight reset toward 0
                    consecutiveLowQualityCount = maxOf(0, consecutiveLowQualityCount - 1)
                    consecutiveHighQualityCount = maxOf(0, consecutiveHighQualityCount - 1)
                }
            }
        }
    }
    
    /**
     * Get current quality tier info for signaling
     */
    fun getCurrentQualityInfo(): JSONObject {
        val tiers = if (isScreenStream) screenQualityTiers else cameraQualityTiers
        val tier = tiers[currentTierIndex]
        return JSONObject().apply {
            put("tierIndex", currentTierIndex)
            put("maxTiers", tiers.size)
            put("label", tier.label)
            put("width", tier.width)
            put("height", tier.height)
            put("fps", tier.fps)
            put("targetBitrate", tier.maxBitrate)
            put("actualBitrate", currentActualBitrateBps)
        }
    }
    
    /**
     * Get current bitrate
     */
    fun getCurrentBitrate(): Int = currentBitrate
    
    /**
     * Get current quality setting
     */
    fun getCurrentQuality(): StreamQuality = currentQuality
    
    /**
     * Get current tier index
     */
    fun getCurrentTierIndex(): Int = currentTierIndex
}
