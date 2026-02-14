package com.familyguardpro.deviceowner

import android.content.Context
import android.util.Log

/**
 * Generic OEM Optimizer - Used when manufacturer is not specifically supported.
 * Applies only standard Android optimizations via Device Policy Manager.
 */
class GenericOemOptimizer(context: Context) : BaseOemOptimizer(context) {
    
    override fun getManufacturer() = "Generic"
    
    override suspend fun optimize() {
        Log.d(TAG, "=== Starting Generic OEM optimization ===")
        
        val batteryOptDisabled = disableBatteryOptimization()
        
        // Apply DPM settings
        try {
            if (dpm.isDeviceOwnerApp(context.packageName)) {
                writeGlobalSetting("adaptive_battery_management_enabled", "0")
                writeGlobalSetting("wifi_sleep_policy", "2")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error applying generic DPM settings", e)
        }
        
        reportStatus(
            autoStartEnabled = true,
            batteryOptDisabled = batteryOptDisabled,
            backgroundRunAllowed = true
        )
        Log.d(TAG, "=== Generic optimization complete ===")
    }
}
