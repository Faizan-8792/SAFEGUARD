package com.familyguardpro.deviceowner

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.familyguardpro.services.DeviceAdminReceiver

/**
 * Base class for OEM-specific battery and background optimizations.
 * 
 * Each OEM (Vivo, Xiaomi, OPPO, Samsung, etc.) has their own proprietary
 * battery/background task management that kills apps. Device Owner mode
 * lets us bypass many of these restrictions programmatically.
 */
abstract class BaseOemOptimizer(protected val context: Context) {
    
    protected val TAG = "OemOptimizer-${getManufacturer()}"
    protected val dpm: DevicePolicyManager = 
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    protected val adminComponent = ComponentName(context, DeviceAdminReceiver::class.java)
    
    abstract fun getManufacturer(): String
    
    /**
     * Run all optimizations for this OEM
     */
    abstract suspend fun optimize()
    
    /**
     * Common: Disable battery optimization (doze) for our app
     */
    protected fun disableBatteryOptimization(): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                // Device Owner can modify settings directly
                if (dpm.isDeviceOwnerApp(context.packageName)) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
                Log.d(TAG, "Battery optimization disable requested")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable battery optimization", e)
            false
        }
    }
    
    /**
     * Common: Try to launch an OEM settings activity
     */
    protected fun tryLaunchActivity(intent: Intent): Boolean {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val resolveInfo = context.packageManager.resolveActivity(intent, 0)
            if (resolveInfo != null) {
                context.startActivity(intent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not launch activity: ${intent.component}", e)
            false
        }
    }
    
    /**
     * Common: Write a setting using Device Owner privileges
     */
    protected fun writeGlobalSetting(key: String, value: String): Boolean {
        return try {
            if (dpm.isDeviceOwnerApp(context.packageName)) {
                dpm.setGlobalSetting(adminComponent, key, value)
                Log.d(TAG, "Set global setting: $key = $value")
                true
            } else false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set global setting: $key", e)
            false
        }
    }
    
    /**
     * Common: Write a secure setting
     */
    protected fun writeSecureSetting(key: String, value: String): Boolean {
        return try {
            if (dpm.isDeviceOwnerApp(context.packageName)) {
                dpm.setSecureSetting(adminComponent, key, value)
                Log.d(TAG, "Set secure setting: $key = $value")
                true
            } else false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set secure setting: $key", e)
            false
        }
    }
    
    /**
     * Report optimization status to server
     */
    protected fun reportStatus(
        autoStartEnabled: Boolean,
        batteryOptDisabled: Boolean,
        backgroundRunAllowed: Boolean
    ) {
        try {
            val prefs = context.getSharedPreferences("do_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("oem_manufacturer", getManufacturer())
                .putBoolean("oem_autostart_enabled", autoStartEnabled)
                .putBoolean("oem_battery_opt_disabled", batteryOptDisabled)
                .putBoolean("oem_background_run_allowed", backgroundRunAllowed)
                .putLong("oem_last_optimized", System.currentTimeMillis())
                .apply()
            Log.d(TAG, "OEM status saved: autostart=$autoStartEnabled, batteryOpt=$batteryOptDisabled, bgRun=$backgroundRunAllowed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save OEM status", e)
        }
    }
    
    /**
     * Common: Lock accessibility service using Device Owner privileges.
     * This should be called at the end of every OEM optimize() method
     * to ensure the accessibility service cannot be disabled by the OEM's
     * battery/power manager.
     */
    protected fun lockAccessibilityService() {
        try {
            if (dpm.isDeviceOwnerApp(context.packageName)) {
                val doManager = DeviceOwnerManager.getInstance(context)
                doManager.forceEnableAccessibility()
                doManager.lockAccessibilitySettings()
                Log.d(TAG, "✅ Accessibility service locked via Device Owner after OEM optimization")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to lock accessibility service", e)
        }
    }
    
    /**
     * Common: Whitelist our package for background running using secure settings.
     * Some OEMs respect these setting keys to exempt apps from aggressive killing.
     */
    protected fun whitelistForBackground() {
        try {
            if (!dpm.isDeviceOwnerApp(context.packageName)) return
            val pkg = context.packageName
            
            // Try common OEM whitelist settings
            val whitelistSettings = mapOf(
                "background_whitelist" to pkg,
                "auto_start_list" to pkg,
                "keep_alive_list" to pkg,
                "protect_list" to pkg
            )
            
            for ((key, value) in whitelistSettings) {
                try {
                    writeGlobalSetting(key, value)
                } catch (e: Exception) {
                    // Not all settings exist on all OEMs, silently skip
                }
            }
            
            Log.d(TAG, "Background whitelisting attempted for $pkg")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to whitelist for background", e)
        }
    }
}
