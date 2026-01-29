package com.familyguardpro.services

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.*
import com.familyguardpro.models.SyncData
import com.familyguardpro.network.ApiClient
import com.familyguardpro.utils.PreferenceManager
import com.familyguardpro.utils.DataCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class DataSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "DataSyncWorker"
        private const val WORK_NAME = "data_sync_worker"
        private const val IMMEDIATE_WORK_NAME = "data_sync_immediate"
        
        fun schedulePeriodicSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            
            val workRequest = PeriodicWorkRequestBuilder<DataSyncWorker>(
                15, TimeUnit.MINUTES,
                5, TimeUnit.MINUTES // Flex interval
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()
            
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    workRequest
                )
            
            Log.d(TAG, "Periodic sync scheduled")
        }
        
        fun runImmediateSync(context: Context) {
            // Don't require network constraint for immediate sync
            // Let the worker run and handle network errors gracefully
            // This prevents "Waiting for network" stuck state
            val workRequest = OneTimeWorkRequestBuilder<DataSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build()
                )
                .build()
            
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    IMMEDIATE_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    workRequest
                )
            Log.d(TAG, "Immediate sync scheduled (no network constraint)")
        }
        
        fun getImmediateWorkName(): String = IMMEDIATE_WORK_NAME
        
        fun cancelSync(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val preferenceManager = PreferenceManager(applicationContext)
        
        if (!preferenceManager.isChildMode()) {
            Log.d(TAG, "Not in child mode, skipping sync")
            return@withContext Result.success()
        }
        
        // Check network connectivity - but be lenient
        val networkStatus = getNetworkStatus()
        Log.d(TAG, "Network status: $networkStatus")
        
        try {
            Log.d(TAG, "Starting data sync...")
            
            val deviceId = preferenceManager.getDeviceId()
            Log.d(TAG, "Using Device ID: $deviceId")
            
            if (deviceId.isEmpty()) {
                Log.e(TAG, "Device ID is empty! Cannot sync.")
                return@withContext Result.failure()
            }
            
            val dataCollector = DataCollector(applicationContext)
            val syncData = dataCollector.collectAllData()
            
            // Build location item if available
            val locationItem: com.familyguardpro.network.LocationItem? = syncData.lastLocation?.let {
                com.familyguardpro.network.LocationItem(
                    latitude = it.latitude,
                    longitude = it.longitude,
                    accuracy = it.accuracy,
                    timestamp = System.currentTimeMillis()
                )
            }
            
            // Transform call logs to proper data class
            val callLogsFormatted = syncData.callLogs?.map { call ->
                com.familyguardpro.network.CallLogItem(
                    number = call.phoneNumber,
                    name = call.contactName,
                    type = call.callType,
                    duration = call.durationSeconds.toLong(),
                    timestamp = call.timestamp
                )
            } ?: emptyList()
            
            // Transform app usage to proper data class
            val appsFormatted = syncData.appUsage?.map { app ->
                com.familyguardpro.network.AppUsageItem(
                    packageName = app.packageName,
                    appName = app.appName,
                    usageTime = app.usageTimeMinutes.toLong(),
                    openCount = 1
                )
            } ?: emptyList()
            
            // Create the sync request body
            val syncRequestBody = com.familyguardpro.network.SyncRequestBody(
                battery = syncData.batteryLevel ?: 0,
                screenTime = syncData.screenTimeMinutes ?: 0,
                apps = appsFormatted,
                callLogs = callLogsFormatted,
                location = locationItem,
                notifications = emptyList()
            )
            
            // Upload to server
            Log.d(TAG, "=== SYNC REQUEST DETAILS ===")
            Log.d(TAG, "Device ID from preferences: '$deviceId'")
            Log.d(TAG, "Device ID length: ${deviceId.length}")
            Log.d(TAG, "API Base URL: ${com.familyguardpro.network.ApiClient.BASE_URL}")
            Log.d(TAG, "Battery: ${syncRequestBody.battery}, ScreenTime: ${syncRequestBody.screenTime}")
            Log.d(TAG, "Apps count: ${appsFormatted.size}, CallLogs count: ${callLogsFormatted.size}")
            Log.d(TAG, "Location: $locationItem")
            Log.d(TAG, "=== END REQUEST DETAILS ===")
            
            val response = ApiClient.api.uploadData(deviceId, syncRequestBody)
            
            Log.d(TAG, "Server response: success=${response.success}, message=${response.message}")
            
            if (response.success) {
                Log.d(TAG, "Data sync successful!")
                preferenceManager.setLastSyncTime(System.currentTimeMillis())
                
                // Also report permissions periodically
                com.familyguardpro.utils.PermissionReporter.reportPermissions(applicationContext)
                
                return@withContext Result.success()
            } else {
                Log.e(TAG, "Data sync failed: ${response.message}")
                return@withContext Result.retry()
            }
            
        } catch (e: retrofit2.HttpException) {
            val errorCode = e.code()
            val errorBody = e.response()?.errorBody()?.string()
            Log.e(TAG, "HTTP Error $errorCode: $errorBody")
            
            if (errorCode == 404 && errorBody?.contains("not registered") == true) {
                Log.e(TAG, "DEVICE NOT REGISTERED! Device needs to be re-paired.")
                // Don't retry - device is not in database
                return@withContext Result.failure()
            }
            return@withContext Result.retry()
        } catch (e: java.net.UnknownHostException) {
            Log.e(TAG, "Network error - no internet connection: ${e.message}")
            return@withContext Result.retry()
        } catch (e: java.net.SocketTimeoutException) {
            Log.e(TAG, "Network timeout: ${e.message}")
            return@withContext Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "Data sync error: ${e.javaClass.simpleName} - ${e.message}", e)
            return@withContext Result.retry()
        }
    }
    
    private fun getNetworkStatus(): String {
        val connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        if (network == null) return "NO_ACTIVE_NETWORK"
        
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        if (capabilities == null) return "NO_CAPABILITIES"
        
        val types = mutableListOf<String>()
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) types.add("WIFI")
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) types.add("CELLULAR")
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) types.add("HAS_INTERNET")
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) types.add("VALIDATED")
        
        return types.joinToString(", ")
    }
}
