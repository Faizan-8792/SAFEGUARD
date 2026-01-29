package com.familyguardpro.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.familyguardpro.R
import com.familyguardpro.models.CallLogData
import java.text.SimpleDateFormat
import java.util.*

class CallLogAdapter(
    private val callLogs: List<CallLogData>,
    private val onPlayClick: (CallLogData) -> Unit,
    private val onDeleteClick: (CallLogData) -> Unit
) : RecyclerView.Adapter<CallLogAdapter.CallLogViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CallLogViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_call_log, parent, false)
        return CallLogViewHolder(view)
    }

    override fun onBindViewHolder(holder: CallLogViewHolder, position: Int) {
        holder.bind(callLogs[position])
    }

    override fun getItemCount(): Int = callLogs.size

    inner class CallLogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivCallType: ImageView = itemView.findViewById(R.id.ivCallType)
        private val tvPhoneNumber: TextView = itemView.findViewById(R.id.tvPhoneNumber)
        private val tvCallType: TextView = itemView.findViewById(R.id.tvCallType)
        private val tvDuration: TextView = itemView.findViewById(R.id.tvDuration)
        private val tvTime: TextView = itemView.findViewById(R.id.tvTime)
        private val tvDate: TextView = itemView.findViewById(R.id.tvDate)
        private val ivRecording: ImageView = itemView.findViewById(R.id.ivRecording)

        fun bind(callLog: CallLogData) {
            tvPhoneNumber.text = callLog.contactName ?: callLog.phoneNumber
            
            // Call type and color
            val (typeText, colorRes) = when (callLog.callType) {
                "incoming" -> "Incoming" to R.color.call_incoming
                "outgoing" -> "Outgoing" to R.color.call_outgoing
                "missed" -> "Missed" to R.color.call_missed
                "rejected" -> "Rejected" to R.color.call_missed
                else -> "Call" to R.color.text_secondary
            }
            tvCallType.text = typeText
            tvCallType.setTextColor(itemView.context.getColor(colorRes))
            ivCallType.setColorFilter(itemView.context.getColor(colorRes))
            
            // Duration
            tvDuration.text = formatDuration(callLog.durationSeconds)
            
            // Time and date
            val (time, date) = formatDateTime(callLog.timestamp)
            tvTime.text = time
            tvDate.text = date
            
            // Recording indicator
            if (!callLog.audioUrl.isNullOrEmpty()) {
                ivRecording.visibility = View.VISIBLE
                itemView.setOnClickListener { onPlayClick(callLog) }
            } else {
                ivRecording.visibility = View.GONE
            }
            
            itemView.setOnLongClickListener {
                onDeleteClick(callLog)
                true
            }
        }

        private fun formatDuration(seconds: Int): String {
            return when {
                seconds == 0 -> "0s"
                seconds < 60 -> "${seconds}s"
                seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
                else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
            }
        }

        private fun formatDateTime(timestamp: Long): Pair<String, String> {
            val timeFmt = SimpleDateFormat("hh:mm a", Locale.getDefault())
            val dateFmt = SimpleDateFormat("MMM dd", Locale.getDefault())
            val now = System.currentTimeMillis()
            val diff = now - timestamp
            
            val dateStr = when {
                diff < 24 * 60 * 60 * 1000 -> "Today"
                diff < 48 * 60 * 60 * 1000 -> "Yesterday"
                else -> dateFmt.format(Date(timestamp))
            }
            
            return timeFmt.format(Date(timestamp)) to dateStr
        }
    }
}
