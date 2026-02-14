package com.familyguardpro.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Tracks WebSocket data usage for monitoring and optimization.
 * 
 * Provides:
 * - Total bytes sent/received
 * - Daily average data usage
 * - Monthly estimate
 * - Reset functionality
 */
object DataUsageTracker {
    
    private const val TAG = "DataUsageTracker"
    private const val PREFS_NAME = "family_guard_data_usage"
    private const val KEY_BYTES_SENT = "bytes_sent"
    private const val KEY_BYTES_RECEIVED = "bytes_received"
    private const val KEY_MESSAGE_COUNT_SENT = "message_count_sent"
    private const val KEY_MESSAGE_COUNT_RECEIVED = "message_count_received"
    private const val KEY_INSTALL_DATE = "install_date"
    private const val KEY_LAST_RESET_DATE = "last_reset_date"
    
    private var prefs: SharedPreferences? = null
    private var initialized = false
    
    /**
     * Initialize the tracker. Call from Application.onCreate()
     */
    fun init(context: Context) {
        if (initialized) return
        
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // Record install date if first time
        if (!prefs!!.contains(KEY_INSTALL_DATE)) {
            prefs!!.edit()
                .putLong(KEY_INSTALL_DATE, System.currentTimeMillis())
                .putLong(KEY_LAST_RESET_DATE, System.currentTimeMillis())
                .apply()
        }
        
        initialized = true
        Log.d(TAG, "DataUsageTracker initialized. Total usage: ${getTotalUsageMB()} MB")
    }
    
    /**
     * Track bytes sent via WebSocket
     */
    fun trackSend(bytes: Int) {
        if (!initialized || prefs == null) return
        
        val currentBytes = prefs!!.getLong(KEY_BYTES_SENT, 0)
        val currentCount = prefs!!.getLong(KEY_MESSAGE_COUNT_SENT, 0)
        
        prefs!!.edit()
            .putLong(KEY_BYTES_SENT, currentBytes + bytes)
            .putLong(KEY_MESSAGE_COUNT_SENT, currentCount + 1)
            .apply()
    }
    
    /**
     * Track bytes received via WebSocket
     */
    fun trackReceive(bytes: Int) {
        if (!initialized || prefs == null) return
        
        val currentBytes = prefs!!.getLong(KEY_BYTES_RECEIVED, 0)
        val currentCount = prefs!!.getLong(KEY_MESSAGE_COUNT_RECEIVED, 0)
        
        prefs!!.edit()
            .putLong(KEY_BYTES_RECEIVED, currentBytes + bytes)
            .putLong(KEY_MESSAGE_COUNT_RECEIVED, currentCount + 1)
            .apply()
    }
    
    /**
     * Get total bytes sent
     */
    fun getBytesSent(): Long {
        return prefs?.getLong(KEY_BYTES_SENT, 0) ?: 0
    }
    
    /**
     * Get total bytes received
     */
    fun getBytesReceived(): Long {
        return prefs?.getLong(KEY_BYTES_RECEIVED, 0) ?: 0
    }
    
    /**
     * Get total data usage in megabytes
     */
    fun getTotalUsageMB(): Float {
        val sent = getBytesSent()
        val received = getBytesReceived()
        return (sent + received) / (1024f * 1024f)
    }
    
    /**
     * Get total data usage in kilobytes
     */
    fun getTotalUsageKB(): Float {
        val sent = getBytesSent()
        val received = getBytesReceived()
        return (sent + received) / 1024f
    }
    
    /**
     * Get message count (sent + received)
     */
    fun getMessageCount(): Long {
        val sent = prefs?.getLong(KEY_MESSAGE_COUNT_SENT, 0) ?: 0
        val received = prefs?.getLong(KEY_MESSAGE_COUNT_RECEIVED, 0) ?: 0
        return sent + received
    }
    
    /**
     * Get days since tracking started
     */
    fun getDaysSinceInstall(): Int {
        val installDate = prefs?.getLong(KEY_INSTALL_DATE, System.currentTimeMillis()) 
            ?: System.currentTimeMillis()
        val daysSince = ((System.currentTimeMillis() - installDate) / (1000 * 60 * 60 * 24)).toInt()
        return maxOf(daysSince, 1) // At least 1 day
    }
    
    /**
     * Get daily average data usage in MB
     */
    fun getDailyAverageMB(): Float {
        return getTotalUsageMB() / getDaysSinceInstall()
    }
    
    /**
     * Get estimated monthly data usage in MB
     */
    fun getMonthlyEstimateMB(): Float {
        return getDailyAverageMB() * 30
    }
    
    /**
     * Get formatted total usage string
     */
    fun getFormattedTotalUsage(): String {
        val totalMB = getTotalUsageMB()
        return if (totalMB < 1) {
            String.format("%.1f KB", getTotalUsageKB())
        } else {
            String.format("%.2f MB", totalMB)
        }
    }
    
    /**
     * Get formatted daily average string
     */
    fun getFormattedDailyAverage(): String {
        val dailyMB = getDailyAverageMB()
        return if (dailyMB < 1) {
            String.format("%.1f KB/day", dailyMB * 1024)
        } else {
            String.format("%.2f MB/day", dailyMB)
        }
    }
    
    /**
     * Get formatted monthly estimate string
     */
    fun getFormattedMonthlyEstimate(): String {
        val monthlyMB = getMonthlyEstimateMB()
        return if (monthlyMB < 1) {
            String.format("%.1f KB/month", monthlyMB * 1024)
        } else {
            String.format("%.1f MB/month", monthlyMB)
        }
    }
    
    /**
     * Reset all statistics
     */
    fun reset() {
        prefs?.edit()
            ?.putLong(KEY_BYTES_SENT, 0)
            ?.putLong(KEY_BYTES_RECEIVED, 0)
            ?.putLong(KEY_MESSAGE_COUNT_SENT, 0)
            ?.putLong(KEY_MESSAGE_COUNT_RECEIVED, 0)
            ?.putLong(KEY_LAST_RESET_DATE, System.currentTimeMillis())
            ?.apply()
        
        Log.d(TAG, "Data usage statistics reset")
    }
    
    /**
     * Get usage summary as a map (for debugging/display)
     */
    fun getUsageSummary(): Map<String, Any> {
        return mapOf(
            "totalMB" to getTotalUsageMB(),
            "dailyAverageMB" to getDailyAverageMB(),
            "monthlyEstimateMB" to getMonthlyEstimateMB(),
            "messageCount" to getMessageCount(),
            "daysSinceInstall" to getDaysSinceInstall(),
            "bytesSent" to getBytesSent(),
            "bytesReceived" to getBytesReceived()
        )
    }
    
    /**
     * Log current usage statistics
     */
    fun logStats() {
        Log.d(TAG, """
            ┌─────────────────────────────────────────
            │ DATA USAGE STATISTICS
            ├─────────────────────────────────────────
            │ Total: ${getFormattedTotalUsage()}
            │ Daily Avg: ${getFormattedDailyAverage()}
            │ Monthly Est: ${getFormattedMonthlyEstimate()}
            │ Messages: ${getMessageCount()}
            │ Days: ${getDaysSinceInstall()}
            └─────────────────────────────────────────
        """.trimIndent())
    }
}
