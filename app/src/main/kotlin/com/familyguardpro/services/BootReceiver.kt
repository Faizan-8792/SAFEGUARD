package com.familyguardpro.services

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.familyguardpro.utils.PreferenceManager

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Boot received: ${intent.action}")
        
        val preferenceManager = PreferenceManager(context)
        
        // Only start services if in child mode
        if (!preferenceManager.isChildMode()) {
            Log.d(TAG, "Not in child mode, skipping")
            return
        }
        
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_REBOOT -> {
                startServices(context)
            }
        }
    }

    private fun startServices(context: Context) {
        try {
            // Start Location Service
            val locationIntent = Intent(context, LocationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(locationIntent)
            } else {
                context.startService(locationIntent)
            }
            
            // Schedule DataSync Worker
            DataSyncWorker.schedulePeriodicSync(context)
            
            // Request rebind for NotificationListenerService
            try {
                val componentName = ComponentName(context, NotificationListener::class.java)
                android.service.notification.NotificationListenerService.requestRebind(componentName)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to rebind NotificationListener", e)
            }
            
            Log.d(TAG, "Services started on boot")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting services on boot", e)
        }
    }
}
