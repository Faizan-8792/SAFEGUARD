package com.familyguardpro.deviceowner

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * OPPO/Realme/OnePlus OEM Optimizer
 * 
 * OPPO devices (including Realme and OnePlus with ColorOS) have:
 * - Auto-launch manager
 * - Battery optimization
 * - App quick freeze
 * - Smart power saver
 */
class OppoOemOptimizer(context: Context) : BaseOemOptimizer(context) {
    
    override fun getManufacturer() = "OPPO"
    
    override suspend fun optimize() {
        Log.d(TAG, "=== Starting OPPO/Realme/OnePlus optimization ===")
        
        var autoStartEnabled = false
        var batteryOptDisabled = false
        var backgroundRunAllowed = false
        
        batteryOptDisabled = disableBatteryOptimization()
        autoStartEnabled = tryEnableAutoStart()
        backgroundRunAllowed = tryDisableBatteryManager()
        
        // Disable app quick freeze
        tryDisableAppFreeze()
        
        reportStatus(autoStartEnabled, batteryOptDisabled, backgroundRunAllowed)
        Log.d(TAG, "=== OPPO optimization complete ===")
    }
    
    private fun tryEnableAutoStart(): Boolean {
        val intents = listOf(
            // ColorOS Auto-launch
            Intent().apply {
                component = ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                )
            },
            // Older ColorOS
            Intent().apply {
                component = ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity"
                )
            },
            // OPPO auto-start
            Intent().apply {
                component = ComponentName(
                    "com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity"
                )
            },
            // Realme
            Intent().apply {
                component = ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.privacypermissionsentry.PermissionTopActivity"
                )
            }
        )
        
        for (intent in intents) {
            if (tryLaunchActivity(intent)) {
                Log.d(TAG, "Launched OPPO auto-start: ${intent.component}")
                return true
            }
        }
        return false
    }
    
    private fun tryDisableBatteryManager(): Boolean {
        val intents = listOf(
            // ColorOS battery optimization
            Intent().apply {
                component = ComponentName(
                    "com.coloros.oppoguardelf",
                    "com.coloros.powermanager.fuelgaue.PowerConsumptionActivity"
                )
            },
            // Battery manager
            Intent().apply {
                component = ComponentName(
                    "com.coloros.oppoguardelf",
                    "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"
                )
            }
        )
        
        for (intent in intents) {
            if (tryLaunchActivity(intent)) return true
        }
        return false
    }
    
    private fun tryDisableAppFreeze() {
        val intent = Intent().apply {
            component = ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.appfrozen.AppFrozenSettingsActivity"
            )
        }
        tryLaunchActivity(intent)
    }
}
