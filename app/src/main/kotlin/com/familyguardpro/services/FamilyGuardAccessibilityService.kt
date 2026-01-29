package com.familyguardpro.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.familyguardpro.utils.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Accessibility Service for FamilyGuard Pro
 * Provides enhanced monitoring capabilities:
 * - App usage tracking
 * - Screen content monitoring
 * - Key event interception
 * - App blocking enforcement
 */
class FamilyGuardAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "FamilyGuardA11y"
        var instance: FamilyGuardAccessibilityService? = null
            private set
        
        fun isRunning(): Boolean = instance != null
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var preferenceManager: PreferenceManager
    private var lastPackageName: String? = null
    private var blockedApps: Set<String> = emptySet()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        preferenceManager = PreferenceManager(this)
        
        Log.d(TAG, "Accessibility Service connected")
        
        // Configure service capabilities
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_FOCUSED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                   AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                   AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }
        
        // Load blocked apps
        loadBlockedApps()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        
        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    handleWindowChange(event)
                }
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    handleTextChange(event)
                }
                AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                    handleFocusChange(event)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing accessibility event", e)
        }
    }

    private fun handleWindowChange(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        
        // Track app changes
        if (packageName != lastPackageName && packageName != "com.familyguardpro") {
            lastPackageName = packageName
            
            // Log app usage
            serviceScope.launch {
                try {
                    // Record app opened
                    Log.d(TAG, "App opened: $packageName")
                    
                    // Check if app is blocked
                    if (blockedApps.contains(packageName)) {
                        blockApp(packageName)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling window change", e)
                }
            }
        }
    }

    private fun handleTextChange(event: AccessibilityEvent) {
        // Capture text input for monitoring (e.g., searches, messages)
        val text = event.text?.joinToString("") ?: return
        val packageName = event.packageName?.toString() ?: return
        
        if (text.isNotEmpty() && packageName != "com.familyguardpro") {
            // Log for monitoring purposes (respecting privacy settings)
            Log.d(TAG, "Text input in $packageName")
        }
    }

    private fun handleFocusChange(event: AccessibilityEvent) {
        val source = event.source ?: return
        
        try {
            // Check for specific UI elements (URLs, contacts, etc.)
            val className = source.className?.toString() ?: ""
            if (className.contains("EditText") || className.contains("WebView")) {
                val text = source.text?.toString() ?: ""
                if (text.isNotEmpty()) {
                    Log.d(TAG, "Focus on input: $className")
                }
            }
        } finally {
            source.recycle()
        }
    }

    private fun blockApp(packageName: String) {
        Log.d(TAG, "Blocking app: $packageName")
        
        // Perform global action to go home
        performGlobalAction(GLOBAL_ACTION_HOME)
        
        // Optionally show a blocking overlay
        val intent = Intent(this, AppBlockerService::class.java).apply {
            putExtra("blocked_package", packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startService(intent)
    }

    fun loadBlockedApps() {
        serviceScope.launch {
            try {
                // Load from preferences or server
                blockedApps = preferenceManager.getBlockedApps().toSet()
                Log.d(TAG, "Loaded ${blockedApps.size} blocked apps")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading blocked apps", e)
            }
        }
    }

    fun updateBlockedApps(apps: List<String>) {
        blockedApps = apps.toSet()
        Log.d(TAG, "Updated blocked apps: $blockedApps")
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "Accessibility Service destroyed")
    }

    // Helper method to get text from accessibility node
    private fun getTextFromNode(node: AccessibilityNodeInfo?): String {
        node ?: return ""
        val result = StringBuilder()
        
        if (node.text != null) {
            result.append(node.text)
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                result.append(" ")
                result.append(getTextFromNode(child))
                child.recycle()
            }
        }
        
        return result.toString().trim()
    }
}
