package com.familyguardpro.services

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.familyguardpro.R
import com.familyguardpro.utils.DeviceUtils
import com.familyguardpro.utils.FcmTokenManager
import com.familyguardpro.utils.PreferenceManager

/**
 * AlarmManager-based watchdog fallback for when JobScheduler is killed by aggressive OEMs.
 * 
 * This is the FALLBACK layer in the triple-redundancy watchdog system:
 * 1. JobScheduler (ServiceWatchdog) - Primary
 * 2. AlarmManager (AlarmManagerWatchdog) - Fallback
 * 3. AccessibilityService self-monitoring - Last resort
 * 
 * Why AlarmManager fallback is needed:
 * - MIUI/Huawei/Oppo can kill JobScheduler jobs
 * - AlarmManager with RTC_WAKEUP has better survival on some devices
 * - This provides redundancy if JobScheduler fails
 */
object AlarmManagerWatchdog {
    
    private const val TAG = "AlarmManagerWatchdog"
    private const val REQUEST_CODE_PERIODIC = 9010
    private const val REQUEST_CODE_ACCESSIBILITY_CHECK = 9011
    private const val REQUEST_CODE_IMMEDIATE = 9012
    
    // Action constants - moved to top level of object (not companion)
    const val ACTION_PERIODIC_CHECK = "com.familyguardpro.ALARM_PERIODIC_CHECK"
    const val ACTION_ACCESSIBILITY_CHECK = "com.familyguardpro.ALARM_ACCESSIBILITY_CHECK"
    const val ACTION_IMMEDIATE_CHECK = "com.familyguardpro.ALARM_IMMEDIATE_CHECK"
    
    // Check interval - 5 minutes (aggressive monitoring)
    private const val CHECK_INTERVAL_MS = 5 * 60 * 1000L
    
    // Quick check interval for critical situations - 45 seconds
    private const val QUICK_CHECK_INTERVAL_MS = 45 * 1000L
    
    /**
     * Schedule periodic watchdog checks via AlarmManager
     */
    fun schedule(context: Context) {
        schedulePeriodicCheck(context)
        scheduleAccessibilityCheck(context)
        Log.i(TAG, "AlarmManager watchdog scheduled")
    }
    
    /**
     * Schedule periodic health check
     */
    private fun schedulePeriodicCheck(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmWatchdogReceiver::class.java).apply {
            action = ACTION_PERIODIC_CHECK
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_PERIODIC,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        // Cancel existing alarm first
        alarmManager.cancel(pendingIntent)
        
        // Use setInexactRepeating for battery efficiency but still reliable
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Use setExactAndAllowWhileIdle for doze mode
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + CHECK_INTERVAL_MS,
                pendingIntent
            )
        } else {
            alarmManager.setRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + CHECK_INTERVAL_MS,
                CHECK_INTERVAL_MS,
                pendingIntent
            )
        }
        
        Log.d(TAG, "Periodic check scheduled for ${CHECK_INTERVAL_MS / 60000} minutes")
    }
    
    /**
     * Schedule quick accessibility service check (PUBLIC - called from other services)
     */
    fun scheduleAccessibilityCheck(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmWatchdogReceiver::class.java).apply {
            action = ACTION_ACCESSIBILITY_CHECK
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_ACCESSIBILITY_CHECK,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + QUICK_CHECK_INTERVAL_MS,
                pendingIntent
            )
        } else {
            alarmManager.setRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + QUICK_CHECK_INTERVAL_MS,
                QUICK_CHECK_INTERVAL_MS,
                pendingIntent
            )
        }
    }
    
    /**
     * Cancel all alarms
     */
    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        
        // Cancel periodic check
        val periodicIntent = Intent(context, AlarmWatchdogReceiver::class.java)
        val periodicPendingIntent = PendingIntent.getBroadcast(
            context, REQUEST_CODE_PERIODIC, periodicIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
        )
        periodicPendingIntent?.let { alarmManager.cancel(it) }
        
        // Cancel accessibility check
        val accessibilityIntent = Intent(context, AlarmWatchdogReceiver::class.java)
        val accessibilityPendingIntent = PendingIntent.getBroadcast(
            context, REQUEST_CODE_ACCESSIBILITY_CHECK, accessibilityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
        )
        accessibilityPendingIntent?.let { alarmManager.cancel(it) }
        
        Log.d(TAG, "AlarmManager watchdog cancelled")
    }
    
    /**
     * Schedule an immediate check (for when service death is detected)
     * @param context The context
     * @param delayMs Delay in milliseconds before the check (default: 5000)
     */
    fun scheduleImmediateCheck(context: Context, delayMs: Long = 5000L) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmWatchdogReceiver::class.java).apply {
            action = ACTION_IMMEDIATE_CHECK
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_IMMEDIATE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs,
                pendingIntent
            )
        } else {
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs,
                pendingIntent
            )
        }
        
        Log.d(TAG, "Immediate check scheduled in ${delayMs}ms")
    }
}

/**
 * BroadcastReceiver for AlarmManager watchdog
 * This is triggered by AlarmManager at specified intervals
 */
class AlarmWatchdogReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "AlarmWatchdogReceiver"
        private const val NOTIFICATION_ID_SERVICE_ALERT = 9020
    }
    
    override fun onReceive(context: Context, intent: Intent?) {
        Log.d(TAG, "Alarm received: ${intent?.action}")
        
        // Acquire partial wake lock to ensure work completes
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "FamilyGuard:AlarmWatchdog"
        )
        wakeLock.acquire(30000) // 30 seconds max
        
        try {
            val prefs = PreferenceManager(context)
            
            // Only run in child mode
            if (!prefs.isChildMode() || !prefs.isSetupComplete()) {
                Log.d(TAG, "Not in child mode, skipping check")
                return
            }
            
            when (intent?.action) {
                AlarmManagerWatchdog.ACTION_PERIODIC_CHECK -> {
                    performFullHealthCheck(context)
                }
                AlarmManagerWatchdog.ACTION_ACCESSIBILITY_CHECK -> {
                    checkAccessibilityService(context)
                }
                AlarmManagerWatchdog.ACTION_IMMEDIATE_CHECK -> {
                    performFullHealthCheck(context)
                }
                else -> {
                    performFullHealthCheck(context)
                }
            }
            
            // Reschedule the alarm (in case MIUI cancelled it)
            AlarmManagerWatchdog.schedule(context)
            
        } finally {
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
        }
    }
    
    /**
     * Full health check - checks all services
     */
    private fun performFullHealthCheck(context: Context) {
        Log.d(TAG, "Performing full health check")
        
        // 1. Check Accessibility Service
        val accessibilityEnabled = isAccessibilityServiceEnabled(context)
        if (!accessibilityEnabled) {
            Log.e(TAG, "⚠️ Accessibility Service is NOT running!")
            
            // Try DO recovery first if available
            try {
                val doManager = com.familyguardpro.deviceowner.DeviceOwnerManager.getInstance(context)
                if (doManager.isDeviceOwner()) {
                    Log.d(TAG, "AlarmWatchdog: Attempting DO force-enable accessibility")
                    val recovered = doManager.forceEnableAccessibility()
                    if (recovered) {
                        Log.d(TAG, "AlarmWatchdog: DO accessibility recovery SUCCESS")
                        doManager.lockAccessibilitySettings()
                    } else {
                        Log.e(TAG, "AlarmWatchdog: DO accessibility recovery FAILED")
                        sendServiceStoppedNotification(context, "Accessibility Service")
                    }
                } else {
                    sendServiceStoppedNotification(context, "Accessibility Service")
                }
            } catch (e: Exception) {
                Log.e(TAG, "AlarmWatchdog: DO recovery error", e)
                sendServiceStoppedNotification(context, "Accessibility Service")
            }
        }
        
        // 2. Check PersistentService (by trying to start it)
        try {
            PersistentService.start(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure PersistentService", e)
        }
        
        // 3. Ensure JobScheduler watchdog is running
        ServiceWatchdog.schedule(context)
        
        // 4. Refresh FCM token to ensure it stays valid
        try {
            FcmTokenManager.refreshTokenAsync()
            Log.d(TAG, "FCM token refresh triggered from AlarmWatchdog")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh FCM token", e)
        }
        
        // 5. Log device info for debugging
        if (DeviceUtils.needsSpecialBackgroundHandling()) {
            Log.i(TAG, "AlarmWatchdog check on ${DeviceUtils.getDeviceInfo()}")
        }
    }
    
    /**
     * Quick accessibility service check with DO recovery
     */
    private fun checkAccessibilityService(context: Context) {
        val accessibilityEnabled = isAccessibilityServiceEnabled(context)
        
        if (!accessibilityEnabled) {
            Log.e(TAG, "⚠️ Accessibility Service killed!")
            
            // Try DO recovery first
            try {
                val doManager = com.familyguardpro.deviceowner.DeviceOwnerManager.getInstance(context)
                if (doManager.isDeviceOwner()) {
                    Log.d(TAG, "Quick check: Attempting DO force-enable accessibility")
                    doManager.forceEnableAccessibility()
                    doManager.lockAccessibilitySettings()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Quick check: DO recovery error", e)
            }
            
            // Send notification
            sendServiceStoppedNotification(context, "Monitoring Service")
            
            // Schedule immediate check to see if user re-enabled
            AlarmManagerWatchdog.scheduleImmediateCheck(context, 3000)
        } else {
            Log.d(TAG, "✓ Accessibility Service is running")
        }
    }
    
    /**
     * Check if Accessibility Service is enabled
     */
    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        try {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            
            return enabledServices.contains(context.packageName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check accessibility", e)
            return false
        }
    }
    
    /**
     * Send notification when service stops
     */
    private fun sendServiceStoppedNotification(context: Context, serviceName: String) {
        val accessibilityIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, accessibilityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val deviceInfo = if (DeviceUtils.isMiui()) {
            "\n\nYour MIUI device may have killed the service. Please check MIUI settings."
        } else ""
        
        val notification = NotificationCompat.Builder(context, "familyguard_urgent")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("⚠️ $serviceName Stopped")
            .setContentText("Tap to re-enable monitoring")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("$serviceName was stopped by the system. Tap to re-enable.$deviceInfo"))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_SERVICE_ALERT, notification)
        } catch (e: SecurityException) {
            Log.e(TAG, "No notification permission", e)
        }
    }
}
