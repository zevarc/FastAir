package com.mob.lee.fastair.io

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

fun getNetIP(context: Context): String? {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = manager.activeNetwork ?: return null
    val capabilities = manager.getNetworkCapabilities(network) ?: return null
    val hasWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    val hasCellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    if (!hasWifi && !hasCellular) {
        return null
    }
    val linkProperties = manager.getLinkProperties(network) ?: return null
    return linkProperties.linkAddresses
        .firstOrNull { it.address is Inet4Address }
        ?.address
        ?.hostAddress
}
