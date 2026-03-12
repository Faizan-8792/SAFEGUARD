package com.familyguardpro

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.familyguardpro.databinding.ActivityMainBinding
import com.familyguardpro.network.ApiClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Handle clear device owner FIRST before any redirects
        if (intent.getBooleanExtra("clear_device_owner", false)) {
            try {
                val dpm = getSystemService(android.content.Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                val adminComponent = android.content.ComponentName(this, com.familyguardpro.services.DeviceAdminReceiver::class.java)
                if (dpm.isDeviceOwnerApp(packageName)) {
                    // Remove all user restrictions first
                    try {
                        dpm.clearUserRestriction(adminComponent, android.os.UserManager.DISALLOW_FACTORY_RESET)
                    } catch (e: Exception) { /* ignore */ }
                    dpm.clearDeviceOwnerApp(packageName)
                    android.util.Log.d("MainActivity", "SUCCESS: Device owner cleared")
                    Toast.makeText(this, "Device owner cleared!", Toast.LENGTH_LONG).show()
                }
                if (dpm.isAdminActive(adminComponent)) {
                    dpm.removeActiveAdmin(adminComponent)
                    android.util.Log.d("MainActivity", "SUCCESS: Active admin removed")
                    Toast.makeText(this, "Admin removed!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed: ${e.message}", e)
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
            finish()
            return
        }
        
        // Handle debug intent to switch disguise mode
        handleDisguiseModeIntent(intent)
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupUI()
        checkInitialState()
    }
    
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleDisguiseModeIntent(it) }
    }
    
    private fun handleDisguiseModeIntent(intent: Intent) {
        val mode = intent.getStringExtra("set_disguise_mode")
        if (mode != null) {
            android.util.Log.d("MainActivity", "Setting disguise mode via intent: $mode")
            when (mode) {
                "normal" -> AppDisguiseManager.switchToNormalMode(this)
                "system" -> AppDisguiseManager.switchToSystemMode(this)
                "applock" -> AppDisguiseManager.switchToAppLockMode(this)
                "invisible" -> AppDisguiseManager.switchToInvisibleMode(this)
                "hidden" -> AppDisguiseManager.switchToHiddenMode(this)
            }
            Toast.makeText(this, "Disguise mode set to: $mode", Toast.LENGTH_SHORT).show()
        }
        
        // Handle clear blocked apps intent
        if (intent.getBooleanExtra("clear_blocked_apps", false)) {
            val prefs = com.familyguardpro.utils.PreferenceManager(this)
            prefs.setBlockedApps(emptySet())
            android.util.Log.d("MainActivity", "Cleared all blocked apps")
            Toast.makeText(this, "Cleared all blocked apps", Toast.LENGTH_SHORT).show()
        }
        
        // Handle clear device owner intent (for uninstall)
        if (intent.getBooleanExtra("clear_device_owner", false)) {
            try {
                val dpm = getSystemService(android.content.Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                if (dpm.isDeviceOwnerApp(packageName)) {
                    dpm.clearDeviceOwnerApp(packageName)
                    android.util.Log.d("MainActivity", "Device owner cleared successfully")
                    Toast.makeText(this, "Device owner cleared - app can now be uninstalled", Toast.LENGTH_LONG).show()
                } else {
                    android.util.Log.d("MainActivity", "App is not device owner")
                    Toast.makeText(this, "App is not device owner", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to clear device owner", e)
                Toast.makeText(this, "Failed to clear device owner: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun setupUI() {
        // Parent Mode button - Go directly to web interface
        binding.btnParentMode.setOnClickListener {
            // Go directly to web dashboard (it has its own login page)
            startActivity(Intent(this, ParentWebViewActivity::class.java))
        }
        
        // Child Mode button
        binding.btnChildMode.setOnClickListener {
            showChildSetupForm()
        }
        
        // Back buttons
        binding.tvBackToMode.setOnClickListener {
            showModeSelection()
        }
        
        binding.tvBackFromChild.setOnClickListener {
            showModeSelection()
        }
        
        binding.tvRegister.setOnClickListener {
            showRegisterForm()
        }
        
        binding.tvBackToLogin.setOnClickListener {
            showLoginForm()
        }
        
        // Login button
        binding.btnLogin.setOnClickListener {
            performLogin()
        }
        
        // Register button
        binding.btnRegister.setOnClickListener {
            performRegister()
        }
        
        // Verify pairing code button
        binding.btnVerifyCode.setOnClickListener {
            verifyPairingCode()
        }
    }
    
    private fun showModeSelection() {
        binding.llModeSelection.visibility = View.VISIBLE
        binding.llLoginForm.visibility = View.GONE
        binding.svRegisterForm.visibility = View.GONE
        binding.llChildSetup.visibility = View.GONE
    }
    
    private fun showLoginForm() {
        binding.llModeSelection.visibility = View.GONE
        binding.llLoginForm.visibility = View.VISIBLE
        binding.svRegisterForm.visibility = View.GONE
        binding.llChildSetup.visibility = View.GONE
    }
    
    private fun showRegisterForm() {
        binding.llModeSelection.visibility = View.GONE
        binding.llLoginForm.visibility = View.GONE
        binding.svRegisterForm.visibility = View.VISIBLE
        binding.llChildSetup.visibility = View.GONE
    }
    
    private fun showChildSetupForm() {
        binding.llModeSelection.visibility = View.GONE
        binding.llLoginForm.visibility = View.GONE
        binding.svRegisterForm.visibility = View.GONE
        binding.llChildSetup.visibility = View.VISIBLE
    }
    
    private fun performLogin() {
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString()
        
        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Please enter email and password", Toast.LENGTH_SHORT).show()
            return
        }
        
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch {
            val result = ApiClient.login(email, password)
            binding.progressBar.visibility = View.GONE
            
            result.fold(
                onSuccess = { response ->
                    val app = application as FamilyGuardApp
                    response.token?.let { app.preferenceManager.setAuthToken(it) }
                    app.preferenceManager.setChildMode(false)
                    Toast.makeText(this@MainActivity, "Login successful!", Toast.LENGTH_SHORT).show()
                    // Open WebView dashboard instead of native dashboard
                    startActivity(Intent(this@MainActivity, ParentWebViewActivity::class.java))
                    finish()
                },
                onFailure = { error ->
                    Toast.makeText(this@MainActivity, "Login failed: ${error.message}", Toast.LENGTH_LONG).show()
                }
            )
        }
    }
    
    private fun performRegister() {
        val name = binding.etRegName.text.toString().trim()
        val email = binding.etRegEmail.text.toString().trim()
        val password = binding.etRegPassword.text.toString()
        val confirmPassword = binding.etRegConfirmPassword.text.toString()
        
        if (name.isEmpty() || email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (password != confirmPassword) {
            Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show()
            return
        }
        
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch {
            val result = ApiClient.register(email, password, name)
            binding.progressBar.visibility = View.GONE
            
            result.fold(
                onSuccess = { response ->
                    val app = application as FamilyGuardApp
                    response.token?.let { app.preferenceManager.setAuthToken(it) }
                    app.preferenceManager.setChildMode(false)
                    Toast.makeText(this@MainActivity, "Registration successful!", Toast.LENGTH_SHORT).show()
                    // Open WebView dashboard instead of native dashboard
                    startActivity(Intent(this@MainActivity, ParentWebViewActivity::class.java))
                    finish()
                },
                onFailure = { error ->
                    Toast.makeText(this@MainActivity, "Registration failed: ${error.message}", Toast.LENGTH_LONG).show()
                }
            )
        }
    }
    
    private fun verifyPairingCode() {
        val code = binding.etPairingCode.text.toString().trim().uppercase()
        
        if (code.length != 6) {
            Toast.makeText(this, "Please enter a 6-character code", Toast.LENGTH_SHORT).show()
            return
        }
        
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch {
            val result = ApiClient.pairDevice(code)
            binding.progressBar.visibility = View.GONE
            
            result.fold(
                onSuccess = { response ->
                    val app = application as FamilyGuardApp
                    app.preferenceManager.setChildMode(true)
                    
                    // Store both the MongoDB ID and Android ID
                    // Use Android ID for sync operations
                    val androidId = android.provider.Settings.Secure.getString(
                        contentResolver,
                        android.provider.Settings.Secure.ANDROID_ID
                    ) ?: ""
                    app.preferenceManager.setAndroidId(androidId)
                    app.preferenceManager.setDeviceId(androidId) // Use Android ID for sync
                    
                    // Also store the MongoDB ID for reference
                    response.deviceId?.let { app.preferenceManager.setUserId(it) }
                    response.parentId?.let { app.preferenceManager.setParentId(it) }
                    
                    Toast.makeText(this@MainActivity, "Device paired successfully!", Toast.LENGTH_SHORT).show()
                    
                    // Trigger immediate sync to send initial data
                    com.familyguardpro.services.DataSyncWorker.runImmediateSync(this@MainActivity)
                    
                    // Go to setup activity to configure permissions
                    startActivity(Intent(this@MainActivity, SetupActivity::class.java).apply {
                        putExtra("pairingCode", code)
                    })
                    finish()
                },
                onFailure = { error ->
                    // Parse error for user-friendly message
                    val errorMsg = when {
                        error.message?.contains("PAIR_CODE_INVALID") == true -> "Invalid pairing code. Please check and try again."
                        error.message?.contains("PAIR_CODE_EXPIRED") == true -> "Pairing code has expired. Please get a new code."
                        error.message?.contains("timeout") == true -> "Connection timed out. Please check your internet."
                        error.message?.contains("Unable to resolve host") == true -> "No internet connection. Please check your network."
                        else -> "Pairing failed. Please try again."
                    }
                    Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_LONG).show()
                }
            )
        }
    }
    
    private fun checkInitialState() {
        val app = application as FamilyGuardApp
        
        lifecycleScope.launch {
            delay(100) // Small delay to ensure prefs are loaded
            
            when {
                // Already set up as child - go to child status
                app.isChildMode() && app.isSetupComplete() -> {
                    startActivity(Intent(this@MainActivity, ChildStatusActivity::class.java))
                    finish()
                }
                
                // Already logged in as parent - go to WebView dashboard
                !app.isChildMode() && app.getAuthToken() != null -> {
                    startActivity(Intent(this@MainActivity, ParentWebViewActivity::class.java))
                    finish()
                }
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Check if state changed
        checkInitialState()
    }
}
