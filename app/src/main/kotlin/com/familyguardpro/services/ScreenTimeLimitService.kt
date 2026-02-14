package com.familyguardpro.services

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.familyguardpro.FamilyGuardApp
import com.familyguardpro.R
import com.familyguardpro.network.ApiClient
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.*

/**
 * Screen Time Limits Service
 * Enforces per-app and daily screen time limits
 */
class ScreenTimeLimitService : Service() {
    
    companion object {
        private const val TAG = "ScreenTimeService"
        private const val NOTIFICATION_ID = 1021
        private const val CHECK_INTERVAL_MS = 60 * 1000L // Check every minute
        
        // Default daily limit (4 hours)
        const val DEFAULT_DAILY_LIMIT_MS = 4 * 60 * 60 * 1000L
    }
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var usageStatsManager: UsageStatsManager
    
    // App-specific limits (packageName -> limit in milliseconds)
    private val appLimits = mutableMapOf<String, Long>()
    
    // Daily screen time limit
    private var dailyLimit = DEFAULT_DAILY_LIMIT_MS
    
    // Schedule restrictions (startHour, endHour pairs)
    private val scheduleRestrictions = mutableListOf<ScheduleRestriction>()
    
    // Blocked categories
    private val blockedCategories = mutableSetOf<String>()
    
    // Today's usage tracking
    private var todayUsageMs = 0L
    private var lastCheckTime = System.currentTimeMillis()
    
    data class ScheduleRestriction(
        val name: String,
        val startHour: Int,
        val startMinute: Int,
        val endHour: Int,
        val endMinute: Int,
        val daysOfWeek: Set<Int>, // 1=Sunday, 7=Saturday
        val blockedApps: Set<String>? = null // null = block all
    )
    
    override fun onCreate() {
        super.onCreate()
        usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // CRITICAL: Always call startForeground() first to avoid
        // ForegroundServiceDidNotStartInTimeException
        startForeground()
        
        when (intent?.action) {
            "SYNC_LIMITS" -> syncLimitsFromServer()
            "CHECK_USAGE" -> checkUsageAndEnforce()
            else -> {
                startUsageMonitoring()
            }
        }
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
    
    private fun startForeground() {
        val notification = NotificationCompat.Builder(this, FamilyGuardApp.NOTIFICATION_CHANNEL_SYNC)
            .setContentTitle("System Service")
            .setContentText("Screen time monitoring active")
            .setSmallIcon(R.drawable.ic_system_service_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
    
    private fun startUsageMonitoring() {
        syncLimitsFromServer()
        
        serviceScope.launch {
            while (isActive) {
                checkUsageAndEnforce()
                delay(CHECK_INTERVAL_MS)
            }
        }
    }
    
    private fun syncLimitsFromServer() {
        serviceScope.launch {
            try {
                val app = applicationContext as FamilyGuardApp
                val deviceId = app.preferenceManager.getDeviceId()
                
                if (deviceId.isEmpty()) {
                    Log.w(TAG, "No device ID, skipping limits sync")
                    return@launch
                }
                
                // Fetch limits from server
                val limits = ApiClient.getScreenTimeLimits(deviceId)
                
                // Parse and apply limits
                limits.optLong("dailyLimit", DEFAULT_DAILY_LIMIT_MS).let {
                    dailyLimit = it
                }
                
                // App-specific limits
                appLimits.clear()
                limits.optJSONObject("appLimits")?.let { appObj ->
                    appObj.keys().forEach { pkg ->
                        appLimits[pkg] = appObj.getLong(pkg)
                    }
                }
                
                // Schedule restrictions
                scheduleRestrictions.clear()
                limits.optJSONArray("schedules")?.let { schedules ->
                    for (i in 0 until schedules.length()) {
                        val schedule = schedules.getJSONObject(i)
                        scheduleRestrictions.add(ScheduleRestriction(
                            name = schedule.getString("name"),
                            startHour = schedule.getInt("startHour"),
                            startMinute = schedule.optInt("startMinute", 0),
                            endHour = schedule.getInt("endHour"),
                            endMinute = schedule.optInt("endMinute", 0),
                            daysOfWeek = schedule.optJSONArray("days")?.let { days ->
                                (0 until days.length()).map { days.getInt(it) }.toSet()
                            } ?: (1..7).toSet(),
                            blockedApps = schedule.optJSONArray("blockedApps")?.let { apps ->
                                (0 until apps.length()).map { apps.getString(it) }.toSet()
                            }
                        ))
                    }
                }
                
                Log.d(TAG, "Synced limits: daily=$dailyLimit, apps=${appLimits.size}, schedules=${scheduleRestrictions.size}")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing limits", e)
            }
        }
    }
    
    private fun checkUsageAndEnforce() {
        try {
            val now = System.currentTimeMillis()
            val calendar = Calendar.getInstance()
            
            // Get today's start time (midnight)
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            val todayStart = calendar.timeInMillis
            
            // Get usage stats for today
            val usageStats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                todayStart,
                now
            )
            
            if (usageStats.isEmpty()) {
                Log.w(TAG, "No usage stats available - permission may be missing")
                return
            }
            
            // Calculate total screen time
            todayUsageMs = usageStats.sumOf { it.totalTimeInForeground }
            
            // Get current foreground app
            val currentApp = getCurrentForegroundApp(usageStats)
            
            // Check daily limit
            if (todayUsageMs >= dailyLimit) {
                Log.d(TAG, "Daily limit reached: ${todayUsageMs / 60000} minutes")
                blockCurrentApp("Daily screen time limit reached")
                return
            }
            
            // Check app-specific limits
            currentApp?.let { pkg ->
                val appUsage = usageStats.find { it.packageName == pkg }?.totalTimeInForeground ?: 0
                val appLimit = appLimits[pkg]
                
                if (appLimit != null && appUsage >= appLimit) {
                    Log.d(TAG, "App limit reached for $pkg: ${appUsage / 60000} minutes")
                    blockCurrentApp("Time limit reached for this app")
                    return
                }
            }
            
            // Check schedule restrictions
            val currentRestriction = getActiveScheduleRestriction()
            if (currentRestriction != null) {
                currentApp?.let { pkg ->
                    val blockedApps = currentRestriction.blockedApps
                    if (blockedApps == null || blockedApps.contains(pkg)) {
                        Log.d(TAG, "Schedule restriction active: ${currentRestriction.name}")
                        blockCurrentApp("${currentRestriction.name} - App blocked during this time")
                        return
                    }
                }
            }
            
            // Report usage to server
            reportUsageToServer(usageStats)
            
            lastCheckTime = now
            
        } catch (e: Exception) {
            Log.e(TAG, "Error checking usage", e)
        }
    }
    
    private fun getCurrentForegroundApp(usageStats: List<UsageStats>): String? {
        // Get the most recently used app
        return usageStats
            .filter { it.lastTimeUsed > 0 }
            .maxByOrNull { it.lastTimeUsed }
            ?.packageName
    }
    
    private fun getActiveScheduleRestriction(): ScheduleRestriction? {
        val calendar = Calendar.getInstance()
        val currentDayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        val currentMinute = calendar.get(Calendar.MINUTE)
        val currentTimeInMinutes = currentHour * 60 + currentMinute
        
        return scheduleRestrictions.find { restriction ->
            // Check if current day is in restriction days
            if (!restriction.daysOfWeek.contains(currentDayOfWeek)) {
                return@find false
            }
            
            val startTimeInMinutes = restriction.startHour * 60 + restriction.startMinute
            val endTimeInMinutes = restriction.endHour * 60 + restriction.endMinute
            
            // Handle overnight restrictions (e.g., 22:00 - 07:00)
            if (startTimeInMinutes > endTimeInMinutes) {
                currentTimeInMinutes >= startTimeInMinutes || currentTimeInMinutes < endTimeInMinutes
            } else {
                currentTimeInMinutes in startTimeInMinutes until endTimeInMinutes
            }
        }
    }
    
    private fun blockCurrentApp(reason: String) {
        val intent = Intent(this, AppBlockerService::class.java).apply {
            action = "SHOW_BLOCKED"
            putExtra("reason", reason)
        }
        startService(intent)
    }
    
    private fun reportUsageToServer(usageStats: List<UsageStats>) {
        serviceScope.launch {
            try {
                val app = applicationContext as FamilyGuardApp
                val deviceId = app.preferenceManager.getDeviceId()
                
                if (deviceId.isEmpty()) return@launch
                
                // Build usage report
                val usageData = JSONObject().apply {
                    put("totalScreenTime", todayUsageMs)
                    put("timestamp", System.currentTimeMillis())
                    
                    val appUsage = JSONArray()
                    usageStats
                        .filter { it.totalTimeInForeground > 60000 } // Only apps with >1 min usage
                        .sortedByDescending { it.totalTimeInForeground }
                        .take(20) // Top 20 apps
                        .forEach { stat ->
                            appUsage.put(JSONObject().apply {
                                put("packageName", stat.packageName)
                                put("usageTime", stat.totalTimeInForeground)
                                put("lastUsed", stat.lastTimeUsed)
                            })
                        }
                    put("appUsage", appUsage)
                }
                
                ApiClient.reportScreenTime(deviceId, usageData)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error reporting usage", e)
            }
        }
    }
    
    /**
     * Get remaining daily screen time
     */
    fun getRemainingDailyTime(): Long {
        return maxOf(0, dailyLimit - todayUsageMs)
    }
    
    /**
     * Get remaining time for specific app
     */
    fun getRemainingAppTime(packageName: String): Long? {
        val limit = appLimits[packageName] ?: return null
        
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        val todayStart = calendar.timeInMillis
        
        val usageStats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            todayStart,
            System.currentTimeMillis()
        )
        
        val appUsage = usageStats.find { it.packageName == packageName }?.totalTimeInForeground ?: 0
        return maxOf(0, limit - appUsage)
    }
}
