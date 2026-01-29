package com.familyguardpro

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.work.Configuration
import androidx.work.WorkManager
import com.familyguardpro.utils.PreferenceManager
import com.google.firebase.FirebaseApp

class FamilyGuardApp : Application(), Configuration.Provider {

    companion object {
        lateinit var instance: FamilyGuardApp
            private set
        
        const val NOTIFICATION_CHANNEL_ID = "familyguard_channel"
        const val NOTIFICATION_CHANNEL_HIDDEN = "familyguard_hidden"
        const val NOTIFICATION_CHANNEL_STREAMING = "familyguard_streaming"
        
        fun getAppContext(): Context = instance.applicationContext
    }

    lateinit var preferenceManager: PreferenceManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        
        // Initialize Firebase
        FirebaseApp.initializeApp(this)
        
        // Initialize preferences
        preferenceManager = PreferenceManager(this)
        
        // Create notification channels
        createNotificationChannels()
        
        // Initialize WorkManager
        WorkManager.initialize(this, workManagerConfiguration)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            
            // Main channel (visible)
            val mainChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "FamilyGuard Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "General notifications"
                setShowBadge(true)
            }
            
            // Hidden channel (for background services - min priority)
            val hiddenChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_HIDDEN,
                "System Services",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Background services"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
            }
            
            // Streaming channel
            val streamingChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_STREAMING,
                "Streaming",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active streaming sessions"
                setShowBadge(false)
            }
            
            notificationManager.createNotificationChannels(
                listOf(mainChannel, hiddenChannel, streamingChannel)
            )
        }
    }
    
    /**
     * Disables all visible notifications for the app (child mode stealth)
     * Call this after child setup is complete
     */
    fun disableAppNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            
            // Make main channel silent and invisible
            val silentChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "FamilyGuard",
                NotificationManager.IMPORTANCE_NONE
            ).apply {
                description = "General notifications"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
            }
            
            notificationManager.createNotificationChannel(silentChannel)
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
