package com.familyguardpro.deviceowner

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.familyguardpro.R
import com.familyguardpro.SetupActivity
import com.google.android.material.card.MaterialCardView

/**
 * ModeSelectionActivity - Shown during initial setup to let parent choose device mode.
 * 
 * Options:
 * 1. Child Mode (default) - Standard monitoring with manual permissions
 * 2. Device Owner Mode - Enhanced monitoring with silent permissions (requires factory-reset provisioning)
 * 
 * This activity appears BEFORE SetupActivity when the app is first launched
 * on an unprovisioned device.
 */
class ModeSelectionActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ModeSelectionActivity"
        const val EXTRA_FROM_SETUP = "from_setup"
        
        fun createIntent(context: Context): Intent {
            return Intent(context, ModeSelectionActivity::class.java)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Check if device is already a Device Owner (provisioned via QR/ADB)
        val doManager = DeviceOwnerManager.getInstance(this)
        if (doManager.isDeviceOwner()) {
            Log.d(TAG, "Device is already Device Owner - proceeding to DO setup")
            proceedWithDeviceOwnerMode()
            return
        }
        
        setContentView(R.layout.activity_mode_selection)
        
        setupUI()
    }

    private fun setupUI() {
        // Child Mode card
        val childModeCard = findViewById<MaterialCardView>(R.id.cardChildMode)
        val childModeButton = findViewById<Button>(R.id.btnSelectChildMode)
        
        // Device Owner Mode card
        val doModeCard = findViewById<MaterialCardView>(R.id.cardDeviceOwnerMode)
        val doModeButton = findViewById<Button>(R.id.btnSelectDeviceOwnerMode)
        
        // Info text
        val infoText = findViewById<TextView>(R.id.tvModeInfo)
        
        childModeCard?.setOnClickListener { selectChildMode() }
        childModeButton?.setOnClickListener { selectChildMode() }
        
        doModeCard?.setOnClickListener { selectDeviceOwnerMode() }
        doModeButton?.setOnClickListener { selectDeviceOwnerMode() }
        
        // Show Device Owner explanation
        infoText?.text = buildString {
            append("Choose how to set up this child device:\n\n")
            append("• Child Mode: Standard setup with manual permission granting. ")
            append("Works on any device without factory reset.\n\n")
            append("• Device Owner Mode: Enhanced control with silent permission granting, ")
            append("app hiding, and uninstall protection. Requires factory reset during setup.")
        }
    }

    private fun selectChildMode() {
        Log.d(TAG, "Child Mode selected")
        
        // Save mode preference
        val prefs = getSharedPreferences("do_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(DeviceOwnerManager.PREF_DO_MODE, false)
            .apply()
        
        // Go to standard setup
        val intent = Intent(this, SetupActivity::class.java)
        startActivity(intent)
        finish()
    }

    private fun selectDeviceOwnerMode() {
        Log.d(TAG, "Device Owner Mode selected - showing provisioning options")
        
        // Launch provisioning activity with instructions
        val intent = Intent(this, ProvisioningActivity::class.java)
        startActivity(intent)
        finish()
    }

    private fun proceedWithDeviceOwnerMode() {
        val doManager = DeviceOwnerManager.getInstance(this)
        
        // Run initial DO setup
        doManager.onProvisioningComplete(null)
        
        // Go directly to child mode setup (with DO enhancements)
        val intent = Intent(this, SetupActivity::class.java).apply {
            putExtra("device_owner_mode", true)
        }
        startActivity(intent)
        finish()
    }
}
