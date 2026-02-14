package com.familyguardpro.deviceowner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import com.familyguardpro.services.WebSocketSyncService
import org.json.JSONObject

/**
 * InstallResultReceiver - Handles results from silent app install/uninstall operations.
 * 
 * When DeviceOwnerManager initiates a silent install/uninstall via PackageInstaller,
 * the result is delivered to this receiver. We then notify the parent dashboard
 * via WebSocket about the result.
 */
class InstallResultReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "InstallResultReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "Received action: $action")
        
        when (action) {
            "com.familyguardpro.INSTALL_RESULT" -> handleInstallResult(context, intent)
            "com.familyguardpro.UNINSTALL_RESULT" -> handleUninstallResult(context, intent)
        }
    }

    private fun handleInstallResult(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Unknown"
        val sessionId = intent.getIntExtra("session_id", -1)
        val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) ?: "Unknown"
        
        Log.d(TAG, "Install result: status=$status, message=$statusMessage, package=$packageName, session=$sessionId")
        
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Log.d(TAG, "✅ App installed successfully: $packageName")
                notifyParent("app_installed", packageName, true, null)
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // This shouldn't happen for Device Owner, but handle it
                Log.w(TAG, "⚠️ User action required for install (unexpected for DO)")
                val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirmIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirmIntent != null) {
                    context.startActivity(confirmIntent)
                }
            }
            PackageInstaller.STATUS_FAILURE -> {
                Log.e(TAG, "❌ Install failed: $statusMessage")
                notifyParent("app_install_failed", packageName, false, statusMessage)
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Log.e(TAG, "❌ Install aborted: $statusMessage")
                notifyParent("app_install_failed", packageName, false, "Installation aborted")
            }
            PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                Log.e(TAG, "❌ Install blocked: $statusMessage")
                notifyParent("app_install_failed", packageName, false, "Installation blocked by policy")
            }
            PackageInstaller.STATUS_FAILURE_CONFLICT -> {
                Log.e(TAG, "❌ Install conflict: $statusMessage")
                notifyParent("app_install_failed", packageName, false, "Package conflict - already installed")
            }
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                Log.e(TAG, "❌ Incompatible package: $statusMessage")
                notifyParent("app_install_failed", packageName, false, "Incompatible with this device")
            }
            PackageInstaller.STATUS_FAILURE_INVALID -> {
                Log.e(TAG, "❌ Invalid package: $statusMessage")
                notifyParent("app_install_failed", packageName, false, "Invalid APK file")
            }
            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                Log.e(TAG, "❌ Insufficient storage: $statusMessage")
                notifyParent("app_install_failed", packageName, false, "Insufficient storage space")
            }
        }
    }

    private fun handleUninstallResult(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Unknown"
        val packageName = intent.getStringExtra("package_name") ?: "Unknown"
        
        Log.d(TAG, "Uninstall result: status=$status, message=$statusMessage, package=$packageName")
        
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Log.d(TAG, "✅ App uninstalled successfully: $packageName")
                notifyParent("app_uninstalled", packageName, true, null)
            }
            else -> {
                Log.e(TAG, "❌ Uninstall failed: $statusMessage")
                notifyParent("app_uninstall_failed", packageName, false, statusMessage)
            }
        }
    }

    /**
     * Notify parent dashboard via WebSocket about install/uninstall result
     */
    private fun notifyParent(type: String, packageName: String, success: Boolean, error: String?) {
        try {
            val data = JSONObject().apply {
                put("type", type)
                put("package_name", packageName)
                put("success", success)
                put("device_owner_mode", true)
                put("timestamp", System.currentTimeMillis())
                if (error != null) put("error", error)
            }
            WebSocketSyncService.sendMessage(type, data)
        } catch (e: Exception) {
            Log.e(TAG, "Error notifying parent about install result", e)
        }
    }
}
