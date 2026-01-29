package com.familyguardpro.adapters

import android.graphics.BitmapFactory
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.familyguardpro.R
import com.familyguardpro.models.NotificationData
import java.text.SimpleDateFormat
import java.util.*

class NotificationAdapter(
    private var notifications: List<NotificationData>,
    private val onNotificationClick: (NotificationData) -> Unit
) : RecyclerView.Adapter<NotificationAdapter.NotificationViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NotificationViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_notification, parent, false)
        return NotificationViewHolder(view)
    }

    override fun onBindViewHolder(holder: NotificationViewHolder, position: Int) {
        holder.bind(notifications[position])
    }

    override fun getItemCount(): Int = notifications.size

    fun updateList(newList: List<NotificationData>) {
        notifications = newList
        notifyDataSetChanged()
    }

    inner class NotificationViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivAppIcon: ImageView = itemView.findViewById(R.id.ivAppIcon)
        private val tvAppName: TextView = itemView.findViewById(R.id.tvAppName)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        private val tvContent: TextView = itemView.findViewById(R.id.tvContent)
        private val tvTime: TextView = itemView.findViewById(R.id.tvTime)
        private val ivImage: ImageView = itemView.findViewById(R.id.ivImage)

        fun bind(notification: NotificationData) {
            tvAppName.text = notification.appName
            tvTitle.text = notification.title
            tvContent.text = notification.text
            tvTime.text = formatTime(notification.timestamp)
            
            // Load app icon
            notification.iconBase64?.let { iconBase64 ->
                try {
                    val decodedBytes = Base64.decode(iconBase64, Base64.DEFAULT)
                    val bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                    ivAppIcon.setImageBitmap(bitmap)
                } catch (e: Exception) {
                    ivAppIcon.setImageResource(getAppIcon(notification.packageName))
                }
            } ?: ivAppIcon.setImageResource(getAppIcon(notification.packageName))
            
            // Load attached image if present
            notification.imageBase64?.let { imageBase64 ->
                try {
                    val decodedBytes = Base64.decode(imageBase64, Base64.DEFAULT)
                    val bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                    ivImage.setImageBitmap(bitmap)
                    ivImage.visibility = View.VISIBLE
                } catch (e: Exception) {
                    ivImage.visibility = View.GONE
                }
            } ?: run { ivImage.visibility = View.GONE }
            
            itemView.setOnClickListener { onNotificationClick(notification) }
        }

        private fun formatTime(timestamp: Long): String {
            val now = System.currentTimeMillis()
            val diff = now - timestamp
            
            return when {
                diff < 60 * 1000 -> "Just now"
                diff < 60 * 60 * 1000 -> "${diff / (60 * 1000)}m ago"
                diff < 24 * 60 * 60 * 1000 -> "${diff / (60 * 60 * 1000)}h ago"
                else -> SimpleDateFormat("MMM dd", Locale.getDefault()).format(Date(timestamp))
            }
        }

        private fun getAppIcon(packageName: String): Int {
            return when {
                packageName.contains("whatsapp") -> R.drawable.ic_whatsapp
                packageName.contains("instagram") -> R.drawable.ic_instagram
                packageName.contains("telegram") -> R.drawable.ic_telegram
                packageName.contains("facebook") -> R.drawable.ic_facebook
                packageName.contains("messenger") -> R.drawable.ic_messenger
                else -> R.drawable.ic_notification
            }
        }
    }
}
