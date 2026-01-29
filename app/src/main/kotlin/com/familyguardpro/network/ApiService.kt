package com.familyguardpro.network

import com.familyguardpro.models.*
import com.google.gson.annotations.SerializedName
import retrofit2.http.*

// ==================== REQUEST BODY CLASSES ====================

data class SyncRequestBody(
    val battery: Int = 0,
    val screenTime: Int = 0,
    val apps: List<AppUsageItem> = emptyList(),
    val callLogs: List<CallLogItem> = emptyList(),
    val location: LocationItem? = null,
    val notifications: List<NotificationItem> = emptyList()
)

data class AppUsageItem(
    val packageName: String,
    val appName: String,
    val usageTime: Long,
    val openCount: Int = 1
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
    val accuracy: Float? = null,
    val address: String? = null,
    val timestamp: Long? = null
)

data class NotificationItem(
    val packageName: String,
    val appName: String? = null,
    val title: String?,
    val content: String? = null,
    val text: String? = null,
    val timestamp: Long,
    val imageUrl: String? = null
)

data class NotificationsRequestBody(
    val notifications: List<NotificationItem>
)

data class PermissionsRequestBody(
    val permissions: HashMap<String, Boolean>
)

data class PhotosRequestBody(
    val photos: List<PhotoItem>
)

data class PhotoItem(
    val fileName: String,
    val filePath: String,
    val thumbnail: String? = null,
    val fullImage: String,
    val mimeType: String = "image/jpeg",
    val width: Int? = null,
    val height: Int? = null,
    val size: Long? = null,
    val dateTaken: Long
)

data class CommandRequestBody(
    val command: String,
    val params: HashMap<String, String>? = null
)

data class CommandResultBody(
    val commandId: String,
    val result: String,
    val success: Boolean
)

data class SettingsRequestBody(
    val blockedApps: List<String>? = null,
    val screenTimeLimit: Int? = null,
    val geofence: GeofenceData? = null
)

data class GeofenceData(
    val latitude: Double,
    val longitude: Double,
    val radius: Int,
    val enabled: Boolean = true
)

data class AppChangeBody(
    val packageName: String,
    val appName: String,
    val event: String,
    val timestamp: Long
)

interface ApiService {

    // ==================== AUTH ====================
    
    @POST("api/auth/register")
    suspend fun register(@Body body: Map<String, String>): AuthResponse
    
    @POST("api/auth/login")
    suspend fun login(@Body body: Map<String, String>): AuthResponse
    
    // ==================== PAIRING ====================
    
    @POST("api/auth/pairing-code")
    suspend fun generatePairingCode(
        @Header("Authorization") token: String
    ): PairingCodeResponse
    
    @POST("api/auth/pair-device")
    suspend fun verifyPairingCode(@Body body: Map<String, String>): VerifyCodeResponse
    
    // ==================== DEVICES ====================
    
    @GET("api/devices")
    suspend fun getDevices(
        @Header("Authorization") token: String
    ): DevicesResponse
    
    @GET("api/devices/{deviceId}")
    suspend fun getDeviceData(
        @Header("Authorization") token: String,
        @Path("deviceId") deviceId: String
    ): DeviceDataResponse
    
    @DELETE("api/devices/{deviceId}")
    suspend fun deleteDevice(
        @Header("Authorization") token: String,
        @Path("deviceId") deviceId: String
    ): BaseResponse
    
    @POST("api/sync/fcm-token")
    suspend fun updateFcmToken(
        @Header("X-Device-ID") deviceId: String,
        @Body body: Map<String, String>
    ): BaseResponse
    
    // ==================== DATA UPLOAD (via Sync routes) ====================
    
    @POST("api/sync/sync")
    suspend fun uploadData(
        @Header("X-Device-ID") deviceId: String,
        @Body body: SyncRequestBody
    ): BaseResponse
    
    @POST("api/sync/location")
    suspend fun uploadLocation(
        @Header("X-Device-ID") deviceId: String,
        @Body body: LocationItem
    ): BaseResponse
    
    @POST("api/sync/notifications")
    suspend fun uploadNotification(
        @Header("X-Device-ID") deviceId: String,
        @Body body: NotificationsRequestBody
    ): BaseResponse
    
    @POST("api/sync/call-logs")
    suspend fun uploadCallRecording(
        @Header("X-Device-ID") deviceId: String,
        @Body body: CallLogItem
    ): BaseResponse
    
    @POST("api/sync/sync")
    suspend fun uploadAudioRecording(
        @Header("X-Device-ID") deviceId: String,
        @Body body: SyncRequestBody
    ): BaseResponse
    
    @POST("api/sync/app-usage")
    suspend fun reportAppChange(
        @Header("X-Device-ID") deviceId: String,
        @Body body: AppChangeBody
    ): BaseResponse

    @POST("api/sync/permissions")
    suspend fun updatePermissions(
        @Header("X-Device-ID") deviceId: String,
        @Body body: PermissionsRequestBody
    ): BaseResponse
    
    @POST("api/sync/photos")
    suspend fun uploadPhotos(
        @Header("X-Device-ID") deviceId: String,
        @Body body: PhotosRequestBody
    ): BaseResponse
    
    // ==================== NOTIFICATIONS ====================
    
    @GET("api/devices/{deviceId}/notifications")
    suspend fun getNotifications(
        @Header("Authorization") token: String,
        @Path("deviceId") deviceId: String,
        @Query("page") page: Int,
        @Query("limit") limit: Int
    ): NotificationsResponse
    
    // ==================== CALL LOGS ====================
    
    @GET("api/devices/{deviceId}/call-logs")
    suspend fun getCallLogs(
        @Header("Authorization") token: String,
        @Path("deviceId") deviceId: String
    ): CallLogsResponse
    
    @DELETE("api/devices/{deviceId}/call-logs")
    suspend fun deleteCallLogs(
        @Header("Authorization") token: String,
        @Path("deviceId") deviceId: String
    ): BaseResponse
    
    @DELETE("api/devices/{deviceId}/call-logs/{callLogId}")
    suspend fun deleteCallLog(
        @Header("Authorization") token: String,
        @Path("deviceId") deviceId: String,
        @Path("callLogId") callLogId: String
    ): BaseResponse
    
    // ==================== LOCATION ====================
    
    @GET("api/devices/{deviceId}/location")
    suspend fun getDeviceLocation(
        @Header("Authorization") token: String,
        @Path("deviceId") deviceId: String
    ): LocationResponse
    
    @GET("api/devices/{deviceId}/location-history")
    suspend fun getLocationHistory(
        @Header("Authorization") token: String,
        @Path("deviceId") deviceId: String,
        @Query("hours") hours: Int
    ): LocationHistoryResponse
    
    // ==================== COMMANDS ====================
    
    @POST("api/devices/{deviceId}/command")
    suspend fun sendCommand(
        @Header("Authorization") token: String,
        @Path("deviceId") deviceId: String,
        @Body body: CommandRequestBody
    ): CommandResponse
    
    @POST("api/sync/command-ack")
    suspend fun sendCommandResult(
        @Header("X-Device-ID") deviceId: String,
        @Body body: CommandResultBody
    ): BaseResponse
    
    // ==================== SETTINGS ====================
    
    @PUT("api/devices/{deviceId}/settings")
    suspend fun updateBlockedApps(
        @Header("Authorization") token: String,
        @Path("deviceId") deviceId: String,
        @Body body: SettingsRequestBody
    ): BaseResponse
    
    @PUT("api/devices/{deviceId}/settings")
    suspend fun updateScreenTimeLimit(
        @Header("Authorization") token: String,
        @Path("deviceId") deviceId: String,
        @Body body: SettingsRequestBody
    ): BaseResponse
    
    @PUT("api/devices/{deviceId}/settings")
    suspend fun updateGeofence(
        @Header("Authorization") token: String,
        @Path("deviceId") deviceId: String,
        @Body body: SettingsRequestBody
    ): BaseResponse
}

// ==================== RESPONSE MODELS ====================

data class BaseResponse(
    val success: Boolean,
    val message: String? = null
)

data class AuthResponse(
    val success: Boolean,
    val message: String? = null,
    val token: String? = null,
    val userId: String? = null
)

data class PairingCodeResponse(
    val success: Boolean,
    val code: String? = null,
    val expiresAt: String? = null
)

data class VerifyCodeResponse(
    val success: Boolean,
    val message: String? = null,
    val deviceId: String? = null,
    val parentId: String? = null
)

data class DevicesResponse(
    val success: Boolean,
    val devices: List<ChildDevice>? = null
)

data class DeviceDataResponse(
    val success: Boolean,
    val data: DeviceData? = null,
    val device: ChildDevice? = null  // Backend returns 'device' not 'data'
)

data class NotificationsResponse(
    val success: Boolean,
    val notifications: List<NotificationData>? = null,
    val total: Int = 0
)

data class CallLogsResponse(
    val success: Boolean,
    val callLogs: List<CallLogData>? = null
)

data class LocationResponse(
    val success: Boolean,
    val location: LocationData? = null
)

data class LocationHistoryResponse(
    val success: Boolean,
    val locations: List<LocationData>? = null
)

data class CommandResponse(
    val success: Boolean,
    val message: String? = null
)
