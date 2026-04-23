package com.familyguardpro

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.familyguardpro.databinding.ActivityCallHistoryBinding
import com.familyguardpro.databinding.ItemCallLogBinding
import com.familyguardpro.models.CallLogEntry
import com.familyguardpro.network.ApiClient
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class CallHistoryActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityCallHistoryBinding
    private var deviceId: String? = null
    private val callLogs = mutableListOf<CallLogEntry>()
    private lateinit var adapter: CallLogAdapter
    private val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCallHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        deviceId = intent.getStringExtra("deviceId")
        
        setupUI()
        loadCallLogs()
    }
    
    private fun setupUI() {
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }
        
        adapter = CallLogAdapter(callLogs)
        binding.rvCallLogs.layoutManager = LinearLayoutManager(this)
        binding.rvCallLogs.adapter = adapter
        
        binding.swipeRefresh.setOnRefreshListener {
            loadCallLogs()
        }
        
        binding.btnDeleteAll.setOnClickListener {
            showDeleteDialog()
        }
    }
    
    private fun loadCallLogs() {
        binding.progressBar.visibility = View.VISIBLE
        binding.llEmptyState.visibility = View.GONE
        
        lifecycleScope.launch {
            deviceId?.let { id ->
                val result = ApiClient.getCallLogs(id)
                
                result.fold(
                    onSuccess = { logs ->
                        binding.progressBar.visibility = View.GONE
                        binding.swipeRefresh.isRefreshing = false
                        
                        callLogs.clear()
                        callLogs.addAll(logs)
                        adapter.notifyDataSetChanged()
                        
                        updateStats()
                        
                        if (callLogs.isEmpty()) {
                            binding.llEmptyState.visibility = View.VISIBLE
                        }
                    },
                    onFailure = { error ->
                        binding.progressBar.visibility = View.GONE
                        binding.swipeRefresh.isRefreshing = false
                        Toast.makeText(this@CallHistoryActivity, "Error: ${error.message}", Toast.LENGTH_LONG).show()
                    }
                )
            } ?: run {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this@CallHistoryActivity, "No device ID", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun updateStats() {
        binding.tvTotalCalls.text = callLogs.size.toString()
        binding.tvIncoming.text = callLogs.count { it.type == "incoming" || it.type == "1" }.toString()
        binding.tvOutgoing.text = callLogs.count { it.type == "outgoing" || it.type == "2" }.toString()
        binding.tvMissed.text = callLogs.count { it.type == "missed" || it.type == "3" }.toString()
    }
    
    private fun showDeleteDialog() {
        AlertDialog.Builder(this)
            .setTitle("Delete Call Logs")
            .setMessage("Are you sure you want to delete all call logs from the child device?")
            .setPositiveButton("Delete") { _, _ ->
                deleteCallLogs()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun deleteCallLogs() {
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch {
            deviceId?.let { id ->
                val result = ApiClient.deleteCallLogs(id)
                
                result.fold(
                    onSuccess = {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(this@CallHistoryActivity, "Call logs deleted", Toast.LENGTH_SHORT).show()
                        loadCallLogs()
                    },
                    onFailure = { error ->
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(this@CallHistoryActivity, "Error: ${error.message}", Toast.LENGTH_LONG).show()
                    }
                )
            }
        }
    }
    
    // Call Log Adapter
    inner class CallLogAdapter(
        private val logs: List<CallLogEntry>
    ) : RecyclerView.Adapter<CallLogAdapter.ViewHolder>() {
        
        inner class ViewHolder(val binding: ItemCallLogBinding) : RecyclerView.ViewHolder(binding.root)
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemCallLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val log = logs[position]
            
            holder.binding.tvPhoneNumber.text = log.name ?: log.number
            
            val typeText = when (log.type) {
                "incoming", "1" -> "Incoming"
                "outgoing", "2" -> "Outgoing"
                else -> "Missed"
            }
            holder.binding.tvCallType.text = typeText
            holder.binding.tvDuration.text = formatDuration(log.duration)
            
            log.timestamp?.let { ts ->
                val date = Date(ts)
                holder.binding.tvTime.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
                holder.binding.tvDate.text = SimpleDateFormat("MMM dd", Locale.getDefault()).format(date)
            }
            
            val typeIcon = when (log.type) {
                "incoming", "1" -> R.drawable.ic_call_incoming
                "outgoing", "2" -> R.drawable.ic_call_outgoing
                else -> R.drawable.ic_call_missed
            }
            holder.binding.ivCallType.setImageResource(typeIcon)
            
            val typeColor = when (log.type) {
                "incoming", "1" -> R.color.success
                "outgoing", "2" -> R.color.warning
                else -> R.color.error
            }
            holder.binding.tvCallType.setTextColor(holder.itemView.context.getColor(typeColor))
        }
        
        override fun getItemCount() = logs.size
        
        private fun formatDuration(seconds: Long): String {
            val mins = seconds / 60
            val secs = seconds % 60
            return String.format("%d:%02d", mins, secs)
        }
    }
}
