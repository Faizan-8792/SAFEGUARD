package com.familyguardpro.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Utility class for detecting device manufacturers and handling
 * manufacturer-specific settings for background operation.
 * 
 * Critical for MIUI/Xiaomi/Poco/Redmi devices which have aggressive
 * background killing that standard Android battery optimization doesn't cover.
 */
object DeviceUtils {
    
    private const val TAG = "DeviceUtils"
    
    // ==================== MANUFACTURER DETECTION ====================
    
    /**
     * Check if device is running MIUI (Xiaomi/Poco/Redmi)
     */
    fun isMiui(): Boolean {
        return !getSystemProperty("ro.miui.ui.version.name").isNullOrEmpty()
    }
    
    /**
     * Get MIUI version number (e.g., 14 for MIUI 14)
     */
    fun getMiuiVersion(): Int {
        val version = getSystemProperty("ro.miui.ui.version.name") ?: return 0
        return try {
            if (version.startsWith("V")) {
                version.substring(1).toIntOrNull() ?: 0
            } else {
                version.filter { it.isDigit() }.take(2).toIntOrNull() ?: 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse MIUI version: $version", e)
            0
        }
    }
    
    /**
     * Check if device is running HyperOS (Xiaomi's new OS replacing MIUI)
     */
    fun isHyperOS(): Boolean {
        return !getSystemProperty("ro.mi.os.version.name").isNullOrEmpty()
    }
    
    /**
     * Check if device is Samsung
     */
    fun isSamsung(): Boolean {
        return Build.MANUFACTURER.equals("samsung", ignoreCase = true)
    }
    
    /**
     * Check if device is Huawei or Honor
     */
    fun isHuawei(): Boolean {
        return Build.MANUFACTURER.equals("huawei", ignoreCase = true) ||
               Build.MANUFACTURER.equals("honor", ignoreCase = true)
    }
    
    /**
     * Check if device is Oppo or Realme (both use ColorOS)
     */
    fun isOppo(): Boolean {
        return Build.MANUFACTURER.equals("oppo", ignoreCase = true) ||
               Build.MANUFACTURER.equals("realme", ignoreCase = true)
    }
    
    /**
     * Check if device is Vivo
     */
    fun isVivo(): Boolean {
        return Build.MANUFACTURER.equals("vivo", ignoreCase = true)
    }
    
    /**
     * Check if device is OnePlus
     */
    fun isOnePlus(): Boolean {
        return Build.MANUFACTURER.equals("oneplus", ignoreCase = true)
    }
    
    /**
     * Check if device needs special background handling
     */
    fun needsSpecialBackgroundHandling(): Boolean {
        return isMiui() || isSamsung() || isHuawei() || isOppo() || isVivo() || isOnePlus()
    }
    
    /**
     * Get human-readable manufacturer/OS name
     */
    fun getManufacturerName(): String {
        return when {
            isHyperOS() -> "HyperOS"
            isMiui() -> "MIUI ${getMiuiVersion()}"
            isSamsung() -> "Samsung"
            isHuawei() -> "Huawei/Honor"
            isOppo() -> "Oppo/Realme"
            isVivo() -> "Vivo"
            isOnePlus() -> "OnePlus"
            else -> Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        }
    }
    
    /**
     * Get device model with manufacturer
     */
    fun getDeviceInfo(): String {
        return "${getManufacturerName()} ${Build.MODEL}"
    }
    
    // ==================== SYSTEM PROPERTY ACCESS ====================
    
    /**
     * Get Android system property using multiple methods
     */
    private fun getSystemProperty(key: String): String? {
        // Method 1: Runtime exec
        try {
            val process = Runtime.getRuntime().exec("getprop $key")
            val result = process.inputStream.bufferedReader().use { it.readText().trim() }
            if (result.isNotEmpty()) {
                return result
            }
        } catch (e: Exception) {
            Log.d(TAG, "getprop failed for $key: ${e.message}")
        }
        
        // Method 2: Reflection
        try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java)
            val result = method.invoke(null, key) as? String
            if (!result.isNullOrEmpty()) {
                return result
            }
        } catch (e: Exception) {
            Log.d(TAG, "Reflection failed for $key: ${e.message}")
        }
        
        return null
    }
    
    // ==================== MIUI INTENTS ====================
    
    /**
     * Open MIUI Autostart settings
     * CRITICAL: Without autostart, app will be killed on reboot
     */
    fun openMiuiAutoStart(context: Context): Boolean {
        val intents = listOf(
            // MIUI 12+ Primary
            Intent().apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            },
            // MIUI Alternative
            Intent().apply {
                action = "miui.intent.action.OP_AUTO_START"
                addCategory(Intent.CATEGORY_DEFAULT)
            },
            // MIUI Permission Editor
            Intent().apply {
                action = "miui.intent.action.APP_PERM_EDITOR"
                setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity"
                )
                putExtra("extra_pkgname", context.packageName)
            },
            // Security Center Home (fallback)
            context.packageManager.getLaunchIntentForPackage("com.miui.securitycenter")
        )
        
        return tryIntents(context, intents)
    }
    
    /**
     * Open MIUI Battery Saver settings for the app
     */
    fun openMiuiBatterySettings(context: Context): Boolean {
        val intents = listOf(
            // MIUI App Battery Settings
            Intent().apply {
                action = "miui.intent.action.APP_PERM_EDITOR"
                setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity"
                )
                putExtra("extra_pkgname", context.packageName)
            },
            // Power Manager (MIUI 14+)
            Intent().apply {
                component = ComponentName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsContainerManagementActivity"
                )
            },
            // General App Info (fallback)
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
        )
        
        return tryIntents(context, intents)
    }
    
    /**
     * Open MIUI Background Autostart settings (MIUI 14+)
     */
    fun openMiuiBackgroundAutoStart(context: Context): Boolean {
        val intents = listOf(
            Intent().apply {
                action = "miui.intent.action.APP_PERM_EDITOR"
                setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity"
                )
                putExtra("extra_pkgname", context.packageName)
            }
        )
        
        return tryIntents(context, intents)
    }
    
    /**
     * Open System Battery Saver settings
     */
    fun openSystemBatterySaver(context: Context): Boolean {
        return try {
            context.startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            true
        } catch (e: Exception) {
            try {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                true
            } catch (e2: Exception) {
                false
            }
        }
    }
    
    /**
     * Open Developer Options (for MIUI Optimization toggle)
     */
    fun openDeveloperOptions(context: Context): Boolean {
        return try {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            true
        } catch (e: Exception) {
            false
        }
    }
    
    // ==================== SAMSUNG INTENTS ====================
    
    /**
     * Open Samsung Device Care / Battery settings
     */
    fun openSamsungBatterySettings(context: Context): Boolean {
        val intents = listOf(
            // Samsung Device Care
            Intent().apply {
                component = ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity"
                )
            },
            // Samsung Smart Manager
            Intent().apply {
                component = ComponentName(
                    "com.samsung.android.sm",
                    "com.samsung.android.sm.ui.battery.BatteryActivity"
                )
            },
            // Fallback to app details
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
        )
        
        return tryIntents(context, intents)
    }
    
    // ==================== HUAWEI INTENTS ====================
    
    /**
     * Open Huawei Protected Apps / Startup Manager
     */
    fun openHuaweiProtectedApps(context: Context): Boolean {
        val intents = listOf(
            // Huawei Startup Manager
            Intent().apply {
                component = ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
            },
            // Huawei Protected Apps (older)
            Intent().apply {
                component = ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity"
                )
            },
            // Honor Manager
            Intent().apply {
                component = ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"
                )
            }
        )
        
        return tryIntents(context, intents)
    }
    
    /**
     * Open Huawei App Launch settings
     */
    fun openHuaweiAppLaunch(context: Context): Boolean {
        return try {
            val intent = Intent().apply {
                action = "huawei.intent.action.HSM_BOOTAPP_MANAGER"
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            openHuaweiProtectedApps(context)
        }
    }
    
    // ==================== OPPO/REALME INTENTS ====================
    
    /**
     * Open Oppo/Realme AutoStart settings
     */
    fun openOppoAutoStart(context: Context): Boolean {
        val intents = listOf(
            // ColorOS Auto Start
            Intent().apply {
                component = ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                )
            },
            // Oppo Safe Center
            Intent().apply {
                component = ComponentName(
                    "com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity"
                )
            },
            // Realme specific
            Intent().apply {
                component = ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity"
                )
            }
        )
        
        return tryIntents(context, intents)
    }
    
    /**
     * Open Oppo/Realme Battery optimization
     */
    fun openOppoBatteryOptimization(context: Context): Boolean {
        val intents = listOf(
            Intent().apply {
                component = ComponentName(
                    "com.coloros.oppoguardelf",
                    "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"
                )
            }
        )
        
        return tryIntents(context, intents)
    }
    
    // ==================== VIVO INTENTS ====================
    
    /**
     * Open Vivo AutoStart settings
     */
    fun openVivoAutoStart(context: Context): Boolean {
        val intents = listOf(
            // Vivo Permission Manager
            Intent().apply {
                component = ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                )
            },
            // Vivo i Manager
            Intent().apply {
                component = ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
                )
            }
        )
        
        return tryIntents(context, intents)
    }
    
    // ==================== ONEPLUS INTENTS ====================
    
    /**
     * Open OnePlus Auto Launch settings
     */
    fun openOnePlusAutoLaunch(context: Context): Boolean {
        val intents = listOf(
            Intent().apply {
                component = ComponentName(
                    "com.oneplus.security",
                    "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
                )
            }
        )
        
        return tryIntents(context, intents)
    }
    
    // ==================== GENERIC INTENTS ====================
    
    /**
     * Open manufacturer-specific autostart settings based on device
     */
    fun openAutoStartSettings(context: Context): Boolean {
        return when {
            isMiui() -> openMiuiAutoStart(context)
            isSamsung() -> openSamsungBatterySettings(context)
            isHuawei() -> openHuaweiProtectedApps(context)
            isOppo() -> openOppoAutoStart(context)
            isVivo() -> openVivoAutoStart(context)
            isOnePlus() -> openOnePlusAutoLaunch(context)
            else -> {
                // Generic fallback - open app details
                try {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                    true
                } catch (e: Exception) {
                    false
                }
            }
        }
    }
    
    /**
     * Open manufacturer-specific screen share protection settings
     * This is needed on some devices (especially Vivo) to disable screen recording protection
     * which causes black screen when mirroring protected apps like Gallery/WhatsApp
     */
    fun openScreenShareProtectionSettings(context: Context): Boolean {
        return when {
            isVivo() -> openVivoScreenShareProtection(context)
            isOppo() -> openOppoScreenShareProtection(context)
            isMiui() -> openMiuiScreenShareProtection(context)
            isSamsung() -> openSamsungScreenShareProtection(context)
            isHuawei() -> openHuaweiScreenShareProtection(context)
            isOnePlus() -> openOnePlusScreenShareProtection(context)
            else -> {
                // Generic fallback - open security settings
                try {
                    context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                    true
                } catch (e: Exception) {
                    false
                }
            }
        }
    }
    
    /**
     * Vivo screen share protection settings
     */
    private fun openVivoScreenShareProtection(context: Context): Boolean {
        val intents = listOf(
            // Vivo i Manager Privacy settings
            Intent().apply {
                component = ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.PurviewTabActivity"
                )
            },
            // Vivo Security - Privacy Protection
            Intent().apply {
                component = ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.FloatWindowManager"
                )
            },
            // Vivo Settings Privacy
            Intent().apply {
                component = ComponentName(
                    "com.android.settings",
                    "com.android.settings.Settings\$VivoPrivacySettingsActivity"
                )
            },
            // Fallback: Open app-specific privacy settings
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
        )
        return tryIntents(context, intents)
    }
    
    /**
     * OPPO/Realme screen share protection settings
     */
    private fun openOppoScreenShareProtection(context: Context): Boolean {
        val intents = listOf(
            // OPPO Privacy Permission
            Intent().apply {
                component = ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.PermissionManagerActivity"
                )
            },
            // Fallback to app details
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
        )
        return tryIntents(context, intents)
    }
    
    /**
     * MIUI screen share protection settings
     */
    private fun openMiuiScreenShareProtection(context: Context): Boolean {
        val intents = listOf(
            // MIUI Privacy settings
            Intent().apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.MainAc498888888Activity"
                )
            },
            // Fallback to app permissions
            Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                putExtra("extra_pkgname", context.packageName)
            },
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
        )
        return tryIntents(context, intents)
    }
    
    /**
     * Samsung screen share protection settings
     */
    private fun openSamsungScreenShareProtection(context: Context): Boolean {
        val intents = listOf(
            // Samsung Privacy settings
            Intent().apply {
                component = ComponentName(
                    "com.samsung.android.app.privacy.dashboard",
                    "com.samsung.android.app.privacy.dashboard.PrivacyDashboardActivity"
                )
            },
            // Fallback to app details
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
        )
        return tryIntents(context, intents)
    }
    
    /**
     * Huawei screen share protection settings
     */
    private fun openHuaweiScreenShareProtection(context: Context): Boolean {
        val intents = listOf(
            // Huawei Privacy settings
            Intent().apply {
                component = ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.permissionmanager.ui.MainActivity"
                )
            },
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
        )
        return tryIntents(context, intents)
    }
    
    /**
     * OnePlus screen share protection settings
     */
    private fun openOnePlusScreenShareProtection(context: Context): Boolean {
        val intents = listOf(
            // OnePlus Privacy settings
            Intent().apply {
                component = ComponentName(
                    "com.oneplus.security",
                    "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
                )
            },
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
        )
        return tryIntents(context, intents)
    }
    
    /**
     * Open manufacturer-specific battery settings based on device
     */
    fun openBatterySettings(context: Context): Boolean {
        return when {
            isMiui() -> openMiuiBatterySettings(context)
            isSamsung() -> openSamsungBatterySettings(context)
            isHuawei() -> openHuaweiAppLaunch(context)
            isOppo() -> openOppoBatteryOptimization(context)
            else -> {
                // Standard Android battery optimization
                try {
                    context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                    true
                } catch (e: Exception) {
                    try {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        })
                        true
                    } catch (e2: Exception) {
                        false
                    }
                }
            }
        }
    }
    
    // ==================== HELPER METHODS ====================
    
    /**
     * Try multiple intents until one works
     */
    private fun tryIntents(context: Context, intents: List<Intent?>): Boolean {
        for (intent in intents) {
            if (intent == null) continue
            try {
                intent.flags = intent.flags or Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                Log.d(TAG, "Successfully opened: ${intent.component ?: intent.action}")
                return true
            } catch (e: Exception) {
                Log.d(TAG, "Failed to open: ${intent.component ?: intent.action}")
                continue
            }
        }
        return false
    }
    
    // ==================== RESTRICTED SETTINGS ====================
    
    /**
     * Check if device needs "Allow restricted settings" permission.
     * Required on Android 13+ (API 33) for accessibility and notification listener services
     * that are installed from sources other than app stores (e.g., sideloaded APKs).
     */
    fun needsRestrictedSettings(): Boolean {
        // Restricted settings check only applies to Android 13+ (API 33)
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }
    
    /**
     * Open the app info settings page where users can enable "Allow restricted settings".
     * On Android 13+, this is found in App Info > More options (3 dots) > Allow restricted settings
     */
    fun openRestrictedSettings(context: Context): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open restricted settings", e)
            false
        }
    }
    
    /**
     * Log device information for debugging
     */
    fun logDeviceInfo() {
        Log.i(TAG, "=== DEVICE INFO ===")
        Log.i(TAG, "Manufacturer: ${Build.MANUFACTURER}")
        Log.i(TAG, "Model: ${Build.MODEL}")
        Log.i(TAG, "Brand: ${Build.BRAND}")
        Log.i(TAG, "Device: ${Build.DEVICE}")
        Log.i(TAG, "Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        Log.i(TAG, "Is MIUI: ${isMiui()}")
        if (isMiui()) {
            Log.i(TAG, "MIUI Version: ${getMiuiVersion()}")
            Log.i(TAG, "Is HyperOS: ${isHyperOS()}")
        }
        Log.i(TAG, "Is Samsung: ${isSamsung()}")
        Log.i(TAG, "Is Huawei: ${isHuawei()}")
        Log.i(TAG, "Is Oppo: ${isOppo()}")
        Log.i(TAG, "Is Vivo: ${isVivo()}")
        Log.i(TAG, "Is OnePlus: ${isOnePlus()}")
        Log.i(TAG, "Needs Special Handling: ${needsSpecialBackgroundHandling()}")
        Log.i(TAG, "Needs Restricted Settings: ${needsRestrictedSettings()}")
        Log.i(TAG, "==================")
    }
}
