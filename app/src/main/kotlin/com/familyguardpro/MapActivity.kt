package com.familyguardpro

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.familyguardpro.databinding.ActivityMapBinding
import com.familyguardpro.network.ApiClient
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MapActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMapBinding
    private var deviceId: String? = null
    private var currentLat: Double = 0.0
    private var currentLng: Double = 0.0
    private val dateFormat = SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault())
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        deviceId = intent.getStringExtra("deviceId")
        
        setupUI()
        loadLocation()
    }
    
    private fun setupUI() {
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }
        
        binding.btnRefreshLocation.setOnClickListener {
            loadLocation()
        }
        
        binding.btnOpenMaps.setOnClickListener {
            openInGoogleMaps()
        }
    }
    
    private fun loadLocation() {
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch {
            deviceId?.let { id ->
                val result = ApiClient.getDeviceLocation(id)
                
                result.fold(
                    onSuccess = { location ->
                        binding.progressBar.visibility = View.GONE
                        
                        currentLat = location.latitude ?: 0.0
                        currentLng = location.longitude ?: 0.0
                        
                        binding.tvLatitude.text = String.format("%.6f", currentLat)
                        binding.tvLongitude.text = String.format("%.6f", currentLng)
                        binding.tvAccuracy.text = "${location.accuracy?.toInt() ?: 0}m"
                        binding.tvAddress.text = location.address ?: "Address not available"
                        
                        location.timestamp?.let { ts ->
                            binding.tvLastUpdate.text = "Last updated: ${dateFormat.format(Date(ts))}"
                        }
                        
                        // Update map placeholder
                        binding.tvMapPlaceholder.text = "Location: $currentLat, $currentLng"
                    },
                    onFailure = { error ->
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(this@MapActivity, "Error: ${error.message}", Toast.LENGTH_LONG).show()
                    }
                )
            } ?: run {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this@MapActivity, "No device ID", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun openInGoogleMaps() {
        if (currentLat != 0.0 || currentLng != 0.0) {
            val uri = Uri.parse("geo:$currentLat,$currentLng?q=$currentLat,$currentLng(Device Location)")
            val intent = Intent(Intent.ACTION_VIEW, uri)
            intent.setPackage("com.google.android.apps.maps")
            
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                // Fallback to browser
                val browserUri = Uri.parse("https://www.google.com/maps?q=$currentLat,$currentLng")
                startActivity(Intent(Intent.ACTION_VIEW, browserUri))
            }
        } else {
            Toast.makeText(this, "No location available", Toast.LENGTH_SHORT).show()
        }
    }
}
