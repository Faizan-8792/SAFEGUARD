package com.familyguardpro.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import com.familyguardpro.FamilyGuardApp

class PhoneStateReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "PhoneStateReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? FamilyGuardApp
        if (app?.preferenceManager?.isChildMode() != true) {
            return
        }
        
        when (intent.action) {
            TelephonyManager.ACTION_PHONE_STATE_CHANGED -> {
                val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                
                Log.d(TAG, "Phone state changed: $state, number: $number")
                
                when (state) {
                    TelephonyManager.EXTRA_STATE_RINGING -> {
                        Log.d(TAG, "Incoming call from $number")
                        // Start call recording if enabled
                        if (app?.preferenceManager?.isCallRecordingEnabled() == true) {
                            context.startForegroundService(Intent(context, CallRecordService::class.java).apply {
                                action = "START"
                                putExtra("phoneNumber", number)
                            })
                        }
                    }
                    TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                        Log.d(TAG, "Call answered")
                    }
                    TelephonyManager.EXTRA_STATE_IDLE -> {
                        Log.d(TAG, "Call ended")
                        // Stop call recording
                        context.startService(Intent(context, CallRecordService::class.java).apply {
                            action = "STOP"
                        })
                    }
                }
            }
            Intent.ACTION_NEW_OUTGOING_CALL -> {
                val number = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER)
                Log.d(TAG, "Outgoing call to $number")
                
                // Start call recording for outgoing calls if enabled
                if (app?.preferenceManager?.isCallRecordingEnabled() == true) {
                    context.startForegroundService(Intent(context, CallRecordService::class.java).apply {
                        action = "START"
                        putExtra("phoneNumber", number)
                    })
                }
            }
        }
    }
}
