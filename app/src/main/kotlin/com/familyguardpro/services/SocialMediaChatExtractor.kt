package com.familyguardpro.services

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.service.notification.StatusBarNotification
import android.util.Log
import com.familyguardpro.FamilyGuardApp
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.*

/**
 * SocialMediaChatExtractor - Extracts and reconstructs chat messages from social media notifications
 * Supports: WhatsApp, Instagram, Facebook Messenger, Telegram, Snapchat, Twitter
 */
class SocialMediaChatExtractor(private val context: Context) {
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    data class SocialMessage(
        val messageId: String = UUID.randomUUID().toString(),
        val appPackage: String,
        val appName: String,
        val contactName: String,
        val contactIdentifier: String, // username, phone, etc.
        val messageText: String,
        val timestamp: Long,
        val messageType: MessageType,
        val profilePhoto: String? = null, // Base64 encoded
        val isGroupChat: Boolean = false,
        val groupName: String? = null,
        val senderInGroup: String? = null,
        val mediaType: MediaType? = null // photo, video, voice, etc.
    )
    
    enum class MessageType { SENT, RECEIVED }
    enum class MediaType { PHOTO, VIDEO, VOICE, STICKER, FILE, LOCATION }
    
    // Supported social media packages
    companion object {
        private const val TAG = "SocialMediaExtractor"
        
        val SOCIAL_MEDIA_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b", // WhatsApp Business
            "com.instagram.android",
            "com.facebook.orca", // Messenger
            "com.facebook.mlite", // Messenger Lite
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "com.snapchat.android",
            "com.twitter.android",
            "com.zhiliaoapp.musically" // TikTok
        )
        
        fun isSocialMediaApp(packageName: String): Boolean {
            return SOCIAL_MEDIA_PACKAGES.contains(packageName)
        }
    }
    
    // Parse notification based on app package - WITH SMART DM FILTERING
    fun parseNotification(sbn: StatusBarNotification): SocialMessage? {
        val packageName = sbn.packageName
        val notification = sbn.notification
        val extras = notification.extras
        
        // Log notification details for debugging
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val category = notification.category
        Log.d(TAG, "📱 Processing: $packageName | Title: '$title' | Text: '$text' | Category: $category")
        
        // Skip empty notifications
        if (title.isEmpty() && text.isEmpty()) {
            Log.d(TAG, "⏭️ Skipping empty notification from $packageName")
            return null
        }
        
        // Skip system/summary notifications (X new messages, Checking for new messages, etc)
        if (isSystemContactName(title) || isNonDMText(title)) {
            Log.d(TAG, "⏭️ Skipping system notification: $title")
            return null
        }
        
        // Skip if text is just a count notification
        if (text.matches(Regex("^\\d+ (new )?messages?.*", RegexOption.IGNORE_CASE))) {
            Log.d(TAG, "⏭️ Skipping count notification: $text")
            return null
        }
        
        // Parse based on app
        val message = try {
            when (packageName) {
                "com.whatsapp", "com.whatsapp.w4b" -> parseWhatsApp(sbn)
                "com.instagram.android" -> parseInstagram(sbn)
                "com.facebook.orca", "com.facebook.mlite" -> parseFacebookMessenger(sbn)
                "org.telegram.messenger", "org.telegram.messenger.web" -> parseTelegram(sbn)
                "com.snapchat.android" -> parseSnapchat(sbn)
                "com.twitter.android" -> parseTwitter(sbn)
                "com.zhiliaoapp.musically" -> parseTikTok(sbn)
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing notification from $packageName", e)
            null
        }
        
        // Final text validation to catch non-DM content (likes, follows, etc)
        if (message != null && isNonDMText(message.messageText)) {
            Log.d(TAG, "⏭️ Skipping activity notification: ${message.messageText.take(50)}")
            return null
        }
        
        if (message != null) {
            Log.d(TAG, "✅ Captured: ${message.contactName} - ${message.messageText.take(30)}")
        }
        
        return message
    }
    
    // ✅ Helper: Check if notification has Reply action (DMs have this)
    private fun hasReplyAction(notification: Notification): Boolean {
        val actions = notification.actions ?: return false
        return actions.any { action ->
            val title = action.title?.toString()?.lowercase() ?: ""
            title.contains("reply") || title.contains("respond") || 
            title.contains("जवाब") || title.contains("उत्तर")
        }
    }
    
    // ✅ Helper: Check if this is a direct message based on group key
    private fun isDirectMessageGroup(sbn: StatusBarNotification): Boolean {
        val groupKey = sbn.groupKey
        
        // If no group key, allow it through (better to have false positive than miss DM)
        if (groupKey.isNullOrEmpty()) return true
        
        return when (sbn.packageName) {
            "org.telegram.messenger", "org.telegram.messenger.web" -> {
                // Telegram group/channel notifications have different key patterns
                // DMs typically have simpler patterns
                !groupKey.contains("group", ignoreCase = true) &&
                !groupKey.contains("channel", ignoreCase = true)
            }
            "com.instagram.android" -> {
                // Instagram DMs should have "direct" in group key
                groupKey.contains("direct", ignoreCase = true) ||
                !groupKey.contains("post", ignoreCase = true)
            }
            else -> true
        }
    }
    
    // ✅ Helper: Detect non-DM content from text patterns
    private fun isNonDMText(text: String): Boolean {
        val nonDMPatterns = listOf(
            // Activity notifications
            "liked your", "commented on", "started following",
            "mentioned you", "tagged you", "replied to you",
            "reacted to", "shared your", "followed you",
            
            // Group notifications
            "posted in", "replied in", "pinned a message",
            "added you to", "removed from",
            
            // System notifications  
            "joined", "left the", "created", "changed",
            "accepted your", "requested to",
            
            // Media shares (not DMs)
            "sent you a post", "sent you a reel", "sent a post",
            
            // Channel/broadcast
            "new post", "new video", "went live",
            
            // Summary notifications (X new messages)
            "new messages", "unread messages", "messages from",
            "new chats", "Checking for new messages"
        )
        
        return nonDMPatterns.any { text.contains(it, ignoreCase = true) }
    }
    
    // ✅ Helper: Check if contact name is a system/summary notification
    private fun isSystemContactName(name: String): Boolean {
        val systemPatterns = listOf(
            "WhatsApp", "Instagram", "Telegram", "Messenger", "Snapchat",
            "messages from", "new messages", "unread", 
            "Updating messages", "Checking for",
            "\\d+ messages", "\\d+ chats"
        )
        return systemPatterns.any { pattern ->
            if (pattern.contains("\\d")) {
                Regex(pattern).containsMatchIn(name)
            } else {
                name.equals(pattern, ignoreCase = true) || name.contains(pattern, ignoreCase = true)
            }
        }
    }
    
    private fun parseWhatsApp(sbn: StatusBarNotification): SocialMessage? {
        val notification = sbn.notification
        val extras = notification.extras
        
        // Extract title (contact name or group name)
        var title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return null
        
        // ✅ FIX: Clean title from notification metadata BEFORE processing
        // Remove patterns like "(5 messages)" or "(17 messages)"
        title = title.replace(Regex("\\s*\\(\\d+\\s+messages?\\)"), "").trim()
        // Remove patterns like "(5)" (unread count)
        title = title.replace(Regex("\\s*\\(\\d+\\)"), "").trim()
        // Remove sender suffix like ": Ahmed Bca" or ": Srishti Singh Nshm"
        if (title.contains(":") && !title.contains("@")) {
            title = title.substringBefore(":").trim()
        }
        
        // Skip notifications without meaningful content
        if (title.isBlank() || title.contains("message") && title.contains("from")) {
            return null // Generic "X messages from Y contacts" notification
        }
        
        // Extract message text
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return null
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: text
        
        // Extract profile photo from notification
        val profilePhoto = extractProfilePhoto(notification)
        
        // Check if group - WhatsApp groups have format "Group Name" or "Group Name (5)"
        // Group messages format: "Sender: message"
        val isGroup = bigText.contains(":") && !bigText.startsWith("You:")
        
        // Parse group vs direct chat
        val (contactName, groupName, sender, actualMessage) = if (isGroup && title != bigText.substringBefore(":").trim()) {
            // It's a group message - title is already cleaned above
            val senderName = bigText.substringBefore(":").trim()
            val msgText = bigText.substringAfter(":").trim()
            Tuple4(title, title, senderName, msgText)
        } else {
            // Direct chat
            Tuple4(title, null, null, bigText)
        }
        
        // Detect if sent by user
        val messageType = when {
            actualMessage.startsWith("You:") || text.startsWith("You:") -> MessageType.SENT
            else -> MessageType.RECEIVED
        }
        
        // Clean message text
        val cleanMessage = actualMessage
            .removePrefix("You:")
            .removePrefix("you:")
            .trim()
        
        if (cleanMessage.isBlank()) return null
        
        // Detect media type
        val mediaType = detectMediaType(cleanMessage)
        
        return SocialMessage(
            appPackage = "com.whatsapp",
            appName = if (sbn.packageName == "com.whatsapp.w4b") "WhatsApp Business" else "WhatsApp",
            contactName = contactName,
            contactIdentifier = contactName,
            messageText = cleanMessage,
            timestamp = sbn.postTime,
            messageType = messageType,
            profilePhoto = profilePhoto,
            isGroupChat = groupName != null,
            groupName = groupName,
            senderInGroup = sender,
            mediaType = mediaType
        )
    }
    
    private fun parseInstagram(sbn: StatusBarNotification): SocialMessage? {
        val notification = sbn.notification
        val extras = notification.extras
        
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return null
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return null
        
        // ✅ ENHANCED: Skip Instagram activity notifications
        if (isInstagramActivity(text)) {
            Log.d(TAG, "Skipping Instagram activity: $text")
            return null
        }
        
        // ✅ ENHANCED: Skip post/story notifications
        if (isPostOrStoryNotification(text)) {
            Log.d(TAG, "Skipping Instagram post/story: $text")
            return null
        }
        
        // ✅ Skip if title contains activity indicators
        if (title.contains("liked") || title.contains("commented") ||
            title.contains("following") || title.contains("mentioned")) {
            Log.d(TAG, "Skipping Instagram notification with activity title: $title")
            return null
        }
        
        val profilePhoto = extractProfilePhoto(notification)
        val mediaType = detectMediaType(text)
        
        return SocialMessage(
            appPackage = "com.instagram.android",
            appName = "Instagram",
            contactName = title,
            contactIdentifier = "@$title",
            messageText = text,
            timestamp = sbn.postTime,
            messageType = MessageType.RECEIVED,
            profilePhoto = profilePhoto,
            mediaType = mediaType
        )
    }
    
    // ✅ Helper: Detect Instagram activity notifications
    private fun isInstagramActivity(text: String): Boolean {
        val activityPatterns = listOf(
            "liked your", "liked a",
            "commented on", "commented:",
            "started following",
            "mentioned you in",
            "tagged you in",
            "shared your",
            "sent you a post", // This is NOT a DM!
            "sent a post",
            "requested to follow",
            "accepted your follow",
            "is now following",
            "follow request",
            "added to their story"
        )
        
        return activityPatterns.any { text.contains(it, ignoreCase = true) }
    }
    
    // ✅ Helper: Detect post/story notifications
    private fun isPostOrStoryNotification(text: String): Boolean {
        val postStoryPatterns = listOf(
            "posted a photo",
            "posted a video",
            "added a story",
            "added to their story",
            "story:",
            "post:",
            "reel:",
            "shared a reel",
            "went live"
        )
        
        return postStoryPatterns.any { text.contains(it, ignoreCase = true) }
    }
    
    private fun parseFacebookMessenger(sbn: StatusBarNotification): SocialMessage? {
        val notification = sbn.notification
        val extras = notification.extras
        
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return null
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return null
        
        // Skip call notifications
        if (text.contains("calling") || text.contains("Missed call")) {
            return null
        }
        
        val profilePhoto = extractProfilePhoto(notification)
        val mediaType = detectMediaType(text)
        
        // Check for group chat
        val isGroup = title.contains(",") || title.contains("to")
        
        return SocialMessage(
            appPackage = "com.facebook.orca",
            appName = "Messenger",
            contactName = title,
            contactIdentifier = title,
            messageText = text,
            timestamp = sbn.postTime,
            messageType = MessageType.RECEIVED,
            profilePhoto = profilePhoto,
            isGroupChat = isGroup,
            groupName = if (isGroup) title else null,
            mediaType = mediaType
        )
    }
    
    private fun parseTelegram(sbn: StatusBarNotification): SocialMessage? {
        val notification = sbn.notification
        val extras = notification.extras
        
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return null
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return null
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: text
        
        // ✅ ENHANCED: Skip group notifications
        if (isGroupNotification(title, text, bigText)) {
            Log.d(TAG, "Skipping Telegram group notification: $title - $text")
            return null
        }
        
        // ✅ ENHANCED: Skip activity notifications
        if (isActivityNotification(text) || isActivityNotification(bigText)) {
            Log.d(TAG, "Skipping Telegram activity notification: $text")
            return null
        }
        
        // ✅ ENHANCED: Skip channel/broadcast notifications
        if (isChannelNotification(title, text)) {
            Log.d(TAG, "Skipping Telegram channel notification: $title")
            return null
        }
        
        val profilePhoto = extractProfilePhoto(notification)
        val mediaType = detectMediaType(bigText)
        
        // If it still looks like a group format, skip it
        // Telegram group format: "Group Name: Sender: Message"
        val colonCount = bigText.count { it == ':' }
        if (colonCount >= 2) {
            Log.d(TAG, "Skipping Telegram group-format message: $bigText")
            return null
        }
        
        return SocialMessage(
            appPackage = "org.telegram.messenger",
            appName = "Telegram",
            contactName = title,
            contactIdentifier = title,
            messageText = bigText,
            timestamp = sbn.postTime,
            messageType = MessageType.RECEIVED,
            profilePhoto = profilePhoto,
            isGroupChat = false, // Only DMs pass through now
            groupName = null,
            senderInGroup = null,
            mediaType = mediaType
        )
    }
    
    // ✅ Helper: Detect group notifications
    private fun isGroupNotification(title: String, text: String, bigText: String): Boolean {
        val groupIndicators = listOf(
            "group", "Group", "GROUP",
            "posted in", "replied in",
            "mentioned you in",
            "added you to",
            "pinned a message",
            "changed group",
            "removed you",
            "was added",
            "new members"
        )
        
        val combined = "$title $text $bigText"
        return groupIndicators.any { combined.contains(it, ignoreCase = true) }
    }
    
    // ✅ Helper: Detect activity notifications
    private fun isActivityNotification(text: String): Boolean {
        val activityPatterns = listOf(
            "mentioned you",
            "replied to you",
            "reacted to",
            "pinned",
            "forwarded",
            "edited",
            "deleted",
            "joined",
            "left",
            "created",
            "changed"
        )
        
        return activityPatterns.any { text.contains(it, ignoreCase = true) }
    }
    
    // ✅ Helper: Detect channel/broadcast notifications
    private fun isChannelNotification(title: String, text: String): Boolean {
        val channelIndicators = listOf(
            "Channel", "channel",
            "Broadcast", "broadcast",
            "Bot", "bot"
        )
        
        return channelIndicators.any { 
            title.contains(it, ignoreCase = true) || text.contains(it, ignoreCase = true)
        }
    }
    
    private fun parseSnapchat(sbn: StatusBarNotification): SocialMessage? {
        val notification = sbn.notification
        val extras = notification.extras
        
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return null
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: "📷 Snap"
        
        // Determine media type based on notification
        val mediaType = when {
            text.contains("Snap", ignoreCase = true) -> MediaType.PHOTO
            text.contains("Video", ignoreCase = true) -> MediaType.VIDEO
            text.contains("Chat", ignoreCase = true) -> null // text message
            else -> MediaType.PHOTO
        }
        
        return SocialMessage(
            appPackage = "com.snapchat.android",
            appName = "Snapchat",
            contactName = title,
            contactIdentifier = title,
            messageText = text,
            timestamp = sbn.postTime,
            messageType = MessageType.RECEIVED,
            mediaType = mediaType
        )
    }
    
    private fun parseTwitter(sbn: StatusBarNotification): SocialMessage? {
        val notification = sbn.notification
        val extras = notification.extras
        
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return null
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return null
        
        // Skip non-DM notifications
        if (!title.contains("sent you") && !text.contains("Direct Message")) {
            // Could be a tweet notification, like, retweet etc.
            // Only capture if it looks like a DM
            if (text.contains("liked") || text.contains("retweeted") || 
                text.contains("followed") || text.contains("replied")) {
                return null
            }
        }
        
        val profilePhoto = extractProfilePhoto(notification)
        
        // Clean up the contact name
        val contactName = title.replace(" sent you a message", "")
            .replace(" sent you", "")
            .trim()
        
        return SocialMessage(
            appPackage = "com.twitter.android",
            appName = "Twitter/X",
            contactName = contactName,
            contactIdentifier = "@$contactName",
            messageText = text,
            timestamp = sbn.postTime,
            messageType = MessageType.RECEIVED,
            profilePhoto = profilePhoto
        )
    }
    
    private fun parseTikTok(sbn: StatusBarNotification): SocialMessage? {
        val notification = sbn.notification
        val extras = notification.extras
        
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return null
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return null
        
        // Skip non-message notifications
        if (text.contains("liked") || text.contains("commented") || 
            text.contains("followed") || text.contains("mentioned")) {
            return null
        }
        
        val profilePhoto = extractProfilePhoto(notification)
        val mediaType = detectMediaType(text)
        
        return SocialMessage(
            appPackage = "com.zhiliaoapp.musically",
            appName = "TikTok",
            contactName = title,
            contactIdentifier = "@$title",
            messageText = text,
            timestamp = sbn.postTime,
            messageType = MessageType.RECEIVED,
            profilePhoto = profilePhoto,
            mediaType = mediaType
        )
    }
    
    private fun extractProfilePhoto(notification: Notification): String? {
        try {
            // Method 1: Large icon (most common for profile photos)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val largeIcon = notification.getLargeIcon()
                if (largeIcon != null) {
                    val drawable = largeIcon.loadDrawable(context)
                    if (drawable is BitmapDrawable) {
                        return bitmapToBase64(drawable.bitmap)
                    }
                }
            }
            
            // Method 2: From extras (legacy)
            val extras = notification.extras
            @Suppress("DEPRECATION")
            val iconBitmap = extras.getParcelable<Bitmap>(Notification.EXTRA_LARGE_ICON)
            if (iconBitmap != null) {
                return bitmapToBase64(iconBitmap)
            }
            
            // Method 3: Big large icon
            @Suppress("DEPRECATION")
            val bigIcon = extras.getParcelable<Bitmap>(Notification.EXTRA_LARGE_ICON_BIG)
            if (bigIcon != null) {
                return bitmapToBase64(bigIcon)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting profile photo", e)
        }
        
        return null
    }
    
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        
        // Scale down if too large
        val maxSize = 100 // pixels
        val scaledBitmap = if (bitmap.width > maxSize || bitmap.height > maxSize) {
            val scale = maxSize.toFloat() / maxOf(bitmap.width, bitmap.height)
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt(),
                (bitmap.height * scale).toInt(),
                true
            )
        } else {
            bitmap
        }
        
        // Compress to reduce size
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
        
        val bytes = outputStream.toByteArray()
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }
    
    private fun detectMediaType(message: String): MediaType? {
        val lowerMsg = message.lowercase()
        return when {
            lowerMsg.contains("📷") || lowerMsg.contains("photo") || 
            lowerMsg.contains("image") || lowerMsg.contains("picture") -> MediaType.PHOTO
            
            lowerMsg.contains("🎥") || lowerMsg.contains("video") -> MediaType.VIDEO
            
            lowerMsg.contains("🎤") || lowerMsg.contains("voice") || 
            lowerMsg.contains("audio") || lowerMsg.contains("voice message") -> MediaType.VOICE
            
            lowerMsg.contains("📄") || lowerMsg.contains("document") || 
            lowerMsg.contains("file") -> MediaType.FILE
            
            lowerMsg.contains("📍") || lowerMsg.contains("location") || 
            lowerMsg.contains("live location") -> MediaType.LOCATION
            
            lowerMsg.contains("sticker") || lowerMsg.contains("gif") -> MediaType.STICKER
            
            else -> null
        }
    }
    
    fun saveAndSyncMessage(message: SocialMessage) {
        scope.launch {
            try {
                val app = context.applicationContext as? FamilyGuardApp
                val deviceId = app?.preferenceManager?.getDeviceId() ?: return@launch
                
                val data = JSONObject().apply {
                    put("message_id", message.messageId)
                    put("device_id", deviceId)
                    put("app_package", message.appPackage)
                    put("app_name", message.appName)
                    put("contact_name", message.contactName)
                    put("contact_identifier", message.contactIdentifier)
                    put("message_text", message.messageText)
                    put("timestamp", message.timestamp)
                    put("message_type", message.messageType.name)
                    put("is_group_chat", message.isGroupChat)
                    
                    message.profilePhoto?.let { put("profile_photo", it) }
                    message.groupName?.let { put("group_name", it) }
                    message.senderInGroup?.let { put("sender_in_group", it) }
                    message.mediaType?.let { put("media_type", it.name) }
                }
                
                // Send via WebSocket for real-time sync
                WebSocketSyncService.sendSocialMessage(data)
                
                Log.d(TAG, "💬 ${message.appName}: ${message.contactName} - ${message.messageText.take(50)}")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error saving social message", e)
            }
        }
    }
    
    fun destroy() {
        scope.cancel()
    }
}

// Helper tuple class
data class Tuple4<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
