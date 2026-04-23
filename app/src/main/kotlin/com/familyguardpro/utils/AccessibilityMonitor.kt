package com.familyguardpro.utils

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.familyguardpro.services.WebSocketSyncService
import org.json.JSONObject

/**
 * Monitors Accessibility Service status and sends alerts to parent dashboard.
 * 
 * Key features:
 * 1. ContentObserver watches for accessibility setting changes
 * 2. Sends alert to parent via WebSocket when accessibility is disabled
 * 3. Does NOT show notification on child device (keeps child view clean)
 */
object AccessibilityMonitor {
    private const val TAG = "AccessibilityMonitor"
    
    private var contentObserver: ContentObserver? = null
    private var isMonitoring = false
    private var lastKnownState = false
    
    /**
     * Start monitoring accessibility service status
     * Call this from PersistentService.onCreate()
     */
    fun startMonitoring(context: Context) {
        if (isMonitoring) return
        
        // Check initial state
        lastKnownState = isAccessibilityEnabled(context)
        Log.d(TAG, "Starting monitoring. Current state: enabled=$lastKnownState")
        
        // If already disabled, send alert to parent
        if (!lastKnownState) {
            sendAccessibilityAlertToParent(false)
        }
        
        // Register content observer to detect changes
        contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                val newState = isAccessibilityEnabled(context)
                Log.d(TAG, "Accessibility setting changed: enabled=$newState (was=$lastKnownState)")
                
                if (!newState && lastKnownState) {
                    // Accessibility was just DISABLED
                    Log.e(TAG, "ACCESSIBILITY WAS DISABLED! Sending alert to parent...")
                    sendAccessibilityAlertToParent(false)
                } else if (newState && !lastKnownState) {
                    // Accessibility was just ENABLED
                    Log.d(TAG, "Accessibility re-enabled. Notifying parent...")
                    sendAccessibilityAlertToParent(true)
                }
                
                lastKnownState = newState
            }
        }
        
        try {
            context.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false,
                contentObserver!!
            )
            
            // Also watch the main accessibility toggle
            context.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED),
                false,
                contentObserver!!
            )
            
            isMonitoring = true
            Log.d(TAG, "ContentObserver registered for accessibility settings")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register ContentObserver", e)
        }
    }
    
    /**
     * Stop monitoring (call from PersistentService.onDestroy())
     */
    fun stopMonitoring(context: Context) {
        contentObserver?.let {
            try {
                context.contentResolver.unregisterContentObserver(it)
                Log.d(TAG, "ContentObserver unregistered")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering ContentObserver", e)
            }
        }
        contentObserver = null
        isMonitoring = false
    }
    
    /**
     * Check if our accessibility service is currently enabled
     */
    fun isAccessibilityEnabled(context: Context): Boolean {
        return try {
            val accessibilityEnabled = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0
            )
            
            if (accessibilityEnabled != 1) return false
            
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            
            enabledServices.contains(context.packageName)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking accessibility", e)
            false
        }
    }
    
    /**
     * Send accessibility alert to parent dashboard via WebSocket
     * No notification shown on child device - keeps child UI clean
     */
    private fun sendAccessibilityAlertToParent(isEnabled: Boolean) {
        try {
            val data = JSONObject().apply {
                put("accessibility_enabled", isEnabled)
                put("timestamp", System.currentTimeMillis())
            }
            
            if (isEnabled) {
                // Send recovery message
                WebSocketSyncService.sendMessage("accessibility_restored", data)
                Log.d(TAG, "Sent accessibility_restored alert to parent")
            } else {
                // Send critical alert
                WebSocketSyncService.sendMessage("child_alert", JSONObject().apply {
                    put("alert_type", "accessibility_disabled")
                    put("message", "Accessibility service was disabled on child device")
                    put("health", data)
                })
                Log.d(TAG, "Sent accessibility_disabled alert to parent")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send accessibility alert", e)
        }
    }
    
    /**
     * Force check accessibility status and send to parent if needed
     * Call this periodically from cross-monitor
     */
    fun checkAndNotify(context: Context) {
        val enabled = isAccessibilityEnabled(context)
        if (!enabled) {
            sendAccessibilityAlertToParent(false)
        }
    }
}
