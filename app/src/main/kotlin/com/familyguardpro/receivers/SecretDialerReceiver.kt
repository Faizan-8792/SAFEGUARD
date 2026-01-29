package com.familyguardpro.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.familyguardpro.ChildStatusActivity
import com.familyguardpro.MainActivity
import com.familyguardpro.utils.HideUtils
import com.familyguardpro.utils.PreferenceManager

/**
 * Secret Dialer Code Receiver
 * 
 * Opens the hidden FamilyGuard app when user dials specific secret codes:
 * - *#*#00000#*#* - Opens the app (primary code)
 * - *#*#12345#*#* - Alternative code
 * - *#*#48273#*#* - GUARD on keypad
 * 
 * This allows parents to access the hidden child monitoring app
 * when it's disguised as a system app.
 * 
 * Note: On Android 8.0+ (API 26+), apps need to handle NEW_OUTGOING_CALL
 * to intercept dialer codes as SECRET_CODE is restricted.
 */
class SecretDialerReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SecretDialerReceiver"
        
        // Secret codes that will open the app (without the *#*# prefix and #*#* suffix)
        private val SECRET_CODE_HOSTS = setOf(
            "00000",   // Primary secret code
            "12345",   // Alternative code
            "48273",   // GUARD on keypad
            "73738"    // RESET on keypad
        )
        
        // Full patterns to match in dialed numbers
        private val DIALER_PATTERNS = listOf(
            "*#*#00000#*#*",
            "*#*#12345#*#*",
            "*#*#48273#*#*",
            "*#*#73738#*#*",
            "*#00000#*",
            "*#12345#*",
            "*#48273#*"
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received broadcast: ${intent.action}")
        
        when (intent.action) {
            // Handle SECRET_CODE broadcast (Android 8.0+)
            "android.provider.Telephony.SECRET_CODE" -> {
                val host = intent.data?.host ?: return
                Log.d(TAG, "Secret code received: $host")
                
                if (SECRET_CODE_HOSTS.contains(host)) {
                    openHiddenApp(context)
                }
            }
            
            // Handle outgoing call - intercept before call is placed
            Intent.ACTION_NEW_OUTGOING_CALL,
            "android.intent.action.NEW_OUTGOING_CALL" -> {
                val dialedNumber = resultData 
                    ?: intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER) 
                    ?: return
                    
                Log.d(TAG, "Outgoing call detected: $dialedNumber")
                
                if (isSecretCode(dialedNumber)) {
                    Log.d(TAG, "Secret code match! Opening app...")
                    
                    // Cancel the call by setting result data to null
                    resultData = null
                    
                    // Open the app
                    openHiddenApp(context)
                }
            }
        }
    }

    private fun isSecretCode(dialedNumber: String): Boolean {
        val cleaned = dialedNumber.replace(" ", "").replace("-", "")
        return DIALER_PATTERNS.any { pattern ->
            cleaned.contains(pattern) || cleaned == pattern
        }
    }

    private fun openHiddenApp(context: Context) {
        Log.d(TAG, "Opening hidden app...")
        
        try {
            val preferenceManager = PreferenceManager(context)
            
            // Show app icon temporarily if hidden
            if (preferenceManager.isAppHidden()) {
                HideUtils.showAppIcon(context)
            }
            
            // Launch appropriate activity based on mode
            val targetActivity = if (preferenceManager.isChildMode()) {
                ChildStatusActivity::class.java
            } else {
                MainActivity::class.java
            }
            
            val openIntent = Intent(context, targetActivity).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            
            context.startActivity(openIntent)
            Log.d(TAG, "App opened successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app: ${e.message}")
        }
    }
}
