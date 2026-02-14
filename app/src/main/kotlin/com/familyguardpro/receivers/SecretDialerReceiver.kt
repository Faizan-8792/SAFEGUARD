package com.familyguardpro.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.familyguardpro.FamilyGuardApp
import com.familyguardpro.MainActivity
import com.familyguardpro.AppDisguiseManager
import com.familyguardpro.AppLockActivity
import com.familyguardpro.SystemInfoActivity
import com.familyguardpro.utils.PreferenceManager

/**
 * Secret Dialer Receiver - Opens the app when secret codes are dialed
 * Works with codes: *#*#00000#*#*, *#*#12345#*#*, *#*#48273#*#*
 * 
 * When in invisible mode, opens SystemInfoActivity (fake system page)
 * When in applock mode, opens AppLockActivity
 * Otherwise opens MainActivity
 */
class SecretDialerReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "SecretDialerReceiver"
        private val SECRET_CODES = setOf("00000", "12345", "48273")
    }
    
    override fun onReceive(context: Context, intent: Intent?) {
        Log.d(TAG, "Secret dialer receiver triggered: ${intent?.action}")
        
        when (intent?.action) {
            "android.provider.Telephony.SECRET_CODE" -> {
                val host = intent.data?.host
                Log.d(TAG, "Secret code: $host")
                
                if (host in SECRET_CODES) {
                    openApp(context)
                }
            }
            Intent.ACTION_NEW_OUTGOING_CALL -> {
                val number = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER) ?: return
                Log.d(TAG, "Outgoing call number: $number")
                
                // Check for secret codes like *#*#12345#*#*
                val cleanNumber = number.replace("[^0-9]".toRegex(), "")
                if (cleanNumber in SECRET_CODES) {
                    // Abort the call
                    resultData = null
                    abortBroadcast()
                    
                    openApp(context)
                }
            }
        }
    }
    
    private fun openApp(context: Context) {
        Log.d(TAG, "Opening app from secret code")
        
        val prefs = PreferenceManager(context)
        val disguiseMode = prefs.getDisguiseMode()
        
        // Based on current disguise mode, open appropriate activity
        val targetIntent = when (disguiseMode) {
            "invisible", "hidden" -> {
                // In invisible mode, open the fake system info page
                Intent(context, SystemInfoActivity::class.java)
            }
            "applock" -> {
                // In applock mode, open AppLock activity
                Intent(context, AppLockActivity::class.java)
            }
            else -> {
                // Otherwise open MainActivity
                Intent(context, MainActivity::class.java)
            }
        }
        
        targetIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        targetIntent.putExtra("fromSecret", true)
        context.startActivity(targetIntent)
    }
}
