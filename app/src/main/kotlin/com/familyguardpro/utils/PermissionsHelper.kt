package com.familyguardpro.utils

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object PermissionsHelper {
    
    private const val TAG = "PermissionsHelper"
    
    // Request codes
    const val REQUEST_PERMISSIONS = 100
    const val REQUEST_USAGE_ACCESS = 101
    const val REQUEST_NOTIFICATION_ACCESS = 102
    const val REQUEST_OVERLAY = 103
    const val REQUEST_BATTERY_OPTIMIZATION = 104
    const val REQUEST_DEVICE_ADMIN = 105
    const val REQUEST_ACCESSIBILITY = 106
    const val REQUEST_BACKGROUND_LOCATION = 107
    
    // All required permissions
    val REQUIRED_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.POST_NOTIFICATIONS
        )
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_EXTERNAL_STORAGE
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }
    
    // Background location (requested separately)
    val BACKGROUND_LOCATION_PERMISSION = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        Manifest.permission.ACCESS_BACKGROUND_LOCATION
    } else {
        null
    }
    
    /**
     * Check if all runtime permissions are granted
     */
    fun hasAllPermissions(context: Context): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * Check if basic permissions are granted (location, phone, storage, audio)
     */
    fun hasBasicPermissions(context: Context): Boolean {
        val basicPermissions = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.RECORD_AUDIO
        )
        return basicPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * Check if usage access permission is granted
     */
    fun hasUsageAccess(context: Context): Boolean {
        return hasUsageStatsPermission(context)
    }
    
    /**
     * Check if notification access permission is granted
     */
    fun hasNotificationAccess(context: Context): Boolean {
        return hasNotificationListenerPermission(context)
    }
    
    /**
     * Check if accessibility service is enabled (generic check)
     */
    fun hasAccessibilityAccess(context: Context): Boolean {
        val serviceName = "${context.packageName}/com.familyguardpro.services.FamilyGuardAccessibilityService"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        
        return enabledServices.split(':').any { it.equals(serviceName, ignoreCase = true) }
    }
    
    /**
     * Get list of missing permissions
     */
    fun getMissingPermissions(context: Context): List<String> {
        return REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * Request runtime permissions
     */
    fun requestPermissions(activity: Activity) {
        val missingPermissions = getMissingPermissions(activity)
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                activity,
                missingPermissions.toTypedArray(),
                REQUEST_PERMISSIONS
            )
        }
    }
    
    /**
     * Request background location permission (must be requested separately on Android 10+)
     */
    fun requestBackgroundLocation(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                    REQUEST_BACKGROUND_LOCATION
                )
            }
        }
    }
    
    /**
     * Check if background location is granted
     */
    fun hasBackgroundLocation(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * Check if usage stats permission is granted
     */
    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }
    
    /**
     * Open usage stats settings
     */
    fun requestUsageStatsPermission(activity: Activity) {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        activity.startActivityForResult(intent, REQUEST_USAGE_ACCESS)
    }
    
    /**
     * Check if notification listener permission is granted
     */
    fun hasNotificationListenerPermission(context: Context): Boolean {
        return NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
    }
    
    /**
     * Open notification listener settings
     */
    fun requestNotificationListenerPermission(activity: Activity) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        activity.startActivityForResult(intent, REQUEST_NOTIFICATION_ACCESS)
    }
    
    /**
     * Check if overlay permission is granted
     */
    fun hasOverlayPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }
    
    /**
     * Request overlay permission
     */
    fun requestOverlayPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${activity.packageName}")
            )
            activity.startActivityForResult(intent, REQUEST_OVERLAY)
        }
    }
    
    /**
     * Check if battery optimization is disabled (app is whitelisted)
     */
    fun isBatteryOptimizationDisabled(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }
    
    /**
     * Request battery optimization exemption
     */
    fun requestBatteryOptimizationExemption(activity: Activity) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${activity.packageName}")
        }
        activity.startActivityForResult(intent, REQUEST_BATTERY_OPTIMIZATION)
    }
    
    /**
     * Check if device admin is enabled
     */
    fun isDeviceAdminEnabled(context: Context, adminReceiver: ComponentName): Boolean {
        val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return devicePolicyManager.isAdminActive(adminReceiver)
    }
    
    /**
     * Request device admin permission
     */
    fun requestDeviceAdmin(activity: Activity, adminReceiver: ComponentName) {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminReceiver)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "This app requires device admin access to protect against unauthorized uninstallation."
            )
        }
        activity.startActivityForResult(intent, REQUEST_DEVICE_ADMIN)
    }
    
    /**
     * Check if accessibility service is enabled
     */
    fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
        val serviceName = "${context.packageName}/${serviceClass.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        
        return enabledServices.split(':').any { it.equals(serviceName, ignoreCase = true) }
    }
    
    /**
     * Open accessibility settings
     */
    fun requestAccessibilityPermission(activity: Activity) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        activity.startActivityForResult(intent, REQUEST_ACCESSIBILITY)
    }
    
    /**
     * Get overall permission status map
     */
    fun getPermissionStatus(context: Context): Map<String, Boolean> {
        return mapOf(
            "location" to (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED),
            "backgroundLocation" to hasBackgroundLocation(context),
            "camera" to (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED),
            "microphone" to (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED),
            "callLog" to (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED),
            "notifications" to hasNotificationListenerPermission(context),
            "usageStats" to hasUsageStatsPermission(context),
            "overlay" to hasOverlayPermission(context),
            "batteryOptimization" to isBatteryOptimizationDisabled(context),
            "storage" to hasStoragePermission(context)
        )
    }
    
    private fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }
}
