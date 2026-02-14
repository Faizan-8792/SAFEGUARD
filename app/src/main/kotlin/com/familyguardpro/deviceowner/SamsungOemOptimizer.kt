package com.familyguardpro.deviceowner

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Samsung OEM Optimizer
 * 
 * Samsung devices with One UI have:
 * - Device Care → Battery → Sleeping apps
 * - App Power Management
 * - Adaptive battery
 * - Background usage limits
 */
class SamsungOemOptimizer(context: Context) : BaseOemOptimizer(context) {
    
    override fun getManufacturer() = "Samsung"
    
    override suspend fun optimize() {
        Log.d(TAG, "=== Starting Samsung/One UI optimization ===")
        
        var autoStartEnabled = true // Samsung doesn't have auto-start restriction
        var batteryOptDisabled = false
        var backgroundRunAllowed = false
        
        batteryOptDisabled = disableBatteryOptimization()
        backgroundRunAllowed = tryDisableSleepingApps()
        
        // Disable adaptive battery via DPM
        tryDisableAdaptiveBattery()
        
        reportStatus(autoStartEnabled, batteryOptDisabled, backgroundRunAllowed)
        Log.d(TAG, "=== Samsung optimization complete ===")
    }
    
    private fun tryDisableSleepingApps(): Boolean {
        val intents = listOf(
            // Device Care → Battery
            Intent().apply {
                component = ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.battery.ui.BatteryActivity"
                )
            },
            // App Power Management
            Intent().apply {
                component = ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.battery.ui.AppPowerManagementActivity"
                )
            },
            // Newer One UI
            Intent().apply {
                component = ComponentName(
                    "com.samsung.android.sm.devicesecurity",
                    "com.samsung.android.sm.devicesecurity.ui.UnusedAppsActivity"
                )
            }
        )
        
        for (intent in intents) {
            if (tryLaunchActivity(intent)) {
                Log.d(TAG, "Launched Samsung battery settings: ${intent.component}")
                return true
            }
        }
        return false
    }
    
    private fun tryDisableAdaptiveBattery() {
        try {
            if (dpm.isDeviceOwnerApp(context.packageName)) {
                writeGlobalSetting("adaptive_battery_management_enabled", "0")
                Log.d(TAG, "Disabled adaptive battery via DPM")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not disable adaptive battery", e)
        }
    }
}
