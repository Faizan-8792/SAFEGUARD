package com.familyguardpro.utils

import android.content.Context
import android.util.Log
import com.familyguardpro.FamilyGuardApp
import com.familyguardpro.network.ApiClient
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

/**
 * FCM Token Manager - AirDroid-style aggressive token management
 * 
 * This manager ensures the FCM token is always valid and registered with the server.
 * It handles:
 * - Immediate token refresh on app startup
 * - Periodic token validation
 * - Force token regeneration when expired
 * - Retry with exponential backoff
 */
object FcmTokenManager {
    
    private const val TAG = "FcmTokenManager"
    
    // Token refresh retry configuration
    private const val MAX_RETRY_ATTEMPTS = 10
    private const val INITIAL_RETRY_DELAY_MS = 2000L
    private const val MAX_RETRY_DELAY_MS = 60000L
    
    // Track last successful registration to avoid spamming
    @Volatile
    private var lastSuccessfulRegistration = 0L
    private const val MIN_REGISTRATION_INTERVAL_MS = 30000L // 30 seconds
    
    // Track whether token is registered
    @Volatile
    private var isTokenRegistered = false
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var refreshJob: Job? = null
    private var backgroundRetryJob: Job? = null
    
    /**
     * Initialize and refresh token immediately
     * Called from FamilyGuardApp.onCreate() and PersistentService.onCreate()
     */
    fun init(context: Context) {
        Log.d(TAG, "Initializing FcmTokenManager...")
        refreshTokenAsync()
        startBackgroundRetry()
    }
    
    /**
     * Refresh token asynchronously with retry logic
     */
    fun refreshTokenAsync() {
        // Cancel any existing refresh job
        refreshJob?.cancel()
        
        refreshJob = scope.launch {
            try {
                refreshTokenWithRetry()
            } catch (e: Exception) {
                Log.e(TAG, "Token refresh failed after all retries", e)
            }
        }
    }
    
    /**
     * Force refresh token - deletes old token and gets a new one
     * Use when server indicates token is expired/invalid
     */
    fun forceRefreshToken() {
        Log.w(TAG, "Force refreshing FCM token (token was invalid)")
        
        scope.launch {
            try {
                // Delete the old token first
                Log.d(TAG, "Deleting old FCM token...")
                FirebaseMessaging.getInstance().deleteToken().await()
                Log.d(TAG, "Old token deleted successfully")
                
                // Small delay before getting new token
                delay(1000)
                
                // Now get a fresh token (this will trigger onNewToken in FcmService)
                val newToken = FirebaseMessaging.getInstance().token.await()
                Log.d(TAG, "New FCM token obtained: ${newToken.take(20)}...")
                
                // Register the new token
                registerTokenWithServer(newToken)
                
            } catch (e: Exception) {
                Log.e(TAG, "Force token refresh failed", e)
            }
        }
    }
    
    /**
     * Refresh token with exponential backoff retry
     */
    private suspend fun refreshTokenWithRetry() {
        var attempt = 0
        var delay = INITIAL_RETRY_DELAY_MS
        
        while (attempt < MAX_RETRY_ATTEMPTS) {
            try {
                val token = getToken()
                if (token != null) {
                    val success = registerTokenWithServer(token)
                    if (success) {
                        Log.d(TAG, "✅ Token refresh successful on attempt ${attempt + 1}")
                        isTokenRegistered = true
                        return
                    }
                    // If device ID is empty, don't count this attempt — 
                    // the ID might not be ready yet (encrypted prefs lag)
                    val deviceId = FamilyGuardApp.instance.preferenceManager.getDeviceId()
                    if (deviceId.isEmpty()) {
                        Log.w(TAG, "Device ID not available yet, will retry...")
                        // Don't increment attempt for device-ID-missing failures
                        delay(delay)
                        delay = (delay * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
                        continue
                    }
                }
            } catch (e: CancellationException) {
                throw e // Rethrow cancellation
            } catch (e: Exception) {
                Log.e(TAG, "Token refresh attempt ${attempt + 1} failed: ${e.message}")
            }
            
            attempt++
            if (attempt < MAX_RETRY_ATTEMPTS) {
                Log.d(TAG, "Retrying token refresh in ${delay}ms (attempt ${attempt + 1}/$MAX_RETRY_ATTEMPTS)")
                delay(delay)
                delay = (delay * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
            }
        }
        
        Log.e(TAG, "❌ Token refresh failed after $MAX_RETRY_ATTEMPTS attempts")
    }    
    /**
     * Background retry: periodically try to register FCM token until successful.
     * This handles the case where device ID isn't available at startup
     * (encrypted prefs not yet decrypted) but becomes available later.
     */
    private fun startBackgroundRetry() {
        backgroundRetryJob?.cancel()
        backgroundRetryJob = scope.launch {
            while (!isTokenRegistered) {
                delay(30_000L) // Check every 30 seconds
                if (isTokenRegistered) break
                
                val deviceId = FamilyGuardApp.instance.preferenceManager.getDeviceId()
                if (deviceId.isEmpty()) continue
                
                try {
                    val token = getToken()
                    if (token != null) {
                        val success = registerTokenWithServer(token)
                        if (success) {
                            Log.d(TAG, "\u2705 Background retry: FCM token registered!")
                            isTokenRegistered = true
                            break
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Background retry failed: ${e.message}")
                }
            }
        }
    }    
    /**
     * Get current FCM token or request a new one
     */
    suspend fun getToken(): String? {
        return try {
            val token = FirebaseMessaging.getInstance().token.await()
            Log.d(TAG, "Got FCM token: ${token.take(20)}...")
            
            // Store locally
            FamilyGuardApp.instance.preferenceManager.setFcmToken(token)
            
            token
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get FCM token", e)
            null
        }
    }
    
    /**
     * Register token with the backend server
     */
    private suspend fun registerTokenWithServer(token: String): Boolean {
        // Rate limiting check (skip if token was never registered)
        val now = System.currentTimeMillis()
        if (isTokenRegistered && now - lastSuccessfulRegistration < MIN_REGISTRATION_INTERVAL_MS) {
            Log.d(TAG, "Skipping registration (rate limited, already registered)")
            return true
        }
        
        val deviceId = FamilyGuardApp.instance.preferenceManager.getDeviceId()
        
        if (deviceId.isEmpty()) {
            Log.w(TAG, "Cannot register token - no device ID")
            return false
        }
        
        return try {
            Log.d(TAG, "Registering FCM token with server...")
            
            val result = ApiClient.updateFcmToken(deviceId, token)
            
            if (result.isSuccess) {
                lastSuccessfulRegistration = now
                Log.d(TAG, "✅ FCM token registered successfully!")
                true
            } else {
                Log.e(TAG, "❌ FCM token registration failed: ${result.exceptionOrNull()?.message}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error registering FCM token", e)
            false
        }
    }
    
    /**
     * Check if token needs refresh based on age or server indications
     */
    fun shouldRefreshToken(): Boolean {
        // Always refresh if token was never successfully registered with server
        if (!isTokenRegistered) return true
        
        val prefs = FamilyGuardApp.instance.preferenceManager
        val lastRefresh = prefs.getLastFcmTokenRefresh()
        val now = System.currentTimeMillis()
        
        // Refresh if token is older than 1 hour
        val oneHourMs = 60 * 60 * 1000L
        return (now - lastRefresh) > oneHourMs
    }
    
    /**
     * Called when FCM command fails with token expired error
     * Triggers force refresh
     */
    fun onTokenExpiredError() {
        Log.w(TAG, "Token expired error received from server - forcing refresh")
        forceRefreshToken()
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        refreshJob?.cancel()
    }
}
