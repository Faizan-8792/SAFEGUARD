package com.familyguardpro.utils

import android.content.Context
import com.familyguardpro.models.NotificationData
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent local buffer for captured notifications.
 *
 * This acts as a fallback when real-time WebSocket or immediate REST upload fails,
 * so periodic sync can still push notifications to the backend later.
 */
class NotificationBuffer(context: Context) {

    companion object {
        private const val PREFS_NAME = "notification_buffer_prefs"
        private const val KEY_BUFFER = "pending_notifications"
        private const val MAX_BUFFER_SIZE = 200
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    fun addNotification(notification: NotificationData) {
        synchronized(lock) {
            val items = readBuffer().toMutableList()
            items.add(notification)

            val trimmed = items
                .distinctBy { notificationKey(it) }
                .sortedByDescending { it.timestamp }
                .take(MAX_BUFFER_SIZE)
                .sortedBy { it.timestamp }

            writeBuffer(trimmed)
        }
    }

    fun getPendingNotifications(): List<NotificationData> {
        synchronized(lock) {
            return readBuffer()
        }
    }

    fun removeNotifications(notifications: List<NotificationData>) {
        if (notifications.isEmpty()) return

        synchronized(lock) {
            val removeKeys = notifications.map { notificationKey(it) }.toSet()
            val remaining = readBuffer().filterNot { notificationKey(it) in removeKeys }
            writeBuffer(remaining)
        }
    }

    private fun readBuffer(): List<NotificationData> {
        val raw = prefs.getString(KEY_BUFFER, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val deviceId = obj.optString("deviceId")
                    val packageName = obj.optString("packageName")
                    val appName = obj.optString("appName")
                    val timestamp = obj.optLong("timestamp")

                    if (deviceId.isBlank() || packageName.isBlank() || timestamp <= 0L) continue

                    add(
                        NotificationData(
                            id = obj.optString("id").takeIf { it.isNotBlank() },
                            deviceId = deviceId,
                            packageName = packageName,
                            appName = appName.ifBlank { packageName },
                            title = obj.optString("title").takeIf { it.isNotBlank() },
                            text = obj.optString("text").takeIf { it.isNotBlank() },
                            timestamp = timestamp
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeBuffer(notifications: List<NotificationData>) {
        val array = JSONArray()
        notifications.forEach { notification ->
            array.put(
                JSONObject().apply {
                    put("id", notification.id)
                    put("deviceId", notification.deviceId)
                    put("packageName", notification.packageName)
                    put("appName", notification.appName)
                    put("title", notification.title)
                    put("text", notification.text)
                    put("timestamp", notification.timestamp)
                }
            )
        }
        prefs.edit().putString(KEY_BUFFER, array.toString()).apply()
    }

    private fun notificationKey(notification: NotificationData): String {
        return listOf(
            notification.deviceId,
            notification.packageName,
            notification.title.orEmpty(),
            notification.text.orEmpty(),
            notification.timestamp.toString()
        ).joinToString("|")
    }
}
