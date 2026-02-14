package com.familyguardpro

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.familyguardpro.databinding.ActivityNotificationListBinding
import com.familyguardpro.databinding.ItemNotificationBinding
import com.familyguardpro.models.NotificationData
import com.familyguardpro.network.ApiClient
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class NotificationListActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityNotificationListBinding
    private var deviceId: String? = null
    private val allNotifications = mutableListOf<NotificationData>()
    private val filteredNotifications = mutableListOf<NotificationData>()
    private lateinit var adapter: NotificationAdapter
    private var currentFilter: String = "all"
    private val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNotificationListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        deviceId = intent.getStringExtra("deviceId")
        
        setupUI()
        loadNotifications()
    }
    
    private fun setupUI() {
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }
        
        adapter = NotificationAdapter(filteredNotifications)
        binding.rvNotifications.layoutManager = LinearLayoutManager(this)
        binding.rvNotifications.adapter = adapter
        
        binding.swipeRefresh.setOnRefreshListener {
            loadNotifications()
        }
        
        // Setup chip filters
        binding.chipGroupFilters.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                currentFilter = when (checkedIds[0]) {
                    R.id.chipAll -> "all"
                    R.id.chipWhatsApp -> "com.whatsapp"
                    R.id.chipInstagram -> "com.instagram.android"
                    R.id.chipTelegram -> "org.telegram.messenger"
                    R.id.chipMessenger -> "com.facebook.orca"
                    else -> "other"
                }
                filterNotifications()
            }
        }
    }
    
    private fun loadNotifications() {
        binding.progressBar.visibility = View.VISIBLE
        binding.llEmptyState.visibility = View.GONE
        
        lifecycleScope.launch {
            deviceId?.let { id ->
                val result = ApiClient.getNotifications(id)
                
                result.fold(
                    onSuccess = { notifications ->
                        binding.progressBar.visibility = View.GONE
                        binding.swipeRefresh.isRefreshing = false
                        
                        allNotifications.clear()
                        allNotifications.addAll(notifications)
                        filterNotifications()
                    },
                    onFailure = { error ->
                        binding.progressBar.visibility = View.GONE
                        binding.swipeRefresh.isRefreshing = false
                        Toast.makeText(this@NotificationListActivity, "Error: ${error.message}", Toast.LENGTH_LONG).show()
                    }
                )
            } ?: run {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this@NotificationListActivity, "No device ID", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun filterNotifications() {
        filteredNotifications.clear()
        
        filteredNotifications.addAll(
            when (currentFilter) {
                "all" -> allNotifications
                "other" -> allNotifications.filter { notification ->
                    val pkg = notification.packageName ?: ""
                    !pkg.contains("whatsapp") && 
                    !pkg.contains("instagram") && 
                    !pkg.contains("telegram") && 
                    !pkg.contains("facebook.orca")
                }
                else -> allNotifications.filter { it.packageName?.contains(currentFilter) == true }
            }
        )
        
        adapter.notifyDataSetChanged()
        
        if (filteredNotifications.isEmpty()) {
            binding.llEmptyState.visibility = View.VISIBLE
        } else {
            binding.llEmptyState.visibility = View.GONE
        }
    }
    
    // Notification Adapter
    inner class NotificationAdapter(
        private val notifications: List<NotificationData>
    ) : RecyclerView.Adapter<NotificationAdapter.ViewHolder>() {
        
        inner class ViewHolder(val binding: ItemNotificationBinding) : RecyclerView.ViewHolder(binding.root)
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemNotificationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val notification = notifications[position]
            
            holder.binding.tvAppName.text = getAppName(notification.packageName)
            holder.binding.tvTitle.text = notification.title ?: "No Title"
            holder.binding.tvContent.text = notification.text ?: notification.content ?: ""
            
            notification.timestamp?.let { ts ->
                holder.binding.tvTime.text = dateFormat.format(Date(ts))
            }
            
            // Set app icon
            val iconRes = when {
                notification.packageName?.contains("whatsapp") == true -> R.drawable.ic_whatsapp
                notification.packageName?.contains("instagram") == true -> R.drawable.ic_instagram
                notification.packageName?.contains("telegram") == true -> R.drawable.ic_telegram
                notification.packageName?.contains("facebook.orca") == true -> R.drawable.ic_messenger
                else -> R.drawable.ic_notification
            }
            holder.binding.ivAppIcon.setImageResource(iconRes)
        }
        
        override fun getItemCount() = notifications.size
        
        private fun getAppName(packageName: String?): String {
            return when {
                packageName?.contains("whatsapp") == true -> "WhatsApp"
                packageName?.contains("instagram") == true -> "Instagram"
                packageName?.contains("telegram") == true -> "Telegram"
                packageName?.contains("facebook.orca") == true -> "Messenger"
                else -> packageName?.substringAfterLast(".") ?: "Unknown"
            }
        }
    }
}
