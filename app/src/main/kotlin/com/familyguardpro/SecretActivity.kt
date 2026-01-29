package com.familyguardpro

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.familyguardpro.utils.HideUtils
import com.familyguardpro.utils.PreferenceManager

class SecretActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val preferenceManager = PreferenceManager(this)
        
        // Show the app icon temporarily
        if (preferenceManager.isAppHidden()) {
            HideUtils.showAppIcon(this)
        }
        
        // Launch main activity
        val intent = if (preferenceManager.isChildMode()) {
            Intent(this, ChildStatusActivity::class.java)
        } else {
            Intent(this, MainActivity::class.java)
        }
        
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
        finish()
    }
}
