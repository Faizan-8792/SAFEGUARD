package com.familyguardpro.services

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log

class ContentFilterVpnService : VpnService() {
    
    companion object {
        private const val TAG = "ContentFilterVpnService"
    }
    
    private var vpnInterface: ParcelFileDescriptor? = null
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START" -> startVpn()
            "STOP" -> stopVpn()
        }
        return START_STICKY
    }
    
    private fun startVpn() {
        if (vpnInterface != null) {
            Log.d(TAG, "VPN already running")
            return
        }
        
        try {
            val builder = Builder()
                .setSession("FamilyGuard Content Filter")
                .addAddress("10.0.0.2", 24)
                .addDnsServer("8.8.8.8")
                .addRoute("0.0.0.0", 0)
            
            vpnInterface = builder.establish()
            
            if (vpnInterface != null) {
                Log.d(TAG, "VPN established")
                // Start filtering thread here if needed
            } else {
                Log.e(TAG, "Failed to establish VPN")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting VPN", e)
        }
    }
    
    private fun stopVpn() {
        try {
            vpnInterface?.close()
            vpnInterface = null
            Log.d(TAG, "VPN stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping VPN", e)
        }
        stopSelf()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
    }
    
    override fun onRevoke() {
        super.onRevoke()
        stopVpn()
    }
}
