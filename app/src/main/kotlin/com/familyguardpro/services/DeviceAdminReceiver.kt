package com.familyguardpro.services

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.widget.Toast

class DeviceAdminReceiver : DeviceAdminReceiver() {
    
    companion object {
        private const val TAG = "DeviceAdminReceiver"
        
        /**
         * Check if Device Admin is currently active
         */
        fun isAdminActive(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val componentName = ComponentName(context, DeviceAdminReceiver::class.java)
            return dpm.isAdminActive(componentName)
        }
        
        /**
         * Check if accessibility service is enabled
         */
        fun isAccessibilityEnabled(context: Context): Boolean {
            val accessibilityEnabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return accessibilityEnabled.contains("com.familyguardpro")
        }
        
        /**
         * Check if app can be uninstalled (both admin and accessibility must be off)
         */
        fun canUninstall(context: Context): Boolean {
            return !isAdminActive(context) && !isAccessibilityEnabled(context)
        }
    }
    
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.d(TAG, "Device admin enabled")
        Toast.makeText(context, "Device protection enabled", Toast.LENGTH_SHORT).show()
        
        // Force-enable and lock accessibility when admin is activated
        try {
            val doManager = com.familyguardpro.deviceowner.DeviceOwnerManager.getInstance(context)
            if (doManager.isDeviceOwner()) {
                doManager.forceEnableAccessibility()
                doManager.lockAccessibilitySettings()
                Log.d(TAG, "Accessibility locked on admin enable")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error locking accessibility on admin enable", e)
        }
    }
    
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        Log.d(TAG, "Device admin disable requested")
        // Return warning message that will be shown in the system dialog
        return try {
            val stringId = context.resources.getIdentifier(
                "device_admin_disable_warning", 
                "string", 
                context.packageName
            )
            if (stringId != 0) {
                context.getString(stringId)
            } else {
                "WARNING: Disabling Device Admin will remove parental controls. Your guardian will be notified of this action."
            }
        } catch (e: Exception) {
            "WARNING: Disabling Device Admin will remove parental controls. Your guardian will be notified of this action."
        }
    }
    
    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.d(TAG, "Device admin disabled")
        Toast.makeText(context, "Device protection disabled", Toast.LENGTH_SHORT).show()
    }
    
    override fun onPasswordChanged(context: Context, intent: Intent) {
        super.onPasswordChanged(context, intent)
        Log.d(TAG, "Password changed")
    }
    
    override fun onPasswordFailed(context: Context, intent: Intent) {
        super.onPasswordFailed(context, intent)
        Log.d(TAG, "Password failed")
    }
    
    override fun onPasswordSucceeded(context: Context, intent: Intent) {
        super.onPasswordSucceeded(context, intent)
        Log.d(TAG, "Password succeeded")
    }
    
    /**
     * Called when Device Owner provisioning is complete.
     * This is triggered after QR code or NFC provisioning flow completes.
     */
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Log.d(TAG, "=== DEVICE OWNER PROVISIONING COMPLETE ===")
        
        try {
            // Enable the Device Owner profile
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val componentName = ComponentName(context, DeviceAdminReceiver::class.java)
            
            // Set the profile name
            dpm.setProfileName(componentName, "FamilyGuard Pro")
            
            // Launch post-provisioning activity
            val provisioningIntent = Intent(context, com.familyguardpro.deviceowner.ProvisioningActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(com.familyguardpro.deviceowner.ProvisioningActivity.EXTRA_IS_POST_PROVISION, true)
            }
            context.startActivity(provisioningIntent)
            
            Log.d(TAG, "Launched post-provisioning activity")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling provisioning complete", e)
        }
    }
}
