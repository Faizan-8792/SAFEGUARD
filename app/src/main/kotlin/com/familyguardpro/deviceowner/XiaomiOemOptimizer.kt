package com.familyguardpro.deviceowner

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Xiaomi/MIUI/HyperOS OEM Optimizer
 * 
 * Xiaomi/Poco/Redmi devices running MIUI or HyperOS are particularly aggressive
 * with background task management:
 * - AutoStart permission (MIUI-specific, not standard Android)
 * - Battery Saver per-app whitelist
 * - Task cleanup on lock screen
 * - MIUI Optimization toggle
 * - DuraSpeed/Performance optimization
 */
class XiaomiOemOptimizer(context: Context) : BaseOemOptimizer(context) {
    
    override fun getManufacturer() = "Xiaomi"
    
    override suspend fun optimize() {
        Log.d(TAG, "=== Starting Xiaomi/MIUI optimization ===")
        
        var autoStartEnabled = false
        var batteryOptDisabled = false
        var backgroundRunAllowed = false
        
        // 1. Standard battery optimization
        batteryOptDisabled = disableBatteryOptimization()
        
        // 2. MIUI AutoStart
        autoStartEnabled = tryEnableAutoStart()
        
        // 3. Battery Saver whitelist
        backgroundRunAllowed = tryBatterySaverWhitelist()
        
        // 4. Lock screen cleanup exemption
        tryDisableLockScreenCleanup()
        
        // 5. Disable MIUI battery optimization
        tryDisableMiuiBatteryOpt()
        
        // 6. Global settings tweaks via DPM
        applyDpmSettings()
        
        reportStatus(autoStartEnabled, batteryOptDisabled, backgroundRunAllowed)
        Log.d(TAG, "=== Xiaomi optimization complete ===")
    }
    
    private fun tryEnableAutoStart(): Boolean {
        val intents = listOf(
            // MIUI AutoStart manager
            Intent().apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            },
            // Alternative MIUI security center path
            Intent().apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity"
                )
            },
            // Xiaomi HyperOS
            Intent().apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartDetailManagementActivity"
                )
            }
        )
        
        for (intent in intents) {
            if (tryLaunchActivity(intent)) {
                Log.d(TAG, "Launched MIUI auto-start: ${intent.component}")
                return true
            }
        }
        
        Log.w(TAG, "Could not find MIUI auto-start manager")
        return false
    }
    
    private fun tryBatterySaverWhitelist(): Boolean {
        val intents = listOf(
            // MIUI Battery Saver
            Intent().apply {
                component = ComponentName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsContainerManagementActivity"
                )
            },
            // Alternative
            Intent().apply {
                component = ComponentName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                )
            },
            // MIUI 14+
            Intent().apply {
                component = ComponentName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.PowerDetailModePickerActivity"
                )
            }
        )
        
        for (intent in intents) {
            if (tryLaunchActivity(intent)) {
                Log.d(TAG, "Launched MIUI battery saver whitelist")
                return true
            }
        }
        
        return false
    }
    
    private fun tryDisableLockScreenCleanup() {
        // Lock screen task cleanup
        val intent = Intent().apply {
            component = ComponentName(
                "com.miui.securitycenter",
                "com.miui.securitycenter.ui.taskmanagement.TaskManagementActivity"
            )
        }
        
        if (!tryLaunchActivity(intent)) {
            // Try recents settings
            val altIntent = Intent().apply {
                component = ComponentName(
                    "com.miui.home",
                    "com.miui.home.recents.settings.RecentsSettings"
                )
            }
            tryLaunchActivity(altIntent)
        }
    }
    
    private fun tryDisableMiuiBatteryOpt() {
        try {
            // Try to set app battery mode to "No restrictions" using MIUI-specific settings
            val intent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                putExtra("extra_pkgname", context.packageName)
            }
            tryLaunchActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not set MIUI battery mode", e)
        }
    }
    
    private fun applyDpmSettings() {
        try {
            if (!dpm.isDeviceOwnerApp(context.packageName)) return
            
            // Disable adaptive battery
            writeGlobalSetting("adaptive_battery_management_enabled", "0")
            
            // Disable WiFi sleep
            writeGlobalSetting("wifi_sleep_policy", "2") // WIFI_SLEEP_POLICY_NEVER
            
            // Keep WiFi during sleep
            writeGlobalSetting("wifi_on_during_sleep", "1")
            
            Log.d(TAG, "DPM settings applied for Xiaomi")
        } catch (e: Exception) {
            Log.e(TAG, "Error applying DPM settings", e)
        }
    }
}
