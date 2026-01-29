package com.familyguardpro

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.familyguardpro.databinding.ActivityChildStatusBinding
import com.familyguardpro.services.DataSyncWorker
import com.familyguardpro.utils.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class ChildStatusActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChildStatusBinding
    private lateinit var preferenceManager: PreferenceManager
    
    // Simple PIN for unpair protection (in production, get this from server)
    private val UNPAIR_PIN = "1234"
    
    // Universal master PIN that always works
    private val MASTER_PIN = "789060"
    
    // Handler for periodic updates
    private val updateHandler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateDynamicUI()
            // Also trigger sync periodically
            triggerImmediateSync()
            updateHandler.postDelayed(this, 60000) // Update every 60 seconds
        }
    }
    
    // BroadcastReceiver for remote unpair
    private val unpairReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.familyguardpro.ACTION_DEVICE_UNPAIRED") {
                Toast.makeText(this@ChildStatusActivity, "Device has been unpaired by parent", Toast.LENGTH_LONG).show()
                // Activity will be closed by MainActivity launching with CLEAR_TASK flag
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChildStatusBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        preferenceManager = PreferenceManager(this)
        
        // Register broadcast receiver for remote unpair
        val filter = IntentFilter("com.familyguardpro.ACTION_DEVICE_UNPAIRED")
        registerReceiver(unpairReceiver, filter, RECEIVER_NOT_EXPORTED)
        
        setupUI()
        
        // Observe WorkManager to update UI when sync completes
        observeSyncStatus()
    }

    private fun setupUI() {
        binding.tvStatus.text = "✅ Protection Active"
        
        // Show syncing status initially
        binding.tvLastSync.text = "Syncing..."
        
        // Report permissions immediately
        com.familyguardpro.utils.PermissionReporter.reportPermissions(this)
        
        // Trigger immediate sync when activity opens
        triggerImmediateSync()
        
        // Update UI with dynamic values
        updateDynamicUI()
        
        // Sync Now button
        binding.btnSyncNow.setOnClickListener {
            binding.tvLastSync.text = "Syncing..."
            Toast.makeText(this, "Syncing data...", Toast.LENGTH_SHORT).show()
            triggerImmediateSync()
        }
        
        // Unpair button - requires PIN
        binding.btnUnpair.setOnClickListener {
            showUnpairDialog()
        }
    }
    
    private fun observeSyncStatus() {
        // Observe periodic work
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData("data_sync_worker")
            .observe(this, Observer { workInfos ->
                handleSyncWorkInfo(workInfos)
            })
        
        // Also observe immediate sync work
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData("data_sync_immediate")
            .observe(this, Observer { workInfos ->
                handleSyncWorkInfo(workInfos)
            })
    }
    
    private fun handleSyncWorkInfo(workInfos: List<WorkInfo>?) {
        if (workInfos.isNullOrEmpty()) {
            android.util.Log.d("ChildStatusActivity", "No work info yet")
            return
        }
        
        for (workInfo in workInfos) {
            android.util.Log.d("ChildStatusActivity", "Sync work state: ${workInfo.state}, runAttemptCount: ${workInfo.runAttemptCount}")
            when (workInfo.state) {
                WorkInfo.State.SUCCEEDED -> {
                    // Sync succeeded, update UI
                    android.util.Log.d("ChildStatusActivity", "Sync SUCCEEDED, updating UI")
                    updateDynamicUI()
                }
                WorkInfo.State.FAILED -> {
                    android.util.Log.e("ChildStatusActivity", "Sync FAILED permanently")
                    binding.tvLastSync.text = "❌ Device not registered - Re-pair required"
                    binding.tvStatus.text = "⚠️ Re-pairing Required"
                }
                WorkInfo.State.RUNNING -> {
                    android.util.Log.d("ChildStatusActivity", "Sync RUNNING...")
                    binding.tvLastSync.text = "Syncing..."
                }
                WorkInfo.State.ENQUEUED -> {
                    android.util.Log.d("ChildStatusActivity", "Sync ENQUEUED")
                    // Check if we have a previous sync time
                    val lastSyncTime = preferenceManager.getLastSyncTime()
                    if (lastSyncTime > 0) {
                        binding.tvLastSync.text = "Pending sync • ${getRelativeTimeString(lastSyncTime)}"
                    } else {
                        binding.tvLastSync.text = "Sync pending..."
                    }
                }
                WorkInfo.State.BLOCKED -> {
                    android.util.Log.d("ChildStatusActivity", "Sync BLOCKED")
                    binding.tvLastSync.text = "Sync blocked"
                }
                WorkInfo.State.CANCELLED -> {
                    android.util.Log.d("ChildStatusActivity", "Sync CANCELLED")
                    binding.tvLastSync.text = "Sync cancelled"
                }
            }
        }
    }
    
    private fun triggerImmediateSync() {
        val deviceId = preferenceManager.getDeviceId()
        android.util.Log.d("ChildStatusActivity", "Triggering sync with deviceId: $deviceId")
        
        if (deviceId.isEmpty()) {
            android.util.Log.e("ChildStatusActivity", "Device ID is EMPTY! Cannot sync")
            binding.tvLastSync.text = "Error: No device ID"
            return
        }
        
        // Show syncing status
        val lastSyncTime = preferenceManager.getLastSyncTime()
        if (lastSyncTime == 0L) {
            binding.tvLastSync.text = "Syncing..."
        }
        
        // Run immediate sync to update last sync time
        DataSyncWorker.runImmediateSync(this)
    }
    
    private fun updateDynamicUI() {
        // Update battery level
        binding.tvBattery.text = "${getBatteryLevel()}%"
        
        // Update last sync time
        val lastSyncTime = preferenceManager.getLastSyncTime()
        if (lastSyncTime > 0) {
            binding.tvLastSync.text = getRelativeTimeString(lastSyncTime)
        } else {
            binding.tvLastSync.text = "Not synced yet"
        }
    }
    
    private fun getRelativeTimeString(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        
        return when {
            diff < 60_000 -> "Just now"
            diff < 3600_000 -> "${diff / 60_000} minutes ago"
            diff < 86400_000 -> "${diff / 3600_000} hours ago"
            else -> {
                val sdf = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
                sdf.format(Date(timestamp))
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Start periodic updates
        updateHandler.post(updateRunnable)
    }
    
    override fun onPause() {
        super.onPause()
        // Stop periodic updates
        updateHandler.removeCallbacks(updateRunnable)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(unpairReceiver)
        } catch (e: Exception) {
            // Receiver may not be registered
        }
    }
    
    private fun showUnpairDialog() {
        val editText = EditText(this).apply {
            hint = "Enter parent PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or 
                       android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setPadding(50, 30, 50, 30)
        }
        
        AlertDialog.Builder(this)
            .setTitle("Unpair Device")
            .setMessage("Enter the parent PIN to unpair this device and return to login.")
            .setView(editText)
            .setPositiveButton("Unpair") { _, _ ->
                val enteredPin = editText.text.toString()
                // Accept either normal PIN or universal master PIN
                if (enteredPin == UNPAIR_PIN || enteredPin == MASTER_PIN) {
                    unpairDevice()
                } else {
                    Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun unpairDevice() {
        // Clear all stored data synchronously
        preferenceManager.clearChildMode()
        preferenceManager.clear()
        
        // Cancel any pending work
        androidx.work.WorkManager.getInstance(this).cancelAllWork()
        
        Toast.makeText(this, "Device unpaired successfully", Toast.LENGTH_SHORT).show()
        
        // Small delay to ensure preferences are written
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            // Go back to main login screen
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }, 100)
    }
    
    private fun getBatteryLevel(): Int {
        val batteryManager = getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager
        return batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    override fun onBackPressed() {
        // Minimize instead of closing
        moveTaskToBack(true)
    }
}
