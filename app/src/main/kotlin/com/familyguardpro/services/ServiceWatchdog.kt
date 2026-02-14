package com.familyguardpro.services

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.familyguardpro.FamilyGuardApp
import com.familyguardpro.MiuiSetupActivity
import com.familyguardpro.R
import com.familyguardpro.utils.DeviceUtils
import com.familyguardpro.utils.FcmTokenManager
import com.familyguardpro.utils.PermissionsHelper
import com.familyguardpro.utils.PreferenceManager

/**
 * Service Watchdog - Monitors critical services and tries to recover them
 * 
 * This is critical for MIUI/Samsung/Huawei devices where the OS aggressively
 * kills background services even with proper permissions configured.
 * 
 * Uses JobScheduler for periodic health checks because:
 * 1. It survives doze mode
 * 2. It persists across reboots with setPersisted(true)
 * 3. It's more reliable than AlarmManager on modern Android
 */
class ServiceWatchdog : JobService() {
    
    companion object {
        private const val TAG = "ServiceWatchdog"
        private const val JOB_ID = 9001
        private const val CHECK_INTERVAL_MINUTES = 5L
        
        // Notification IDs
        private const val NOTIFICATION_ID_SERVICE_STOPPED = 9999
        private const val NOTIFICATION_ID_SETUP_REMINDER = 9998
        private const val NOTIFICATION_ID_HEALTH_WARNING = 9997
        
        // Notification channel for urgent alerts
        private const val CHANNEL_ID_URGENT = "familyguard_urgent"
        
        // Preference keys
        private const val PREFS_NAME = "watchdog_prefs"
        private const val KEY_LAST_CHECK = "last_health_check"
        private const val KEY_SERVICE_DEATHS = "service_death_count"
        private const val KEY_LAST_DEATH = "last_death_time"
        
        /**
         * Schedule the watchdog job
         */
        fun schedule(context: Context) {
            createUrgentChannel(context)
            
            val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            
            // Check if already scheduled
            val pendingJob = jobScheduler.getPendingJob(JOB_ID)
            if (pendingJob != null) {
                Log.d(TAG, "Watchdog already scheduled")
                return
            }
            
            val componentName = ComponentName(context, ServiceWatchdog::class.java)
            
            val jobInfo = JobInfo.Builder(JOB_ID, componentName)
                .setPeriodic(CHECK_INTERVAL_MINUTES * 60 * 1000) // Convert to ms
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                .setPersisted(true) // Survive reboot
                .build()
            
            val result = jobScheduler.schedule(jobInfo)
            if (result == JobScheduler.RESULT_SUCCESS) {
                Log.i(TAG, "Watchdog scheduled successfully (every $CHECK_INTERVAL_MINUTES min)")
            } else {
                Log.e(TAG, "Failed to schedule watchdog")
                // Fallback to AlarmManager
                scheduleAlarmFallback(context)
            }
        }
        
        /**
         * Cancel the watchdog
         */
        fun cancel(context: Context) {
            val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            jobScheduler.cancel(JOB_ID)
            Log.d(TAG, "Watchdog cancelled")
        }
        
        /**
         * Run health check immediately
         */
        fun checkNow(context: Context) {
            val intent = Intent(context, ServiceWatchdog::class.java).apply {
                action = "CHECK_NOW"
            }
            context.startService(intent)
        }
        
        /**
         * Fallback using AlarmManager for devices where JobScheduler fails
         */
        private fun scheduleAlarmFallback(context: Context) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(context, WatchdogAlarmReceiver::class.java)
                val pendingIntent = PendingIntent.getBroadcast(
                    context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                
                alarmManager.setRepeating(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + CHECK_INTERVAL_MINUTES * 60 * 1000,
                    CHECK_INTERVAL_MINUTES * 60 * 1000,
                    pendingIntent
                )
                Log.i(TAG, "Watchdog AlarmManager fallback scheduled")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule alarm fallback", e)
            }
        }
        
        /**
         * Create urgent notification channel
         */
        private fun createUrgentChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID_URGENT,
                    "Urgent Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Critical alerts when monitoring stops"
                    enableLights(true)
                    enableVibration(true)
                    setShowBadge(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                
                val notificationManager = context.getSystemService(NotificationManager::class.java)
                notificationManager.createNotificationChannel(channel)
            }
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "CHECK_NOW") {
            runHealthCheck()
        }
        return START_NOT_STICKY
    }
    
    override fun onStartJob(params: JobParameters?): Boolean {
        Log.d(TAG, "Watchdog health check started")
        
        // Run health check on background thread
        Thread {
            runHealthCheck()
            jobFinished(params, false)
        }.start()
        
        return true // Job is still running (async)
    }
    
    override fun onStopJob(params: JobParameters?): Boolean {
        // Return true to reschedule
        return true
    }
    
    /**
     * Main health check logic
     */
    private fun runHealthCheck() {
        val context = applicationContext
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // Update last check time
        prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        
        Log.d(TAG, "Running health check on ${DeviceUtils.getDeviceInfo()}")
        
        // 1. Check if in child mode
        val preferenceManager = PreferenceManager(context)
        if (!preferenceManager.isChildMode()) {
            Log.d(TAG, "Not in child mode, skipping health check")
            return
        }
        
        // 2. Check Accessibility Service
        val accessibilityRunning = PermissionsHelper.hasAccessibilityAccess(context)
        if (!accessibilityRunning) {
            Log.e(TAG, "⚠️ ACCESSIBILITY SERVICE IS NOT RUNNING!")
            
            // Try DO recovery first if available
            try {
                val doManager = com.familyguardpro.deviceowner.DeviceOwnerManager.getInstance(context)
                if (doManager.isDeviceOwner()) {
                    Log.d(TAG, "ServiceWatchdog: Attempting DO force-enable accessibility")
                    val recovered = doManager.forceEnableAccessibility()
                    if (recovered) {
                        Log.d(TAG, "ServiceWatchdog: DO accessibility recovery SUCCESS")
                    } else {
                        handleServiceDeath(context, prefs)
                    }
                } else {
                    handleServiceDeath(context, prefs)
                }
            } catch (e: Exception) {
                Log.e(TAG, "ServiceWatchdog: DO recovery error", e)
                handleServiceDeath(context, prefs)
            }
        } else {
            Log.d(TAG, "✓ Accessibility service is running")
        }
        
        // 3. Check if PersistentService is running (by checking foreground notification)
        // This is indirect but works across processes
        val persistentRunning = isPersistentServiceRunning(context)
        if (!persistentRunning) {
            Log.e(TAG, "⚠️ PERSISTENT SERVICE IS NOT RUNNING!")
            // Try to restart it
            try {
                PersistentService.start(context)
                Log.d(TAG, "Attempted to restart PersistentService")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart PersistentService", e)
            }
        } else {
            Log.d(TAG, "✓ Persistent service appears to be running")
        }
        
        // 4. Refresh FCM token to ensure connectivity
        try {
            FcmTokenManager.refreshTokenAsync()
            Log.d(TAG, "✓ FCM token refresh triggered from ServiceWatchdog")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh FCM token", e)
        }
        
        // 5. Check battery saver mode (kills accessibility on MIUI)
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isPowerSaveMode) {
            Log.w(TAG, "⚠️ BATTERY SAVER IS ENABLED - May affect background operation")
            if (DeviceUtils.isMiui()) {
                showBatterySaverWarning(context)
            }
        }
        
        // 6. Check MIUI setup completion
        if (DeviceUtils.isMiui()) {
            val miuiSetupComplete = MiuiSetupActivity.isSetupNeeded(context)
            if (miuiSetupComplete) {
                Log.w(TAG, "MIUI setup not completed")
                showMiuiSetupReminder(context)
            }
        }
        
        // 7. Log device-specific info
        if (DeviceUtils.needsSpecialBackgroundHandling()) {
            Log.i(TAG, "Device requires special handling: ${DeviceUtils.getManufacturerName()}")
        }
        
        Log.d(TAG, "Health check completed")
    }
    
    /**
     * Handle service death - notify and try to recover
     */
    private fun handleServiceDeath(context: Context, prefs: android.content.SharedPreferences) {
        // Track death count
        val deathCount = prefs.getInt(KEY_SERVICE_DEATHS, 0) + 1
        prefs.edit()
            .putInt(KEY_SERVICE_DEATHS, deathCount)
            .putLong(KEY_LAST_DEATH, System.currentTimeMillis())
            .apply()
        
        Log.e(TAG, "Service death #$deathCount detected")
        
        // Show notification to user
        showServiceStoppedNotification(context, deathCount)
        
        // If on MIUI and setup not complete, show that reminder
        if (DeviceUtils.isMiui() && MiuiSetupActivity.isSetupNeeded(context)) {
            showMiuiSetupReminder(context)
        }
        
        // TODO: Send alert to parent dashboard via API
        // This could be implemented to notify parents when child device goes offline
        // ApiClient.sendAlert("accessibility_service_stopped", DeviceUtils.getDeviceInfo())
    }
    
    /**
     * Check if PersistentService is running
     */
    private fun isPersistentServiceRunning(context: Context): Boolean {
        // Check if our foreground notification exists
        try {
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            val activeNotifications = notificationManager.activeNotifications
            
            for (notification in activeNotifications) {
                // Check for our persistent service notification ID
                if (notification.id == 1001) { // PersistentService.NOTIFICATION_ID
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check notification", e)
        }
        
        return false
    }
    
    /**
     * Show notification when service stops
     */
    private fun showServiceStoppedNotification(context: Context, deathCount: Int) {
        val accessibilityIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, accessibilityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val deviceInfo = if (DeviceUtils.isMiui()) {
            "\n\nNote: MIUI devices require special setup. Tap to configure."
        } else ""
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_URGENT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("⚠️ FamilyGuard Stopped")
            .setContentText("Monitoring service was stopped. Tap to restart.")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("The monitoring service was stopped by the system (death #$deathCount). " +
                        "Please tap to re-enable Accessibility Service.$deviceInfo"))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(pendingIntent)
            .build()
        
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_SERVICE_STOPPED, notification)
        } catch (e: SecurityException) {
            Log.e(TAG, "No notification permission", e)
        }
    }
    
    /**
     * Show battery saver warning
     */
    private fun showBatterySaverWarning(context: Context) {
        val batteryIntent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, batteryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_URGENT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Battery Saver Active")
            .setContentText("Battery saver may stop monitoring. Tap to disable.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_HEALTH_WARNING, notification)
        } catch (e: SecurityException) {
            Log.e(TAG, "No notification permission", e)
        }
    }
    
    /**
     * Show MIUI setup reminder
     */
    private fun showMiuiSetupReminder(context: Context) {
        val setupIntent = Intent(context, MiuiSetupActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, setupIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_URGENT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("MIUI Setup Required")
            .setContentText("Complete setup to ensure background operation on your ${DeviceUtils.getManufacturerName()} device.")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Your MIUI device requires additional configuration to keep the app running in background. " +
                        "Tap to complete the setup wizard."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_SETUP_REMINDER, notification)
        } catch (e: SecurityException) {
            Log.e(TAG, "No notification permission", e)
        }
    }
}

/**
 * Fallback receiver for AlarmManager on devices where JobScheduler is unreliable
 */
class WatchdogAlarmReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Log.d("WatchdogAlarmReceiver", "Alarm triggered, running health check")
        ServiceWatchdog.checkNow(context)
    }
}
