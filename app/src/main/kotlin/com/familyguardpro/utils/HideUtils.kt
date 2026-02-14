package com.familyguardpro.utils

import android.content.Context
import android.util.Log
import com.familyguardpro.AppDisguiseManager

/**
 * DEPRECATED: Use AppDisguiseManager instead
 * This is a compatibility wrapper that delegates to AppDisguiseManager
 */
object HideUtils {
    
    private const val TAG = "HideUtils"
    
    /**
     * Hide the app icon from launcher completely
     * Delegates to AppDisguiseManager.switchToInvisibleMode()
     */
    fun hideApp(context: Context) {
        Log.d(TAG, "hideApp() called - delegating to AppDisguiseManager.switchToInvisibleMode()")
        AppDisguiseManager.switchToInvisibleMode(context)
    }
    
    /**
     * Show the app icon in launcher as FamilyGuard Pro
     * Delegates to AppDisguiseManager.switchToNormalMode()
     */
    fun showApp(context: Context) {
        Log.d(TAG, "showApp() called - delegating to AppDisguiseManager.switchToNormalMode()")
        AppDisguiseManager.switchToNormalMode(context)
    }
    
    /**
     * Check if app is currently hidden (invisible mode)
     */
    fun isHidden(context: Context): Boolean {
        return AppDisguiseManager.isInvisible(context)
    }
    
    /**
     * Toggle app visibility
     */
    fun toggleVisibility(context: Context) {
        if (isHidden(context)) {
            showApp(context)
        } else {
            hideApp(context)
        }
    }
}
