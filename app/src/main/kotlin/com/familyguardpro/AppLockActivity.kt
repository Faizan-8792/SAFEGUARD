package com.familyguardpro

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ImageButton
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import com.familyguardpro.utils.PreferenceManager
import com.familyguardpro.applock.*

/**
 * Full-Featured App Lock Activity - Disguised interface for FamilyGuard
 * Shows a legitimate, fully functional app lock interface while parental control runs in background
 * Features:
 * - PIN/Pattern/Biometric lock
 * - App locking
 * - Photo/Video Vault
 * - Intruder Selfie
 * - Settings panel
 */
class AppLockActivity : AppCompatActivity() {
    
    private lateinit var preferenceManager: PreferenceManager
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: AppListAdapter
    private val installedApps = mutableListOf<AppInfo>()
    private val lockedApps = mutableSetOf<String>()
    
    // Secret tap counter for revealing real app
    private var secretTapCount = 0
    private var lastTapTime = 0L
    
    // Flag to track if we're waiting for usage stats permission
    private var pendingServiceStart = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        preferenceManager = PreferenceManager(this)
        
        // Check if launched via AppLockLauncher alias
        val componentName = intent?.component?.className ?: ""
        val launchedFromLauncher = componentName.contains("AppLockLauncher") || 
                                   componentName.contains("AppLockActivity")
        
        // If app is not configured AND we're in applock disguise mode, show AppLock UI anyway
        // This allows the AppLock disguise to work even before full setup
        val disguiseMode = preferenceManager.getDisguiseMode()
        val isInAppLockMode = disguiseMode == "applock"
        
        // Only redirect to setup if:
        // 1. App is not configured AND
        // 2. We're NOT in applock disguise mode AND
        // 3. We're NOT launched from launcher
        if (!preferenceManager.isConfigured() && !isInAppLockMode && !launchedFromLauncher) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        
        // Show AppLock interface in these cases:
        // 1. Launched from AppLockLauncher alias
        // 2. In applock disguise mode
        // 3. App is configured and this activity is opened directly
        
        setContentView(R.layout.activity_app_lock)
        
        setupUI()
        loadInstalledApps()
        
        // Start the app lock service if enabled
        if (preferenceManager.isAppLockServiceEnabled()) {
            startAppLockService()
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == com.familyguardpro.utils.PermissionsHelper.REQUEST_USAGE_ACCESS) {
            // Check if permission was granted
            if (com.familyguardpro.utils.PermissionsHelper.hasUsageStatsPermission(this)) {
                if (pendingServiceStart) {
                    pendingServiceStart = false
                    // Now actually start the service
                    val intent = Intent(this, AppLockService::class.java).apply {
                        action = "START"
                    }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                    Toast.makeText(this, "App Lock service started", Toast.LENGTH_SHORT).show()
                }
            } else {
                pendingServiceStart = false
                preferenceManager.setAppLockServiceEnabled(false)
                Toast.makeText(this, "Usage access permission denied. App Lock disabled.", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun setupUI() {
        // Title with secret tap
        val titleView = findViewById<TextView>(R.id.tvAppLockTitle)
        titleView?.setOnClickListener {
            handleSecretTap()
        }
        
        // Or use app icon
        val appIcon = findViewById<ImageView>(R.id.ivAppIcon)
        appIcon?.setOnClickListener {
            handleSecretTap()
        }
        
        // Setup RecyclerView for apps
        recyclerView = findViewById(R.id.rvApps)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = AppListAdapter(installedApps, lockedApps) { appInfo ->
            toggleAppLock(appInfo)
        }
        recyclerView.adapter = adapter
        
        // Feature Buttons
        findViewById<View>(R.id.btnVault)?.setOnClickListener {
            openVault()
        }
        
        findViewById<View>(R.id.btnIntruders)?.setOnClickListener {
            openIntruderPhotos()
        }
        
        findViewById<View>(R.id.btnSettings)?.setOnClickListener {
            showSettingsDialog()
        }
        
        // Set PIN button (LinearLayout in layout)
        findViewById<View>(R.id.btnSetPin)?.setOnClickListener {
            showSetPinDialog()
        }
        
        // Lock all button
        findViewById<View>(R.id.btnLockAll)?.setOnClickListener {
            lockAllApps()
        }
        
        // About button - hides reveal functionality
        findViewById<View>(R.id.btnAbout)?.setOnClickListener {
            showAboutDialog()
        }
        
        // Update locked apps count
        updateLockedCount()
    }
    
    private fun openVault() {
        // Check if PIN is set
        val pin = preferenceManager.getAppLockPin()
        if (pin.isEmpty()) {
            Toast.makeText(this, "Please set a PIN first", Toast.LENGTH_SHORT).show()
            showSetPinDialog()
            return
        }
        
        // Show PIN dialog
        showPinDialogForAction {
            startActivity(Intent(this, VaultActivity::class.java))
        }
    }
    
    private fun openIntruderPhotos() {
        showPinDialogForAction {
            startActivity(Intent(this, IntruderPhotosActivity::class.java))
        }
    }
    
    private fun showPinDialogForAction(onSuccess: () -> Unit) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_pin_input, null)
        val etPin = dialogView.findViewById<EditText>(R.id.etPin)
        
        AlertDialog.Builder(this)
            .setTitle("Enter PIN")
            .setView(dialogView)
            .setPositiveButton("Confirm") { _, _ ->
                val enteredPin = etPin.text.toString()
                val savedPin = preferenceManager.getAppLockPin()
                
                if (enteredPin == savedPin || (savedPin.isEmpty() && enteredPin == "0007")) {
                    onSuccess()
                } else {
                    Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showSettingsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_applock_settings, null)
        
        val switchBiometric = dialogView.findViewById<Switch>(R.id.switchBiometric)
        val switchIntruder = dialogView.findViewById<Switch>(R.id.switchIntruder)
        val switchHidePattern = dialogView.findViewById<Switch>(R.id.switchHidePattern)
        val switchService = dialogView.findViewById<Switch>(R.id.switchService)
        val tvLockMethod = dialogView.findViewById<TextView>(R.id.tvLockMethod)
        val tvRelockTime = dialogView.findViewById<TextView>(R.id.tvRelockTime)
        
        // Load current settings
        switchBiometric?.isChecked = preferenceManager.isBiometricEnabled()
        switchIntruder?.isChecked = preferenceManager.isIntruderSelfieEnabled()
        switchHidePattern?.isChecked = preferenceManager.isHidePatternEnabled()
        switchService?.isChecked = preferenceManager.isAppLockServiceEnabled()
        
        val lockMethod = preferenceManager.getLockMethod()
        tvLockMethod?.text = when (lockMethod) {
            "pattern" -> "Pattern"
            "biometric" -> "Biometric"
            else -> "PIN"
        }
        
        val relockTime = preferenceManager.getRelockTime()
        tvRelockTime?.text = when (relockTime) {
            0 -> "Immediately"
            1 -> "When screen turns off"
            2 -> "After 1 minute"
            3 -> "After 5 minutes"
            4 -> "After 30 minutes"
            else -> "When screen turns off"
        }
        
        // Lock method selector
        dialogView.findViewById<View>(R.id.layoutLockMethod)?.setOnClickListener {
            showLockMethodSelector(tvLockMethod)
        }
        
        // Relock time selector
        dialogView.findViewById<View>(R.id.layoutRelockTime)?.setOnClickListener {
            showRelockTimeSelector(tvRelockTime)
        }
        
        AlertDialog.Builder(this)
            .setTitle("App Lock Settings")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                // Save settings
                preferenceManager.setBiometricEnabled(switchBiometric?.isChecked ?: false)
                preferenceManager.setIntruderSelfieEnabled(switchIntruder?.isChecked ?: true)
                preferenceManager.setHidePatternEnabled(switchHidePattern?.isChecked ?: false)
                
                val serviceEnabled = switchService?.isChecked ?: false
                preferenceManager.setAppLockServiceEnabled(serviceEnabled)
                
                if (serviceEnabled) {
                    startAppLockService()
                } else {
                    stopAppLockService()
                }
                
                Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showLockMethodSelector(textView: TextView?) {
        val options = arrayOf("PIN", "Pattern")
        
        AlertDialog.Builder(this)
            .setTitle("Select Lock Method")
            .setItems(options) { _, which ->
                val method = when (which) {
                    1 -> "pattern"
                    else -> "pin"
                }
                preferenceManager.setLockMethod(method)
                textView?.text = options[which]
                
                if (method == "pattern") {
                    showSetPatternDialog()
                }
            }
            .show()
    }
    
    // Pattern setup - draw and confirm
    private var firstPattern: String? = null
    
    private fun showSetPatternDialog() {
        firstPattern = null
        
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_set_pattern, null)
        val patternView = dialogView.findViewById<com.familyguardpro.applock.PatternLockView>(R.id.patternView)
        val instructionText = dialogView.findViewById<TextView>(R.id.tvInstruction)
        
        instructionText?.text = "Draw your pattern (min 4 dots)"
        
        val dialog = AlertDialog.Builder(this)
            .setTitle("Set Pattern Lock")
            .setView(dialogView)
            .setNegativeButton("Cancel") { d, _ ->
                firstPattern = null
                d.dismiss()
            }
            .create()
        
        patternView?.setOnPatternListener(object : com.familyguardpro.applock.PatternLockView.OnPatternListener {
            override fun onPatternComplete(pattern: String) {
                if (pattern.length < 4) {
                    Toast.makeText(this@AppLockActivity, "Pattern must have at least 4 dots", Toast.LENGTH_SHORT).show()
                    patternView.clearPattern()
                    return
                }
                
                if (firstPattern == null) {
                    // First pattern entry
                    firstPattern = pattern
                    instructionText?.text = "Draw pattern again to confirm"
                    patternView.clearPattern()
                } else {
                    // Confirm pattern
                    if (firstPattern == pattern) {
                        preferenceManager.setLockPattern(pattern)
                        Toast.makeText(this@AppLockActivity, "Pattern set successfully!", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    } else {
                        Toast.makeText(this@AppLockActivity, "Patterns don't match. Try again", Toast.LENGTH_SHORT).show()
                        firstPattern = null
                        instructionText?.text = "Draw your pattern (min 4 dots)"
                        patternView.clearPattern()
                    }
                }
            }
        })
        
        dialog.show()
    }
    
    private fun showRelockTimeSelector(textView: TextView?) {
        val options = arrayOf(
            "Immediately",
            "When screen turns off",
            "After 1 minute",
            "After 5 minutes",
            "After 30 minutes"
        )
        
        AlertDialog.Builder(this)
            .setTitle("Re-lock Apps")
            .setItems(options) { _, which ->
                preferenceManager.setRelockTime(which)
                textView?.text = options[which]
            }
            .show()
    }
    
    private fun startAppLockService() {
        // Check for Usage Stats permission first
        if (!com.familyguardpro.utils.PermissionsHelper.hasUsageStatsPermission(this)) {
            pendingServiceStart = true
            AlertDialog.Builder(this)
                .setTitle("Permission Required")
                .setMessage("App Lock needs Usage Access permission to detect which apps are running. Please enable it in Settings.")
                .setPositiveButton("Open Settings") { _, _ ->
                    com.familyguardpro.utils.PermissionsHelper.requestUsageStatsPermission(this)
                }
                .setNegativeButton("Cancel") { _, _ ->
                    pendingServiceStart = false
                    preferenceManager.setAppLockServiceEnabled(false)
                    Toast.makeText(this, "App Lock service disabled", Toast.LENGTH_SHORT).show()
                }
                .show()
            return
        }
        
        val intent = Intent(this, AppLockService::class.java).apply {
            action = "START"
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "App Lock service started", Toast.LENGTH_SHORT).show()
    }
    
    private fun stopAppLockService() {
        val intent = Intent(this, AppLockService::class.java).apply {
            action = "STOP"
        }
        startService(intent)
        Toast.makeText(this, "App Lock service stopped", Toast.LENGTH_SHORT).show()
    }
    
    private fun updateLockedCount() {
        findViewById<TextView>(R.id.tvLockedCount)?.text = "${lockedApps.size} apps locked"
    }
    
    private fun handleSecretTap() {
        val currentTime = System.currentTimeMillis()
        
        if (currentTime - lastTapTime > 2000) {
            // Reset if more than 2 seconds between taps
            secretTapCount = 0
        }
        
        lastTapTime = currentTime
        secretTapCount++
        
        if (secretTapCount >= 7) {
            // Show PIN dialog to access real app
            showRevealPinDialog()
            secretTapCount = 0
        }
    }
    
    private fun showRevealPinDialog() {
        val savedPin = preferenceManager.getAdminPin()
        
        // Check if PIN is still default (not set by parent)
        if (savedPin == "123456") {
            AlertDialog.Builder(this)
                .setTitle("PIN Required")
                .setMessage("Please set a PIN from parent page → Settings, then try again.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_pin_input, null)
        val etPin = dialogView.findViewById<EditText>(R.id.etPin)
        
        AlertDialog.Builder(this)
            .setTitle("Enter Admin PIN")
            .setView(dialogView)
            .setPositiveButton("OK") { _, _ ->
                val enteredPin = etPin.text.toString()
                
                if (enteredPin == savedPin) {
                    // Show real app options
                    showAppModeDialog()
                } else {
                    Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showAppModeDialog() {
        AlertDialog.Builder(this)
            .setTitle("FamilyGuard")
            .setMessage("Access parental control options:")
            .setPositiveButton("Open Dashboard") { _, _ ->
                // Open real dashboard - go directly to DashboardActivity if already logged in
                if (preferenceManager.isSetupComplete() && preferenceManager.getAuthToken() != null) {
                    // Already logged in, go directly to dashboard
                    startActivity(Intent(this, DashboardActivity::class.java))
                } else {
                    // Not logged in, go to MainActivity for login
                    startActivity(Intent(this, MainActivity::class.java))
                }
            }
            .setNeutralButton("Show as Normal App") { _, _ ->
                // Reset disguise mode
                preferenceManager.setDisguiseMode("normal")
                AppDisguiseManager.switchToNormalMode(this)
                Toast.makeText(this, "App will appear normally now", Toast.LENGTH_LONG).show()
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun loadInstalledApps() {
        val pm = packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        
        installedApps.clear()
        
        for (appInfo in packages) {
            // Skip system apps (optional)
            if (appInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0) {
                try {
                    val appName = pm.getApplicationLabel(appInfo).toString()
                    val icon = pm.getApplicationIcon(appInfo)
                    installedApps.add(AppInfo(appInfo.packageName, appName, icon))
                } catch (e: Exception) {
                    // Skip apps that fail to load
                }
            }
        }
        
        // Sort alphabetically
        installedApps.sortBy { it.name }
        
        // Load locked apps from preferences
        lockedApps.clear()
        lockedApps.addAll(preferenceManager.getLockedApps())
        
        adapter.notifyDataSetChanged()
        updateLockedCount()
    }
    
    private fun toggleAppLock(appInfo: AppInfo) {
        if (lockedApps.contains(appInfo.packageName)) {
            lockedApps.remove(appInfo.packageName)
        } else {
            lockedApps.add(appInfo.packageName)
        }
        preferenceManager.setLockedApps(lockedApps)
        // Don't call notifyDataSetChanged() here - it causes recursive calls
        // The switch already shows the new state
        updateLockedCount()
        
        // Actually block the app via FamilyGuard's real blocking system
        // This makes the disguise functional!
        updateRealBlockedApps()
    }
    
    private fun lockAllApps() {
        lockedApps.clear()
        installedApps.forEach { lockedApps.add(it.packageName) }
        preferenceManager.setLockedApps(lockedApps)
        adapter.notifyDataSetChanged()
        updateRealBlockedApps()
        updateLockedCount()
        Toast.makeText(this, "All apps locked", Toast.LENGTH_SHORT).show()
    }
    
    private fun updateRealBlockedApps() {
        // AppLock uses its own locked apps list (separate from blocked apps)
        // The AppLockService monitors and shows PIN screen for locked apps
        // Do NOT sync with blocked apps - that shows "blocked by parent" overlay
        
        // Just ensure the AppLock service is running if there are locked apps
        if (lockedApps.isNotEmpty() && preferenceManager.isAppLockServiceEnabled()) {
            startAppLockService()
        }
    }
    
    private fun showSetPinDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_set_pin, null)
        val etCurrentPin = dialogView.findViewById<EditText>(R.id.etCurrentPin)
        val etNewPin = dialogView.findViewById<EditText>(R.id.etNewPin)
        val etConfirmPin = dialogView.findViewById<EditText>(R.id.etConfirmPin)
        
        val savedPin = preferenceManager.getAppLockPin()
        if (savedPin.isEmpty()) {
            etCurrentPin?.visibility = View.GONE
        }
        
        AlertDialog.Builder(this)
            .setTitle("Set App Lock PIN")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val currentPin = etCurrentPin?.text.toString()
                val newPin = etNewPin?.text.toString() ?: ""
                val confirmPin = etConfirmPin?.text.toString() ?: ""
                
                if (savedPin.isNotEmpty() && currentPin != savedPin) {
                    Toast.makeText(this, "Current PIN is incorrect", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                if (newPin.length < 4) {
                    Toast.makeText(this, "PIN must be at least 4 digits", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                if (newPin != confirmPin) {
                    Toast.makeText(this, "PINs do not match", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                preferenceManager.setAppLockPin(newPin)
                Toast.makeText(this, "PIN saved successfully", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle("App Lock Pro")
            .setMessage("Version 2.0\n\n• Protect your apps with PIN, Pattern, or Fingerprint\n• Secure Photo & Video Vault\n• Intruder Selfie on wrong PIN\n• Custom lock timers\n\nTip: Tap the title 7 times for hidden options.")
            .setPositiveButton("OK", null)
            .show()
    }
    
    // Data class for app info
    data class AppInfo(
        val packageName: String,
        val name: String,
        val icon: Drawable
    )
    
    // Adapter for the RecyclerView
    inner class AppListAdapter(
        private val apps: List<AppInfo>,
        private val locked: Set<String>,
        private val onToggle: (AppInfo) -> Unit
    ) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {
        
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.ivAppIcon)
            val name: TextView = view.findViewById(R.id.tvAppName)
            val switch: Switch = view.findViewById(R.id.switchLock)
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app_lock, parent, false)
            return ViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = apps[position]
            holder.icon.setImageDrawable(app.icon)
            holder.name.text = app.name
            
            // Remove listener before setting checked state to prevent recursive calls
            holder.switch.setOnCheckedChangeListener(null)
            holder.switch.isChecked = locked.contains(app.packageName)
            
            // Set listener after setting checked state
            holder.switch.setOnCheckedChangeListener { _, isChecked ->
                // Only call if state actually changed
                val wasLocked = locked.contains(app.packageName)
                if (isChecked != wasLocked) {
                    onToggle(app)
                }
            }
            
            holder.itemView.setOnClickListener {
                holder.switch.toggle()
            }
        }
        
        override fun getItemCount() = apps.size
    }
}
