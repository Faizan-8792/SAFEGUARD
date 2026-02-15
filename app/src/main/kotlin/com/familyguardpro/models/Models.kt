package com.familyguardpro.models

import com.google.gson.annotations.SerializedName

// Auth Models
data class AuthResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("token") val token: String?,
    @SerializedName("user") val user: User?,
    @SerializedName("error") val error: ErrorResponse?
)

data class User(
    @SerializedName("_id") val id: String,
    @SerializedName("email") val email: String,
    @SerializedName("name") val name: String?
)

data class ErrorResponse(
    @SerializedName("code") val code: String?,
    @SerializedName("message") val message: String?,
    @SerializedName("detail") val detail: String?
)

// Pairing Models
data class PairingResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("code") val code: String?,
    @SerializedName("expiresAt") val expiresAt: Long?
) {
    // Alias for easier access
    val pairingCode: String? get() = code
}

// Device Models
data class Device(
    @SerializedName("_id") val id: String,
    @SerializedName("deviceName") val deviceName: String,
    @SerializedName("deviceModel") val deviceModel: String?,
    @SerializedName("androidVersion") val androidVersion: String?,
    @SerializedName("androidId") val androidId: String?,
    @SerializedName("fcmToken") val fcmToken: String?,
    @SerializedName("parentId") val parentId: String?,
    @SerializedName("isOnline") val isOnline: Boolean?,
    @SerializedName("lastSeen") val lastSeen: Long?,
    @SerializedName("batteryLevel") val batteryLevel: Int?,
    @SerializedName("location") val location: LocationData?,
    @SerializedName("screenTime") val screenTime: Long?,
    @SerializedName("createdAt") val createdAt: String?,
    @SerializedName("updatedAt") val updatedAt: String?,
    @SerializedName("permissions") val permissions: DevicePermissions?
)

data class DeviceResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("device") val device: Device?,
    @SerializedName("deviceId") val deviceId: String?,
    @SerializedName("parentId") val parentId: String?,
    @SerializedName("message") val message: String?
)

data class DeviceInfo(
    @SerializedName("deviceName") val deviceName: String,
    @SerializedName("deviceModel") val deviceModel: String,
    @SerializedName("androidVersion") val androidVersion: String,
    @SerializedName("sdkVersion") val sdkVersion: Int = 0,
    @SerializedName("androidId") val androidId: String
)

data class DevicePermissions(
    @SerializedName("location") val location: Boolean?,
    @SerializedName("backgroundLocation") val backgroundLocation: Boolean?,
    @SerializedName("camera") val camera: Boolean?,
    @SerializedName("microphone") val microphone: Boolean?,
    @SerializedName("callLog") val callLog: Boolean?,
    @SerializedName("notifications") val notifications: Boolean?,
    @SerializedName("usageStats") val usageStats: Boolean?,
    @SerializedName("overlay") val overlay: Boolean?,
    @SerializedName("batteryOptimization") val batteryOptimization: Boolean?,
    @SerializedName("deviceAdmin") val deviceAdmin: Boolean?,
    @SerializedName("accessibility") val accessibility: Boolean?,
    @SerializedName("storage") val storage: Boolean?
)

// Location Models
data class LocationData(
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double,
    @SerializedName("accuracy") val accuracy: Float?,
    @SerializedName("timestamp") val timestamp: Long,
    @SerializedName("address") val address: String?
)

// Call Log Models
data class CallLogEntry(
    @SerializedName("_id") val id: String?,
    @SerializedName("number") val number: String,
    @SerializedName("name") val name: String?,
    @SerializedName("type") val type: String, // incoming, outgoing, missed, rejected
    @SerializedName("duration") val duration: Long,
    @SerializedName("timestamp") val timestamp: Long,
    @SerializedName("audioUrl") val audioUrl: String?
)

// App Usage Models
data class AppUsageData(
    @SerializedName("packageName") val packageName: String,
    @SerializedName("appName") val appName: String,
    @SerializedName("usageTime") val usageTime: Long,
    @SerializedName("lastUsed") val lastUsed: Long
)

// Installed App Data (for Device Owner hide/uninstall feature)
data class InstalledAppData(
    @SerializedName("packageName") val packageName: String,
    @SerializedName("appName") val appName: String,
    @SerializedName("isSystemApp") val isSystemApp: Boolean,
    @SerializedName("isEnabled") val isEnabled: Boolean
)

// Photo Models
data class PhotoData(
    @SerializedName("_id") val id: String?,
    @SerializedName("name") val name: String,
    @SerializedName("timestamp") val timestamp: Long,
    @SerializedName("size") val size: Long,
    @SerializedName("uri") val uri: String?,
    @SerializedName("path") val path: String?,
    @SerializedName("url") val url: String? = null
)

data class GalleryResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("photos") val photos: List<PhotoData>,
    @SerializedName("total") val total: Int,
    @SerializedName("page") val page: Int
)

// Notification Models
data class NotificationData(
    @SerializedName("_id") val id: String? = null,
    @SerializedName("deviceId") val deviceId: String,
    @SerializedName("packageName") val packageName: String,
    @SerializedName("appName") val appName: String,
    @SerializedName("title") val title: String?,
    @SerializedName("text") val text: String?,
    @SerializedName("timestamp") val timestamp: Long,
    @SerializedName("icon") val icon: String? = null,
    @SerializedName("image") val image: String? = null
) {
    // Alias for compatibility
    val content: String? get() = text
}

data class NotificationListResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("notifications") val notifications: List<NotificationData>,
    @SerializedName("total") val total: Int,
    @SerializedName("page") val page: Int
)

// Sync Models
data class SyncData(
    @SerializedName("deviceId") val deviceId: String,
    @SerializedName("timestamp") val timestamp: Long,
    @SerializedName("batteryLevel") val batteryLevel: Int,
    @SerializedName("location") val location: LocationData?,
    @SerializedName("callLogs") val callLogs: List<CallLogEntry>,
    @SerializedName("appUsage") val appUsage: List<AppUsageData>,
    @SerializedName("photos") val photos: List<PhotoData>,
    @SerializedName("deviceInfo") val deviceInfo: DeviceInfo?,
    @SerializedName("fcmToken") val fcmToken: String? = null,
    @SerializedName("permissions") val permissions: DevicePermissions? = null
)

data class SyncResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String?,
    @SerializedName("commands") val commands: List<PendingCommand>?
)

// Command Models
data class CommandResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String?
)

data class PendingCommand(
    @SerializedName("command") val command: String,
    @SerializedName("params") val params: Map<String, Any>?
)

// Audio Models
data class AudioFile(
    @SerializedName("_id") val id: String?,
    @SerializedName("deviceId") val deviceId: String,
    @SerializedName("type") val type: String, // call_recording, live_listen
    @SerializedName("duration") val duration: Long,
    @SerializedName("timestamp") val timestamp: Long,
    @SerializedName("url") val url: String?,
    @SerializedName("callNumber") val callNumber: String? = null,
    @SerializedName("callName") val callName: String? = null
)

// SMS Models
data class SmsData(
    @SerializedName("address") val address: String,
    @SerializedName("contactName") val contactName: String?,
    @SerializedName("body") val body: String,
    @SerializedName("type") val type: String, // inbox, sent, draft, outbox
    @SerializedName("date") val date: Long,
    @SerializedName("read") val read: Boolean = false
)

// Keystroke Monitoring Models
data class KeystrokeData(
    @SerializedName("deviceId") val deviceId: String,
    @SerializedName("sessionId") val sessionId: String,
    @SerializedName("timestamp") val timestamp: Long,
    @SerializedName("packageName") val packageName: String,
    @SerializedName("appName") val appName: String,
    @SerializedName("contactName") val contactName: String,
    @SerializedName("textContent") val textContent: String,
    @SerializedName("fieldType") val fieldType: String // message, search, password, comment, text
)

data class KeystrokeSession(
    @SerializedName("_id") val id: String?,
    @SerializedName("deviceId") val deviceId: String,
    @SerializedName("sessionId") val sessionId: String,
    @SerializedName("appPackage") val appPackage: String,
    @SerializedName("appName") val appName: String,
    @SerializedName("contactName") val contactName: String,
    @SerializedName("messages") val messages: List<KeystrokeMessage>,
    @SerializedName("messageCount") val messageCount: Int,
    @SerializedName("firstMessageTime") val firstMessageTime: Long,
    @SerializedName("lastMessageTime") val lastMessageTime: Long,
    @SerializedName("riskLevel") val riskLevel: String, // LOW, MEDIUM, HIGH
    @SerializedName("flaggedKeywords") val flaggedKeywords: List<String>,
    @SerializedName("sentiment") val sentiment: String // Positive, Neutral, Negative
)

data class KeystrokeMessage(
    @SerializedName("timestamp") val timestamp: Long,
    @SerializedName("text") val text: String,
    @SerializedName("fieldType") val fieldType: String
)

data class KeystrokeBatchRequest(
    @SerializedName("deviceId") val deviceId: String,
    @SerializedName("keystrokes") val keystrokes: List<KeystrokeData>
)

data class KeystrokeSessionsResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("sessions") val sessions: List<KeystrokeSession>,
    @SerializedName("total") val total: Int,
    @SerializedName("stats") val stats: KeystrokeStats?
)

data class KeystrokeStats(
    @SerializedName("totalSessions") val totalSessions: Int,
    @SerializedName("totalMessages") val totalMessages: Int,
    @SerializedName("highRiskCount") val highRiskCount: Int,
    @SerializedName("mediumRiskCount") val mediumRiskCount: Int
)
