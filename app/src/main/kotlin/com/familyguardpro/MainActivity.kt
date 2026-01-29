package com.familyguardpro

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.familyguardpro.databinding.ActivityMainBinding
import com.familyguardpro.network.ApiClient
import com.familyguardpro.utils.PreferenceManager
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var preferenceManager: PreferenceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        preferenceManager = PreferenceManager(this)
        
        // Check if already configured
        when {
            preferenceManager.isChildMode() -> {
                // Child mode - go to status screen
                startActivity(Intent(this, ChildStatusActivity::class.java))
                finish()
                return
            }
            preferenceManager.isParentMode() && preferenceManager.isLoggedIn() -> {
                // Parent mode - go to dashboard
                startActivity(Intent(this, DashboardActivity::class.java))
                finish()
                return
            }
        }
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupUI()
    }

    private fun setupUI() {
        // Parent Mode button
        binding.btnParentMode.setOnClickListener {
            showParentLogin()
        }
        
        // Child Mode button
        binding.btnChildMode.setOnClickListener {
            showChildSetup()
        }
    }

    private fun showParentLogin() {
        binding.llModeSelection.visibility = View.GONE
        binding.llLoginForm.visibility = View.VISIBLE
        binding.llChildSetup.visibility = View.GONE
        binding.svRegisterForm.visibility = View.GONE
        
        binding.btnLogin.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString()
            
            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            loginParent(email, password)
        }
        
        binding.tvRegister.setOnClickListener {
            showRegisterForm()
        }
        
        binding.tvBackToMode.setOnClickListener {
            showModeSelection()
        }
    }

    private fun showRegisterForm() {
        binding.llModeSelection.visibility = View.GONE
        binding.llLoginForm.visibility = View.GONE
        binding.llChildSetup.visibility = View.GONE
        binding.svRegisterForm.visibility = View.VISIBLE
        
        binding.btnRegister.setOnClickListener {
            val email = binding.etRegEmail.text.toString().trim()
            val password = binding.etRegPassword.text.toString()
            val confirmPassword = binding.etRegConfirmPassword.text.toString()
            
            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            if (password != confirmPassword) {
                Toast.makeText(this, "Passwords don't match", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            registerParent(email, password)
        }
        
        binding.tvBackToLogin.setOnClickListener {
            showParentLogin()
        }
    }

    private fun showChildSetup() {
        binding.llModeSelection.visibility = View.GONE
        binding.llLoginForm.visibility = View.GONE
        binding.llChildSetup.visibility = View.VISIBLE
        binding.svRegisterForm.visibility = View.GONE
        
        binding.btnVerifyCode.setOnClickListener {
            val code = binding.etPairingCode.text.toString().trim()
            
            if (code.length != 6) {
                Toast.makeText(this, "Enter 6-digit code", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            verifyPairingCode(code)
        }
        
        binding.tvBackFromChild.setOnClickListener {
            showModeSelection()
        }
    }

    private fun showModeSelection() {
        binding.llModeSelection.visibility = View.VISIBLE
        binding.llLoginForm.visibility = View.GONE
        binding.llChildSetup.visibility = View.GONE
        binding.svRegisterForm.visibility = View.GONE
    }

    private fun loginParent(email: String, password: String) {
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch {
            try {
                val response = ApiClient.api.login(
                    mapOf("email" to email, "password" to password)
                )
                
                if (response.success) {
                    preferenceManager.setParentMode(true)
                    preferenceManager.setLoggedIn(true)
                    preferenceManager.setAuthToken(response.token ?: "")
                    preferenceManager.setUserId(response.userId ?: "")
                    preferenceManager.setEmail(email)
                    
                    Toast.makeText(this@MainActivity, "Login successful!", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this@MainActivity, DashboardActivity::class.java))
                    finish()
                }
            } catch (e: Exception) {
                com.familyguardpro.utils.ErrorHandler.showDialog(this@MainActivity, e)
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun registerParent(email: String, password: String) {
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch {
            try {
                val name = binding.etRegName?.text?.toString()?.trim() ?: email.substringBefore("@")
                
                val response = ApiClient.api.register(
                    mapOf("email" to email, "password" to password, "name" to name)
                )
                
                if (response.success) {
                    preferenceManager.setParentMode(true)
                    preferenceManager.setLoggedIn(true)
                    preferenceManager.setAuthToken(response.token ?: "")
                    preferenceManager.setUserId(response.userId ?: "")
                    preferenceManager.setEmail(email)
                    
                    Toast.makeText(this@MainActivity, "Registration successful!", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this@MainActivity, DashboardActivity::class.java))
                    finish()
                }
            } catch (e: Exception) {
                com.familyguardpro.utils.ErrorHandler.showDialog(this@MainActivity, e)
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun verifyPairingCode(code: String) {
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch {
            try {
                val deviceId = android.provider.Settings.Secure.getString(
                    contentResolver, 
                    android.provider.Settings.Secure.ANDROID_ID
                )
                val deviceName = android.os.Build.MODEL
                val deviceModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
                val androidVersion = android.os.Build.VERSION.RELEASE
                
                val response = ApiClient.api.verifyPairingCode(
                    mapOf(
                        "code" to code,
                        "deviceId" to deviceId,
                        "name" to deviceName,
                        "model" to deviceModel,
                        "androidVersion" to androidVersion
                    )
                )
                
                if (response.success) {
                    preferenceManager.setChildMode(true)
                    preferenceManager.setDeviceId(response.deviceId ?: deviceId)
                    preferenceManager.setParentId(response.parentId ?: "")
                    
                    Toast.makeText(this@MainActivity, "Device paired successfully!", Toast.LENGTH_SHORT).show()
                    
                    // Go to setup activity for permissions
                    startActivity(Intent(this@MainActivity, SetupActivity::class.java))
                    finish()
                }
            } catch (e: Exception) {
                com.familyguardpro.utils.ErrorHandler.showDialog(this@MainActivity, e)
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }
}
