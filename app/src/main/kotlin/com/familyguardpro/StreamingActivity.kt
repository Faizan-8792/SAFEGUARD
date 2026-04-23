package com.familyguardpro

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.*
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.SurfaceHolder
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.familyguardpro.databinding.ActivityStreamingBinding
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class StreamingActivity : AppCompatActivity(), SurfaceHolder.Callback {
    
    private lateinit var binding: ActivityStreamingBinding
    private var webSocket: WebSocket? = null
    private var deviceId: String? = null
    private var streamType: String? = null
    private var isStreaming = false
    
    // Audio playback
    private var audioTrack: AudioTrack? = null
    private val audioSampleRate = 44100
    private val audioChannels = AudioFormat.CHANNEL_OUT_MONO
    private val audioEncoding = AudioFormat.ENCODING_PCM_16BIT
    
    // Surface for video
    private var surfaceHolder: SurfaceHolder? = null
    
    companion object {
        private const val TAG = "StreamingActivity"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStreamingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        deviceId = intent.getStringExtra("deviceId")
        streamType = intent.getStringExtra("streamType")
        
        if (deviceId == null || streamType == null) {
            Toast.makeText(this, "Invalid parameters", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        setupUI()
        connectWebSocket()
    }
    
    private fun setupUI() {
        binding.toolbar.title = when (streamType) {
            "screen_mirror" -> "Screen Mirror"
            "camera" -> "Live Camera"
            "live_listen" -> "Live Listen"
            else -> "Streaming"
        }
        
        binding.toolbar.setNavigationOnClickListener {
            stopStreaming()
            finish()
        }
        
        binding.btnToggleStream.setOnClickListener {
            if (isStreaming) {
                stopStreaming()
            } else {
                startStreaming()
            }
        }
        
        binding.btnRetry.setOnClickListener {
            binding.llError.visibility = View.GONE
            binding.llLoading.visibility = View.VISIBLE
            connectWebSocket()
        }
        
        // Setup surface view
        binding.surfaceView.holder.addCallback(this)
        
        // Show appropriate view for stream type
        when (streamType) {
            "live_listen" -> {
                binding.surfaceView.visibility = View.GONE
                binding.llAudioVisualizer.visibility = View.VISIBLE
            }
            else -> {
                binding.surfaceView.visibility = View.VISIBLE
                binding.llAudioVisualizer.visibility = View.GONE
            }
        }
        
        binding.tvLoadingStatus.text = "Connecting..."
    }
    
    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceHolder = holder
    }
    
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceHolder = null
    }
    
    private fun connectWebSocket() {
        val token = (application as FamilyGuardApp).getAuthToken() ?: run {
            Toast.makeText(this, "Not authenticated", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
        
        val wsUrl = "${FamilyGuardApp.WS_URL}?token=$token"
        Log.d(TAG, "Connecting to WebSocket: $wsUrl")
        
        val request = Request.Builder()
            .url(wsUrl)
            .build()
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                runOnUiThread {
                    binding.tvLoadingStatus.text = "Connected, starting stream..."
                    startStreaming()
                }
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error", t)
                runOnUiThread {
                    binding.llLoading.visibility = View.GONE
                    binding.llError.visibility = View.VISIBLE
                    binding.tvErrorMessage.text = "Connection failed: ${t.message}"
                }
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
                runOnUiThread {
                    binding.tvLoadingStatus.text = "Disconnected"
                    binding.llLoading.visibility = View.VISIBLE
                    isStreaming = false
                    updateStreamButton()
                }
            }
        })
    }
    
    private fun startStreaming() {
        val command = when (streamType) {
            "screen_mirror" -> "start_screen_mirror"
            "camera" -> "start_camera"
            "live_listen" -> "start_live_listen"
            else -> return
        }
        
        val message = JSONObject().apply {
            put("type", "command")
            put("command", command)
            put("targetDeviceId", deviceId)
        }.toString()
        
        webSocket?.send(message)
        
        isStreaming = true
        binding.tvLoadingStatus.text = "Waiting for device to start streaming..."
        binding.llLoading.visibility = View.VISIBLE
        updateStreamButton()
        
        // Initialize audio track for live listen
        if (streamType == "live_listen") {
            initAudioTrack()
        }
    }
    
    private fun stopStreaming() {
        val command = when (streamType) {
            "screen_mirror" -> "stop_screen_mirror"
            "camera" -> "stop_camera"
            "live_listen" -> "stop_live_listen"
            else -> return
        }
        
        val message = JSONObject().apply {
            put("type", "command")
            put("command", command)
            put("targetDeviceId", deviceId)
        }.toString()
        
        webSocket?.send(message)
        
        isStreaming = false
        binding.tvLoadingStatus.text = "Stream stopped"
        binding.llLoading.visibility = View.GONE
        updateStreamButton()
        
        // Stop audio
        audioTrack?.stop()
    }
    
    private fun updateStreamButton() {
        binding.btnToggleStream.text = if (isStreaming) "Stop" else "Start"
    }
    
    private fun handleMessage(message: String) {
        try {
            val json = JSONObject(message)
            val type = json.optString("type")
            
            when (type) {
                "stream_frame", "screen_frame", "camera_frame" -> {
                    handleVideoFrame(json)
                }
                "audio_data", "live_audio" -> {
                    handleAudioData(json)
                }
                "stream_started" -> {
                    runOnUiThread {
                        binding.tvLoadingStatus.text = "Streaming..."
                        binding.llLoading.visibility = View.GONE
                    }
                }
                "stream_stopped" -> {
                    runOnUiThread {
                        binding.tvLoadingStatus.text = "Stream stopped"
                        binding.llLoading.visibility = View.VISIBLE
                        isStreaming = false
                        updateStreamButton()
                    }
                }
                "error" -> {
                    val error = json.optString("message", "Unknown error")
                    runOnUiThread {
                        binding.llLoading.visibility = View.GONE
                        binding.llError.visibility = View.VISIBLE
                        binding.tvErrorMessage.text = "Error: $error"
                    }
                }
                "device_offline" -> {
                    runOnUiThread {
                        binding.llLoading.visibility = View.GONE
                        binding.llError.visibility = View.VISIBLE
                        binding.tvErrorMessage.text = "Device is offline"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message", e)
        }
    }
    
    private fun handleVideoFrame(json: JSONObject) {
        try {
            val frameData = json.optString("data") ?: json.optString("frame")
            if (frameData.isNotEmpty()) {
                val imageBytes = Base64.decode(frameData, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                
                if (bitmap != null) {
                    runOnUiThread {
                        // Draw bitmap on surface
                        surfaceHolder?.let { holder ->
                            val canvas = holder.lockCanvas()
                            if (canvas != null) {
                                canvas.drawBitmap(bitmap, 0f, 0f, null)
                                holder.unlockCanvasAndPost(canvas)
                            }
                        }
                        binding.tvLoadingStatus.text = "Streaming..."
                        binding.llLoading.visibility = View.GONE
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding video frame", e)
        }
    }
    
    private fun handleAudioData(json: JSONObject) {
        try {
            val audioData = json.optString("data") ?: json.optString("audio")
            if (audioData.isNotEmpty()) {
                val audioBytes = Base64.decode(audioData, Base64.DEFAULT)
                playAudio(audioBytes)
                
                runOnUiThread {
                    binding.tvLoadingStatus.text = "Listening..."
                    binding.llLoading.visibility = View.GONE
                    binding.llAudioVisualizer.visibility = View.VISIBLE
                    // Update audio duration display
                    val durationSecs = (System.currentTimeMillis() / 1000) % 3600
                    binding.tvAudioDuration.text = String.format("%02d:%02d", durationSecs / 60, durationSecs % 60)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding audio", e)
        }
    }
    
    private fun initAudioTrack() {
        val bufferSize = AudioTrack.getMinBufferSize(
            audioSampleRate,
            audioChannels,
            audioEncoding
        )
        
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(audioSampleRate)
                    .setEncoding(audioEncoding)
                    .setChannelMask(audioChannels)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        
        audioTrack?.play()
    }
    
    private fun playAudio(audioBytes: ByteArray) {
        try {
            audioTrack?.write(audioBytes, 0, audioBytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "Error playing audio", e)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
        webSocket?.close(1000, "Activity destroyed")
        audioTrack?.release()
    }
    
    override fun onPause() {
        super.onPause()
        if (isStreaming) {
            stopStreaming()
        }
    }
}
