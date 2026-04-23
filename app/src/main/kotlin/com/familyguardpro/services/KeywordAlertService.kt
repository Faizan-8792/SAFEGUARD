package com.familyguardpro.services

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import com.familyguardpro.FamilyGuardApp
import com.familyguardpro.R
import com.familyguardpro.network.ApiClient
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keyword Alerts Service
 * Monitors notifications, SMS, and browser history for suspicious keywords
 * Alerts parent when concerning content is detected
 */
class KeywordAlertService(private val context: Context) {
    
    companion object {
        private const val TAG = "KeywordAlertService"
        
        // Default suspicious keywords (can be customized by parent)
        private val DEFAULT_KEYWORDS = setOf(
            // Cyberbullying
            "kill yourself", "kys", "hate you", "nobody likes you", "loser",
            "ugly", "stupid", "worthless", "die", "suicide",
            
            // Drugs
            "weed", "marijuana", "cocaine", "drugs", "get high", "dealer",
            "pills", "xanax", "molly", "edibles", "smoke up",
            
            // Violence
            "fight", "hurt you", "beat up", "gun", "weapon", "knife",
            "shoot", "kill", "murder",
            
            // Predator behavior
            "keep it secret", "don't tell", "send pics", "nudes", "meet up alone",
            "how old are you", "where do you live", "send photo", "just between us",
            
            // Self-harm
            "cut myself", "self harm", "hurt myself", "cutting", "razor",
            
            // Inappropriate content
            "porn", "xxx", "nsfw", "onlyfans", "sex", "nude"
        )
        
        // Keyword severity levels
        const val SEVERITY_LOW = 1
        const val SEVERITY_MEDIUM = 2
        const val SEVERITY_HIGH = 3
        const val SEVERITY_CRITICAL = 4
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val customKeywords = mutableSetOf<KeywordEntry>()
    private val alertHistory = mutableMapOf<String, Long>() // Prevent duplicate alerts
    
    data class KeywordEntry(
        val keyword: String,
        val severity: Int,
        val category: String
    )
    
    data class AlertMatch(
        val keyword: String,
        val severity: Int,
        val category: String,
        val source: String,
        val context: String,
        val timestamp: Long
    )
    
    init {
        loadCustomKeywords()
    }
    
    /**
     * Scan text for suspicious keywords
     */
    fun scanText(
        text: String,
        source: String,
        packageName: String? = null
    ): List<AlertMatch> {
        val matches = mutableListOf<AlertMatch>()
        val lowerText = text.lowercase()
        
        // Check default keywords
        DEFAULT_KEYWORDS.forEach { keyword ->
            if (lowerText.contains(keyword)) {
                val severity = getSeverityForKeyword(keyword)
                matches.add(AlertMatch(
                    keyword = keyword,
                    severity = severity,
                    category = getCategoryForKeyword(keyword),
                    source = source,
                    context = extractContext(text, keyword),
                    timestamp = System.currentTimeMillis()
                ))
            }
        }
        
        // Check custom keywords
        customKeywords.forEach { entry ->
            if (lowerText.contains(entry.keyword.lowercase())) {
                matches.add(AlertMatch(
                    keyword = entry.keyword,
                    severity = entry.severity,
                    category = entry.category,
                    source = source,
                    context = extractContext(text, entry.keyword),
                    timestamp = System.currentTimeMillis()
                ))
            }
        }
        
        // Report matches
        if (matches.isNotEmpty()) {
            reportAlerts(matches, packageName)
        }
        
        return matches
    }
    
    /**
     * Scan notification content
     */
    fun scanNotification(
        title: String?,
        text: String?,
        packageName: String
    ) {
        val content = "${title ?: ""} ${text ?: ""}"
        if (content.isBlank()) return
        
        val appName = getAppName(packageName)
        val matches = scanText(content, "Notification from $appName", packageName)
        
        if (matches.isNotEmpty()) {
            Log.d(TAG, "Found ${matches.size} keyword matches in notification from $appName")
        }
    }
    
    /**
     * Scan SMS message
     */
    fun scanSms(
        sender: String,
        body: String
    ) {
        val matches = scanText(body, "SMS from $sender", null)
        
        if (matches.isNotEmpty()) {
            Log.d(TAG, "Found ${matches.size} keyword matches in SMS from $sender")
        }
    }
    
    /**
     * Scan browser history entry
     */
    fun scanBrowserHistory(
        url: String,
        title: String
    ) {
        val content = "$url $title"
        val matches = scanText(content, "Browser: $title", null)
        
        if (matches.isNotEmpty()) {
            Log.d(TAG, "Found ${matches.size} keyword matches in browser history")
        }
    }
    
    private fun extractContext(text: String, keyword: String): String {
        val lowerText = text.lowercase()
        val keywordLower = keyword.lowercase()
        val index = lowerText.indexOf(keywordLower)
        
        if (index == -1) return text.take(100)
        
        // Get 30 characters before and after the keyword
        val start = maxOf(0, index - 30)
        val end = minOf(text.length, index + keyword.length + 30)
        
        var context = text.substring(start, end)
        if (start > 0) context = "...$context"
        if (end < text.length) context = "$context..."
        
        return context
    }
    
    private fun getSeverityForKeyword(keyword: String): Int {
        return when (keyword.lowercase()) {
            // Critical - immediate danger
            "kill yourself", "kys", "suicide", "kill", "murder", "gun", "weapon",
            "send pics", "nudes", "meet up alone", "cut myself" -> SEVERITY_CRITICAL
            
            // High - serious concern
            "die", "hurt you", "fight", "beat up", "self harm", "cocaine",
            "don't tell", "keep it secret", "how old are you" -> SEVERITY_HIGH
            
            // Medium - needs attention
            "hate you", "drugs", "weed", "porn", "xxx", "knife", "shoot",
            "where do you live", "send photo" -> SEVERITY_MEDIUM
            
            // Low - monitor
            else -> SEVERITY_LOW
        }
    }
    
    private fun getCategoryForKeyword(keyword: String): String {
        return when (keyword.lowercase()) {
            "kill yourself", "kys", "hate you", "nobody likes you", "loser",
            "ugly", "stupid", "worthless" -> "Cyberbullying"
            
            "weed", "marijuana", "cocaine", "drugs", "get high", "dealer",
            "pills", "xanax", "molly", "edibles", "smoke up" -> "Drugs"
            
            "fight", "hurt you", "beat up", "gun", "weapon", "knife",
            "shoot", "kill", "murder" -> "Violence"
            
            "keep it secret", "don't tell", "send pics", "nudes", "meet up alone",
            "how old are you", "where do you live", "send photo", "just between us" -> "Predator"
            
            "die", "cut myself", "self harm", "hurt myself", "cutting", "razor",
            "suicide" -> "Self-Harm"
            
            "porn", "xxx", "nsfw", "onlyfans", "sex", "nude" -> "Adult Content"
            
            else -> "Suspicious"
        }
    }
    
    private fun reportAlerts(matches: List<AlertMatch>, packageName: String?) {
        scope.launch {
            try {
                val app = context.applicationContext as FamilyGuardApp
                val deviceId = app.preferenceManager.getDeviceId()
                
                if (deviceId.isEmpty()) return@launch
                
                val alertsJson = JSONArray()
                
                matches.forEach { match ->
                    // Check for duplicate alerts (same keyword within 5 minutes)
                    val alertKey = "${match.keyword}_${match.source}"
                    val lastAlert = alertHistory[alertKey] ?: 0
                    
                    if (System.currentTimeMillis() - lastAlert > 5 * 60 * 1000) {
                        alertsJson.put(JSONObject().apply {
                            put("keyword", match.keyword)
                            put("severity", match.severity)
                            put("category", match.category)
                            put("source", match.source)
                            put("context", match.context)
                            put("timestamp", match.timestamp)
                            packageName?.let { put("packageName", it) }
                        })
                        
                        alertHistory[alertKey] = System.currentTimeMillis()
                        
                        // Show local notification for high severity
                        if (match.severity >= SEVERITY_HIGH) {
                            showLocalAlert(match)
                        }
                    }
                }
                
                if (alertsJson.length() > 0) {
                    ApiClient.reportKeywordAlerts(deviceId, alertsJson)
                    Log.d(TAG, "Reported ${alertsJson.length()} keyword alerts to server")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error reporting alerts", e)
            }
        }
    }
    
    private fun showLocalAlert(match: AlertMatch) {
        try {
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            
            val notification = NotificationCompat.Builder(context, FamilyGuardApp.NOTIFICATION_CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_system_service_notification)
                .setContentTitle("⚠️ ${match.category} Alert")
                .setContentText("Suspicious keyword detected in ${match.source}")
                .setStyle(NotificationCompat.BigTextStyle()
                    .bigText("Keyword: \"${match.keyword}\"\nSource: ${match.source}\nContext: ${match.context}"))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            
            notificationManager.notify(match.keyword.hashCode(), notification)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error showing local alert", e)
        }
    }
    
    private fun loadCustomKeywords() {
        scope.launch {
            try {
                val app = context.applicationContext as FamilyGuardApp
                val deviceId = app.preferenceManager.getDeviceId()
                
                if (deviceId.isEmpty()) return@launch
                
                val keywords = ApiClient.getCustomKeywords(deviceId)
                
                customKeywords.clear()
                for (i in 0 until keywords.length()) {
                    val obj = keywords.getJSONObject(i)
                    customKeywords.add(KeywordEntry(
                        keyword = obj.getString("keyword"),
                        severity = obj.optInt("severity", SEVERITY_MEDIUM),
                        category = obj.optString("category", "Custom")
                    ))
                }
                
                Log.d(TAG, "Loaded ${customKeywords.size} custom keywords")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error loading custom keywords", e)
            }
        }
    }
    
    private fun getAppName(packageName: String): String {
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName.substringAfterLast(".")
        }
    }
    
    /**
     * Add custom keyword at runtime
     */
    fun addCustomKeyword(keyword: String, severity: Int = SEVERITY_MEDIUM, category: String = "Custom") {
        customKeywords.add(KeywordEntry(keyword, severity, category))
    }
    
    /**
     * Remove custom keyword
     */
    fun removeCustomKeyword(keyword: String) {
        customKeywords.removeAll { it.keyword.equals(keyword, ignoreCase = true) }
    }
    
    /**
     * Get all active keywords
     */
    fun getAllKeywords(): List<String> {
        return DEFAULT_KEYWORDS.toList() + customKeywords.map { it.keyword }
    }
}
