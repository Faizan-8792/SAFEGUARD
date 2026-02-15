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
                
                Log.w(TAG, "Phone state changed: $state, number: $number")
                
                when (state) {
                    TelephonyManager.EXTRA_STATE_RINGING -> {
                        Log.w(TAG, "Incoming call from $number")
                        // Start/ensure call recording service is running with phone info
                        if (app?.preferenceManager?.isCallRecordingEnabled() == true) {
                            context.startForegroundService(Intent(context, CallRecordService::class.java).apply {
                                putExtra("phoneNumber", number ?: "")
                                putExtra("callType", "incoming")
                            })
                        }
                    }
                    TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                        Log.w(TAG, "Call answered/active")
                        // Just ensure call service is running - do NOT send callType here
                        // because it overwrites outgoing→incoming. callType was already set 
                        // by RINGING (incoming) or ACTION_NEW_OUTGOING_CALL (outgoing)
                        if (app?.preferenceManager?.isCallRecordingEnabled() == true) {
                            context.startForegroundService(Intent(context, CallRecordService::class.java).apply {
                                if (!number.isNullOrEmpty()) {
                                    putExtra("phoneNumber", number)
                                }
                                // No callType here - let the existing one stay
                            })
                        }
                    }
                    TelephonyManager.EXTRA_STATE_IDLE -> {
                        Log.w(TAG, "Call ended")
                        // Stop call recording
                        context.startService(Intent(context, CallRecordService::class.java).apply {
                            action = "STOP"
                        })
                    }
                }
            }
            Intent.ACTION_NEW_OUTGOING_CALL -> {
                val number = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER)
                Log.w(TAG, "Outgoing call to $number")
                
                // Start call recording service with outgoing call info
                if (app?.preferenceManager?.isCallRecordingEnabled() == true) {
                    context.startForegroundService(Intent(context, CallRecordService::class.java).apply {
                        putExtra("phoneNumber", number ?: "")
                        putExtra("callType", "outgoing")
                    })
                }
            }
        }
    }
}
