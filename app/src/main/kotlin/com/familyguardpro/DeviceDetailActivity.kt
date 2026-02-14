package com.familyguardpro

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.familyguardpro.databinding.ActivityDeviceDetailBinding

class DeviceDetailActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityDeviceDetailBinding
    private var deviceId: String? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        deviceId = intent.getStringExtra("deviceId")
        
        if (deviceId == null) {
            Toast.makeText(this, "Invalid device", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        setupUI()
    }
    
    private fun setupUI() {
        supportActionBar?.title = "Device Details"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        
        // Load device details
        loadDeviceDetails()
    }
    
    private fun loadDeviceDetails() {
        // TODO: Load device details from API
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}
