package com.familyguardpro

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Secret Activity - Entry point for hidden app access
 * This is triggered via:
 * - URI: familyguard://open
 * - Dialer codes: *#*#00000#*#*, *#*#12345#*#*, *#*#48273#*#*
 * 
 * When in invisible mode, this opens the fake SystemInfoActivity
 * which shows real device info and has a 6-tap secret entry
 */
class SecretActivity : AppCompatActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val app = application as FamilyGuardApp
        val prefs = app.preferenceManager
        val disguiseMode = prefs.getDisguiseMode()
        val isChild = prefs.isChildMode()
        
        when {
            // If in invisible mode, open the fake system info page
            disguiseMode == "invisible" || disguiseMode == "hidden" -> {
                startActivity(Intent(this, SystemInfoActivity::class.java))
            }
            // If in applock mode, open AppLock activity
            disguiseMode == "applock" -> {
                startActivity(Intent(this, AppLockActivity::class.java))
            }
            // If child mode, go to child status
            isChild -> {
                startActivity(Intent(this, ChildStatusActivity::class.java))
            }
            // Otherwise go to main activity
            else -> {
                startActivity(Intent(this, MainActivity::class.java))
            }
        }
        
        finish()
    }
}
