package com.familyguardpro.deviceowner

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.familyguardpro.services.WebSocketSyncService
import kotlinx.coroutines.*
import org.json.JSONObject

/**
 * DOAccessibilityMonitor - Enhanced accessibility monitoring for Device Owner mode.
 * 
 * Unlike the standard AccessibilityMonitor which only DETECTS when accessibility
 * is disabled and alerts the parent, this DO-enhanced version can AUTOMATICALLY
 * RE-ENABLE the accessibility service using Device Owner privileges.
 * 
 * Flow:
 * 1. ContentObserver detects accessibility setting change
 * 2. If accessibility was disabled and auto-recover is enabled:
 *    a. Wait 2 seconds (in case user is in settings)
 *    b. Use DeviceOwnerManager.forceEnableAccessibility()
 *    c. Send notification to parent about the recovery
 * 3. If auto-recover is disabled, fall back to parent alert (like standard monitor)
 */
object DOAccessibilityMonitor {
    private const val TAG = "DOAccessibilityMonitor"
    
    private var contentObserver: ContentObserver? = null
    private var isMonitoring = false
    private var lastKnownState = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // Recovery cooldown to prevent rapid re-enables (1 second - fast recovery)
    private var lastRecoveryTime = 0L
    private const val RECOVERY_COOLDOWN_MS = 1000L
    
    // Max recovery attempts per hour (generous limit for aggressive protection)
    private var recoveryCountThisHour = 0
    private var hourStartTime = 0L
    private const val MAX_RECOVERIES_PER_HOUR = 100
    
    // Retry configuration for failed recoveries
    private const val MAX_RETRY_ATTEMPTS = 5
    private const val RETRY_DELAY_MS = 500L

    /**
     * Start monitoring accessibility state with DO auto-recovery capability.
     * Should be called from PersistentService or FamilyGuardAccessibilityService.
     */
    fun startMonitoring(context: Context) {
        if (isMonitoring) return
        
        val doManager = DeviceOwnerManager.getInstance(context)
        if (!doManager.isDeviceOwner()) {
            Log.d(TAG, "Not device owner - skipping DO accessibility monitor")
            return
        }
        
        lastKnownState = isAccessibilityEnabled(context)
        
        // Initial check
        if (!lastKnownState && doManager.isAccessibilityAutoRecoverEnabled()) {
            Log.w(TAG, "Accessibility disabled on startup - attempting recovery")
            attemptRecovery(context)
        }
        
        contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                handleAccessibilityChange(context)
            }
        }
        
        // Register observers for accessibility settings
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            false,
            contentObserver!!
        )
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED),
            false,
            contentObserver!!
        )
        
        isMonitoring = true
        Log.d(TAG, "DO Accessibility monitoring started")
    }

    /**
     * Stop monitoring
     */
    fun stopMonitoring(context: Context) {
        contentObserver?.let {
            context.contentResolver.unregisterContentObserver(it)
        }
        contentObserver = null
        isMonitoring = false
        scope.cancel()
        Log.d(TAG, "DO Accessibility monitoring stopped")
    }

    /**
     * Handle accessibility setting change
     */
    private fun handleAccessibilityChange(context: Context) {
        val newState = isAccessibilityEnabled(context)
        
        if (!newState && lastKnownState) {
            // Accessibility was DISABLED
            Log.w(TAG, "⚠️ Accessibility service was DISABLED!")
            
            val doManager = DeviceOwnerManager.getInstance(context)
            
            if (doManager.isAccessibilityAutoRecoverEnabled()) {
                // Auto-recover using DO privileges - fast recovery (300ms)
                scope.launch {
                    delay(300)
                    
                    // Re-check (maybe it was re-enabled manually)
                    if (!isAccessibilityEnabled(context)) {
                        attemptRecoveryWithRetry(context)
                    }
                }
            } else {
                // No auto-recover - just alert parent
                sendAlertToParent(false, recovered = false)
            }
        } else if (newState && !lastKnownState) {
            // Accessibility was RE-ENABLED (maybe by user or recovery)
            Log.d(TAG, "✅ Accessibility service re-enabled")
            sendAlertToParent(true, recovered = false)
        }
        
        lastKnownState = newState
    }

    /**
     * Attempt recovery with automatic retry on failure
     */
    private fun attemptRecoveryWithRetry(context: Context) {
        scope.launch {
            for (attempt in 1..MAX_RETRY_ATTEMPTS) {
                if (isAccessibilityEnabled(context)) {
                    Log.d(TAG, "✅ Accessibility already re-enabled before retry #$attempt")
                    lastKnownState = true
                    sendAlertToParent(true, recovered = true)
                    return@launch
                }
                
                Log.d(TAG, "Recovery attempt #$attempt of $MAX_RETRY_ATTEMPTS")
                attemptRecovery(context)
                
                // Wait and check if it worked
                delay(RETRY_DELAY_MS)
                
                if (isAccessibilityEnabled(context)) {
                    Log.d(TAG, "✅ Recovery successful on attempt #$attempt")
                    lastKnownState = true
                    return@launch
                }
            }
            
            // All retries failed - schedule a delayed re-check
            Log.e(TAG, "All $MAX_RETRY_ATTEMPTS recovery attempts failed, scheduling delayed retry")
            delay(5000)
            if (!isAccessibilityEnabled(context)) {
                attemptRecovery(context)
            }
        }
    }

    /**
     * Attempt to recover (re-enable) accessibility service
     */
    private fun attemptRecovery(context: Context) {
        val now = System.currentTimeMillis()
        
        // Check cooldown
        if (now - lastRecoveryTime < RECOVERY_COOLDOWN_MS) {
            Log.w(TAG, "Recovery cooldown active - skipping")
            return
        }
        
        // Check hourly limit
        if (now - hourStartTime > 3600000L) {
            // Reset hourly counter
            recoveryCountThisHour = 0
            hourStartTime = now
        }
        
        if (recoveryCountThisHour >= MAX_RECOVERIES_PER_HOUR) {
            Log.e(TAG, "Max recovery attempts per hour reached ($MAX_RECOVERIES_PER_HOUR)")
            sendAlertToParent(false, recovered = false, 
                message = "Accessibility keeps being disabled. Max auto-recovery attempts reached.")
            return
        }
        
        // Attempt recovery
        val doManager = DeviceOwnerManager.getInstance(context)
        val success = doManager.forceEnableAccessibility()
        
        lastRecoveryTime = now
        recoveryCountThisHour++
        
        if (success) {
            Log.d(TAG, "✅ Accessibility auto-recovered (attempt #$recoveryCountThisHour)")
            lastKnownState = true
            sendAlertToParent(true, recovered = true)
        } else {
            Log.e(TAG, "❌ Accessibility recovery FAILED")
            sendAlertToParent(false, recovered = false, 
                message = "Auto-recovery failed. Manual intervention may be needed.")
        }
    }

    /**
     * Check if accessibility service is enabled
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
            Log.e(TAG, "Error checking accessibility state", e)
            false
        }
    }

    /**
     * Send alert to parent via WebSocket
     */
    private fun sendAlertToParent(
        isEnabled: Boolean, 
        recovered: Boolean,
        message: String? = null
    ) {
        try {
            if (isEnabled) {
                val data = JSONObject().apply {
                    put("type", if (recovered) "accessibility_auto_recovered" else "accessibility_restored")
                    put("timestamp", System.currentTimeMillis())
                    put("device_owner_mode", true)
                    if (recovered) {
                        put("recovery_count", recoveryCountThisHour)
                    }
                }
                WebSocketSyncService.sendMessage("accessibility_restored", data)
            } else {
                val data = JSONObject().apply {
                    put("type", "child_alert")
                    put("alert_type", "accessibility_disabled")
                    put("device_owner_mode", true)
                    put("auto_recover_enabled", true)
                    if (message != null) put("message", message)
                    put("timestamp", System.currentTimeMillis())
                }
                WebSocketSyncService.sendMessage("child_alert", data)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending accessibility alert", e)
        }
    }

    /**
     * Force an immediate accessibility check and recovery if needed
     */
    fun checkAndRecoverNow(context: Context) {
        if (!isAccessibilityEnabled(context)) {
            val doManager = DeviceOwnerManager.getInstance(context)
            if (doManager.isDeviceOwner()) {
                attemptRecovery(context)
            }
        }
    }
}
