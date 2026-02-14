package com.familyguardpro

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.familyguardpro.databinding.ActivityDashboardBinding
import com.familyguardpro.databinding.ItemDeviceBinding
import com.familyguardpro.models.Device
import com.familyguardpro.network.ApiClient
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class DashboardActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityDashboardBinding
    private var devices = mutableListOf<Device>()
    private lateinit var adapter: DeviceAdapter
    private val dateFormat = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault())
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setSupportActionBar(binding.toolbar)
        
        setupUI()
        checkAuthAndLoad()
    }
    
    private fun setupUI() {
        // Setup RecyclerView
        adapter = DeviceAdapter(devices) { device ->
            openDeviceDetail(device)
        }
        binding.rvDevices.layoutManager = LinearLayoutManager(this)
        binding.rvDevices.adapter = adapter
        
        // SwipeRefresh
        binding.swipeRefresh.setOnRefreshListener {
            loadDevices()
        }
        
        // Add device FAB
        binding.fabAddDevice.setOnClickListener {
            showPairingCodeDialog()
        }
        
        // Logout button
        binding.btnLogout.setOnClickListener {
            logout()
        }
    }
    
    private fun openDeviceDetail(device: Device) {
        startActivity(Intent(this, DeviceDetailActivity::class.java).apply {
            putExtra("deviceId", device.id)
            putExtra("deviceName", device.deviceName)
        })
    }
    
    private fun checkAuthAndLoad() {
        val token = (application as FamilyGuardApp).getAuthToken()
        if (token == null) {
            showLoginDialog()
        } else {
            loadDevices()
        }
    }
    
    private fun showLoginDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_login, null)
        val etEmail = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etEmail)
        val etPassword = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etPassword)
        
        AlertDialog.Builder(this)
            .setTitle("Login")
            .setView(dialogView)
            .setPositiveButton("Login") { _, _ ->
                val email = etEmail.text.toString()
                val password = etPassword.text.toString()
                if (email.isNotBlank() && password.isNotBlank()) {
                    login(email, password)
                } else {
                    Toast.makeText(this, "Please enter email and password", Toast.LENGTH_SHORT).show()
                    showLoginDialog()
                }
            }
            .setNeutralButton("Register") { _, _ ->
                showRegisterDialog()
            }
            .setNegativeButton("Cancel") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }
    
    private fun showRegisterDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_register, null)
        val etName = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etName)
        val etEmail = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etEmail)
        val etPassword = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etPassword)
        
        AlertDialog.Builder(this)
            .setTitle("Register")
            .setView(dialogView)
            .setPositiveButton("Register") { _, _ ->
                val name = etName.text.toString()
                val email = etEmail.text.toString()
                val password = etPassword.text.toString()
                if (name.isNotBlank() && email.isNotBlank() && password.isNotBlank()) {
                    register(email, password, name)
                } else {
                    Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
                    showRegisterDialog()
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                showLoginDialog()
            }
            .setCancelable(false)
            .show()
    }
    
    private fun login(email: String, password: String) {
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch {
            val result = ApiClient.login(email, password)
            
            result.fold(
                onSuccess = { response ->
                    val prefs = (application as FamilyGuardApp).preferenceManager
                    prefs.setAuthToken(response.token ?: "")
                    prefs.setUserId(response.user?.id ?: "")
                    prefs.setUserEmail(response.user?.email ?: "")
                    
                    loadDevices()
                },
                onFailure = { error ->
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this@DashboardActivity, "Login failed: ${error.message}", Toast.LENGTH_LONG).show()
                    showLoginDialog()
                }
            )
        }
    }
    
    private fun register(email: String, password: String, name: String) {
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch {
            val result = ApiClient.register(email, password, name)
            
            result.fold(
                onSuccess = { response ->
                    val prefs = (application as FamilyGuardApp).preferenceManager
                    prefs.setAuthToken(response.token ?: "")
                    prefs.setUserId(response.user?.id ?: "")
                    prefs.setUserEmail(response.user?.email ?: "")
                    
                    loadDevices()
                },
                onFailure = { error ->
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this@DashboardActivity, "Registration failed: ${error.message}", Toast.LENGTH_LONG).show()
                    showLoginDialog()
                }
            )
        }
    }
    
    private fun loadDevices() {
        binding.progressBar.visibility = View.VISIBLE
        binding.llEmptyState.visibility = View.GONE
        
        lifecycleScope.launch {
            val result = ApiClient.getDevices()
            
            result.fold(
                onSuccess = { deviceList ->
                    binding.progressBar.visibility = View.GONE
                    binding.swipeRefresh.isRefreshing = false
                    
                    devices.clear()
                    devices.addAll(deviceList)
                    adapter.notifyDataSetChanged()
                    
                    // Update stats
                    binding.tvTotalDevices.text = devices.size.toString()
                    binding.tvOnlineDevices.text = devices.count { it.isOnline == true }.toString()
                    binding.tvOfflineDevices.text = devices.count { it.isOnline != true }.toString()
                    
                    if (devices.isEmpty()) {
                        binding.llEmptyState.visibility = View.VISIBLE
                    }
                },
                onFailure = { error ->
                    binding.progressBar.visibility = View.GONE
                    binding.swipeRefresh.isRefreshing = false
                    Toast.makeText(this@DashboardActivity, "Error: ${error.message}", Toast.LENGTH_LONG).show()
                }
            )
        }
    }
    
    private fun showPairingCodeDialog() {
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch {
            val result = ApiClient.generatePairingCode()
            
            result.fold(
                onSuccess = { response ->
                    binding.progressBar.visibility = View.GONE
                    val code = response.pairingCode ?: "N/A"
                    
                    AlertDialog.Builder(this@DashboardActivity)
                        .setTitle("Pairing Code")
                        .setMessage("Enter this code on the child device:\n\n$code\n\nCode expires in 10 minutes.")
                        .setPositiveButton("OK", null)
                        .show()
                },
                onFailure = { error ->
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this@DashboardActivity, "Error: ${error.message}", Toast.LENGTH_LONG).show()
                }
            )
        }
    }
    
    private fun logout() {
        (application as FamilyGuardApp).preferenceManager.clear()
        showLoginDialog()
    }
    
    override fun onResume() {
        super.onResume()
        if ((application as FamilyGuardApp).getAuthToken() != null) {
            loadDevices()
        }
    }
    
    // Device Adapter
    inner class DeviceAdapter(
        private val devices: List<Device>,
        private val onClick: (Device) -> Unit
    ) : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {
        
        inner class ViewHolder(val binding: ItemDeviceBinding) : RecyclerView.ViewHolder(binding.root)
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val device = devices[position]
            
            holder.binding.tvDeviceName.text = device.deviceName
            holder.binding.tvDeviceModel.text = device.deviceModel ?: "Unknown"
            holder.binding.tvBattery.text = "${device.batteryLevel ?: 0}%"
            
            val isOnline = device.isOnline == true
            holder.binding.viewOnlineStatus.setBackgroundResource(
                if (isOnline) R.drawable.ic_online else R.drawable.ic_offline
            )
            
            device.lastSeen?.let { lastSeen ->
                holder.binding.tvLastSeen.text = "Last: ${dateFormat.format(Date(lastSeen))}"
            } ?: run {
                holder.binding.tvLastSeen.text = "Last: Never"
            }
            
            holder.itemView.setOnClickListener { onClick(device) }
        }
        
        override fun getItemCount() = devices.size
    }
}
