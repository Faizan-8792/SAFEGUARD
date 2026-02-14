package com.familyguardpro.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

/**
 * Broadcast receiver for geofence transition events
 */
class GeofenceBroadcastReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "GeofenceReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent)
        
        if (geofencingEvent == null) {
            Log.e(TAG, "GeofencingEvent is null")
            return
        }
        
        if (geofencingEvent.hasError()) {
            val errorMessage = getErrorString(geofencingEvent.errorCode)
            Log.e(TAG, "Geofence error: $errorMessage")
            return
        }
        
        val geofenceTransition = geofencingEvent.geofenceTransition
        
        if (geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER ||
            geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT ||
            geofenceTransition == Geofence.GEOFENCE_TRANSITION_DWELL) {
            
            val triggeringGeofences = geofencingEvent.triggeringGeofences
            val geofenceIds = triggeringGeofences?.map { it.requestId } ?: emptyList()
            
            Log.d(TAG, "Geofence transition: $geofenceTransition, zones: $geofenceIds")
            
            // Forward to GeofenceService
            val serviceIntent = Intent(context, GeofenceService::class.java).apply {
                action = "HANDLE_TRANSITION"
                putExtra("transition_type", geofenceTransition)
                putStringArrayListExtra("geofence_ids", ArrayList(geofenceIds))
            }
            
            context.startService(serviceIntent)
        } else {
            Log.e(TAG, "Unknown geofence transition: $geofenceTransition")
        }
    }
    
    private fun getErrorString(errorCode: Int): String {
        return when (errorCode) {
            1 -> "GEOFENCE_NOT_AVAILABLE"
            2 -> "GEOFENCE_TOO_MANY_GEOFENCES"
            3 -> "GEOFENCE_TOO_MANY_PENDING_INTENTS"
            else -> "UNKNOWN_ERROR"
        }
    }
}
