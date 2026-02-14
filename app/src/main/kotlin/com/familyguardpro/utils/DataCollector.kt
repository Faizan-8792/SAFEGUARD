package com.familyguardpro.utils

import android.Manifest
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.CallLog
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import com.familyguardpro.models.*
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DataCollector(private val context: Context) {
    
    companion object {
        private const val TAG = "DataCollector"
        private const val HOURS_24 = 24 * 60 * 60 * 1000L
        private const val DAYS_7 = 7 * 24 * 60 * 60 * 1000L // 7 days for photos
    }
    
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    
    // Collect all data for sync
    suspend fun collectAllData(): SyncData {
        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        
        return SyncData(
            deviceId = deviceId,
            timestamp = System.currentTimeMillis(),
            batteryLevel = getBatteryLevel(),
            location = getCurrentLocation(),
            callLogs = getCallLogs(),
            appUsage = getAppUsage(),
            photos = getRecentPhotos(),
            deviceInfo = getDeviceInfo()
        )
    }
    
    // Battery level
    fun getBatteryLevel(): Int {
        val batteryIntent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        
        return batteryIntent?.let { intent ->
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) {
                (level * 100 / scale)
            } else {
                -1
            }
        } ?: -1
    }
    
    // Location
    suspend fun getCurrentLocation(): LocationData? {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Location permission not granted")
            return null
        }
        
        return suspendCancellableCoroutine { continuation ->
            val cancellationTokenSource = CancellationTokenSource()
            
            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                cancellationTokenSource.token
            ).addOnSuccessListener { location: Location? ->
                if (location != null) {
                    continuation.resume(
                        LocationData(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            accuracy = location.accuracy,
                            timestamp = location.time,
                            address = null // Could be reverse geocoded
                        )
                    )
                } else {
                    // Try last known location
                    getLastKnownLocation()?.let {
                        continuation.resume(it)
                    } ?: continuation.resume(null)
                }
            }.addOnFailureListener { e ->
                Log.e(TAG, "Error getting location", e)
                continuation.resume(null)
            }
            
            continuation.invokeOnCancellation {
                cancellationTokenSource.cancel()
            }
        }
    }
    
    private fun getLastKnownLocation(): LocationData? {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        
        for (provider in providers) {
            try {
                val location = locationManager.getLastKnownLocation(provider)
                if (location != null) {
                    return LocationData(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracy = location.accuracy,
                        timestamp = location.time,
                        address = null
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error getting last known location from $provider", e)
            }
        }
        
        return null
    }
    
    // Call logs
    fun getCallLogs(): List<CallLogEntry> {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Call log permission not granted")
            return emptyList()
        }
        
        val callLogs = mutableListOf<CallLogEntry>()
        val cutoffTime = System.currentTimeMillis() - HOURS_24
        
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DURATION,
            CallLog.Calls.DATE
        )
        
        val selection = "${CallLog.Calls.DATE} > ?"
        val selectionArgs = arrayOf(cutoffTime.toString())
        val sortOrder = "${CallLog.Calls.DATE} DESC"
        
        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(CallLog.Calls._ID)
                val numberIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val nameIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                val typeIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                val durationIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                val dateIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                
                while (cursor.moveToNext()) {
                    val type = when (cursor.getInt(typeIndex)) {
                        CallLog.Calls.INCOMING_TYPE -> "incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                        CallLog.Calls.MISSED_TYPE -> "missed"
                        CallLog.Calls.REJECTED_TYPE -> "rejected"
                        else -> "unknown"
                    }
                    
                    callLogs.add(
                        CallLogEntry(
                            id = cursor.getLong(idIndex).toString(),
                            number = cursor.getString(numberIndex) ?: "Unknown",
                            name = cursor.getString(nameIndex),
                            type = type,
                            duration = cursor.getLong(durationIndex),
                            timestamp = cursor.getLong(dateIndex),
                            audioUrl = null
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading call logs", e)
        }
        
        return callLogs
    }
    
    // App usage stats
    fun getAppUsage(): List<AppUsageData> {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyList()
        
        val endTime = System.currentTimeMillis()
        val startTime = endTime - HOURS_24
        
        val usageStatsList = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startTime,
            endTime
        )
        
        if (usageStatsList.isNullOrEmpty()) {
            Log.w(TAG, "No usage stats available")
            return emptyList()
        }
        
        val packageManager = context.packageManager
        
        return usageStatsList
            .filter { it.totalTimeInForeground > 0 }
            .sortedByDescending { it.totalTimeInForeground }
            .take(50)
            .mapNotNull { usageStats ->
                try {
                    val appInfo = packageManager.getApplicationInfo(usageStats.packageName, 0)
                    val appName = packageManager.getApplicationLabel(appInfo).toString()
                    
                    AppUsageData(
                        packageName = usageStats.packageName,
                        appName = appName,
                        usageTime = usageStats.totalTimeInForeground,
                        lastUsed = usageStats.lastTimeUsed
                    )
                } catch (e: PackageManager.NameNotFoundException) {
                    null
                }
            }
    }
    
    // Total screen time (today - resets at midnight)
    fun getScreenTime(): Long {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return 0L
        
        // Get start of today (midnight)
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDay = calendar.timeInMillis
        val endTime = System.currentTimeMillis()
        
        val usageStatsList = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startOfDay,
            endTime
        )
        
        return usageStatsList?.sumOf { it.totalTimeInForeground } ?: 0L
    }
    
    // Get photos with configurable time range (defaults to 2 years for full sync)
    fun getRecentPhotos(daysBack: Int = 730): List<PhotoData> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Media images permission not granted")
                return emptyList()
            }
        } else {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Storage permission not granted")
                return emptyList()
            }
        }
        
        val photos = mutableListOf<PhotoData>()
        // Calculate cutoff based on days back (default 2 years = 730 days)
        val cutoffTime = (System.currentTimeMillis() - (daysBack.toLong() * 24 * 60 * 60 * 1000)) / 1000
        
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATA
        )
        
        val selection = "${MediaStore.Images.Media.DATE_ADDED} > ?"
        val selectionArgs = arrayOf(cutoffTime.toString())
        // Sort by newest first - this ensures we sync latest photos first
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        
        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val dataIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                
                // Get up to 1000 photos (server will filter based on quota)
                while (cursor.moveToNext() && photos.size < 1000) {
                    val id = cursor.getLong(idIndex)
                    val contentUri = Uri.withAppendedPath(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id.toString()
                    )
                    
                    photos.add(
                        PhotoData(
                            id = id.toString(),
                            name = cursor.getString(nameIndex) ?: "Unknown",
                            timestamp = cursor.getLong(dateIndex) * 1000,
                            size = cursor.getLong(sizeIndex),
                            uri = contentUri.toString(),
                            path = cursor.getString(dataIndex)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading photos", e)
        }
        
        Log.d(TAG, "Found ${photos.size} photos from last $daysBack days")
        return photos
    }
    
    /**
     * Get photos within a specific date range from the device gallery
     * Uses DATE_TAKEN for accurate photo capture time, with DATE_ADDED fallback
     * Only accesses photos matching the specified date range - no full gallery scan
     * 
     * @param startDateMs Start date in milliseconds (epoch time)
     * @param endDateMs End date in milliseconds (epoch time)
     * @return List of photos taken between startDate and endDate
     */
    fun getPhotosInDateRange(startDateMs: Long, endDateMs: Long): List<PhotoData> {
        // Check permissions based on Android version
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "READ_MEDIA_IMAGES permission not granted")
                return emptyList()
            }
        } else {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "READ_EXTERNAL_STORAGE permission not granted")
                return emptyList()
            }
        }
        
        val photos = mutableListOf<PhotoData>()
        
        // DATE_TAKEN is stored in milliseconds, DATE_ADDED is stored in seconds
        val startMs = startDateMs
        val endMs = endDateMs
        val startSec = startDateMs / 1000
        val endSec = endDateMs / 1000
        
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        Log.d(TAG, "=== DATE RANGE FILTER SYNC ===")
        Log.d(TAG, "Start: ${sdf.format(java.util.Date(startDateMs))} ($startMs ms)")
        Log.d(TAG, "End: ${sdf.format(java.util.Date(endDateMs))} ($endMs ms)")
        
        // Projection fields as per specification
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,     // Primary: actual capture time (milliseconds)
            MediaStore.Images.Media.DATE_ADDED,     // Fallback: file added time (seconds)
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATA            // File path for source detection
        )
        
        // Query using DATE_TAKEN (milliseconds) with OR fallback to DATE_ADDED (seconds)
        // This ensures we catch photos whether they have EXIF data or not
        val selection = """
            (${MediaStore.Images.Media.DATE_TAKEN} >= ? AND ${MediaStore.Images.Media.DATE_TAKEN} <= ?)
            OR
            (${MediaStore.Images.Media.DATE_TAKEN} = 0 AND ${MediaStore.Images.Media.DATE_ADDED} >= ? AND ${MediaStore.Images.Media.DATE_ADDED} <= ?)
        """.trimIndent()
        
        val selectionArgs = arrayOf(
            startMs.toString(),   // DATE_TAKEN start (milliseconds)
            endMs.toString(),     // DATE_TAKEN end (milliseconds)
            startSec.toString(),  // DATE_ADDED start (seconds) - fallback
            endSec.toString()     // DATE_ADDED end (seconds) - fallback
        )
        
        // Sort by DATE_TAKEN DESC (newest first within range)
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        
        Log.d(TAG, "Executing MediaStore query with date filter...")
        
        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateTakenIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val dateAddedIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val dataIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                
                Log.d(TAG, "MediaStore query returned ${cursor.count} photos matching date range")
                
                // Get all photos in the date range (up to 2000 for larger ranges)
                while (cursor.moveToNext() && photos.size < 2000) {
                    val id = cursor.getLong(idIndex)
                    
                    // Use ContentUris.withAppendedId for proper URI resolution (as per specification)
                    val contentUri = android.content.ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    
                    // Use DATE_TAKEN if available (> 0), otherwise fall back to DATE_ADDED
                    val dateTaken = cursor.getLong(dateTakenIndex)
                    val dateAdded = cursor.getLong(dateAddedIndex) * 1000 // Convert to ms
                    val timestamp = if (dateTaken > 0) dateTaken else dateAdded
                    
                    val fileName = cursor.getString(nameIndex) ?: "Unknown"
                    val filePath = cursor.getString(dataIndex) ?: ""
                    
                    photos.add(
                        PhotoData(
                            id = id.toString(),
                            name = fileName,
                            timestamp = timestamp,
                            size = cursor.getLong(sizeIndex),
                            uri = contentUri.toString(),
                            path = filePath
                        )
                    )
                    
                    // Log first few photos for debugging
                    if (photos.size <= 3) {
                        Log.d(TAG, "Photo ${photos.size}: $fileName, dateTaken=$dateTaken, dateAdded=$dateAdded, timestamp=${sdf.format(java.util.Date(timestamp))}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading photos in date range", e)
        }
        
        Log.d(TAG, "=== RESULT: ${photos.size} photos will be synced from date range ===")
        return photos
    }
    
    // Device info
    fun getDeviceInfo(): DeviceInfo {
        return DeviceInfo(
            deviceName = Build.MODEL,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = Build.VERSION.RELEASE,
            sdkVersion = Build.VERSION.SDK_INT,
            androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        )
    }
    
    // SMS messages
    fun getSmsMessages(hoursBack: Int = 48): List<SmsData> {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "SMS permission not granted")
            return emptyList()
        }
        
        val smsList = mutableListOf<SmsData>()
        val cutoffTime = System.currentTimeMillis() - (hoursBack * 60 * 60 * 1000L)
        
        val projection = arrayOf(
            "_id",
            "address",
            "body",
            "type",
            "date",
            "read"
        )
        
        val selection = "date > ?"
        val selectionArgs = arrayOf(cutoffTime.toString())
        val sortOrder = "date DESC"
        
        try {
            // Read inbox
            context.contentResolver.query(
                Uri.parse("content://sms"),
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val addressIndex = cursor.getColumnIndexOrThrow("address")
                val bodyIndex = cursor.getColumnIndexOrThrow("body")
                val typeIndex = cursor.getColumnIndexOrThrow("type")
                val dateIndex = cursor.getColumnIndexOrThrow("date")
                val readIndex = cursor.getColumnIndexOrThrow("read")
                
                while (cursor.moveToNext() && smsList.size < 200) {
                    val type = when (cursor.getInt(typeIndex)) {
                        1 -> "inbox"
                        2 -> "sent"
                        3 -> "draft"
                        4 -> "outbox"
                        else -> "inbox"
                    }
                    
                    val address = cursor.getString(addressIndex) ?: "Unknown"
                    val contactName = getContactName(address)
                    
                    smsList.add(
                        SmsData(
                            address = address,
                            contactName = contactName,
                            body = cursor.getString(bodyIndex) ?: "",
                            type = type,
                            date = cursor.getLong(dateIndex),
                            read = cursor.getInt(readIndex) == 1
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading SMS", e)
        }
        
        return smsList
    }
    
    // Get contact name from phone number
    private fun getContactName(phoneNumber: String): String? {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        
        try {
            val uri = Uri.withAppendedPath(
                android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(0)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error getting contact name", e)
        }
        
        return null
    }
}
