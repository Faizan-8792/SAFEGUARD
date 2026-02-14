package com.familyguardpro.deviceowner

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Vivo OEM Optimizer
 * 
 * Vivo/iQOO devices use FuntouchOS/OriginOS with aggressive background task management.
 * Key settings:
 * - iManager → App Manager → Background High Power Consumption
 * - iManager → Battery → Background Power Consumption Management
 * - Settings → Battery → Background Power Consumption Management
 * - Auto-start management via iManager
 */
class VivoOemOptimizer(context: Context) : BaseOemOptimizer(context) {
    
    override fun getManufacturer() = "Vivo"
    
    override suspend fun optimize() {
        Log.d(TAG, "=== Starting Vivo/iQOO optimization ===")
        
        var autoStartEnabled = false
        var batteryOptDisabled = false
        var backgroundRunAllowed = false
        
        // 1. Disable battery optimization (Android standard)
        batteryOptDisabled = disableBatteryOptimization()
        
        // 2. Try to add ourselves to auto-start whitelist
        autoStartEnabled = tryEnableAutoStart()
        
        // 3. Try to allow background high power consumption
        backgroundRunAllowed = tryAllowBackgroundPower()
        
        // 4. Try to disable memory cleanup for our app
        tryDisableMemoryCleanup()
        
        // 5. Keep app alive settings
        tryKeepAlive()
        
        reportStatus(autoStartEnabled, batteryOptDisabled, backgroundRunAllowed)
        Log.d(TAG, "=== Vivo optimization complete ===")
    }
    
    private fun tryEnableAutoStart(): Boolean {
        // Vivo auto-start managers (different FuntouchOS versions)
        val intents = listOf(
            // FuntouchOS 9+
            Intent().apply {
                component = ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                )
            },
            // Older FuntouchOS
            Intent().apply {
                component = ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"
                )
            },
            // iManager auto-start
            Intent().apply {
                component = ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
                )
            },
            // OriginOS
            Intent().apply {
                component = ComponentName(
                    "com.vivo.abe",
                    "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"
                )
            }
        )
        
        for (intent in intents) {
            if (tryLaunchActivity(intent)) {
                Log.d(TAG, "Launched Vivo auto-start manager: ${intent.component}")
                return true
            }
        }
        
        Log.w(TAG, "Could not find Vivo auto-start manager")
        return false
    }
    
    private fun tryAllowBackgroundPower(): Boolean {
        val intents = listOf(
            // Background Power Consumption Management
            Intent().apply {
                component = ComponentName(
                    "com.vivo.abe",
                    "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"
                )
            },
            // Battery optimization screen
            Intent().apply {
                component = ComponentName(
                    "com.iqoo.powersaving",
                    "com.iqoo.powersaving.PowerSavingManagerActivity"
                )
            }
        )
        
        for (intent in intents) {
            if (tryLaunchActivity(intent)) {
                Log.d(TAG, "Launched Vivo background power settings: ${intent.component}")
                return true
            }
        }
        
        return false
    }
    
    private fun tryDisableMemoryCleanup() {
        // Try to open memory cleanup whitelist
        val intent = Intent().apply {
            component = ComponentName(
                "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
            )
        }
        tryLaunchActivity(intent)
    }
    
    private fun tryKeepAlive() {
        // Use Device Owner to set keep-alive through global settings
        try {
            // Disable adaptive battery (if supported)
            writeGlobalSetting("adaptive_battery_management_enabled", "0")
        } catch (e: Exception) {
            Log.w(TAG, "Could not disable adaptive battery", e)
        }
    }
}
