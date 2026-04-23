package com.familyguardpro.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.familyguardpro.FamilyGuardApp
import com.familyguardpro.utils.DeviceUtils
import com.familyguardpro.utils.FcmTokenManager

/**
 * Boot Receiver with priority 1000 (highest)
 * 
 * Starts all services and the triple-redundancy watchdog system on boot.
 * Handles MIUI/manufacturer-specific boot scenarios.
 */
class BootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BootReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        val receivedAction = intent.action
        
        if (receivedAction == Intent.ACTION_BOOT_COMPLETED ||
            receivedAction == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            receivedAction == "android.intent.action.QUICKBOOT_POWERON" ||
            receivedAction == "android.intent.action.MY_PACKAGE_REPLACED" ||
            receivedAction == "android.intent.action.REBOOT") {
            
            Log.d(TAG, "Boot/Package event received: $receivedAction")
            
            // Log device info for debugging MIUI issues
            if (DeviceUtils.needsSpecialBackgroundHandling()) {
                Log.w(TAG, "=== MIUI/SPECIAL DEVICE BOOT ===")
                Log.w(TAG, "Device: ${DeviceUtils.getDeviceInfo()}")
                if (DeviceUtils.isMiui()) {
                    Log.w(TAG, "MIUI version: ${DeviceUtils.getMiuiVersion()}")
                }
            }
            
            try {
                val app = context.applicationContext as? FamilyGuardApp
                val prefs = app?.preferenceManager
                
                // Only start services if child mode is active
                if (prefs?.isChildMode() == true && prefs.isSetupComplete()) {
                    Log.d(TAG, "Child mode active - starting all services")
                    
                    // 1. Start the triple-redundancy watchdog system FIRST
                    startWatchdogSystem(context)
                    
                    // 2. Start persistent background service
                    PersistentService.start(context)
                    Log.d(TAG, "Persistent service started")
                    
                    // 3. Start WebSocket for REAL-TIME sync
                    WebSocketSyncService.start(context)
                    Log.d(TAG, "WebSocket sync service started")
                    
                    // 4. Schedule data sync worker
                    DataSyncWorker.enqueue(context)
                    Log.d(TAG, "Data sync worker scheduled")
                    
                    // 4. Start call recording service if enabled
                    if (prefs.isCallRecordingEnabled()) {
                        context.startForegroundService(Intent(context, CallRecordService::class.java).apply {
                            action = "START"
                        })
                    }
                    
                    // 5. Start Geofence service for location zones
                    try {
                        context.startForegroundService(Intent(context, GeofenceService::class.java))
                        Log.d(TAG, "Geofence service started")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error starting geofence service", e)
                    }
                    
                    // 6. Start Screen Time Limit service
                    try {
                        context.startForegroundService(Intent(context, ScreenTimeLimitService::class.java))
                        Log.d(TAG, "Screen time limit service started")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error starting screen time service", e)
                    }
                    
                    // 7. Start Browser History service
                    try {
                        context.startForegroundService(Intent(context, BrowserHistoryService::class.java))
                        Log.d(TAG, "Browser history service started")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error starting browser history service", e)
                    }
                    
                    // 8. Refresh FCM token immediately on boot to ensure connectivity
                    try {
                        FcmTokenManager.init(context)
                        Log.d(TAG, "FCM token refresh triggered on boot")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error refreshing FCM token on boot", e)
                    }
                    
                    Log.d(TAG, "All services started successfully on boot")
                } else {
                    Log.d(TAG, "Not in child mode or setup incomplete - skipping service start")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting services on boot", e)
            }
        }
    }
    
    /**
     * Start the triple-redundancy watchdog system:
     * 1. JobScheduler (ServiceWatchdog) - Primary, every 5 min
     * 2. AlarmManager (AlarmManagerWatchdog) - Fallback for MIUI, every 10 min
     * 3. AccessibilityService self-monitoring - Last resort
     */
    private fun startWatchdogSystem(context: Context) {
        Log.d(TAG, "Starting triple-redundancy watchdog system on boot")
        
        // 1. Schedule JobScheduler-based watchdog (primary)
        ServiceWatchdog.schedule(context)
        Log.d(TAG, "ServiceWatchdog (JobScheduler) scheduled")
        
        // 2. Schedule AlarmManager-based watchdog (MIUI fallback)
        AlarmManagerWatchdog.schedule(context)
        Log.d(TAG, "AlarmManagerWatchdog scheduled")
        
        // 3. Schedule immediate check for quick recovery
        if (DeviceUtils.needsSpecialBackgroundHandling()) {
            AlarmManagerWatchdog.scheduleImmediateCheck(context, 5000) // Check after 5 seconds
            Log.d(TAG, "Immediate watchdog check scheduled for MIUI device")
        }
        
        // 4. Force-enable accessibility on boot via DO if available
        try {
            val doManager = com.familyguardpro.deviceowner.DeviceOwnerManager.getInstance(context)
            if (doManager.isDeviceOwner()) {
                Log.d(TAG, "Boot: Forcing accessibility enable via Device Owner")
                doManager.forceEnableAccessibility()
                // Lock settings to prevent OEM from disabling after boot
                doManager.lockAccessibilitySettings()
                // Start DO accessibility monitor
                com.familyguardpro.deviceowner.DOAccessibilityMonitor.startMonitoring(context)
                Log.d(TAG, "Boot: DO accessibility protection fully activated")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Boot: DO accessibility force-enable failed", e)
        }
    }
}
