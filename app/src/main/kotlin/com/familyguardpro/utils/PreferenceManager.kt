package com.familyguardpro.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class PreferenceManager(context: Context) {

    private val prefs: SharedPreferences

    companion object {
        private const val PREF_NAME = "familyguard_prefs"
        
        private const val KEY_IS_CHILD_MODE = "is_child_mode"
        private const val KEY_IS_PARENT_MODE = "is_parent_mode"
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_EMAIL = "email"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_PARENT_ID = "parent_id"
        private const val KEY_FCM_TOKEN = "fcm_token"
        private const val KEY_APP_HIDDEN = "app_hidden"
        private const val KEY_LAST_SYNC_TIME = "last_sync_time"
        private const val KEY_CALL_RECORDING_ENABLED = "call_recording_enabled"
        private const val KEY_BLOCKED_APPS = "blocked_apps"
        private const val KEY_BLOCKED_DOMAINS = "blocked_domains"
        private const val KEY_SCREEN_TIME_LIMIT = "screen_time_limit"
        private const val KEY_SCREEN_TIME_USED = "screen_time_used"
    }

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = try {
            EncryptedSharedPreferences.create(
                context,
                PREF_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            android.util.Log.e("PreferenceManager", "EncryptedSharedPreferences failed, clearing and using regular: ${e.message}")
            // Clear the corrupted encrypted prefs file
            try {
                context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().clear().commit()
            } catch (clearError: Exception) {
                android.util.Log.e("PreferenceManager", "Failed to clear prefs: ${clearError.message}")
            }
            // Fallback to regular SharedPreferences if encryption fails
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        }
    }

    // Mode settings
    fun isChildMode(): Boolean = prefs.getBoolean(KEY_IS_CHILD_MODE, false)
    fun setChildMode(value: Boolean) = prefs.edit().putBoolean(KEY_IS_CHILD_MODE, value).apply()

    fun isParentMode(): Boolean = prefs.getBoolean(KEY_IS_PARENT_MODE, false)
    fun setParentMode(value: Boolean) = prefs.edit().putBoolean(KEY_IS_PARENT_MODE, value).apply()

    fun isLoggedIn(): Boolean = prefs.getBoolean(KEY_IS_LOGGED_IN, false)
    fun setLoggedIn(value: Boolean) = prefs.edit().putBoolean(KEY_IS_LOGGED_IN, value).apply()

    // Auth
    fun getAuthToken(): String = prefs.getString(KEY_AUTH_TOKEN, "") ?: ""
    fun setAuthToken(value: String) = prefs.edit().putString(KEY_AUTH_TOKEN, value).apply()

    fun getUserId(): String = prefs.getString(KEY_USER_ID, "") ?: ""
    fun setUserId(value: String) = prefs.edit().putString(KEY_USER_ID, value).apply()

    fun getEmail(): String = prefs.getString(KEY_EMAIL, "") ?: ""
    fun setEmail(value: String) = prefs.edit().putString(KEY_EMAIL, value).apply()

    // Device
    fun getDeviceId(): String {
        val id = prefs.getString(KEY_DEVICE_ID, "") ?: ""
        android.util.Log.d("PreferenceManager", "getDeviceId() returning: '$id' (length: ${id.length})")
        return id
    }
    fun setDeviceId(value: String) {
        android.util.Log.d("PreferenceManager", "setDeviceId() saving: '$value' (length: ${value.length})")
        prefs.edit().putString(KEY_DEVICE_ID, value).commit() // Use commit() for immediate write
    }

    fun getParentId(): String = prefs.getString(KEY_PARENT_ID, "") ?: ""
    fun setParentId(value: String) = prefs.edit().putString(KEY_PARENT_ID, value).apply()

    fun getFcmToken(): String = prefs.getString(KEY_FCM_TOKEN, "") ?: ""
    fun setFcmToken(value: String) = prefs.edit().putString(KEY_FCM_TOKEN, value).apply()

    // App state
    fun isAppHidden(): Boolean = prefs.getBoolean(KEY_APP_HIDDEN, false)
    fun setAppHidden(value: Boolean) = prefs.edit().putBoolean(KEY_APP_HIDDEN, value).apply()

    fun getLastSyncTime(): Long = prefs.getLong(KEY_LAST_SYNC_TIME, 0)
    fun setLastSyncTime(value: Long) = prefs.edit().putLong(KEY_LAST_SYNC_TIME, value).apply()

    // Call recording
    fun isCallRecordingEnabled(): Boolean = prefs.getBoolean(KEY_CALL_RECORDING_ENABLED, false)
    fun setCallRecordingEnabled(value: Boolean) = prefs.edit().putBoolean(KEY_CALL_RECORDING_ENABLED, value).apply()

    // Blocked apps
    fun getBlockedApps(): Set<String> = prefs.getStringSet(KEY_BLOCKED_APPS, emptySet()) ?: emptySet()
    fun setBlockedApps(apps: Set<String>) = prefs.edit().putStringSet(KEY_BLOCKED_APPS, apps).apply()

    // Blocked domains
    fun getBlockedDomains(): Set<String> = prefs.getStringSet(KEY_BLOCKED_DOMAINS, emptySet()) ?: emptySet()
    fun setBlockedDomains(domains: Set<String>) = prefs.edit().putStringSet(KEY_BLOCKED_DOMAINS, domains).apply()

    // Screen time
    fun getScreenTimeLimit(): Int = prefs.getInt(KEY_SCREEN_TIME_LIMIT, 0) // in minutes, 0 = unlimited
    fun setScreenTimeLimit(minutes: Int) = prefs.edit().putInt(KEY_SCREEN_TIME_LIMIT, minutes).apply()

    fun getScreenTimeUsedToday(): Int = prefs.getInt(KEY_SCREEN_TIME_USED, 0)
    fun setScreenTimeUsedToday(minutes: Int) = prefs.edit().putInt(KEY_SCREEN_TIME_USED, minutes).apply()

    fun isScreenTimeLimitExceeded(): Boolean {
        val limit = getScreenTimeLimit()
        if (limit == 0) return false
        return getScreenTimeUsedToday() >= limit
    }

    // Clear all
    fun clear() {
        prefs.edit().clear().commit() // Use commit() for synchronous clear
    }
    
    // Explicitly clear child mode synchronously
    fun clearChildMode() {
        prefs.edit()
            .putBoolean(KEY_IS_CHILD_MODE, false)
            .remove(KEY_DEVICE_ID)
            .remove(KEY_PARENT_ID)
            .remove(KEY_FCM_TOKEN)
            .remove(KEY_LAST_SYNC_TIME)
            .commit()
    }
}
