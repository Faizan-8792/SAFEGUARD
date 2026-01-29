package com.familyguardpro

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.SurfaceHolder
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.familyguardpro.databinding.ActivityStreamingBinding
import com.familyguardpro.utils.PreferenceManager
import kotlinx.coroutines.*
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.nio.ByteBuffer

class StreamingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStreamingBinding
    private lateinit var preferenceManager: PreferenceManager
    
    private var deviceId: String = ""
    private var streamType: String = ""
    
    private var webSocketClient: WebSocketClient? = null
    private var videoDecoder: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private var isStreaming = false

    companion object {
        private const val TAG = "StreamingActivity"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStreamingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        preferenceManager = PreferenceManager(this)
        deviceId = intent.getStringExtra("deviceId") ?: ""
        streamType = intent.getStringExtra("streamType") ?: "camera"
        
        setupUI()
    }

    override fun onResume() {
        super.onResume()
        startStreaming()
    }

    override fun onPause() {
        super.onPause()
        stopStreaming()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
    }

    private fun setupUI() {
        binding.toolbar.title = when (streamType) {
            "camera" -> "📷 Remote Camera"
            "screen_mirror" -> "📱 Screen Mirror"
            "live_audio" -> "🔊 Live Listen"
            "live_call" -> "📞 Live Call"
            else -> "Streaming"
        }
        
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        
        // Show/hide video surface based on stream type
        if (streamType == "live_audio" || streamType == "live_call") {
            binding.surfaceView.visibility = View.GONE
            binding.llAudioVisualizer.visibility = View.VISIBLE
        } else {
            binding.surfaceView.visibility = View.VISIBLE
            binding.llAudioVisualizer.visibility = View.GONE
        }
        
        binding.surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (streamType != "live_audio" && streamType != "live_call") {
                    initVideoDecoder(holder)
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                releaseVideoDecoder()
            }
        })
        
        binding.btnToggleStream.setOnClickListener {
            finish()
        }
    }

    private fun startStreaming() {
        if (isStreaming) return
        isStreaming = true
        
        binding.llLoading.visibility = View.VISIBLE
        binding.tvLoadingStatus.text = "Connecting..."
        
        val baseUrl = com.familyguardpro.network.ApiClient.BASE_URL
            .replace("https://", "wss://")
            .replace("http://", "ws://")
        
        val wsUrl = "$baseUrl/api/stream/$deviceId?type=$streamType&token=${preferenceManager.getAuthToken()}"
        
        try {
            webSocketClient = object : WebSocketClient(URI(wsUrl)) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    runOnUiThread {
                        binding.llLoading.visibility = View.GONE
                        binding.llStreamInfo.visibility = View.VISIBLE
                        Toast.makeText(this@StreamingActivity, "Connected", Toast.LENGTH_SHORT).show()
                    }
                    
                    if (streamType == "live_audio" || streamType == "live_call") {
                        initAudioTrack()
                    }
                }

                override fun onMessage(message: String?) {
                    message?.let { processMessage(it) }
                }

                override fun onMessage(bytes: ByteBuffer?) {
                    bytes?.let { processVideoFrame(it) }
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    runOnUiThread {
                        binding.llLoading.visibility = View.VISIBLE
                        binding.tvLoadingStatus.text = "Disconnected"
                        if (remote) {
                            Toast.makeText(this@StreamingActivity, "Stream ended", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                override fun onError(ex: Exception?) {
                    runOnUiThread {
                        binding.llLoading.visibility = View.GONE
                        binding.llError.visibility = View.VISIBLE
                        binding.tvErrorMessage.text = "Connection error: ${ex?.message}"
                        Toast.makeText(this@StreamingActivity, "Connection error: ${ex?.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            
            webSocketClient?.connect()
            
        } catch (e: Exception) {
            Log.e(TAG, "WebSocket error", e)
            binding.llLoading.visibility = View.GONE
            binding.llError.visibility = View.VISIBLE
            Toast.makeText(this, "Failed to connect", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopStreaming() {
        isStreaming = false
        webSocketClient?.close()
        webSocketClient = null
        releaseVideoDecoder()
        releaseAudioTrack()
    }

    private fun initVideoDecoder(holder: SurfaceHolder) {
        try {
            val width = if (streamType == "screen_mirror") 640 else 854
            val height = if (streamType == "screen_mirror") 360 else 480
            
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            
            videoDecoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            videoDecoder?.configure(format, holder.surface, null, 0)
            videoDecoder?.start()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init video decoder", e)
        }
    }

    private fun releaseVideoDecoder() {
        try {
            videoDecoder?.stop()
            videoDecoder?.release()
            videoDecoder = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing decoder", e)
        }
    }

    private fun initAudioTrack() {
        try {
            val bufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            
            audioTrack?.play()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init audio track", e)
        }
    }

    private fun releaseAudioTrack() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audio track", e)
        }
    }

    private fun processMessage(message: String) {
        try {
            // Check if it's audio data (base64 encoded)
            if (message.startsWith("audio:")) {
                val audioData = Base64.decode(message.substring(6), Base64.DEFAULT)
                playAudio(audioData)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing message", e)
        }
    }

    private fun processVideoFrame(buffer: ByteBuffer) {
        videoDecoder?.let { decoder ->
            try {
                val inputBufferIndex = decoder.dequeueInputBuffer(10000)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputBufferIndex)
                    inputBuffer?.clear()
                    inputBuffer?.put(buffer)
                    decoder.queueInputBuffer(inputBufferIndex, 0, buffer.remaining(), 0, 0)
                }
                
                val bufferInfo = MediaCodec.BufferInfo()
                var outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000)
                
                while (outputBufferIndex >= 0) {
                    decoder.releaseOutputBuffer(outputBufferIndex, true)
                    outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error decoding video", e)
            }
        }
    }

    private fun playAudio(audioData: ByteArray) {
        audioTrack?.write(audioData, 0, audioData.size)
    }
}
