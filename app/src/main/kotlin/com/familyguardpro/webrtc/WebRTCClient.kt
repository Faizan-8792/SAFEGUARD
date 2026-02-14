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
                                PeerConnection.IceConnectionState.CONNECTED -> 
                                    Log.d(TAG, "ICE: Connected! ✅")
                                PeerConnection.IceConnectionState.COMPLETED -> 
                                    Log.d(TAG, "ICE: Completed! All candidates checked ✅")
                                PeerConnection.IceConnectionState.FAILED -> 
                                    Log.e(TAG, "ICE: FAILED! ❌ No connectivity path found")
                                PeerConnection.IceConnectionState.DISCONNECTED -> 
                                    Log.w(TAG, "ICE: Disconnected")
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
                
                // Start capture with common resolution
                videoCapturer?.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS)
                Log.d(TAG, "Capture started: ${VIDEO_WIDTH}x${VIDEO_HEIGHT}@${VIDEO_FPS}fps")
                
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
        executor.execute {
            try {
                Log.d(TAG, "Starting screen capture with dimensions: ${SCREEN_WIDTH}x${SCREEN_HEIGHT}@${SCREEN_FPS}fps")
                
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
                
                // Start capture
                screenCapturer?.startCapture(SCREEN_WIDTH, SCREEN_HEIGHT, SCREEN_FPS)
                Log.d(TAG, "Screen capture startCapture called")
                
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
    
    // ============== Adaptive Bitrate Control ==============
    
    /**
     * Set stream quality preset
     */
    fun setQuality(quality: StreamQuality) {
        currentQuality = quality
        when (quality) {
            StreamQuality.LOW -> setBitrate(200_000)
            StreamQuality.MEDIUM -> setBitrate(500_000)
            StreamQuality.HIGH -> setBitrate(1_500_000)
            StreamQuality.AUTO -> startAdaptiveBitrate()
        }
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
        executor.execute {
            try {
                // Set initial bitrate
                setBitrate(START_BITRATE)
                
                // Start monitoring stats every 3 seconds
                Thread {
                    while (peerConnection != null && currentQuality == StreamQuality.AUTO) {
                        try {
                            Thread.sleep(3000)
                            checkConnectionStats()
                        } catch (e: InterruptedException) {
                            break
                        }
                    }
                }.start()
                
            } catch (e: Exception) {
                Log.e(TAG, "Error starting adaptive bitrate", e)
            }
        }
    }
    
    /**
     * Check connection statistics and adjust bitrate
     */
    private fun checkConnectionStats() {
        peerConnection?.getStats { report ->
            var packetsLost = 0L
            var packetsSent = 0L
            var bytesRemaining = 0L
            
            report.statsMap.values.forEach { stats ->
                if (stats.type == "outbound-rtp" && stats.toString().contains("video")) {
                    try {
                        val members = stats.members
                        packetsLost = (members["packetsLost"] as? Number)?.toLong() ?: 0
                        packetsSent = (members["packetsSent"] as? Number)?.toLong() ?: 0
                        bytesRemaining = (members["bytesSent"] as? Number)?.toLong() ?: 0
                    } catch (e: Exception) {
                        // Ignore parsing errors
                    }
                }
            }
            
            // Calculate packet loss percentage
            val totalPackets = packetsLost + packetsSent
            val lossRate = if (totalPackets > 0) {
                (packetsLost.toDouble() / totalPackets) * 100
            } else {
                0.0
            }
            
            Log.d(TAG, "Connection stats - Loss: ${"%.2f".format(lossRate)}%, Bitrate: ${currentBitrate / 1000} kbps")
            
            // Adjust bitrate based on loss rate
            when {
                lossRate > 5.0 -> {
                    // High packet loss - reduce quality
                    consecutiveLowQualityCount++
                    consecutiveHighQualityCount = 0
                    
                    if (consecutiveLowQualityCount >= 2) {
                        val newBitrate = maxOf(MIN_BITRATE, (currentBitrate * 0.7).toInt())
                        if (newBitrate < currentBitrate) {
                            Log.d(TAG, "Reducing bitrate due to packet loss")
                            setBitrate(newBitrate)
                        }
                        consecutiveLowQualityCount = 0
                    }
                }
                lossRate < 1.0 -> {
                    // Low packet loss - can increase quality
                    consecutiveHighQualityCount++
                    consecutiveLowQualityCount = 0
                    
                    if (consecutiveHighQualityCount >= 3) {
                        val newBitrate = minOf(MAX_BITRATE, (currentBitrate * 1.2).toInt())
                        if (newBitrate > currentBitrate) {
                            Log.d(TAG, "Increasing bitrate - good connection")
                            setBitrate(newBitrate)
                        }
                        consecutiveHighQualityCount = 0
                    }
                }
                else -> {
                    // Moderate loss - maintain current quality
                    consecutiveLowQualityCount = 0
                    consecutiveHighQualityCount = 0
                }
            }
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
}
