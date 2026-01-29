package com.familyguardpro.utils

import android.Manifest
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.BatteryManager
import android.os.Build
import android.provider.CallLog
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import com.familyguardpro.models.AppUsageData
import com.familyguardpro.models.CallLogData
import com.familyguardpro.models.InstalledApp
import com.familyguardpro.models.LocationData
import com.familyguardpro.models.SyncData
import java.util.*

/**
 * Collects various device data for synchronization with the server.
 */
class DataCollector(private val context: Context) {

    companion object {
        private const val TAG = "DataCollector"
    }

    /**
     * Collects all data types for periodic sync.
     */
    fun collectAllData(): SyncData {
        return SyncData(
            batteryLevel = getBatteryLevel(),
            screenTimeMinutes = getScreenTimeMinutes(),
            appUsage = getAppUsageStats(),
            callLogs = getCallLogs(),
            lastLocation = null, // Location is collected separately by LocationService
            webHistory = getWebHistory(),
            installedApps = getInstalledApps()
        )
    }

    /**
     * Gets the current battery level as percentage.
     */
    fun getBatteryLevel(): Int {
        val batteryIntent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        
        return if (level >= 0 && scale > 0) {
            (level * 100 / scale)
        } else {
            -1
        }
    }

    /**
     * Gets total screen time in minutes for the last 24 hours.
     */
    fun getScreenTimeMinutes(): Int {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        
        val endTime = System.currentTimeMillis()
        val startTime = endTime - (24 * 60 * 60 * 1000) // 24 hours ago
        
        val usageStats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startTime,
            endTime
        )
        
        var totalTime = 0L
        usageStats?.forEach { stats ->
            totalTime += stats.totalTimeInForeground
        }
        
        return (totalTime / (1000 * 60)).toInt() // Convert to minutes
    }

    /**
     * Gets app usage statistics for the last 24 hours.
     */
    fun getAppUsageStats(): List<AppUsageData> {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        
        val endTime = System.currentTimeMillis()
        val startTime = endTime - (24 * 60 * 60 * 1000)
        
        val usageStats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startTime,
            endTime
        )
        
        val appUsageList = mutableListOf<AppUsageData>()
        
        usageStats?.filter { it.totalTimeInForeground > 60000 }?.forEach { stats ->
            val appName = try {
                val appInfo = context.packageManager.getApplicationInfo(stats.packageName, 0)
                context.packageManager.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                stats.packageName
            }
            
            appUsageList.add(
                AppUsageData(
                    packageName = stats.packageName,
                    appName = appName,
                    usageTimeMinutes = (stats.totalTimeInForeground / (1000 * 60)).toInt(),
                    lastUsed = stats.lastTimeUsed
                )
            )
        }
        
        return appUsageList.sortedByDescending { it.usageTimeMinutes }
    }

    /**
     * Gets call log entries from the last 24 hours.
     */
    fun getCallLogs(): List<CallLogData> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        
        val callLogs = mutableListOf<CallLogData>()
        val cutoffTime = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
        
        val cursor: Cursor? = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
            ),
            "${CallLog.Calls.DATE} > ?",
            arrayOf(cutoffTime.toString()),
            "${CallLog.Calls.DATE} DESC"
        )
        
        cursor?.use {
            while (it.moveToNext()) {
                val id = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls._ID))
                val number = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER)) ?: ""
                val name = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME))
                val type = it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.TYPE))
                val date = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DATE))
                val duration = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DURATION))
                
                val callType = when (type) {
                    CallLog.Calls.INCOMING_TYPE -> "incoming"
                    CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                    CallLog.Calls.MISSED_TYPE -> "missed"
                    CallLog.Calls.REJECTED_TYPE -> "rejected"
                    else -> "unknown"
                }
                
                callLogs.add(
                    CallLogData(
                        id = id.toString(),
                        phoneNumber = number,
                        contactName = name,
                        callType = callType,
                        timestamp = date,
                        durationSeconds = duration.toInt(),
                        audioUrl = null
                    )
                )
            }
        }
        
        return callLogs
    }

    /**
     * Gets web browser history (Chrome and other browsers).
     * Note: This requires special permissions on newer Android versions.
     */
    fun getWebHistory(): List<Map<String, Any>> {
        // Browser history access is restricted on modern Android
        // This is a placeholder - actual implementation depends on browser
        return emptyList()
    }

    /**
     * Gets list of installed apps.
     */
    fun getInstalledApps(): List<InstalledApp> {
        val pm = context.packageManager
        val apps = mutableListOf<InstalledApp>()
        
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        
        packages.filter { 
            // Only user-installed apps (not system apps)
            (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
            (it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        }.forEach { appInfo ->
            try {
                val packageInfo = pm.getPackageInfo(appInfo.packageName, 0)
                
                apps.add(
                    InstalledApp(
                        packageName = appInfo.packageName,
                        appName = pm.getApplicationLabel(appInfo).toString(),
                        versionName = packageInfo.versionName ?: "",
                        installedTime = packageInfo.firstInstallTime,
                        lastUpdated = packageInfo.lastUpdateTime
                    )
                )
            } catch (e: Exception) {
                // Ignore
            }
        }
        
        return apps.sortedBy { it.appName.lowercase() }
    }

    /**
     * Gets recently captured photos (last 24 hours).
     */
    fun getRecentPhotos(): List<String> {
        val photos = mutableListOf<String>()
        val cutoffTime = (System.currentTimeMillis() - (24 * 60 * 60 * 1000)) / 1000
        
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DATE_ADDED
        )
        
        val selection = "${MediaStore.Images.Media.DATE_ADDED} > ?"
        val selectionArgs = arrayOf(cutoffTime.toString())
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            
            while (cursor.moveToNext() && photos.size < 50) {
                val path = cursor.getString(dataColumn)
                photos.add(path)
            }
        }
        
        return photos
    }
}
