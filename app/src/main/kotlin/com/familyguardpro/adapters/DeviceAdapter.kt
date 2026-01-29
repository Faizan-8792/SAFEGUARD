package com.familyguardpro.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.familyguardpro.R
import com.familyguardpro.models.ChildDevice
import java.text.SimpleDateFormat
import java.util.*

class DeviceAdapter(
    private val devices: List<ChildDevice>,
    private val onDeviceClick: (ChildDevice) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(devices[position])
    }

    override fun getItemCount(): Int = devices.size

    inner class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvDeviceName: TextView = itemView.findViewById(R.id.tvDeviceName)
        private val tvDeviceModel: TextView = itemView.findViewById(R.id.tvDeviceModel)
        private val tvLastSeen: TextView = itemView.findViewById(R.id.tvLastSeen)
        private val tvBattery: TextView = itemView.findViewById(R.id.tvBattery)
        private val viewOnlineStatus: View = itemView.findViewById(R.id.viewOnlineStatus)

        fun bind(device: ChildDevice) {
            tvDeviceName.text = device.name
            tvDeviceModel.text = device.model ?: "Android Device"
            tvBattery.text = "🔋 ${device.batteryLevel}%"
            
            // lastSeen is now a Long (timestamp in ms from backend)
            val lastSeenTime = device.lastSeen
            val isOnline = device.isOnline || (lastSeenTime > 0 && System.currentTimeMillis() - lastSeenTime < 5 * 60 * 1000)
            
            if (isOnline) {
                tvLastSeen.text = "🟢 Online"
                viewOnlineStatus.setBackgroundResource(R.drawable.ic_online)
            } else {
                if (lastSeenTime > 0) {
                    val sdf = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault())
                    tvLastSeen.text = "Last seen: ${sdf.format(Date(lastSeenTime))}"
                } else {
                    tvLastSeen.text = "Last seen: Unknown"
                }
                viewOnlineStatus.setBackgroundResource(R.drawable.ic_offline)
            }

            itemView.setOnClickListener { onDeviceClick(device) }
        }
    }
}
