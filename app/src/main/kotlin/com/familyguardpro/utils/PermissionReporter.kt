package com.familyguardpro.utils

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import com.familyguardpro.network.ApiClient
import com.familyguardpro.services.DeviceAdminReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Utility class to check and report device permissions to the server
 */
object PermissionReporter {
    private const val TAG = "PermissionReporter"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Check all permissions and return a map of permission statuses
     */
    fun checkAllPermissions(context: Context): Map<String, Boolean> {
        return mapOf(
            "location" to hasLocationPermission(context),
            "backgroundLocation" to hasBackgroundLocationPermission(context),
            "camera" to hasCameraPermission(context),
            "microphone" to hasMicrophonePermission(context),
            "storage" to hasStoragePermission(context),
            "callLog" to hasCallLogPermission(context),
            "contacts" to hasContactsPermission(context),
            "sms" to hasSmsPermission(context),
            "phone" to hasPhonePermission(context),
            "notifications" to hasNotificationListenerPermission(context),
            "usageStats" to hasUsageStatsPermission(context),
            "overlay" to hasOverlayPermission(context),
            "batteryOptimization" to hasBatteryOptimizationExemption(context),
            "deviceAdmin" to hasDeviceAdminPermission(context),
            "accessibility" to hasAccessibilityPermission(context)
        )
    }

    /**
     * Report permissions to the server
     */
    fun reportPermissions(context: Context) {
        val prefs = PreferenceManager(context)
        if (!prefs.isChildMode()) return

        val deviceId = prefs.getDeviceId()
        if (deviceId.isEmpty()) return

        val permissions = checkAllPermissions(context)

        scope.launch {
            try {
                val response = ApiClient.api.updatePermissions(
                    deviceId,
                    com.familyguardpro.network.PermissionsRequestBody(
                        permissions = HashMap(permissions)
                    )
                )
                if (response.success) {
                    Log.d(TAG, "Permissions reported successfully")
                } else {
                    Log.e(TAG, "Failed to report permissions: ${response.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reporting permissions", e)
            }
        }
    }

    /**
     * Get list of missing required permissions
     */
    fun getMissingPermissions(context: Context): List<String> {
        val permissions = checkAllPermissions(context)
        val required = listOf(
            "location", "backgroundLocation", "camera", "microphone",
            "callLog", "phone", "notifications", "usageStats",
            "batteryOptimization", "deviceAdmin"
        )
        return required.filter { !(permissions[it] ?: false) }
    }

    // Individual permission checks
    private fun hasLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasBackgroundLocationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            hasLocationPermission(context)
        }
    }

    private fun hasCameraPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasMicrophonePermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasCallLogPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasContactsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasSmsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasPhonePermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasNotificationListenerPermission(context: Context): Boolean {
        val packageName = context.packageName
        val flat = Settings.Secure.getString(
            context.contentResolver, 
            "enabled_notification_listeners"
        )
        return flat?.contains(packageName) == true
    }

    private fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = appOps.checkOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        )
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    private fun hasOverlayPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    private fun hasBatteryOptimizationExemption(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun hasDeviceAdminPermission(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(context, DeviceAdminReceiver::class.java)
        return dpm.isAdminActive(adminComponent)
    }

    private fun hasAccessibilityPermission(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabledServices.any { it.resolveInfo.serviceInfo.packageName == context.packageName }
    }
}
