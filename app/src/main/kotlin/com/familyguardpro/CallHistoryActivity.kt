package com.familyguardpro

import android.media.MediaPlayer
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.familyguardpro.adapters.CallLogAdapter
import com.familyguardpro.databinding.ActivityCallHistoryBinding
import com.familyguardpro.models.CallLogData
import com.familyguardpro.network.ApiClient
import com.familyguardpro.utils.PreferenceManager
import kotlinx.coroutines.launch
import java.io.File

class CallHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCallHistoryBinding
    private lateinit var preferenceManager: PreferenceManager
    private lateinit var adapter: CallLogAdapter
    
    private var deviceId: String = ""
    private val callLogs = mutableListOf<CallLogData>()
    private var mediaPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCallHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        preferenceManager = PreferenceManager(this)
        deviceId = intent.getStringExtra("deviceId") ?: ""
        
        setupUI()
        loadCallLogs()
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
    }

    private fun setupUI() {
        binding.toolbar.title = "📞 Call History"
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        
        // Delete All button
        binding.btnDeleteAll.setOnClickListener {
            showDeleteConfirmation()
        }
        
        adapter = CallLogAdapter(
            callLogs,
            onPlayClick = { callLog -> playRecording(callLog) },
            onDeleteClick = { callLog -> deleteCallLog(callLog) }
        )
        
        binding.rvCallLogs.apply {
            layoutManager = LinearLayoutManager(this@CallHistoryActivity)
            adapter = this@CallHistoryActivity.adapter
        }
        
        binding.swipeRefresh.setOnRefreshListener {
            loadCallLogs()
        }
    }

    private fun loadCallLogs() {
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch {
            try {
                val response = ApiClient.api.getCallLogs(
                    "Bearer ${preferenceManager.getAuthToken()}",
                    deviceId
                )
                
                if (response.success) {
                    callLogs.clear()
                    callLogs.addAll(response.callLogs ?: emptyList())
                    adapter.notifyDataSetChanged()
                    
                    binding.llEmptyState.visibility = if (callLogs.isEmpty()) View.VISIBLE else View.GONE
                    binding.btnDeleteAll.visibility = if (callLogs.isEmpty()) View.GONE else View.VISIBLE
                }
            } catch (e: Exception) {
                Toast.makeText(this@CallHistoryActivity, "Error loading call logs", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressBar.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun playRecording(callLog: CallLogData) {
        if (callLog.audioUrl.isNullOrEmpty()) {
            Toast.makeText(this, "No recording available", Toast.LENGTH_SHORT).show()
            return
        }
        
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(callLog.audioUrl)
                prepareAsync()
                setOnPreparedListener {
                    start()
                    Toast.makeText(this@CallHistoryActivity, "Playing...", Toast.LENGTH_SHORT).show()
                }
                setOnCompletionListener {
                    Toast.makeText(this@CallHistoryActivity, "Playback complete", Toast.LENGTH_SHORT).show()
                }
                setOnErrorListener { _, _, _ ->
                    Toast.makeText(this@CallHistoryActivity, "Playback error", Toast.LENGTH_SHORT).show()
                    true
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error playing recording", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteCallLog(callLog: CallLogData) {
        AlertDialog.Builder(this)
            .setTitle("Delete Recording")
            .setMessage("Delete this call recording?")
            .setPositiveButton("Delete") { _, _ ->
                performDeleteCallLog(callLog.id)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performDeleteCallLog(callLogId: String) {
        lifecycleScope.launch {
            try {
                val response = ApiClient.api.deleteCallLog(
                    "Bearer ${preferenceManager.getAuthToken()}",
                    deviceId,
                    callLogId
                )
                
                if (response.success) {
                    callLogs.removeAll { it.id == callLogId }
                    adapter.notifyDataSetChanged()
                    Toast.makeText(this@CallHistoryActivity, "Deleted", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@CallHistoryActivity, "Delete failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showDeleteConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("🗑️ Delete All Call Logs")
            .setMessage("This will:\n\n1. Delete all recordings from server\n2. Delete ALL call logs from child's phone\n\nThis cannot be undone!")
            .setPositiveButton("Delete All") { _, _ ->
                deleteAllCallLogs()
            }
            .setNegativeButton("Cancel", null)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }

    private fun deleteAllCallLogs() {
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch {
            try {
                val response = ApiClient.api.deleteCallLogs(
                    "Bearer ${preferenceManager.getAuthToken()}",
                    deviceId
                )
                
                if (response.success) {
                    callLogs.clear()
                    adapter.notifyDataSetChanged()
                    binding.llEmptyState.visibility = View.VISIBLE
                    binding.btnDeleteAll.visibility = View.GONE
                    
                    Toast.makeText(
                        this@CallHistoryActivity,
                        "All call logs deleted from child's phone",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        this@CallHistoryActivity,
                        response.message ?: "Delete failed",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@CallHistoryActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }
}
