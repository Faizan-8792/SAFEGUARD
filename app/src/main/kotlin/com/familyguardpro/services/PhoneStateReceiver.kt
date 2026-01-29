package com.familyguardpro.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.familyguardpro.utils.PreferenceManager

class PhoneStateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PhoneStateReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val preferenceManager = PreferenceManager(context)
        
        if (!preferenceManager.isChildMode()) return
        if (!preferenceManager.isCallRecordingEnabled()) return
        
        when (intent.action) {
            "android.intent.action.PHONE_STATE" -> {
                // Start call record service to handle call state
                val serviceIntent = Intent(context, CallRecordService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
            "android.intent.action.NEW_OUTGOING_CALL" -> {
                val number = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER)
                Log.d(TAG, "Outgoing call to: $number")
            }
        }
    }
}
