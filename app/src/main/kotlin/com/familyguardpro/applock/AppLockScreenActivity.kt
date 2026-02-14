package com.familyguardpro.applock

import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.familyguardpro.R
import com.familyguardpro.utils.PreferenceManager
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executor

/**
 * Lock Screen Activity - Shows when a locked app is opened
 * Supports PIN, Pattern, and Biometric (Fingerprint) authentication
 * Takes intruder selfie on failed attempts
 */
class AppLockScreenActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "AppLockScreen"
        private const val MAX_FAILED_ATTEMPTS = 3
    }
    
    private lateinit var preferenceManager: PreferenceManager
    private lateinit var executor: Executor
    private var biometricPrompt: BiometricPrompt? = null
    
    private var lockedPackageName: String? = null
    private var failedAttempts = 0
    
    // UI Elements
    private var appIconView: ImageView? = null
    private var appNameView: TextView? = null
    private var pinInput: EditText? = null
    private var unlockButton: Button? = null
    private var biometricButton: ImageButton? = null
    private var patternView: PatternLockView? = null
    private var pinLayout: LinearLayout? = null
    private var patternLayout: LinearLayout? = null
    private var errorText: TextView? = null
    
    // Camera for intruder selfie
    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var imageReader: ImageReader? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Prevent screenshots for security
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        setContentView(R.layout.activity_lock_screen)
        preferenceManager = PreferenceManager(this)
        executor = ContextCompat.getMainExecutor(this)
        lockedPackageName = intent.getStringExtra("package_name")
        initViews()
        setupAppInfo()

        // Priority: fingerprint > pattern > pin > password
        val biometricEnabled = preferenceManager.isBiometricEnabled() && isBiometricAvailable()
        val patternEnabled = preferenceManager.getLockMethod() == "pattern" && !biometricEnabled
        val pinEnabled = preferenceManager.getLockMethod() == "pin" && !biometricEnabled && !patternEnabled
        val passwordEnabled = preferenceManager.getLockMethod() == "password" && !biometricEnabled && !patternEnabled && !pinEnabled

        if (biometricEnabled) {
            showOnlyBiometric()
        } else if (patternEnabled) {
            showOnlyPattern()
        } else if (pinEnabled) {
            showOnlyPin()
        } else if (passwordEnabled) {
            showOnlyPassword()
        }
    }
        private fun isBiometricAvailable(): Boolean {
            val biometricManager = BiometricManager.from(this)
            return biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
        }

        private fun showOnlyBiometric() {
            pinLayout?.visibility = View.GONE
            patternLayout?.visibility = View.GONE
            biometricButton?.visibility = View.VISIBLE
            setupBiometric()
        }

        private fun showOnlyPattern() {
            pinLayout?.visibility = View.GONE
            patternLayout?.visibility = View.VISIBLE
            biometricButton?.visibility = View.GONE
            patternView?.setOnPatternListener(object : PatternLockView.OnPatternListener {
                override fun onPatternComplete(pattern: String) {
                    verifyPattern(pattern)
                }
            })
            if (preferenceManager.isHidePatternEnabled()) {
                patternView?.setPathVisible(false)
            }
        }

        private fun showOnlyPin() {
            pinLayout?.visibility = View.VISIBLE
            patternLayout?.visibility = View.GONE
            biometricButton?.visibility = View.GONE
        }

        private fun showOnlyPassword() {
            // If you have a password layout, show it here. Otherwise, fallback to pin.
            showOnlyPin()
        }
    
    private fun initViews() {
        appIconView = findViewById(R.id.appIcon)
        appNameView = findViewById(R.id.appName)
        pinInput = findViewById(R.id.pinInput)
        unlockButton = findViewById(R.id.unlockButton)
        biometricButton = findViewById(R.id.biometricButton)
        pinLayout = findViewById(R.id.pinLayout)
        patternLayout = findViewById(R.id.patternLayout)
        patternView = findViewById(R.id.patternView)
        errorText = findViewById(R.id.errorText)
        
        unlockButton?.setOnClickListener {
            verifyPin()
        }
        
        biometricButton?.setOnClickListener {
            showBiometricPrompt()
        }
    }
    
    private fun setupAppInfo() {
        lockedPackageName?.let { pkg ->
            try {
                val pm = packageManager
                val appInfo = pm.getApplicationInfo(pkg, 0)
                appIconView?.setImageDrawable(pm.getApplicationIcon(appInfo))
                appNameView?.text = pm.getApplicationLabel(appInfo)
            } catch (e: PackageManager.NameNotFoundException) {
                appNameView?.text = "Unknown App"
            }
        }
    }
    
    private fun setupLockMethod() {
        val lockMethod = preferenceManager.getLockMethod()
        
        when (lockMethod) {
            "pattern" -> {
                pinLayout?.visibility = View.GONE
                patternLayout?.visibility = View.VISIBLE
                patternView?.setOnPatternListener(object : PatternLockView.OnPatternListener {
                    override fun onPatternComplete(pattern: String) {
                        verifyPattern(pattern)
                    }
                })
                // Hide pattern lines if enabled
                if (preferenceManager.isHidePatternEnabled()) {
                    patternView?.setPathVisible(false)
                }
            }
            else -> { // PIN
                pinLayout?.visibility = View.VISIBLE
                patternLayout?.visibility = View.GONE
            }
        }
    }
    
    private fun setupBiometric() {
        // Check if biometric is enabled in settings and device supports it
        if (!preferenceManager.isBiometricEnabled()) {
            biometricButton?.visibility = View.GONE
            return
        }
        
        val biometricManager = BiometricManager.from(this)
        when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK)) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                Log.d(TAG, "Biometric authentication available")
                biometricButton?.visibility = View.VISIBLE
                setupBiometricPrompt()
                // Auto-show biometric prompt
                showBiometricPrompt()
            }
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
                Log.d(TAG, "No biometric hardware")
                biometricButton?.visibility = View.GONE
            }
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> {
                Log.d(TAG, "Biometric hardware unavailable")
                biometricButton?.visibility = View.GONE
            }
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                Log.d(TAG, "No biometric enrolled")
                biometricButton?.visibility = View.GONE
                Toast.makeText(this, "Please add fingerprint in phone settings first", Toast.LENGTH_LONG).show()
            }
            else -> {
                biometricButton?.visibility = View.GONE
            }
        }
    }
    
    private fun setupBiometricPrompt() {
        biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    Log.d(TAG, "Biometric authentication succeeded")
                    onUnlockSuccess()
                }
                
                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Log.d(TAG, "Biometric authentication failed")
                    // Don't call onUnlockFailed for biometric - user may retry
                    Toast.makeText(this@AppLockScreenActivity, "Fingerprint not recognized", Toast.LENGTH_SHORT).show()
                }
                
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Log.d(TAG, "Biometric error: $errorCode - $errString")
                    // User cancelled or other error - just dismiss, don't show error
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                        errorCode != BiometricPrompt.ERROR_CANCELED) {
                        Toast.makeText(this@AppLockScreenActivity, errString, Toast.LENGTH_SHORT).show()
                    }
                }
            })
    }
    
    private fun showBiometricPrompt() {
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock App")
            .setSubtitle("Use your fingerprint to unlock")
            .setNegativeButtonText("Use PIN/Pattern")
            .build()
        
        biometricPrompt?.authenticate(promptInfo)
    }
    
    private fun verifyPin() {
        val enteredPin = pinInput?.text?.toString() ?: ""
        val savedPin = preferenceManager.getAppLockPin()
        
        if (enteredPin.isEmpty()) {
            Toast.makeText(this, "Please enter PIN", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (enteredPin == savedPin) {
            onUnlockSuccess()
        } else {
            onUnlockFailed()
        }
    }
    
    private fun verifyPattern(pattern: String) {
        val savedPattern = preferenceManager.getLockPattern()
        
        if (pattern == savedPattern) {
            onUnlockSuccess()
        } else {
            onUnlockFailed()
            patternView?.showError()
        }
    }
    
    private fun onUnlockSuccess() {
        val pkg = lockedPackageName
        if (pkg != null) {
            // Notify service that app is unlocked
            val intent = Intent(this, AppLockService::class.java).apply {
                action = "UNLOCK"
                putExtra("package_name", pkg)
            }
            startService(intent)

            // Bring the locked app to the foreground
            try {
                val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launchIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch locked app: $pkg", e)
            }
        }
        finish()
    }
    
    private fun onUnlockFailed() {
        failedAttempts++
        errorText?.visibility = View.VISIBLE
        errorText?.text = "Wrong PIN/Pattern. Attempts: $failedAttempts/$MAX_FAILED_ATTEMPTS"
        pinInput?.text?.clear()
        patternView?.clearPattern()
        
        if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
            // Take intruder selfie
            if (preferenceManager.isIntruderSelfieEnabled()) {
                takeIntruderSelfie()
            }
            failedAttempts = 0
            
            // Optional: Lock out for 30 seconds
            lockoutUser()
        }
    }
    
    private fun lockoutUser() {
        unlockButton?.isEnabled = false
        patternView?.isEnabled = false
        biometricButton?.isEnabled = false
        errorText?.text = "Too many attempts. Wait 30 seconds..."
        
        Handler(Looper.getMainLooper()).postDelayed({
            unlockButton?.isEnabled = true
            patternView?.isEnabled = true
            biometricButton?.isEnabled = true
            errorText?.visibility = View.GONE
        }, 30000)
    }
    
    private fun takeIntruderSelfie() {
        Log.d(TAG, "Taking intruder selfie")
        
        cameraManager = getSystemService(CAMERA_SERVICE) as? CameraManager
        
        try {
            val frontCameraId = getFrontCameraId() ?: return
            
            imageReader = ImageReader.newInstance(640, 480, android.graphics.ImageFormat.JPEG, 1)
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                image?.let {
                    val buffer = it.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    saveIntruderPhoto(bytes)
                    it.close()
                }
                closeCameraResources()
            }, Handler(Looper.getMainLooper()))
            
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) 
                == PackageManager.PERMISSION_GRANTED) {
                cameraManager?.openCamera(frontCameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        capturePhoto()
                    }
                    
                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                    }
                    
                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        Log.e(TAG, "Camera error: $error")
                    }
                }, Handler(Looper.getMainLooper()))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to take intruder selfie", e)
        }
    }
    
    private fun getFrontCameraId(): String? {
        cameraManager?.cameraIdList?.forEach { id ->
            val characteristics = cameraManager?.getCameraCharacteristics(id)
            val facing = characteristics?.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                return id
            }
        }
        return null
    }
    
    private fun capturePhoto() {
        try {
            val surface = imageReader?.surface ?: return
            
            cameraDevice?.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        val captureRequest = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                        captureRequest?.addTarget(surface)
                        captureRequest?.set(CaptureRequest.JPEG_ORIENTATION, 270)
                        
                        captureRequest?.build()?.let { request ->
                            session.capture(request, null, null)
                        }
                    }
                    
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Camera session configuration failed")
                    }
                },
                Handler(Looper.getMainLooper())
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Camera access exception", e)
        }
    }
    
    private fun saveIntruderPhoto(bytes: ByteArray) {
        try {
            val intruderDir = File(filesDir, "intruders")
            if (!intruderDir.exists()) {
                intruderDir.mkdirs()
            }
            
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val file = File(intruderDir, "intruder_$timestamp.jpg")
            
            FileOutputStream(file).use { fos ->
                fos.write(bytes)
            }
            
            Log.d(TAG, "Intruder photo saved: ${file.absolutePath}")
            
            // Show toast
            Toast.makeText(this, "Security photo captured", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save intruder photo", e)
        }
    }
    
    private fun closeCameraResources() {
        try {
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera resources", e)
        }
    }
    
    override fun onBackPressed() {
        // Go to home screen instead of back to locked app
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        finish()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        closeCameraResources()
    }
}
