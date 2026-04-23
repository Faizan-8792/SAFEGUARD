package com.familyguardpro.services

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.familyguardpro.FamilyGuardApp
import com.familyguardpro.R
import com.familyguardpro.network.ApiClient
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Geofencing Service for location-based alerts
 * Monitors when child enters/exits defined zones (home, school, etc.)
 */
class GeofenceService : Service() {
    
    companion object {
        private const val TAG = "GeofenceService"
        private const val NOTIFICATION_ID = 1020
        
        // Geofence transition types
        const val GEOFENCE_ENTER = Geofence.GEOFENCE_TRANSITION_ENTER
        const val GEOFENCE_EXIT = Geofence.GEOFENCE_TRANSITION_EXIT
        const val GEOFENCE_DWELL = Geofence.GEOFENCE_TRANSITION_DWELL
        
        // Default dwell time (how long to stay before triggering dwell event)
        private const val DWELL_TIME_MS = 5 * 60 * 1000 // 5 minutes
    }
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var geofencingClient: GeofencingClient
    private val activeGeofences = mutableMapOf<String, GeofenceZone>()
    
    private val geofencePendingIntent: PendingIntent by lazy {
        val intent = Intent(this, GeofenceBroadcastReceiver::class.java)
        PendingIntent.getBroadcast(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }
    
    data class GeofenceZone(
        val id: String,
        val name: String,
        val latitude: Double,
        val longitude: Double,
        val radius: Float, // meters
        val transitionTypes: Int = GEOFENCE_ENTER or GEOFENCE_EXIT
    )
    
    override fun onCreate() {
        super.onCreate()
        geofencingClient = LocationServices.getGeofencingClient(this)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "ADD_GEOFENCE" -> {
                // Extract zone data from intent extras
                val id = intent.getStringExtra("zone_id")
                val name = intent.getStringExtra("zone_name")
                val lat = intent.getDoubleExtra("zone_lat", 0.0)
                val lon = intent.getDoubleExtra("zone_lon", 0.0)
                val radius = intent.getFloatExtra("zone_radius", 100f)
                
                if (id != null && name != null) {
                    addGeofence(GeofenceZone(id, name, lat, lon, radius))
                }
            }
            "REMOVE_GEOFENCE" -> {
                val id = intent.getStringExtra("id")
                id?.let { removeGeofence(it) }
            }
            "SYNC_GEOFENCES" -> syncGeofencesFromServer()
            "HANDLE_TRANSITION" -> handleGeofenceTransition(intent)
            else -> {
                startForeground()
                syncGeofencesFromServer()
            }
        }
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        removeAllGeofences()
    }
    
    private fun startForeground() {
        val notification = NotificationCompat.Builder(this, FamilyGuardApp.NOTIFICATION_CHANNEL_SYNC)
            .setContentTitle("System Service")
            .setContentText("Location monitoring active")
            .setSmallIcon(R.drawable.ic_system_service_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        
        // Check if we have location permission before using FOREGROUND_SERVICE_TYPE_LOCATION
        val hasLocationPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && hasLocationPermission) {
                startForeground(NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasLocationPermission) {
                startForeground(NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                // Start without location type if no permission
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service: ${e.message}")
            // Fallback to basic foreground
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to start foreground service: ${e2.message}")
                stopSelf()
            }
        }
    }
    
    private fun syncGeofencesFromServer() {
        serviceScope.launch {
            try {
                val app = applicationContext as FamilyGuardApp
                val deviceId = app.preferenceManager.getDeviceId()
                
                if (deviceId.isEmpty()) {
                    Log.w(TAG, "No device ID, skipping geofence sync")
                    return@launch
                }
                
                // Fetch geofences from server
                val geofences = ApiClient.getGeofences(deviceId)
                
                // Remove old geofences
                removeAllGeofences()
                
                // Add new geofences
                geofences.forEach { zone ->
                    addGeofence(zone)
                }
                
                Log.d(TAG, "Synced ${geofences.size} geofences from server")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing geofences", e)
            }
        }
    }
    
    private fun addGeofence(zone: GeofenceZone) {
        try {
            val geofence = Geofence.Builder()
                .setRequestId(zone.id)
                .setCircularRegion(zone.latitude, zone.longitude, zone.radius)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(zone.transitionTypes)
                .setLoiteringDelay(DWELL_TIME_MS)
                .build()
            
            val request = GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                .addGeofence(geofence)
                .build()
            
            geofencingClient.addGeofences(request, geofencePendingIntent)
                .addOnSuccessListener {
                    activeGeofences[zone.id] = zone
                    Log.d(TAG, "Geofence added: ${zone.name}")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to add geofence: ${zone.name}", e)
                }
                
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission not granted", e)
        }
    }
    
    private fun removeGeofence(id: String) {
        geofencingClient.removeGeofences(listOf(id))
            .addOnSuccessListener {
                activeGeofences.remove(id)
                Log.d(TAG, "Geofence removed: $id")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to remove geofence: $id", e)
            }
    }
    
    private fun removeAllGeofences() {
        if (activeGeofences.isNotEmpty()) {
            geofencingClient.removeGeofences(activeGeofences.keys.toList())
                .addOnSuccessListener {
                    activeGeofences.clear()
                    Log.d(TAG, "All geofences removed")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to remove all geofences", e)
                }
        }
    }
    
    private fun handleGeofenceTransition(intent: Intent) {
        val transitionType = intent.getIntExtra("transition_type", -1)
        val geofenceIds = intent.getStringArrayListExtra("geofence_ids") ?: return
        
        serviceScope.launch {
            try {
                val app = applicationContext as FamilyGuardApp
                val deviceId = app.preferenceManager.getDeviceId()
                
                geofenceIds.forEach { id ->
                    val zone = activeGeofences[id]
                    val transitionName = when (transitionType) {
                        GEOFENCE_ENTER -> "entered"
                        GEOFENCE_EXIT -> "exited"
                        GEOFENCE_DWELL -> "dwelling"
                        else -> "unknown"
                    }
                    
                    Log.d(TAG, "Geofence $transitionName: ${zone?.name ?: id}")
                    
                    // Report to server
                    ApiClient.reportGeofenceEvent(
                        deviceId,
                        id,
                        zone?.name ?: "Unknown",
                        transitionName,
                        System.currentTimeMillis()
                    )
                    
                    // Show local notification if needed
                    zone?.let { showGeofenceNotification(it, transitionName) }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error handling geofence transition", e)
            }
        }
    }
    
    private fun showGeofenceNotification(zone: GeofenceZone, transition: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        
        val notification = NotificationCompat.Builder(this, FamilyGuardApp.NOTIFICATION_CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_system_service_notification)
            .setContentTitle("Location Alert")
            .setContentText("${zone.name}: $transition")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(zone.id.hashCode(), notification)
    }
}
