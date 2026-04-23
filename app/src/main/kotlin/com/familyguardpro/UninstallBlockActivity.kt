package com.familyguardpro

import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.familyguardpro.utils.PreferenceManager

/**
 * Full-screen blocking activity shown when uninstall attempt is detected
 * Requires parent PIN to dismiss
 */
class UninstallBlockActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "UninstallBlockActivity"
        // Default PIN if none set
        private const val DEFAULT_PIN = "0007"
    }
    
    private lateinit var preferenceManager: PreferenceManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Make this activity full screen and show over lock screen
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        
        preferenceManager = PreferenceManager(this)
        
        setContentView(R.layout.activity_uninstall_block)
        
        setupUI()
    }
    
    private fun setupUI() {
        val tvMessage = findViewById<TextView>(R.id.tvBlockMessage)
        val etPin = findViewById<EditText>(R.id.etParentPin)
        val btnVerify = findViewById<Button>(R.id.btnVerifyPin)
        val btnCancel = findViewById<Button>(R.id.btnCancel)
        
        tvMessage.text = "⚠️ Parental Control Protection\n\nThis action requires parent verification.\nEnter parent PIN to continue."
        
        btnVerify.setOnClickListener {
            val enteredPin = etPin.text.toString()
            val savedPin = preferenceManager.getParentPin() ?: DEFAULT_PIN
            
            if (enteredPin == savedPin) {
                // PIN correct - allow uninstall
                Toast.makeText(this, "Verified. You may now uninstall.", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                // Wrong PIN
                Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show()
                etPin.text.clear()
            }
        }
        
        btnCancel.setOnClickListener {
            // Go back to home screen
            val homeIntent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            homeIntent.addCategory(android.content.Intent.CATEGORY_HOME)
            homeIntent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(homeIntent)
            finish()
        }
    }
    
    override fun onBackPressed() {
        // Prevent back button - must enter PIN or cancel
        Toast.makeText(this, "Enter parent PIN to continue", Toast.LENGTH_SHORT).show()
    }
}
