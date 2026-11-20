package com.mob.lee.fastair.viewmodel

import android.Manifest
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.*
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.mob.lee.fastair.R
import com.mob.lee.fastair.base.AppFragment
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 *
 * @Author:         Andy（632518410）
 * @CreateDate:     2020/6/19 11:08
 * @Description:    无
 */
class DeviceViewModel : AppViewModel() {
    val state by lazy { MutableLiveData<Boolean>() }
    val connectState by lazy { MutableLiveData<Boolean>() }
    val p2pState by lazy { MutableLiveData(P2pState.IDLE) }
    val connectError by lazy { MutableLiveData<Int?>() }
    val action by lazy { MutableLiveData<Pair<Int, Int>>() }
    val p2pInfo by lazy { MutableLiveData<WifiP2pInfo?>() }
    val device by lazy { MutableLiveData<WifiP2pDevice?>() }
    val devices by lazy { MutableLiveData<Collection<WifiP2pDevice>>() }

    internal var manager: WifiP2pManager? = null

    private val receiver by lazy {
        object : BroadcastReceiver() {
            // Every code path into the manager is gated by hasPermission() in init().
            @SuppressLint("MissingPermission")
            override fun onReceive(context: Context?, intent: Intent?) {
                Log.i(TAG, " Receive broadcast intent = ${intent?.action}")
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> state.value = (WifiP2pManager.WIFI_P2P_STATE_ENABLED == intent.getIntExtra(
                        WifiP2pManager.EXTRA_WIFI_STATE, WifiP2pManager.WIFI_P2P_STATE_DISABLED
                    )).apply { Log.i(TAG, "Device state = $this") }

                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> manager?.requestPeers(channel) {
                        Log.i(TAG, "Receive peers,peers = ${it.deviceList.joinToString()}")
                        // requestPeers reports discovery order, which is reshuffled
                        // every time the framework restarts its scan. Sort so the
                        // rows keep a stable position.
                        devices.value = it.deviceList
                            .sortedBy { peer -> peer.deviceName.orEmpty().lowercase() }
                    }


                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val info = intent.parcelableExtra<WifiP2pInfo>(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                        if (info?.groupFormed == true) {
                            p2pInfo.value = info
                            connectState.value = true
                            // The group exists; the timeout no longer applies.
                            clearConnectTimeout()
                            setP2pState(P2pState.CONNECTED)
                        } else {
                            p2pInfo.value = null
                            connectState.value = false
                            // A not-yet-formed group also reports false while the
                            // negotiation is still running, so this is not a
                            // failure. Only the timeout decides that.
                            if (p2pState.value != P2pState.CONNECTING) {
                                setP2pState(P2pState.IDLE)
                            }
                        }
                        Log.i(
                            TAG,
                            "p2pinfo = ${p2pInfo.value}, connected = ${connectState.value},group = ${
                                intent.parcelableExtra<WifiP2pGroup>(WifiP2pManager.EXTRA_WIFI_P2P_GROUP)
                            }"
                        )
                    }

                    WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                        val discoveryState = intent.getIntExtra(
                            WifiP2pManager.EXTRA_DISCOVERY_STATE,
                            WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED
                        )
                        Log.i(TAG, "Discovery state = $discoveryState")
                        // The framework stops peer discovery on its own after a
                        // while and does not resume it, which empties the list
                        // until the page is reopened. Restart while the user is
                        // still on it.
                        if (WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED == discoveryState) {
                            restartDiscover()
                        }
                    }

                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> device.value = intent.parcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE).apply {
                        Log.i(TAG, "Device change device = $this")
                    }
                }
            }
        }
    }

    private var channel: WifiP2pManager.Channel? = null
    private var connectTimes = RETRY_TIMES
    private var connectTimeout: Job? = null

    /** True between a successful start and an explicit stop. */
    private var scanning = false
    private var retries = 0
    private var pendingRetry: Job? = null

    fun init(activity: FragmentActivity) {
        if (hasPermission(activity)) {
            manager = ContextCompat.getSystemService(activity, WifiP2pManager::class.java)

            val c = channel ?: let {
                registerReceiver(activity)

                manager?.initialize(activity, activity.mainLooper) {
                    stopScan()
                }?.apply { channel = this }
            }
            if (Build.VERSION.SDK_INT >= 29 && null != c) {
                requestInfo(c)
            }
        }
    }

    // Callers reach here only after hasPermission() passed in init().
    @SuppressLint("MissingPermission")
    fun discover(listener: WifiP2pManager.ActionListener?) {
        connectTimes = RETRY_TIMES
        Log.i(TAG, "discover peers requested, p2pEnabled = ${state.value}")
        channel?.let {
            manager?.discoverPeers(it, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    connectTimes = RETRY_TIMES
                    scanning = true
                    retries = 0
                    Log.i(TAG, "discover peers success")
                }

                override fun onFailure(reason: Int) {
                    Log.e(TAG, "discover peers failed, raw reason = $reason")
                    if (WifiP2pManager.BUSY == reason) {
                        if (0 != connectTimes) {
                            connectTimes -= 1
                            disconnect(null)
                            manager?.discoverPeers(it, this)
                        }
                    }
                    listener?.onFailure(reason)
                }
            })
        }
    }

    /**
     * Stop scanning for good so a scan the framework stopped on its own is not
     * restarted in the background after the user leaves the page.
     */
    fun endDiscover() {
        retries = 0
        pendingRetry?.cancel()
        pendingRetry = null
        stopDiscover()
    }

    /**
     * The framework stops discovery by itself after a while and never resumes
     * it. Relaunch it while the user is still browsing, backing off so a device
     * that cannot scan does not spin.
     */
    private fun restartDiscover() {
        if (!scanning) {
            return
        }
        // While an invitation is in flight the radio belongs to it: a scan would
        // come back BUSY and discover() would tear the connection down. Wait for
        // a later stop instead, or for the explicit discover() after a failure.
        if (p2pState.value == P2pState.CONNECTING) {
            Log.i(TAG, "Discover stopped while connecting; not restarting it")
            return
        }
        if (retries >= MAX_RESTART) {
            Log.w(TAG, "Discover stopped repeatedly, giving up restarting it")
            return
        }
        val wait = RESTART_DELAY_MS * (retries + 1)
        retries += 1
        Log.i(TAG, "Discover stopped, restarting in ${wait}ms (attempt $retries)")
        pendingRetry = viewModelScope.launch {
            delay(wait)
            if (scanning) {
                discover(null)
            }
        }
    }

    // The discovery page refuses to start without the nearby-devices permission.
    @SuppressLint("MissingPermission")
    fun connect(device: WifiP2pDevice, listener: WifiP2pManager.ActionListener?) {
        val config = WifiP2pConfig()
        config.deviceAddress = device.deviceAddress

        clearConnectTimeout()
        // A scan restart must not fire while the invitation owns the radio.
        pendingRetry?.cancel()
        pendingRetry = null
        // Abandon any previous attempt so two invitations never overlap.
        manager?.cancelConnect(channel, null)
        setP2pState(P2pState.CONNECTING)
        manager?.connect(channel, config, listener)
        connectTimeout = viewModelScope.launch {
            delay(CONNECT_TIMEOUT_MS)
            Log.w(TAG, "Connect timed out after ${CONNECT_TIMEOUT_MS}ms")
            connectError.value = R.string.connect_timeout
            manager?.cancelConnect(channel, null)
            setP2pState(P2pState.FAILED)
        }
        Log.i(TAG, "Start connect.")
    }

    /**
     * Abandon an in-flight connect request. The framework otherwise keeps
     * retrying the invitation indefinitely. Does nothing once a group has
     * formed, so leaving the discovery screen cannot break a live connection.
     */
    fun cancelConnect() {
        if (p2pState.value != P2pState.CONNECTING) {
            return
        }
        clearConnectTimeout()
        manager?.cancelConnect(channel, null)
        setP2pState(P2pState.IDLE)
        Log.i(TAG, "Connect request cancelled.")
    }

    private fun clearConnectTimeout() {
        connectTimeout?.cancel()
        connectTimeout = null
    }

    private fun setP2pState(next: P2pState) {
        if (p2pState.value == next) {
            return
        }
        p2pState.value = next
        Log.i(TAG, "P2P state -> $next")
    }

    fun disconnect(actionListener: WifiP2pManager.ActionListener?) {
        clearConnectTimeout()
        manager?.removeGroup(channel, actionListener)
        manager?.cancelConnect(channel, actionListener)
        connectState.value = false
        p2pInfo.value = null
        setP2pState(P2pState.IDLE)
        Log.i(TAG, "Group has been removed.")
    }

    fun stopDiscover(){
        scanning = false
        channel?.let {
            manager?.stopPeerDiscovery(it,null)
        }
    }

    fun withConnectNavigation(
        fragment: AppFragment, target: Int, args: (Bundle.() -> Unit)? = null
    ) {
        if (isConnected()) {
            fragment.navigation(target, args)
        } else {
            fragment.navigation(R.id.discoverFragment, args)
        }
    }

    fun isConnected() = device.value?.status==WifiP2pDevice.CONNECTED

    fun bundle(): Bundle {
        val info = p2pInfo.value
        val host = info?.groupOwnerAddress?.hostAddress
        val groupOwner = info?.isGroupOwner ?: false
        val bundle = Bundle()
        bundle.putString(HOST, host)
        bundle.putBoolean(GROUP_OWNER, groupOwner)
        Log.d(TAG, "bundle host = $host, groupwoner = $groupOwner")
        return bundle
    }

    fun <T> startService(context: Context,service:Class<T>){
        Intent(context,service).apply {
            putExtras(bundle())
            context.startService(this)
        }
    }
    @SuppressLint("MissingPermission")
    @TargetApi(Build.VERSION_CODES.Q)
    private fun requestInfo(channel: WifiP2pManager.Channel) {
        manager?.apply {
            requestConnectionInfo(channel) {
                p2pInfo.value = it
            }
            requestDeviceInfo(channel) {
                device.value = it
            }
        }

    }

    private fun hasPermission(context: Context): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(
            context, permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun registerReceiver(context: FragmentActivity) {
        context.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                context.unregisterReceiver(receiver)
            }
        })

        val intentFilter = IntentFilter()
        /*指示Wi-Fi P2P是否开启*/
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        /*对等节点列表发生了变化*/
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        /*扫描状态发生了变化*/
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
        /*连接状态发生了变化*/
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        /*设备配置发生了变化*/
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)

        context.registerReceiver(receiver, intentFilter)

    }

    private fun stopScan() {
        channel?.let {
            disconnect(null)
            manager?.stopPeerDiscovery(it, null)
            channel = null
        }
    }

    private inline fun <reified T> Intent?.parcelableExtra(name: String): T? = this?.let {
        IntentCompat.getParcelableExtra(it, name, T::class.java)
    }

    companion object {
        @JvmStatic
        val TAG = "DeviceViewModel"

        @JvmStatic
        val HOST = "host"

        @JvmStatic
        val GROUP_OWNER = "groupOwner"

        @JvmStatic
        val RETRY_TIMES = 3

        /**
         * How long to wait for the group to be reported as formed before giving
         * up on a connect request. Group formation normally completes in a few
         * seconds.
         */
        const val CONNECT_TIMEOUT_MS = 15_000L

        /** Delay before the first scan restart; scaled by the attempt count. */
        const val RESTART_DELAY_MS = 1_000L

        /** Give up restarting a scan that keeps stopping. */
        const val MAX_RESTART = 20

        fun unBundle(bundle: Bundle?): Pair<String?, Boolean> {
            bundle ?: return null to false
            return (bundle.getString(HOST, null) to bundle.getBoolean(GROUP_OWNER, false)).apply { Log.d(TAG, "unbundle host = $first,group_owner = $second") }
        }
    }
}