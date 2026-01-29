package com.familyguardpro

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.familyguardpro.adapters.DeviceAdapter
import com.familyguardpro.databinding.ActivityDashboardBinding
import com.familyguardpro.models.ChildDevice
import com.familyguardpro.network.ApiClient
import com.familyguardpro.utils.PreferenceManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class DashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding
    private lateinit var preferenceManager: PreferenceManager
    private lateinit var deviceAdapter: DeviceAdapter
    private val devices = mutableListOf<ChildDevice>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        preferenceManager = PreferenceManager(this)
        
        setupUI()
        loadDevices()
    }

    override fun onResume() {
        super.onResume()
        loadDevices()
    }

    private fun setupUI() {
        binding.toolbar.title = "FamilyGuard Pro"
        
        // Device list
        deviceAdapter = DeviceAdapter(devices) { device ->
            openDeviceDetail(device)
        }
        binding.rvDevices.apply {
            layoutManager = LinearLayoutManager(this@DashboardActivity)
            adapter = deviceAdapter
        }
        
        // Add device FAB
        binding.fabAddDevice.setOnClickListener {
            generatePairingCode()
        }
        
        // Refresh
        binding.swipeRefresh.setOnRefreshListener {
            loadDevices()
        }
        
        // Logout button
        binding.btnLogout.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Logout")
                .setMessage("Are you sure you want to logout?")
                .setPositiveButton("Logout") { _, _ ->
                    logout()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun loadDevices() {
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch {
            try {
                val response = ApiClient.api.getDevices(
                    "Bearer ${preferenceManager.getAuthToken()}"
                )
                
                if (response.success) {
                    devices.clear()
                    devices.addAll(response.devices ?: emptyList())
                    deviceAdapter.notifyDataSetChanged()
                    
                    binding.llEmptyState.visibility = if (devices.isEmpty()) View.VISIBLE else View.GONE
                    binding.rvDevices.visibility = if (devices.isEmpty()) View.GONE else View.VISIBLE
                }
            } catch (e: Exception) {
                Toast.makeText(this@DashboardActivity, "Error loading devices", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressBar.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun generatePairingCode() {
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch {
            try {
                val response = ApiClient.api.generatePairingCode(
                    "Bearer ${preferenceManager.getAuthToken()}"
                )
                
                if (response.success && response.code != null) {
                    showPairingCodeDialog(response.code, response.expiresAt ?: "")
                } else {
                    Toast.makeText(this@DashboardActivity, "Failed to generate code", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@DashboardActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun showPairingCodeDialog(code: String, expiresAt: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_pairing_code, null)
        val tvCode = dialogView.findViewById<android.widget.TextView>(R.id.tvPairingCode)
        val tvExpiry = dialogView.findViewById<android.widget.TextView>(R.id.tvExpiry)
        val btnPair = dialogView.findViewById<android.widget.Button>(R.id.btnPair)
        
        tvCode.text = code
        tvExpiry.text = "Valid for 24 hours"
        
        val dialog = AlertDialog.Builder(this)
            .setTitle("Pairing Code")
            .setView(dialogView)
            .setPositiveButton("Done", null)
            .create()
        
        // Use btnPair as a copy button
        btnPair.text = "Copy Code"
        btnPair.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Pairing Code", code))
            Toast.makeText(this, "Code copied!", Toast.LENGTH_SHORT).show()
        }
        
        dialog.show()
    }

    private fun openDeviceDetail(device: ChildDevice) {
        val intent = Intent(this, DeviceDetailActivity::class.java).apply {
            putExtra("deviceId", device.id)
            putExtra("deviceName", device.name)
        }
        startActivity(intent)
    }

    private fun logout() {
        preferenceManager.clear()
        startActivity(Intent(this, MainActivity::class.java))
        finishAffinity()
    }
}
