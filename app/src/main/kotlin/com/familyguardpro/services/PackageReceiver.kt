package com.familyguardpro.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.familyguardpro.FamilyGuardApp
import com.familyguardpro.network.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PackageReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "PackageReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? FamilyGuardApp
        if (app?.preferenceManager?.isChildMode() != true) {
            return
        }
        
        val packageName = intent.data?.schemeSpecificPart ?: return
        
        when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED -> {
                Log.d(TAG, "Package installed: $packageName")
                reportAppChange(context, "installed", packageName)
            }
            Intent.ACTION_PACKAGE_REMOVED -> {
                Log.d(TAG, "Package removed: $packageName")
                reportAppChange(context, "uninstalled", packageName)
            }
        }
    }
    
    private fun reportAppChange(context: Context, action: String, packageName: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as? FamilyGuardApp
                val deviceId = app?.preferenceManager?.getDeviceId() ?: return@launch
                
                val appName = try {
                    val pm = context.packageManager
                    val appInfo = pm.getApplicationInfo(packageName, 0)
                    pm.getApplicationLabel(appInfo).toString()
                } catch (e: Exception) {
                    packageName
                }
                
                ApiClient.reportAppChange(deviceId, mapOf(
                    "action" to action,
                    "packageName" to packageName,
                    "appName" to appName,
                    "timestamp" to System.currentTimeMillis()
                ))
                
            } catch (e: Exception) {
                Log.e(TAG, "Error reporting app change", e)
            }
        }
    }
}
