package com.familyguardpro

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.familyguardpro.adapters.NotificationAdapter
import com.familyguardpro.databinding.ActivityNotificationListBinding
import com.familyguardpro.models.NotificationData
import com.familyguardpro.network.ApiClient
import com.familyguardpro.utils.PreferenceManager
import kotlinx.coroutines.launch

class NotificationListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotificationListBinding
    private lateinit var preferenceManager: PreferenceManager
    private lateinit var adapter: NotificationAdapter
    
    private var deviceId: String = ""
    private val notifications = mutableListOf<NotificationData>()
    private var currentPage = 1
    private var isLoading = false
    private var hasMore = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNotificationListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        preferenceManager = PreferenceManager(this)
        deviceId = intent.getStringExtra("deviceId") ?: ""
        
        setupUI()
        loadNotifications()
    }

    private fun setupUI() {
        binding.toolbar.title = "🔔 Notifications"
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        
        adapter = NotificationAdapter(notifications) { notification ->
            // Show notification details
            showNotificationDetail(notification)
        }
        
        binding.rvNotifications.apply {
            layoutManager = LinearLayoutManager(this@NotificationListActivity)
            adapter = this@NotificationListActivity.adapter
            
            // Pagination
            addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    
                    val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                    val visibleItemCount = layoutManager.childCount
                    val totalItemCount = layoutManager.itemCount
                    val firstVisibleItem = layoutManager.findFirstVisibleItemPosition()
                    
                    if (!isLoading && hasMore && (visibleItemCount + firstVisibleItem) >= totalItemCount - 5) {
                        loadMoreNotifications()
                    }
                }
            })
        }
        
        binding.swipeRefresh.setOnRefreshListener {
            currentPage = 1
            hasMore = true
            notifications.clear()
            adapter.notifyDataSetChanged()
            loadNotifications()
        }
        
        // Filter chips
        binding.chipAll.setOnClickListener { filterNotifications(null) }
        binding.chipWhatsApp.setOnClickListener { filterNotifications("com.whatsapp") }
        binding.chipInstagram.setOnClickListener { filterNotifications("com.instagram.android") }
        binding.chipTelegram.setOnClickListener { filterNotifications("org.telegram.messenger") }
    }

    private fun loadNotifications() {
        if (isLoading) return
        isLoading = true
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch {
            try {
                val response = ApiClient.api.getNotifications(
                    "Bearer ${preferenceManager.getAuthToken()}",
                    deviceId,
                    currentPage,
                    50
                )
                
                if (response.success) {
                    val newNotifications = response.notifications ?: emptyList()
                    notifications.addAll(newNotifications)
                    adapter.notifyDataSetChanged()
                    
                    hasMore = newNotifications.size >= 50
                    currentPage++
                    
                    binding.llEmptyState.visibility = if (notifications.isEmpty()) View.VISIBLE else View.GONE
                }
            } catch (e: Exception) {
                Toast.makeText(this@NotificationListActivity, "Error loading notifications", Toast.LENGTH_SHORT).show()
            } finally {
                isLoading = false
                binding.progressBar.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun loadMoreNotifications() {
        loadNotifications()
    }

    private fun filterNotifications(packageName: String?) {
        val filtered = if (packageName == null) {
            notifications
        } else {
            notifications.filter { it.packageName == packageName }
        }
        
        adapter.updateList(filtered)
    }

    private fun showNotificationDetail(notification: NotificationData) {
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(notification.appName)
            .setMessage("""
                Title: ${notification.title}
                
                Content: ${notification.text}
                
                Time: ${formatTime(notification.timestamp)}
                
                Package: ${notification.packageName}
            """.trimIndent())
            .setPositiveButton("OK", null)
            .create()
        
        dialog.show()
    }

    private fun formatTime(timestamp: Long): String {
        val sdf = java.text.SimpleDateFormat("MMM dd, yyyy hh:mm a", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(timestamp))
    }
}
