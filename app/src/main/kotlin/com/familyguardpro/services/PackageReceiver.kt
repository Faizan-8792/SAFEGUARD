package com.familyguardpro.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.familyguardpro.network.ApiClient
import com.familyguardpro.utils.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PackageReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PackageReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val preferenceManager = PreferenceManager(context)
        
        if (!preferenceManager.isChildMode()) return
        
        val packageName = intent.data?.schemeSpecificPart ?: return
        
        when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED -> {
                Log.d(TAG, "App installed: $packageName")
                reportAppChange(context, preferenceManager, packageName, "installed")
            }
            Intent.ACTION_PACKAGE_REMOVED -> {
                Log.d(TAG, "App uninstalled: $packageName")
                reportAppChange(context, preferenceManager, packageName, "uninstalled")
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun reportAppChange(
        context: Context,
        preferenceManager: PreferenceManager,
        packageName: String,
        action: String
    ) {
        val appName = try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val deviceId = preferenceManager.getDeviceId()
                ApiClient.api.reportAppChange(
                    deviceId,
                    com.familyguardpro.network.AppChangeBody(
                        packageName = packageName,
                        appName = appName,
                        event = "installed",
                        timestamp = System.currentTimeMillis()
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to report app change", e)
            }
        }
    }
}
