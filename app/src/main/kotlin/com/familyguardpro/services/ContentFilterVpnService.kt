package com.familyguardpro.services

import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.familyguardpro.FamilyGuardApp
import com.familyguardpro.R
import com.familyguardpro.utils.PreferenceManager
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import kotlinx.coroutines.*

class ContentFilterVpnService : VpnService() {

    companion object {
        private const val TAG = "ContentFilterVpn"
        private const val NOTIFICATION_ID = 1007
        private const val VPN_MTU = 1500
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var preferenceManager: PreferenceManager
    
    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false

    // Blocked domains
    private val blockedDomains = mutableSetOf<String>()
    private val blockedKeywords = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        preferenceManager = PreferenceManager(this)
        loadBlockedContent()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START" -> startVpn()
            "STOP" -> stopVpn()
            "UPDATE_RULES" -> loadBlockedContent()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
    }

    private fun startVpn() {
        if (isRunning) return
        
        startForeground()
        
        try {
            val builder = Builder()
                .setSession("FamilyGuard Content Filter")
                .setMtu(VPN_MTU)
                .addAddress("10.0.0.2", 24)
                .addDnsServer("8.8.8.8")
                .addDnsServer("8.8.4.4")
                .addRoute("0.0.0.0", 0)
            
            // Exclude our own app
            builder.addDisallowedApplication(packageName)
            
            vpnInterface = builder.establish()
            
            if (vpnInterface != null) {
                isRunning = true
                startPacketForwarding()
                Log.d(TAG, "VPN started successfully")
            } else {
                Log.e(TAG, "Failed to establish VPN interface")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting VPN", e)
        }
    }

    private fun stopVpn() {
        isRunning = false
        vpnInterface?.close()
        vpnInterface = null
        serviceScope.cancel()
    }

    private fun startForeground() {
        val notification = NotificationCompat.Builder(this, FamilyGuardApp.NOTIFICATION_CHANNEL_HIDDEN)
            .setContentTitle("Content Filter")
            .setContentText("Active")
            .setSmallIcon(R.drawable.ic_system_update)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
        
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun loadBlockedContent() {
        // Load from preferences or server
        blockedDomains.clear()
        blockedKeywords.clear()
        
        // Default blocked sites (can be updated from server)
        blockedDomains.addAll(listOf(
            "porn", "xxx", "adult", "sex",
            "gambling", "casino", "bet",
            "drugs", "weed", "marijuana"
        ))
        
        // Add domains from preferences
        preferenceManager.getBlockedDomains().forEach {
            blockedDomains.add(it.lowercase())
        }
    }

    private fun startPacketForwarding() {
        val vpn = vpnInterface ?: return
        
        serviceScope.launch(Dispatchers.IO) {
            val inputStream = FileInputStream(vpn.fileDescriptor)
            val outputStream = FileOutputStream(vpn.fileDescriptor)
            
            val packet = ByteBuffer.allocate(VPN_MTU)
            
            while (isRunning) {
                try {
                    packet.clear()
                    val length = inputStream.read(packet.array())
                    
                    if (length > 0) {
                        packet.limit(length)
                        
                        // Check if packet should be blocked
                        if (shouldBlockPacket(packet)) {
                            Log.d(TAG, "Blocked packet")
                            continue
                        }
                        
                        // Forward packet (simplified - real implementation needs full NAT)
                        outputStream.write(packet.array(), 0, length)
                    }
                } catch (e: Exception) {
                    if (isRunning) {
                        Log.e(TAG, "Packet forwarding error", e)
                    }
                }
            }
        }
    }

    private fun shouldBlockPacket(packet: ByteBuffer): Boolean {
        try {
            // Extract DNS queries and check against blocked list
            val data = String(packet.array(), 0, packet.limit(), Charsets.UTF_8)
            
            for (domain in blockedDomains) {
                if (data.lowercase().contains(domain)) {
                    return true
                }
            }
            
            for (keyword in blockedKeywords) {
                if (data.lowercase().contains(keyword)) {
                    return true
                }
            }
        } catch (e: Exception) {
            // Ignore parsing errors
        }
        
        return false
    }
}
