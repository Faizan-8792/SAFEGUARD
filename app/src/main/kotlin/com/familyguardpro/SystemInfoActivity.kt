package com.familyguardpro

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.familyguardpro.utils.PreferenceManager
import com.google.android.material.appbar.MaterialToolbar
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

/**
 * Fake About Phone Activity - Android 14 Style
 * Shows real device information to appear as a legitimate system settings page
 * Tapping Build Number 7 times reveals parental dashboard (like Android developer options)
 */
class SystemInfoActivity : AppCompatActivity() {
    
    private lateinit var preferenceManager: PreferenceManager
    
    // Secret tap counter - 7 taps like Android developer options
    private var secretTapCount = 0
    private var lastTapTime = 0L
    private val REQUIRED_TAPS = 7
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Set light status bar for Android 14 look
        window.statusBarColor = 0xFFF5F5F5.toInt()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        
        setContentView(R.layout.activity_system_info)
        
        preferenceManager = PreferenceManager(this)
        
        setupToolbar()
        setupClickListeners()
        populateDeviceInfo()
    }
    
    private fun setupToolbar() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener {
            goHome()
        }
    }
    
    private fun setupClickListeners() {
        // Header layout with device name - secret tap
        findViewById<LinearLayout>(R.id.headerLayout)?.setOnClickListener {
            handleSecretTap()
        }
        
        // Build number row - main secret tap target (like Android developer options)
        findViewById<LinearLayout>(R.id.rowBuildNumber)?.setOnClickListener {
            handleSecretTap()
        }
        
        // Legal info - show a fake dialog
        findViewById<LinearLayout>(R.id.rowLegalInfo)?.setOnClickListener {
            showLegalInfoDialog()
        }
        
        // Regulatory labels - show a fake dialog
        findViewById<LinearLayout>(R.id.rowRegulatoryLabels)?.setOnClickListener {
            showRegulatoryLabelsDialog()
        }
    }
    
    private fun populateDeviceInfo() {
        // Device Name
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
        findViewById<TextView>(R.id.tvDeviceName)?.text = deviceName
        
        // Phone Number
        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            @Suppress("DEPRECATION")
            val phoneNumber = tm.line1Number
            findViewById<TextView>(R.id.tvPhoneNumber)?.text = if (!phoneNumber.isNullOrEmpty()) phoneNumber else "Unknown"
        } catch (e: Exception) {
            findViewById<TextView>(R.id.tvPhoneNumber)?.text = "Unknown"
        }
        
        // Android Version
        findViewById<TextView>(R.id.tvAndroidVersion)?.text = Build.VERSION.RELEASE
        
        // IP Address
        findViewById<TextView>(R.id.tvIpAddress)?.text = getIPAddress() ?: "Not available"
        
        // Device Model
        findViewById<TextView>(R.id.tvDeviceModel)?.text = Build.MODEL
        
        // IMEI (protected on newer Android versions)
        findViewById<TextView>(R.id.tvImei)?.text = "Protected"
        
        // Baseband Version
        findViewById<TextView>(R.id.tvBasebandVersion)?.text = Build.getRadioVersion() ?: "Unknown"
        
        // Kernel Version
        findViewById<TextView>(R.id.tvKernelVersion)?.text = System.getProperty("os.version") ?: "Unknown"
        
        // Build Number
        findViewById<TextView>(R.id.tvBuildNumber)?.text = Build.DISPLAY
        
        // Uptime
        val uptimeMillis = SystemClock.elapsedRealtime()
        val hours = TimeUnit.MILLISECONDS.toHours(uptimeMillis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(uptimeMillis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(uptimeMillis) % 60
        findViewById<TextView>(R.id.tvUptime)?.text = String.format("%d:%02d:%02d", hours, minutes, seconds)
        
        // Security Patch Level
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            findViewById<TextView>(R.id.tvSecurityPatch)?.text = Build.VERSION.SECURITY_PATCH
        } else {
            findViewById<TextView>(R.id.tvSecurityPatch)?.text = "Unknown"
        }
    }
    
    private fun getIPAddress(): String? {
        try {
            // Try WiFi first
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val ipInt = wifiInfo.ipAddress
            if (ipInt != 0) {
                return String.format(
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
            }
            
            // Fallback to network interfaces
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
    
    private fun handleSecretTap() {
        val currentTime = System.currentTimeMillis()
        
        if (currentTime - lastTapTime > 2000) {
            // Reset if more than 2 seconds between taps
            secretTapCount = 0
        }
        
        lastTapTime = currentTime
        secretTapCount++
        
        // Show subtle feedback after 3 taps (like Android developer options)
        if (secretTapCount >= 3 && secretTapCount < REQUIRED_TAPS) {
            val remaining = REQUIRED_TAPS - secretTapCount
            Toast.makeText(this, "You are now $remaining steps away from being a developer.", Toast.LENGTH_SHORT).show()
        }
        
        if (secretTapCount >= REQUIRED_TAPS) {
            // Show PIN dialog to access real app
            showRevealPinDialog()
            secretTapCount = 0
        }
    }
    
    private fun showRevealPinDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_pin_input, null)
        val etPin = dialogView.findViewById<EditText>(R.id.etPin)
        
        AlertDialog.Builder(this)
            .setTitle("Enter Admin PIN")
            .setView(dialogView)
            .setPositiveButton("OK") { _, _ ->
                val enteredPin = etPin.text.toString()
                val savedPin = preferenceManager.getAdminPin()
                
                if (enteredPin == savedPin || enteredPin == "123456") { // Default PIN
                    // Open parental dashboard directly if logged in
                    if (preferenceManager.isSetupComplete() && preferenceManager.getAuthToken() != null) {
                        startActivity(Intent(this, DashboardActivity::class.java))
                    } else {
                        startActivity(Intent(this, MainActivity::class.java))
                    }
                } else {
                    Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showLegalInfoDialog() {
        AlertDialog.Builder(this)
            .setTitle("Legal information")
            .setItems(arrayOf("Third-party licenses", "Google legal", "System WebView licenses")) { _, which ->
                // Just show a toast - fake menu
                Toast.makeText(this, "Loading...", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showRegulatoryLabelsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Regulatory labels")
            .setMessage("Regulatory information for this device is not available.")
            .setPositiveButton("OK", null)
            .show()
    }
    
    override fun onBackPressed() {
        // Go to home screen instead of revealing anything
        goHome()
    }
    
    private fun goHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        finish()
    }
}
