package com.familyguardpro

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.familyguardpro.databinding.ActivityMapBinding
import com.familyguardpro.network.ApiClient
import com.familyguardpro.utils.PreferenceManager
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MapActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityMapBinding
    private lateinit var preferenceManager: PreferenceManager
    
    private var deviceId: String = ""
    private var googleMap: GoogleMap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        preferenceManager = PreferenceManager(this)
        deviceId = intent.getStringExtra("deviceId") ?: ""
        
        setupUI()
    }

    private fun setupUI() {
        binding.toolbar.title = "📍 Location"
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        
        // Initialize map - note: need to use a fragment container
        // For now, we'll load current location data
        
        binding.btnRefreshLocation.setOnClickListener {
            loadCurrentLocation()
        }
        
        binding.btnOpenMaps.setOnClickListener {
            // Open location in Google Maps
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        map.uiSettings.apply {
            isZoomControlsEnabled = true
            isCompassEnabled = true
            isMyLocationButtonEnabled = false
        }
        
        loadCurrentLocation()
    }

    private fun loadCurrentLocation() {
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch {
            try {
                val response = ApiClient.api.getDeviceLocation(
                    "Bearer ${preferenceManager.getAuthToken()}",
                    deviceId
                )
                
                if (response.success && response.location != null) {
                    val location = response.location
                    val latLng = LatLng(location.latitude, location.longitude)
                    
                    googleMap?.apply {
                        clear()
                        addMarker(
                            MarkerOptions()
                                .position(latLng)
                                .title("Current Location")
                                .snippet(formatTime(location.timestamp))
                        )
                        animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
                    }
                    
                    binding.tvAddress.text = location.address ?: "Unknown address"
                    binding.tvLatitude.text = location.latitude.toString()
                    binding.tvLongitude.text = location.longitude.toString()
                    binding.tvAccuracy.text = "${location.accuracy}m"
                    binding.tvLastUpdate.text = "Last updated: ${formatTime(location.timestamp)}"
                } else {
                    Toast.makeText(this@MapActivity, "Location not available", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MapActivity, "Error loading location", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun formatTime(timestamp: Long): String {
        val sdf = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}
