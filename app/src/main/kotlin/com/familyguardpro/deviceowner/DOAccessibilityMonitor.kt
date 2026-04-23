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
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // Recovery cooldown to prevent rapid re-enables (500ms - fast recovery)
    private var lastRecoveryTime = 0L
    private const val RECOVERY_COOLDOWN_MS = 500L
    
    // Max recovery attempts per hour (generous limit for aggressive protection)
    private var recoveryCountThisHour = 0
    private var hourStartTime = 0L
    private const val MAX_RECOVERIES_PER_HOUR = 200
    
    // Retry configuration for failed recoveries
    private const val MAX_RETRY_ATTEMPTS = 10
    private const val RETRY_DELAY_MS = 300L
    
    // Periodic re-lock interval (every 30 seconds, re-assert accessibility settings)
    private var periodicLockJob: Job? = null
    private const val PERIODIC_LOCK_INTERVAL_MS = 30_000L

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
        
        // Ensure scope is active (fix: recreate if previously cancelled)
        if (!scope.isActive) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
        
        lastKnownState = isAccessibilityEnabled(context)
        
        // Initial check: force-enable and lock if not already enabled
        if (!lastKnownState) {
            Log.w(TAG, "Accessibility disabled on startup - attempting recovery + lock")
            attemptRecovery(context)
        }
        
        // CRITICAL: Lock accessibility settings on startup to prevent disabling
        doManager.lockAccessibilitySettings()
        
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
        
        // Start periodic re-lock job: every 30 seconds, re-assert accessibility is enabled and locked.
        // This catches cases where the OEM battery manager silently removes the service
        // without triggering the ContentObserver.
        periodicLockJob = scope.launch {
            while (isActive) {
                delay(PERIODIC_LOCK_INTERVAL_MS)
                try {
                    if (!isAccessibilityEnabled(context)) {
                        Log.w(TAG, "⚠️ Periodic check: Accessibility disabled! Force re-enabling...")
                        attemptRecovery(context)
                    }
                    // Re-lock settings every cycle to prevent OEM from loosening the restriction
                    doManager.lockAccessibilitySettings()
                } catch (e: Exception) {
                    Log.e(TAG, "Periodic lock check error", e)
                }
            }
        }
        
        isMonitoring = true
        Log.d(TAG, "DO Accessibility monitoring started (with periodic lock)")
    }

    /**
     * Stop monitoring
     */
    fun stopMonitoring(context: Context) {
        contentObserver?.let {
            context.contentResolver.unregisterContentObserver(it)
        }
        contentObserver = null
        periodicLockJob?.cancel()
        periodicLockJob = null
        isMonitoring = false
        // Don't cancel scope — it will be reused on next startMonitoring()
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
            
            // ALWAYS auto-recover in Device Owner mode — don't rely on preference
            // Immediate recovery (no delay) — every millisecond matters
            if (!scope.isActive) {
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            }
            scope.launch {
                // Immediate first attempt
                if (!isAccessibilityEnabled(context)) {
                    attemptRecoveryWithRetry(context)
                }
            }
            
            // Also re-lock settings to prevent further changes
            try {
                doManager.lockAccessibilitySettings()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to re-lock accessibility settings", e)
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
     * Force an immediate accessibility check and recovery if needed.
     * Also re-locks settings to prevent future disabling.
     */
    fun checkAndRecoverNow(context: Context) {
        val doManager = DeviceOwnerManager.getInstance(context)
        if (!doManager.isDeviceOwner()) return
        
        if (!isAccessibilityEnabled(context)) {
            attemptRecovery(context)
        }
        // Always re-lock settings regardless
        doManager.lockAccessibilitySettings()
    }
}
