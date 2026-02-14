package com.familyguardpro

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.familyguardpro.databinding.ActivityMiuiSetupBinding
import com.familyguardpro.utils.DeviceUtils
import com.familyguardpro.utils.PermissionsHelper

/**
 * MIUI-specific setup wizard for Xiaomi/Poco/Redmi devices.
 * 
 * MIUI has 10+ hidden restrictions that kill background services:
 * 1. Autostart permission (disabled by default)
 * 2. Background autostart (MIUI 14+)
 * 3. Battery saver kills accessibility services
 * 4. App battery saver (separate from Android's)
 * 5. Clear cache when device locked
 * 6. Turn off mobile data when locked
 * 7. Sleep mode scenarios
 * 8. Automated tasks (time-based killing)
 * 9. MIUI optimization setting
 * 10. App pinning/locking required
 */
class MiuiSetupActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMiuiSetupBinding
    private var currentStep = 0
    private lateinit var steps: List<SetupStep>
    
    private val handler = Handler(Looper.getMainLooper())
    
    data class SetupStep(
        val title: String,
        val description: String,
        val guide: String,
        val critical: Boolean = false,
        val checkMethod: () -> Boolean,
        val openSettingsMethod: () -> Boolean,
        val skipCondition: () -> Boolean = { false } // If true, skip this step
    )
    
    companion object {
        private const val TAG = "MiuiSetupActivity"
        private const val PREFS_NAME = "miui_setup"
        private const val KEY_COMPLETED = "completed"
        private const val KEY_COMPLETED_AT = "completed_at"
        private const val KEY_LAST_STEP = "last_step"
        
        /**
         * Check if MIUI setup is needed
         */
        fun isSetupNeeded(context: Context): Boolean {
            if (!DeviceUtils.isMiui()) return false
            
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return !prefs.getBoolean(KEY_COMPLETED, false)
        }
        
        /**
         * Start MIUI setup activity
         */
        fun start(context: Context) {
            context.startActivity(Intent(context, MiuiSetupActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }
        
        /**
         * Mark setup as completed
         */
        fun markCompleted(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_COMPLETED, true)
                .putLong(KEY_COMPLETED_AT, System.currentTimeMillis())
                .apply()
        }
        
        /**
         * Reset setup (for testing or re-running)
         */
        fun resetSetup(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .clear()
                .apply()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMiuiSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Log device info
        DeviceUtils.logDeviceInfo()
        
        initSteps()
        setupClickListeners()
        
        // Restore last step if returning
        currentStep = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getInt(KEY_LAST_STEP, 0)
        
        showCurrentStep()
    }
    
    private fun initSteps() {
        val miuiVersion = DeviceUtils.getMiuiVersion()
        
        steps = listOf(
            // Step 0: Welcome/Warning
            SetupStep(
                title = "Xiaomi/MIUI Device Detected",
                description = "Your ${DeviceUtils.getDeviceInfo()} requires special setup",
                guide = """
                    MIUI has aggressive battery optimization that kills background apps.
                    
                    Without completing these steps, the app WILL STOP WORKING after a few minutes.
                    
                    This setup takes about 3-5 minutes and only needs to be done once.
                    
                    Please complete ALL steps carefully.
                """.trimIndent(),
                critical = true,
                checkMethod = { true },
                openSettingsMethod = { true }
            ),
            
            // Step 1: Autostart Permission - CRITICAL
            SetupStep(
                title = "Enable Autostart",
                description = "CRITICAL: App will stop after reboot without this",
                guide = """
                    1. Tap 'Open Settings' button below
                    2. The Security app will open
                    3. Find 'FamilyGuard Pro' or 'System Service' in the list
                    4. Toggle the switch to ON (green)
                    5. Press back to return here
                    
                    Note: Some versions show this under:
                    Settings > Apps > Manage apps > FamilyGuard Pro > Autostart
                """.trimIndent(),
                critical = true,
                checkMethod = { false }, // Can't programmatically check
                openSettingsMethod = { DeviceUtils.openMiuiAutoStart(this) }
            ),
            
            // Step 2: Battery Saver - No Restrictions
            SetupStep(
                title = "Battery Saver → No Restrictions",
                description = "Prevents MIUI from stopping background monitoring",
                guide = """
                    1. Tap 'Open Settings' button below
                    2. Find 'Battery saver' or 'Power consumption'
                    3. Select 'No restrictions' (not 'Intelligent' or 'Restrict')
                    4. Press back to return here
                    
                    This ensures the app can run continuously in background.
                """.trimIndent(),
                critical = true,
                checkMethod = { 
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    pm.isIgnoringBatteryOptimizations(packageName)
                },
                openSettingsMethod = { DeviceUtils.openMiuiBatterySettings(this) }
            ),
            
            // Step 3: Background Autostart (MIUI 14+)
            SetupStep(
                title = "Background Autostart (MIUI 14+)",
                description = "Allow app to start from background",
                guide = """
                    1. Tap 'Open Settings' below
                    2. Find 'Other permissions' or 'Special permissions'
                    3. Look for 'Background autostart' toggle
                    4. Enable it (turn ON)
                    5. Press back to return here
                    
                    If you don't see this option, your MIUI version doesn't require it.
                """.trimIndent(),
                critical = false,
                checkMethod = { true },
                openSettingsMethod = { DeviceUtils.openMiuiBackgroundAutoStart(this) },
                skipCondition = { miuiVersion < 14 }
            ),
            
            // Step 4: Display overlay permission
            SetupStep(
                title = "Display Pop-up Windows",
                description = "Required for foreground service",
                guide = """
                    1. Tap 'Open Settings' below
                    2. Find 'FamilyGuard Pro' or 'System Service'
                    3. Enable 'Display pop-up windows'
                    4. Also enable 'Display pop-up window while running in background'
                    5. Press back to return here
                """.trimIndent(),
                critical = false,
                checkMethod = { Settings.canDrawOverlays(this) },
                openSettingsMethod = {
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                            data = Uri.parse("package:$packageName")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        })
                        true
                    } catch (e: Exception) {
                        false
                    }
                }
            ),
            
            // Step 5: Allow Restricted Settings (Android 13+ / MIUI 14+)
            SetupStep(
                title = "Allow Restricted Settings",
                description = "Required for Accessibility Service on Android 13+",
                guide = """
                    IMPORTANT FOR MIUI 14+ / Android 13+:
                    
                    1. Tap 'Open Settings' button below
                    2. Look for 'Allow restricted settings' option
                    3. Toggle it ON
                    
                    If prompted, enter your device PIN/Pattern/Password
                    
                    Why this is needed:
                    Android 13+ blocks sideloaded apps from enabling Accessibility 
                    and other sensitive permissions by default.
                    
                    Without this permission:
                    • Accessibility Service cannot be enabled
                    • App monitoring will not work
                    • Keystroke monitoring will not work
                    
                    If you don't see this option, your device may not require it.
                """.trimIndent(),
                critical = true,
                checkMethod = { true }, // Can't programmatically check
                openSettingsMethod = { 
                    try {
                        // Open app info where "Allow restricted settings" can be found
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:$packageName")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        startActivity(intent)
                        true
                    } catch (e: Exception) {
                        false
                    }
                },
                skipCondition = { android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU }
            ),
            
            // Step 6: Disable System Battery Saver
            SetupStep(
                title = "Disable System Battery Saver",
                description = "Battery saver mode kills Accessibility Service",
                guide = """
                    1. Tap 'Open Settings' below
                    2. Navigate to: Settings > Battery 
                    3. Turn OFF 'Battery saver' mode
                    4. Also turn OFF 'Ultra battery saver' if present
                    5. Press back to return here
                    
                    IMPORTANT: When battery saver is ON, MIUI forcibly disables 
                    Accessibility Service even if it shows as "enabled" in settings.
                """.trimIndent(),
                critical = true,
                checkMethod = { true },
                openSettingsMethod = { DeviceUtils.openSystemBatterySaver(this) }
            ),
            
            // Step 6: Battery Settings - Advanced
            SetupStep(
                title = "Battery Advanced Settings",
                description = "Prevent automatic cleanup when locked",
                guide = """
                    Go to: Settings > Battery > Settings (gear icon)
                    
                    Configure these settings:
                    
                    • 'Turn off mobile data when device is locked' → Set to NEVER
                    • 'Clear cache when device is locked' → Set to NEVER
                    • 'Limit background activity' → DISABLED
                    
                    These settings prevent MIUI from killing the app when you lock your phone.
                """.trimIndent(),
                critical = true,
                checkMethod = { true },
                openSettingsMethod = { DeviceUtils.openSystemBatterySaver(this) }
            ),
            
            // Step 7: Disable Sleep Mode
            SetupStep(
                title = "Disable Sleep Mode",
                description = "Sleep mode kills background apps",
                guide = """
                    Go to: Settings > Battery > Scenarios
                    
                    • Turn OFF 'Sleep mode'
                    • Turn OFF any scheduled power saving modes
                    
                    Or go to: Settings > Sleep
                    • Disable sleep mode completely
                    
                    Sleep mode aggressively kills background apps to save battery.
                """.trimIndent(),
                critical = false,
                checkMethod = { true },
                openSettingsMethod = { DeviceUtils.openSystemBatterySaver(this) }
            ),
            
            // Step 8: Disable Automated Tasks
            SetupStep(
                title = "Disable Automated Tasks",
                description = "Scheduled optimizations kill apps",
                guide = """
                    Go to: Settings > Battery > Automated tasks
                    
                    • Disable ALL scheduled tasks
                    • Turn OFF any time-based optimizations
                    
                    These scheduled tasks can kill background apps at specific times.
                """.trimIndent(),
                critical = false,
                checkMethod = { true },
                openSettingsMethod = { DeviceUtils.openSystemBatterySaver(this) }
            ),
            
            // Step 9: Disable MIUI Optimization - CRITICAL
            SetupStep(
                title = "Disable MIUI Optimization",
                description = "MOST IMPORTANT step for background operation",
                guide = """
                    CRITICAL STEP:
                    
                    1. First, enable Developer Options:
                       Settings > About phone > Tap 'MIUI version' 7 times
                    
                    2. Then go to:
                       Settings > Additional settings > Developer options
                    
                    3. Scroll to the very bottom
                    
                    4. Find 'MIUI optimization' and turn it OFF
                    
                    5. Confirm by tapping 'Turn off' in the popup
                    
                    6. Your device may reboot - this is normal
                    
                    This single setting controls most of MIUI's aggressive killing behavior.
                """.trimIndent(),
                critical = true,
                checkMethod = { true },
                openSettingsMethod = { DeviceUtils.openDeveloperOptions(this) }
            ),
            
            // Step 10: Lock App in Recent Apps - CRITICAL
            SetupStep(
                title = "Lock App in Recent Apps",
                description = "THE MOST CRITICAL STEP - Without this app WILL stop",
                guide = """
                    THIS IS THE MOST IMPORTANT STEP:
                    
                    1. Press the Recent Apps button (□ square icon)
                    
                    2. Find 'FamilyGuard Pro' or 'System Service' card
                    
                    3. SWIPE DOWN on the app card (NOT left/right)
                    
                    4. You'll see a LOCK ICON (🔒) appear at top of the card
                    
                    5. The lock means the app is protected from being killed
                    
                    ⚠️ WITHOUT THIS STEP, MIUI WILL KILL THE APP WITHIN MINUTES!
                    
                    ⚠️ NEVER use 'Clear All' in recents - it removes the lock!
                """.trimIndent(),
                critical = true,
                checkMethod = { true },
                openSettingsMethod = { 
                    // Go to home so user can open recents
                    startActivity(Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                    true
                }
            )
        )
    }
    
    private fun setupClickListeners() {
        binding.btnOpenSettings.setOnClickListener {
            openCurrentSettings()
        }
        
        binding.btnNext.setOnClickListener {
            goToNextStep()
        }
        
        binding.btnSkip.setOnClickListener {
            goToNextStep()
        }
        
        binding.btnBack.setOnClickListener {
            goToPreviousStep()
        }
    }
    
    private fun showCurrentStep() {
        // Skip steps that don't apply
        while (currentStep < steps.size && steps[currentStep].skipCondition()) {
            currentStep++
        }
        
        if (currentStep >= steps.size) {
            finishSetup()
            return
        }
        
        // Save current step
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putInt(KEY_LAST_STEP, currentStep)
            .apply()
        
        val step = steps[currentStep]
        val totalSteps = steps.filter { !it.skipCondition() }.size
        val actualStep = steps.take(currentStep + 1).count { !it.skipCondition() }
        
        // Update UI
        binding.tvStepNumber.text = "Step $actualStep of $totalSteps"
        binding.progressBar.max = totalSteps
        binding.progressBar.progress = actualStep
        
        binding.tvTitle.text = step.title
        binding.tvDescription.text = step.description
        binding.tvGuide.text = step.guide
        
        // Critical indicator
        if (step.critical) {
            binding.tvDescription.setTextColor(ContextCompat.getColor(this, R.color.error))
            binding.cardCritical.visibility = View.VISIBLE
        } else {
            binding.tvDescription.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            binding.cardCritical.visibility = View.GONE
        }
        
        // Button visibility
        binding.btnBack.visibility = if (currentStep > 0) View.VISIBLE else View.INVISIBLE
        binding.btnSkip.visibility = if (!step.critical) View.VISIBLE else View.GONE
        
        // Open Settings button - hide for welcome step
        binding.btnOpenSettings.visibility = if (currentStep == 0) View.GONE else View.VISIBLE
        
        // Next button text
        binding.btnNext.text = when {
            currentStep == 0 -> "Start Setup"
            currentStep == steps.size - 1 -> "Finish"
            else -> "Next"
        }
        
        // Check if already completed
        if (step.checkMethod()) {
            binding.cardStatus.visibility = View.VISIBLE
            binding.tvStatus.text = "✓ Already configured"
            binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.success))
        } else {
            binding.cardStatus.visibility = View.GONE
        }
    }
    
    private fun openCurrentSettings() {
        val step = steps[currentStep]
        val success = step.openSettingsMethod()
        
        if (!success) {
            AlertDialog.Builder(this)
                .setTitle("Cannot Open Settings")
                .setMessage("Please manually navigate to the settings mentioned in the guide above.")
                .setPositiveButton("OK", null)
                .show()
        }
    }
    
    private fun goToNextStep() {
        currentStep++
        showCurrentStep()
    }
    
    private fun goToPreviousStep() {
        if (currentStep > 0) {
            currentStep--
            showCurrentStep()
        }
    }
    
    private fun finishSetup() {
        // Mark as completed
        markCompleted(this)
        
        // Show completion dialog
        AlertDialog.Builder(this)
            .setTitle("MIUI Setup Complete!")
            .setMessage(
                """
                All MIUI settings have been configured.
                
                IMPORTANT REMINDERS:
                
                ✓ Keep the app LOCKED in recent apps
                ✓ Don't use 'Clear All' button in recents
                ✓ Keep system battery saver OFF
                ✓ Keep MIUI optimization DISABLED
                
                If app stops working, run this setup again from:
                Settings > Apps > FamilyGuard Pro
                
                The app should now work continuously in background!
                """.trimIndent()
            )
            .setPositiveButton("Finish") { _, _ ->
                // Return to main setup or child status
                Toast.makeText(this, "MIUI setup complete!", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setCancelable(false)
            .show()
    }
    
    override fun onResume() {
        super.onResume()
        // Recheck status when returning from settings
        handler.postDelayed({
            if (currentStep < steps.size) {
                val step = steps[currentStep]
                if (step.checkMethod()) {
                    binding.cardStatus.visibility = View.VISIBLE
                    binding.tvStatus.text = "✓ Configured"
                    binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.success))
                }
            }
        }, 500)
    }
    
    override fun onBackPressed() {
        // Warn user before exiting incomplete setup
        if (currentStep > 0 && currentStep < steps.size) {
            AlertDialog.Builder(this)
                .setTitle("Exit Setup?")
                .setMessage(
                    "Setup is not complete. Without finishing, the app may not work properly on your MIUI device.\n\nAre you sure you want to exit?"
                )
                .setPositiveButton("Exit Anyway") { _, _ ->
                    super.onBackPressed()
                }
                .setNegativeButton("Continue Setup", null)
                .show()
        } else {
            super.onBackPressed()
        }
    }
}
