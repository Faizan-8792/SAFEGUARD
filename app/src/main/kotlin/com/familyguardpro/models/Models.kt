package com.familyguardpro.models

import android.os.Parcel
import android.os.Parcelable
import com.google.gson.annotations.SerializedName

// ==================== Device Models ====================

data class ChildDevice(
    @SerializedName("_id", alternate = ["id"])
    val id: String = "",
    val name: String = "",
    val model: String? = null,
    val lastSeen: Long = 0,  // Changed to Long - timestamp in milliseconds from backend
    @SerializedName("battery", alternate = ["batteryLevel"])
    val batteryLevel: Int = 0,
    val screenTime: Int = 0,  // Screen time in minutes
    val isOnline: Boolean = false
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readString(),
        parcel.readLong(),
        parcel.readInt(),
        parcel.readInt(),
        parcel.readByte() != 0.toByte()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(id)
        parcel.writeString(name)
        parcel.writeString(model)
        parcel.writeLong(lastSeen)
        parcel.writeInt(batteryLevel)
        parcel.writeInt(screenTime)
        parcel.writeByte(if (isOnline) 1 else 0)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<ChildDevice> {
        override fun createFromParcel(parcel: Parcel): ChildDevice = ChildDevice(parcel)
        override fun newArray(size: Int): Array<ChildDevice?> = arrayOfNulls(size)
    }
}

data class DeviceData(
    val batteryLevel: Int = 0,
    val screenTimeMinutes: Int = 0,
    val lastLocation: String? = null,
    val lastLocationTime: Long? = null,
    val newNotifications: Int = 0,
    val callRecordingEnabled: Boolean = false,
    val lastSyncTime: Long? = null
)

// ==================== Sync Data ====================

data class SyncData(
    val batteryLevel: Int,
    val screenTimeMinutes: Int,
    val appUsage: List<AppUsageData>,
    val callLogs: List<CallLogData>,
    val lastLocation: LocationData?,
    val webHistory: List<Map<String, Any>>,
    val installedApps: List<InstalledApp>
)

data class AppUsageData(
    val packageName: String,
    val appName: String,
    val usageTimeMinutes: Int,
    val lastUsed: Long
)

// ==================== Call Log ====================

data class CallLogData(
    val id: String,
    val phoneNumber: String,
    val contactName: String? = null,
    val callType: String, // incoming, outgoing, missed, rejected
    val timestamp: Long,
    val durationSeconds: Int,
    val audioUrl: String? = null
)

// ==================== Notification ====================

data class NotificationData(
    val id: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val subText: String? = null,
    val timestamp: Long,
    val iconBase64: String? = null,
    val imageBase64: String? = null,
    val isOngoing: Boolean = false,
    val category: String? = null
)

// ==================== Location ====================

data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float = 0f,
    val altitude: Double = 0.0,
    val speed: Float = 0f,
    val address: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

// ==================== Installed Apps ====================

data class InstalledApp(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val installedTime: Long,
    val lastUpdated: Long
)

// ==================== Geofence ====================

data class Geofence(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float,
    val isActive: Boolean = true
)

// ==================== Screen Time ====================

data class ScreenTimeLimit(
    val packageName: String? = null, // null means all apps
    val limitMinutes: Int,
    val usedMinutes: Int = 0,
    val isActive: Boolean = true
)
