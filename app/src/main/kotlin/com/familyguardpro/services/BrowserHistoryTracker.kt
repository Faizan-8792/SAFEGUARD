package com.familyguardpro.services

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.familyguardpro.FamilyGuardApp
import com.familyguardpro.network.ApiClient
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * Browser History Tracker using Accessibility Service
 * Extracts URLs directly from browser address bars (works on all modern browsers)
 * This bypasses Chrome's blocked ContentProvider
 */
class BrowserHistoryTracker(private val service: AccessibilityService) {
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Current page tracking
    private var currentUrl: String? = null
    private var currentTitle: String? = null
    private var currentBrowser: String? = null
    private var pageStartTime: Long = 0
    
    // Recently synced URLs (avoid duplicates)
    private val recentUrls = mutableSetOf<String>()
    private var lastCleanupTime = System.currentTimeMillis()
    
    // Browser configurations with their URL bar resource IDs
    private val browsers = mapOf(
        "com.android.chrome" to BrowserConfig(
            name = "Chrome",
            urlBarIds = listOf("url_bar", "search_box", "omnibox_text", "url_bar_hint")
        ),
        "com.chrome.beta" to BrowserConfig(
            name = "Chrome Beta",
            urlBarIds = listOf("url_bar", "search_box", "omnibox_text")
        ),
        "org.mozilla.firefox" to BrowserConfig(
            name = "Firefox",
            urlBarIds = listOf("mozac_browser_toolbar_url_view", "url_bar", "mozac_browser_toolbar_edit_url_view")
        ),
        "com.sec.android.app.sbrowser" to BrowserConfig(
            name = "Samsung Internet",
            urlBarIds = listOf("location_bar_edit_text", "url_bar", "location_bar_text")
        ),
        "com.microsoft.emmx" to BrowserConfig(
            name = "Edge",
            urlBarIds = listOf("url_bar", "url_bar_title", "url_bar_text")
        ),
        "com.opera.browser" to BrowserConfig(
            name = "Opera",
            urlBarIds = listOf("url_field", "url_bar", "address_bar")
        ),
        "com.brave.browser" to BrowserConfig(
            name = "Brave",
            urlBarIds = listOf("url_bar", "search_box", "omnibox_text")
        ),
        "com.UCMobile.intl" to BrowserConfig(
            name = "UC Browser",
            urlBarIds = listOf("url_bar", "title_bar", "url_edit")
        ),
        "com.opera.mini.native" to BrowserConfig(
            name = "Opera Mini",
            urlBarIds = listOf("url_field", "url_bar")
        )
    )
    
    data class BrowserConfig(
        val name: String,
        val urlBarIds: List<String>
    )
    
    /**
     * Process accessibility events for browser URL tracking
     */
    fun onAccessibilityEvent(event: AccessibilityEvent, packageName: String) {
        Log.d(TAG, "🔍 onAccessibilityEvent called for: $packageName, type=${event.eventType}")
        
        val browserConfig = browsers[packageName]
        if (browserConfig == null) {
            Log.e(TAG, "❌ No browser config for: $packageName")
            return
        }
        
        // Only process window state changes and content changes
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            Log.d(TAG, "⏭️ Skipping event type: ${event.eventType}")
            return
        }
        
        val source = try {
            event.source
        } catch (e: Exception) {
            null
        } ?: return
        
        try {
            extractUrlFromBrowser(source, packageName, browserConfig)
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting URL: ${e.message}")
        } finally {
            try {
                source.recycle()
            } catch (e: Exception) {
                // Ignore recycle errors
            }
        }
    }
    
    private fun extractUrlFromBrowser(node: AccessibilityNodeInfo, packageName: String, config: BrowserConfig) {
        var urlNode: AccessibilityNodeInfo? = null
        
        // Method 1: Find URL bar by resource ID
        for (urlBarId in config.urlBarIds) {
            urlNode = findNodeByResourceId(node, packageName, urlBarId)
            if (urlNode != null && urlNode.text?.isNotBlank() == true) break
        }
        
        // Method 2: Search by EditText class with URL pattern
        if (urlNode == null || urlNode.text.isNullOrBlank()) {
            urlNode = findUrlByClass(node)
        }
        
        val rawUrl = urlNode?.text?.toString() ?: return
        
        // Normalize URL
        val url = normalizeUrl(rawUrl) ?: return
        
        // Validate URL
        if (!isValidUrl(url)) return
        
        // Get page title
        val title = extractPageTitle(node, config.name) ?: extractDomain(url)
        
        // Check if new page
        if (url != currentUrl) {
            // Save previous page visit
            if (currentUrl != null) {
                savePageVisit()
            }
            
            // Start tracking new page
            currentUrl = url
            currentTitle = title
            currentBrowser = config.name
            pageStartTime = System.currentTimeMillis()
            
            Log.d(TAG, "📱 New page: $title")
            Log.d(TAG, "🔗 URL: $url (${config.name})")
        }
    }
    
    private fun findNodeByResourceId(
        node: AccessibilityNodeInfo,
        packageName: String,
        resourceId: String
    ): AccessibilityNodeInfo? {
        val fullId = "$packageName:id/$resourceId"
        return try {
            val nodes = node.findAccessibilityNodeInfosByViewId(fullId)
            nodes.firstOrNull()
        } catch (e: Exception) {
            null
        }
    }
    
    private fun findUrlByClass(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Check if this is an EditText with URL pattern
        if (node.className?.toString() == "android.widget.EditText") {
            val text = node.text?.toString() ?: ""
            if (text.startsWith("http") || 
                text.contains(".com") || 
                text.contains(".org") ||
                text.contains(".net") ||
                text.contains(".in")) {
                return node
            }
        }
        
        // Recursive search
        for (i in 0 until node.childCount) {
            try {
                val child = node.getChild(i) ?: continue
                val found = findUrlByClass(child)
                if (found != null) return found
                child.recycle()
            } catch (e: Exception) {
                // Ignore errors during traversal
            }
        }
        
        return null
    }
    
    private fun extractPageTitle(node: AccessibilityNodeInfo, browserName: String): String? {
        // Try to find WebView for content description (page title)
        val webView = findWebView(node)
        webView?.contentDescription?.toString()?.let { 
            if (it.isNotBlank() && !it.startsWith("http")) {
                return it.take(100) // Limit title length
            }
        }
        
        // Try window content description
        node.contentDescription?.toString()?.let {
            if (it.isNotBlank() && !it.startsWith("http") && it.length > 3) {
                return it.take(100)
            }
        }
        
        return null
    }
    
    private fun findWebView(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val className = node.className?.toString() ?: ""
        if (className.contains("WebView")) {
            return node
        }
        
        for (i in 0 until node.childCount) {
            try {
                val child = node.getChild(i) ?: continue
                val found = findWebView(child)
                if (found != null) return found
                child.recycle()
            } catch (e: Exception) {
                // Ignore
            }
        }
        
        return null
    }
    
    private fun normalizeUrl(rawUrl: String): String? {
        var url = rawUrl.trim()
        
        // Add https if no protocol
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            // Check if it looks like a domain
            if (url.contains(".") && !url.contains(" ")) {
                url = "https://$url"
            } else {
                return null // Not a valid URL
            }
        }
        
        return url
    }
    
    private fun isValidUrl(url: String): Boolean {
        if (url.length < 10) return false
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        if (url.contains("localhost")) return false
        if (url.contains("127.0.0.1")) return false
        if (url.contains("192.168.")) return false
        if (url.contains("chrome://")) return false
        if (url.contains("about:")) return false
        if (url.contains("file://")) return false
        
        // Check for valid domain pattern
        return try {
            val uri = URI(url)
            uri.host?.contains(".") == true
        } catch (e: Exception) {
            false
        }
    }
    
    private fun savePageVisit() {
        val url = currentUrl ?: return
        val title = currentTitle ?: url
        val browser = currentBrowser ?: "Unknown"
        val duration = (System.currentTimeMillis() - pageStartTime) / 1000 // seconds
        
        // Skip very short visits (< 2 seconds = accidental clicks)
        if (duration < 2) return
        
        // Skip if recently synced
        val urlKey = "$url|${pageStartTime / 60000}" // Key by minute
        if (recentUrls.contains(urlKey)) return
        recentUrls.add(urlKey)
        
        // Cleanup old URLs periodically
        cleanupRecentUrls()
        
        scope.launch {
            try {
                val app = service.applicationContext as? FamilyGuardApp
                val deviceId = app?.preferenceManager?.getDeviceId() ?: return@launch
                
                if (deviceId.isEmpty()) {
                    Log.w(TAG, "No device ID, skipping browser history sync")
                    return@launch
                }
                
                val domain = extractDomain(url)
                
                val historyArray = JSONArray()
                historyArray.put(JSONObject().apply {
                    put("url", url)
                    put("title", title)
                    put("browser", browser)
                    put("visitedAt", pageStartTime)
                    put("visitCount", 1)
                    put("durationSeconds", duration)
                    put("domain", domain)
                })
                
                // Sync to server
                ApiClient.syncBrowserHistory(deviceId, historyArray)
                Log.d(TAG, "✓ Synced: $domain ($browser, ${duration}s)")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error saving page visit: ${e.message}")
            }
        }
    }
    
    private fun extractDomain(url: String): String {
        return try {
            val uri = URI(url)
            var host = uri.host ?: return url
            if (host.startsWith("www.")) {
                host = host.substring(4)
            }
            host
        } catch (e: Exception) {
            url
        }
    }
    
    private fun cleanupRecentUrls() {
        val now = System.currentTimeMillis()
        if (now - lastCleanupTime > 5 * 60 * 1000) { // Every 5 minutes
            if (recentUrls.size > 100) {
                recentUrls.clear()
            }
            lastCleanupTime = now
        }
    }
    
    /**
     * Call this when screen turns off or app switches to save current page
     */
    fun flushCurrentPage() {
        if (currentUrl != null) {
            savePageVisit()
            currentUrl = null
            currentTitle = null
            currentBrowser = null
        }
    }
    
    /**
     * Check if package is a supported browser
     */
    fun isBrowserPackage(packageName: String): Boolean {
        val isBrowser = browsers.containsKey(packageName)
        if (isBrowser) {
            Log.d(TAG, "✓ Browser detected: $packageName")
        }
        return isBrowser
    }
    
    fun destroy() {
        scope.cancel()
        recentUrls.clear()
    }
    
    companion object {
        private const val TAG = "BrowserHistoryTracker"
    }
}
