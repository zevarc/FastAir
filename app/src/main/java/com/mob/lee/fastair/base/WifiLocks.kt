package com.mob.lee.fastair.base

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log

/**
 * Holds a Wi-Fi lock and a multicast lock for the lifetime of an active connection.
 *
 * Without them the platform may power the Wi-Fi radio down or filter multicast
 * traffic while the screen is off, which silently kills Wi-Fi Direct discovery
 * and any socket riding on the group. Reference counted so several services can
 * hold it at once.
 */
object WifiLocks {
    private const val TAG = "WifiLocks"
    private const val LOCK_TAG = "fastair:wifi"
    private const val MULTICAST_TAG = "fastair:multicast"

    private var refCount = 0
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    @Synchronized
    fun acquire(context: Context) {
        refCount++
        if (refCount > 1) {
            return
        }
        val manager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        // Acquire independently: one failing must not cost us the other.
        try {
            @Suppress("DEPRECATION")
            wifiLock = manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, LOCK_TAG)
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire Wi-Fi lock: ${e.message}")
        }
        try {
            multicastLock = manager.createMulticastLock(MULTICAST_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire multicast lock: ${e.message}")
        }
        Log.i(TAG, "Locks acquired. wifi=${null != wifiLock}, multicast=${null != multicastLock}")
    }

    @Synchronized
    fun release() {
        if (refCount == 0) {
            return
        }
        refCount--
        if (refCount > 0) {
            return
        }
        try {
            multicastLock?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release multicast lock: ${e.message}")
        }
        try {
            wifiLock?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release Wi-Fi lock: ${e.message}")
        }
        multicastLock = null
        wifiLock = null
        Log.i(TAG, "Wi-Fi and multicast locks released.")
    }
}
