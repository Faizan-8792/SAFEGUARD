package com.familyguardpro.utils

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import com.familyguardpro.FamilyGuardApp
import com.familyguardpro.network.ApiClient
import com.familyguardpro.services.DeviceAdminReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object PermissionReporter {
    
    private const val TAG = "PermissionReporter"
    
    fun reportPermissions(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as? FamilyGuardApp
                val deviceId = app?.preferenceManager?.getDeviceId() ?: return@launch
                
                val permissions = collectPermissions(context)
                
                Log.d(TAG, "Reporting permissions: $permissions")
                
                ApiClient.updatePermissions(deviceId, permissions)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to report permissions", e)
            }
        }
    }
    
    private fun collectPermissions(context: Context): Map<String, Boolean> {
        val accessibilityEnabled = isAccessibilityServiceEnabled(context)
        val notificationsEnabled = isNotificationListenerEnabled(context)
        
        return mapOf(
            "camera" to hasPermission(context, Manifest.permission.CAMERA),
            "microphone" to hasPermission(context, Manifest.permission.RECORD_AUDIO),
            "location" to hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION),
            "backgroundLocation" to (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || 
                hasPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)),
            "phone" to hasPermission(context, Manifest.permission.READ_PHONE_STATE),
            "contacts" to hasPermission(context, Manifest.permission.READ_CONTACTS),
            "callLog" to hasPermission(context, Manifest.permission.READ_CALL_LOG),
            "storage" to hasStoragePermission(context),
            "notifications" to notificationsEnabled,
            "usageStats" to hasUsageStatsPermission(context),
            "batteryOptimization" to isIgnoringBatteryOptimizations(context),
            "overlay" to hasOverlayPermission(context),
            // Added missing permissions
            "sms" to hasSmsPermission(context),
            "deviceAdmin" to isDeviceAdminActive(context),
            "accessibility" to accessibilityEnabled,
            // restrictedSettings - inferred from accessibility/notifications working on Android 13+
            // If accessibility or notifications listener is enabled on Android 13+, restricted settings must be allowed
            "restrictedSettings" to (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || 
                accessibilityEnabled || notificationsEnabled)
        )
    }
    
    private fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == 
            PackageManager.PERMISSION_GRANTED
    }
    
    private fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(context, Manifest.permission.READ_MEDIA_IMAGES) ||
                hasPermission(context, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            hasPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
    
    private fun isNotificationListenerEnabled(context: Context): Boolean {
        val packageName = context.packageName
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
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
    
    private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }
    
    private fun hasOverlayPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }
    
    private fun hasSmsPermission(context: Context): Boolean {
        return hasPermission(context, Manifest.permission.READ_SMS) &&
               hasPermission(context, Manifest.permission.RECEIVE_SMS)
    }
    
    private fun isDeviceAdminActive(context: Context): Boolean {
        return try {
            val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) 
                as android.app.admin.DevicePolicyManager
            val adminComponent = ComponentName(context, DeviceAdminReceiver::class.java)
            devicePolicyManager.isAdminActive(adminComponent)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking device admin", e)
            false
        }
    }
    
    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        return try {
            val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) 
                as AccessibilityManager
            val enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            )
            val packageName = context.packageName
            enabledServices.any { 
                it.resolveInfo.serviceInfo.packageName == packageName 
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking accessibility service", e)
            false
        }
    }
}
