package com.familyguardpro.deviceowner

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.familyguardpro.R
import com.familyguardpro.SetupActivity
import com.familyguardpro.network.ApiClient
import com.familyguardpro.utils.PreferenceManager
import kotlinx.coroutines.*

/**
 * ProvisioningActivity - Guides the user through Device Owner provisioning.
 * 
 * Two provisioning methods:
 * 1. QR Code Method (recommended) - Factory reset → scan QR → automatic setup
 * 2. ADB Method (advanced) - Connect USB → run ADB command → open app
 * 
 * This activity also handles post-provisioning setup when the app detects
 * it has been set as Device Owner.
 */
class ProvisioningActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ProvisioningActivity"
        const val EXTRA_IS_POST_PROVISION = "is_post_provision"
        
        fun createPostProvisionIntent(context: Context): Intent {
            return Intent(context, ProvisioningActivity::class.java).apply {
                putExtra(EXTRA_IS_POST_PROVISION, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var prefManager: PreferenceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        prefManager = PreferenceManager(this)
        
        val isPostProvision = intent.getBooleanExtra(EXTRA_IS_POST_PROVISION, false)
        
        if (isPostProvision) {
            // Device was just provisioned - run post-provisioning setup
            handlePostProvisioning()
            return
        }
        
        setContentView(R.layout.activity_provisioning)
        setupUI()
    }

    private fun setupUI() {
        // QR Code method section
        val btnQrMethod = findViewById<Button>(R.id.btnQrCodeMethod)
        val tvQrSteps = findViewById<TextView>(R.id.tvQrCodeSteps)
        
        // ADB method section
        val btnAdbMethod = findViewById<Button>(R.id.btnAdbMethod)
        val tvAdbSteps = findViewById<TextView>(R.id.tvAdbSteps)
        
        // Back button
        val btnBack = findViewById<Button>(R.id.btnBackToModeSelection)
        
        btnQrMethod?.setOnClickListener {
            showQrCodeInstructions()
        }
        
        btnAdbMethod?.setOnClickListener {
            showAdbInstructions()
        }
        
        btnBack?.setOnClickListener {
            val intent = Intent(this, ModeSelectionActivity::class.java)
            startActivity(intent)
            finish()
        }
        
        // Set QR Code steps
        tvQrSteps?.text = buildString {
            appendLine("QR Code Provisioning (Recommended)")
            appendLine()
            appendLine("Steps:")
            appendLine("1. On the PARENT phone, open FamilyGuard dashboard")
            appendLine("2. Go to Device Owner → Generate QR Code")
            appendLine("3. Factory reset this child device")
            appendLine("4. On the Welcome screen, tap 6 times rapidly")
            appendLine("5. QR code scanner will appear")
            appendLine("6. Scan the QR code from parent dashboard")
            appendLine("7. Device will set up automatically")
            appendLine()
            appendLine("⚠️ This requires a factory reset of the child device!")
        }
        
        // Set ADB steps
        tvAdbSteps?.text = buildString {
            appendLine("ADB Provisioning (Advanced)")
            appendLine()
            appendLine("Prerequisites:")
            appendLine("• ADB installed on computer")
            appendLine("• USB Debugging enabled on child device")
            appendLine("• FamilyGuard Pro installed on child device")
            appendLine("• All Google accounts removed from child device")
            appendLine()
            appendLine("Command:")
            appendLine("adb shell dpm set-device-owner")
            appendLine("  com.familyguardpro/.services.DeviceAdminReceiver")
            appendLine()
            appendLine("After running the command, open this app.")
        }
    }

    private fun showQrCodeInstructions() {
        AlertDialog.Builder(this)
            .setTitle("QR Code Method")
            .setMessage(
                "To use QR Code provisioning:\n\n" +
                "1. Go to the parent dashboard (web)\n" +
                "2. Select the Device Owner section\n" +
                "3. Click 'Generate QR Code'\n" +
                "4. Factory reset the child device\n" +
                "5. On Welcome screen, tap 6 times\n" +
                "6. Scan the generated QR code\n\n" +
                "The child device will automatically download and set up " +
                "FamilyGuard Pro as Device Owner.\n\n" +
                "⚠️ WARNING: This will erase all data on the child device!"
            )
            .setPositiveButton("I Understand") { _, _ ->
                // Show a toast with reminder
                android.widget.Toast.makeText(
                    this,
                    "Generate the QR code from the parent dashboard",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAdbInstructions() {
        AlertDialog.Builder(this)
            .setTitle("ADB Method")
            .setMessage(
                "Run this command on your computer:\n\n" +
                "adb shell dpm set-device-owner com.familyguardpro/com.familyguardpro.services.DeviceAdminReceiver\n\n" +
                "If you get an error about accounts:\n" +
                "1. Remove all Google accounts from Settings\n" +
                "2. Disable 'Find My Device'\n" +
                "3. Try the command again\n\n" +
                "For Xiaomi/MIUI:\n" +
                "• Also disable MIUI Optimization in Developer Options\n" +
                "• Run: adb shell pm remove-user 999\n\n" +
                "After success, tap 'I've Run the Command' below."
            )
            .setPositiveButton("I've Run the Command") { _, _ ->
                checkDeviceOwnerStatus()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkDeviceOwnerStatus() {
        val doManager = DeviceOwnerManager.getInstance(this)
        
        if (doManager.isDeviceOwner()) {
            android.widget.Toast.makeText(
                this, "✅ Device Owner activated!", android.widget.Toast.LENGTH_LONG
            ).show()
            handlePostProvisioning()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Not Device Owner")
                .setMessage(
                    "Device Owner is not yet active.\n\n" +
                    "Please make sure you:\n" +
                    "1. Removed all Google accounts\n" +
                    "2. Ran the ADB command successfully\n" +
                    "3. Saw 'Success' in the terminal\n\n" +
                    "Would you like to try again?"
                )
                .setPositiveButton("Check Again") { _, _ -> checkDeviceOwnerStatus() }
                .setNegativeButton("Use Child Mode Instead") { _, _ ->
                    // Fall back to regular child mode
                    val intent = Intent(this, SetupActivity::class.java)
                    startActivity(intent)
                    finish()
                }
                .show()
        }
    }

    /**
     * Handle post-provisioning setup.
     * This is called either:
     * 1. After QR code provisioning (DeviceAdminReceiver.onProfileProvisioningComplete)
     * 2. After ADB provisioning (user confirms from this activity)
     */
    private fun handlePostProvisioning() {
        // Show a progress screen
        setContentView(R.layout.activity_provisioning_progress)
        
        val tvStatus = findViewById<TextView>(R.id.tvProvisioningStatus)
        val progressBar = findViewById<ProgressBar>(R.id.provisioningProgress)
        
        scope.launch {
            try {
                tvStatus?.text = "Setting up Device Owner mode..."
                progressBar?.visibility = View.VISIBLE
                
                val doManager = DeviceOwnerManager.getInstance(this@ProvisioningActivity)
                
                // Step 1: Mark provisioned
                withContext(Dispatchers.IO) {
                    delay(500)
                }
                tvStatus?.text = "Activating Device Owner..."
                doManager.markProvisioned()
                
                // Step 2: Grant permissions
                withContext(Dispatchers.IO) {
                    delay(500)
                }
                tvStatus?.text = "Granting permissions silently..."
                withContext(Dispatchers.IO) {
                    doManager.grantAllPermissions()
                }
                
                // Step 3: Enable uninstall protection
                tvStatus?.text = "Enabling uninstall protection..."
                withContext(Dispatchers.IO) {
                    doManager.setUninstallProtection(true)
                }
                
                // Step 4: Force-enable accessibility
                tvStatus?.text = "Enabling accessibility service..."
                withContext(Dispatchers.IO) {
                    doManager.forceEnableAccessibility()
                    doManager.setAccessibilityAutoRecover(true)
                }
                
                // Step 5: Run OEM optimizer
                tvStatus?.text = "Optimizing for ${android.os.Build.MANUFACTURER}..."
                withContext(Dispatchers.IO) {
                    try {
                        val optimizer = OemOptimizerFactory.createOptimizer(this@ProvisioningActivity)
                        optimizer.optimize()
                    } catch (e: Exception) {
                        Log.e(TAG, "OEM optimization failed", e)
                    }
                }
                
                // Step 6: Confirm with server
                tvStatus?.text = "Confirming with server..."
                withContext(Dispatchers.IO) {
                    try {
                        val deviceId = prefManager.getDeviceId()
                        val parentId = prefManager.getParentId()
                        if (!deviceId.isNullOrEmpty() && !parentId.isNullOrEmpty()) {
                            ApiClient.confirmDeviceOwnerProvisioning(
                                deviceId = deviceId,
                                parentUserId = parentId,
                                method = "adb"
                            )
                        } else {
                            Log.w(TAG, "Missing deviceId or parentId, skipping server confirmation")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Server confirmation failed (will retry later)", e)
                    }
                }
                
                tvStatus?.text = "✅ Device Owner setup complete!"
                progressBar?.visibility = View.GONE
                
                delay(1500)
                
                // Proceed to child mode setup
                prefManager.setChildMode(true)
                val intent = Intent(this@ProvisioningActivity, SetupActivity::class.java).apply {
                    putExtra("device_owner_mode", true)
                    putExtra("skip_permissions", true) // Already granted silently
                }
                startActivity(intent)
                finish()
                
            } catch (e: Exception) {
                Log.e(TAG, "Post-provisioning setup failed", e)
                tvStatus?.text = "Setup failed: ${e.message}"
                progressBar?.visibility = View.GONE
                
                delay(3000)
                // Still proceed - can retry the DO-specific setups later
                val intent = Intent(this@ProvisioningActivity, SetupActivity::class.java)
                startActivity(intent)
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
