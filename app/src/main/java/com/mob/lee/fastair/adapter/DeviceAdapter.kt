package com.mob.lee.fastair.adapter

import android.net.wifi.p2p.WifiP2pDevice
import com.mob.lee.fastair.R

/**
 * One discovered Wi-Fi Direct peer, rendered as a list row. The row carries no
 * selection state: taps are reported through [action].
 */
class DeviceAdapter(val action: (WifiP2pDevice) -> Unit) :
    AppListAdapter<WifiP2pDevice>(R.layout.item_device) {

    override fun onBindViewHolder(holder: AppViewHolder, position: Int, data: WifiP2pDevice) {
        holder.text(R.id.item_device_name, data.deviceName)
        holder.text(R.id.item_device_status, statusText(data.status))
        holder.itemView.setOnClickListener { action(data) }
    }

    private fun statusText(status: Int): Int = when (status) {
        WifiP2pDevice.AVAILABLE -> R.string.device_status_available
        WifiP2pDevice.CONNECTED -> R.string.device_status_connected
        WifiP2pDevice.FAILED -> R.string.device_status_failed
        WifiP2pDevice.INVITED -> R.string.device_status_invaited
        else -> R.string.device_status_unavailable
    }
}
