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
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
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
        
        @Volatile
        private var running = false
        
        fun isRunning(): Boolean = running
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
    private var currentCallType: String = "unknown"
    private var recordingStartTime: Long = 0L

    override fun onCreate() {
        super.onCreate()
        running = true
        preferenceManager = PreferenceManager(this)
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        setupPhoneStateListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.w(TAG, "onStartCommand called, mode=${intent?.getStringExtra("mode")}, action=${intent?.action}")
        
        // Handle STOP action
        if (intent?.action == "STOP") {
            Log.w(TAG, "STOP action received")
            stopCallRecording()
            return START_STICKY
        }
        
        startForeground()
        
        // Track phone number from intent
        intent?.getStringExtra("phoneNumber")?.let { number ->
            if (number.isNotEmpty()) {
                currentPhoneNumber = number
                Log.w(TAG, "Updated currentPhoneNumber=$number")
            }
        }
        
        // Track call type from intent - but DON'T override if recording is already in progress
        // This prevents PhoneStateReceiver from overwriting outgoing→incoming
        intent?.getStringExtra("callType")?.let { type ->
            if (type.isNotEmpty()) {
                if (isRecording && currentCallType != "unknown") {
                    Log.w(TAG, "Ignoring callType=$type update because recording is active with callType=$currentCallType")
                } else {
                    currentCallType = type
                    Log.w(TAG, "Updated currentCallType=$type")
                }
            }
        }
        
        // ALWAYS ensure PhoneStateListener is registered when call recording is enabled
        val isEnabled = preferenceManager.isCallRecordingEnabled()
        val hasPermission = hasPhonePermission()
        Log.w(TAG, "Call recording enabled=$isEnabled, hasPhonePermission=$hasPermission")
        if (isEnabled && hasPermission) {
            registerPhoneStateListener()
        }
        
        when (intent?.getStringExtra("mode")) {
            "live_listen" -> startLiveListen()
            "record" -> startRecording()
            else -> {
                // Service started for call detection - listener already registered above
                if (!isEnabled) {
                    Log.d(TAG, "Call recording disabled in preferences")
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
        running = false
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
        Log.d(TAG, "Setting up PhoneStateListener")
        phoneStateListener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                Log.w(TAG, "onCallStateChanged: state=$state, phoneNumber=$phoneNumber")
                when (state) {
                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        Log.w(TAG, "CALL_STATE_OFFHOOK - Call active")
                        if (!phoneNumber.isNullOrEmpty()) currentPhoneNumber = phoneNumber
                        val shouldRecord = preferenceManager.isCallRecordingEnabled() && !isRecording
                        Log.w(TAG, "shouldRecord=$shouldRecord, isRecording=$isRecording")
                        if (shouldRecord) {
                            startCallRecording()
                        }
                    }
                    TelephonyManager.CALL_STATE_IDLE -> {
                        Log.w(TAG, "CALL_STATE_IDLE - Call ended, isRecording=$isRecording")
                        if (isRecording) {
                            stopCallRecording()
                        }
                    }
                    TelephonyManager.CALL_STATE_RINGING -> {
                        Log.w(TAG, "CALL_STATE_RINGING - Incoming call from $phoneNumber")
                        if (!phoneNumber.isNullOrEmpty()) currentPhoneNumber = phoneNumber
                        currentCallType = "incoming"
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun registerPhoneStateListener() {
        try {
            Log.w(TAG, "Registering PhoneStateListener with TelephonyManager")
            telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
            Log.w(TAG, "PhoneStateListener registered successfully")
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
        if (isRecording) {
            Log.w(TAG, "startCallRecording() skipped - already recording")
            return
        }
        isRecording = true
        recordingStartTime = System.currentTimeMillis()
        
        Log.w(TAG, "Starting call recording for: $currentPhoneNumber, callType=$currentCallType, startTime=$recordingStartTime")
        
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "call_${timestamp}.mp3"
        currentRecordingFile = File(cacheDir, fileName)
        
        audioRecorder = AudioRecorder(this)
        val recordStarted = audioRecorder?.startRecording(currentRecordingFile!!.absolutePath, forCallRecording = true)
        Log.w(TAG, "AudioRecorder.startRecording returned: $recordStarted")
    }

    private fun stopCallRecording() {
        if (!isRecording) {
            Log.w(TAG, "stopCallRecording() skipped - not recording")
            return
        }
        isRecording = false
        
        // Calculate actual duration in seconds
        var durationSeconds = if (recordingStartTime > 0L) {
            ((System.currentTimeMillis() - recordingStartTime) / 1000).toInt()
        } else 0
        
        Log.w(TAG, "Stopping call recording, duration=${durationSeconds}s, recordingStartTime=$recordingStartTime, callType=$currentCallType")
        
        audioRecorder?.stopRecording()
        
        // Check cache file
        val fileSize = currentRecordingFile?.length() ?: 0
        Log.w(TAG, "Cache file: ${currentRecordingFile?.absolutePath}, exists=${currentRecordingFile?.exists()}, size=$fileSize")
        
        // Fallback duration from WAV file data if recordingStartTime was not set
        // WAV PCM 16-bit mono 44100Hz = 88200 bytes per second (+ 44 byte header)
        if (durationSeconds <= 0 && fileSize > 44) {
            durationSeconds = ((fileSize - 44) / 88200).toInt()
            Log.w(TAG, "Using fallback duration from file size: ${durationSeconds}s")
        }
        
        // Resolve contact name from phone number
        val contactName = getContactName(currentPhoneNumber)
        Log.w(TAG, "Resolved contact name: $contactName for number: $currentPhoneNumber")
        
        // Save state before resetting
        val phoneNumber = currentPhoneNumber
        val callType = currentCallType
        
        // Reset state for next call
        recordingStartTime = 0L
        currentCallType = "unknown"
        // Don't reset currentPhoneNumber - might be needed if service restarts
        
        // Upload recording and schedule deletion
        currentRecordingFile?.let { file ->
            if (file.exists() && file.length() > 0) {
                // Copy to permanent location first
                val recordingsDir = File(getExternalFilesDir(null), "CallRecordings")
                if (!recordingsDir.exists()) recordingsDir.mkdirs()
                
                val permanentFile = File(recordingsDir, file.name)
                file.copyTo(permanentFile, overwrite = true)
                file.delete() // Delete temp file
                
                Log.w(TAG, "Uploading: phone=$phoneNumber, contact=$contactName, type=$callType, duration=${durationSeconds}s")
                // Upload and schedule auto-delete
                uploadCallRecordingWithAutoDelete(permanentFile, phoneNumber, contactName, durationSeconds, callType)
            } else {
                Log.w(TAG, "No file to upload: exists=${file.exists()}, size=${file.length()}")
            }
        }
    }

    private fun getContactName(phoneNumber: String?): String? {
        if (phoneNumber.isNullOrEmpty()) return null
        try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
            val cursor: Cursor? = contentResolver.query(uri, projection, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    return it.getString(it.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve contact name for $phoneNumber: ${e.message}")
        }
        return null
    }

    private fun uploadCallRecordingWithAutoDelete(file: File, phoneNumber: String?, contactName: String?, durationSeconds: Int, callType: String = "unknown") {
        serviceScope.launch {
            var uploadSuccess = false
            var retryCount = 0
            val maxRetries = 3
            
            while (!uploadSuccess && retryCount < maxRetries) {
                uploadSuccess = uploadCallRecording(file, phoneNumber, contactName, durationSeconds, callType)
                if (!uploadSuccess) {
                    retryCount++
                    Log.w(TAG, "Upload failed, retry $retryCount/$maxRetries")
                    delay(10000) // Wait 10 seconds between retries
                }
            }
            
            if (uploadSuccess) {
                Log.d(TAG, "Call recording uploaded successfully, scheduling deletion in 2 minutes")
                // Delete after 2 minutes
                delay(2 * 60 * 1000L) // 2 minutes
                if (file.exists()) {
                    file.delete()
                    Log.d(TAG, "Call recording deleted from device: ${file.name}")
                }
            } else {
                Log.e(TAG, "Failed to upload after $maxRetries retries, keeping file for later sync")
                // Mark file for later sync
                val markerFile = File(file.parent, "${file.name}.pending")
                markerFile.writeText(phoneNumber ?: "Unknown")
            }
        }
    }

    private suspend fun uploadCallRecording(file: File, phoneNumber: String?, contactName: String?, durationSeconds: Int, callType: String = "unknown"): Boolean {
        return try {
            val deviceId = preferenceManager.getDeviceId()
            val baseUrl = ApiClient.BASE_URL.trimEnd('/')
            val uploadUrl = "$baseUrl/api/sync/call-recording"
            
            Log.w(TAG, "Uploading call recording: ${file.name}, size=${file.length()}, duration=${durationSeconds}s, contact=$contactName, deviceId=$deviceId")
            
            val duration = durationSeconds
            
            // Create multipart request
            val boundary = "----FormBoundary${System.currentTimeMillis()}"
            val connection = java.net.URL(uploadUrl).openConnection() as java.net.HttpURLConnection
            
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.setRequestProperty("x-device-id", deviceId)
            
            val outputStream = connection.outputStream
            val writer = java.io.PrintWriter(java.io.OutputStreamWriter(outputStream, "UTF-8"), true)
            
            // Add deviceId field
            writer.append("--$boundary\r\n")
            writer.append("Content-Disposition: form-data; name=\"deviceId\"\r\n\r\n")
            writer.append(deviceId)
            
            // Add file field
            writer.append("\r\n--$boundary\r\n")
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
            
            // Add contactName field
            writer.append("\r\n--$boundary\r\n")
            writer.append("Content-Disposition: form-data; name=\"contactName\"\r\n\r\n")
            writer.append(contactName ?: "")
            
            // Add callType field - use the passed callType parameter, not the class variable
            writer.append("\r\n--$boundary\r\n")
            writer.append("Content-Disposition: form-data; name=\"callType\"\r\n\r\n")
            writer.append(if (callType != "unknown") callType else "incoming")
            
            // Add duration field
            writer.append("\r\n--$boundary\r\n")
            writer.append("Content-Disposition: form-data; name=\"duration\"\r\n\r\n")
            writer.append(duration.toString())
            
            // Add timestamp field
            writer.append("\r\n--$boundary\r\n")
            writer.append("Content-Disposition: form-data; name=\"timestamp\"\r\n\r\n")
            writer.append(System.currentTimeMillis().toString())
            
            // End boundary
            writer.append("\r\n--$boundary--\r\n")
            writer.flush()
            
            val responseCode = connection.responseCode
            connection.disconnect()
            
            if (responseCode in 200..299) {
                Log.w(TAG, "Call recording uploaded successfully: ${file.name}")
                true
            } else {
                // Read error response
                val errorStream = connection.errorStream
                val errorMsg = errorStream?.bufferedReader()?.readText() ?: "No error message"
                Log.e(TAG, "Upload failed: HTTP $responseCode - $errorMsg")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload error: ${e.javaClass.simpleName} - ${e.message}")
            e.printStackTrace()
            false
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
