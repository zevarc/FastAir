package com.mob.lee.fastair.fragment

import android.Manifest
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.MenuProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mob.lee.fastair.R
import com.mob.lee.fastair.adapter.DeviceAdapter
import com.mob.lee.fastair.base.AppFragment
import com.mob.lee.fastair.p2p.failedReason
import com.mob.lee.fastair.service.FileService
import com.mob.lee.fastair.service.MessageService
import com.mob.lee.fastair.utils.dialog
import com.mob.lee.fastair.utils.errorToast
import com.mob.lee.fastair.viewmodel.DeviceViewModel
import com.mob.lee.fastair.viewmodel.P2pState

/**
 * Created by Andy on 2017/8/11.
 */
class DiscoverFragment : AppFragment() {
    override val layout: Int = R.layout.fragment_discover

    private var jumped = false

    val viewModel by lazy {
        activityViewModel<DeviceViewModel>()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.registerPermission(this)
    }

    override fun setting() {
        title(R.string.discover_device, true)

        val deviceStatus = view<TextView>(R.id.device_status)
        val deviceAdapter = DeviceAdapter { device ->
            if (viewModel.p2pState.value != P2pState.CONNECTING) {
                connect(device)
            }
        }
        val deviceList = view<RecyclerView>(R.id.deviceList)
        deviceList?.layoutManager = LinearLayoutManager(requireContext())
        deviceList?.adapter = deviceAdapter

        deviceStatus?.text = getString(R.string.scan_ing)
        viewModel.devices.observe {
            deviceAdapter.replaceAll(it)
        }
        viewModel.device.observe {
            // This is the local device; the peers are the rows in the list.
            view<Toolbar>(R.id.toolbar)?.subtitle = it?.deviceName
        }
        viewModel.p2pState.observe {
            when (it) {
                P2pState.CONNECTING -> {
                    deviceStatus?.text = getString(R.string.connecting)
                }

                P2pState.FAILED -> {
                    requireContext().errorToast(viewModel.connectError.value ?: R.string.disconnected)
                    deviceStatus?.text = getString(R.string.scan_ing)
                    // Put the list back into a usable state for another attempt.
                    viewModel.discover(null)
                }

                P2pState.CONNECTED -> {
                    if (jumped) {
                        return@observe
                    }
                    jumped = true
                    viewModel.endDiscover()
                    jump()
                }

                P2pState.IDLE -> {}
            }
        }
        viewModel.withPermission(
            this,
            if (Build.VERSION.SDK_INT >= 33) {
                Manifest.permission.NEARBY_WIFI_DEVICES
            } else {
                Manifest.permission.ACCESS_FINE_LOCATION
            },
            action = { hasPermission ->
                if (hasPermission) {
                    viewModel.init(requireActivity())
                    viewModel.discover(
                        object : WifiP2pManager.ActionListener {
                            override fun onSuccess() {

                            }

                            override fun onFailure(reason: Int) {
                                context?.errorToast(failedReason(reason))
                            }
                        })
                } else {
                    mParent?.dialog {
                        setMessage(R.string.need_location)
                            .setPositiveButton(R.string.turn_on) { _, _ ->
                                viewModel.openSetting(this@DiscoverFragment)
                            }
                            .setNegativeButton(R.string.cancel) { _, _ ->
                                requireContext().errorToast(R.string.rejected_location)
                            }
                    }
                }
            })

        requireActivity().addMenuProvider(object :MenuProvider{
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_discover, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                if (R.id.menu_discover_help == menuItem.itemId) {
                    mParent?.dialog {
                        setMessage(R.string.discover_help)
                            .setPositiveButton(R.string.knowIt, null)
                    }
                    return true
                }
                return false
            }
        },this)
    }

    private fun connect(device: WifiP2pDevice) {
        // Discovery must stay running: stopPeerDiscovery() clears the framework peer
        // cache and invalidates device.deviceAddress, so the invitation is never sent.
        viewModel.connect(device, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Connect request accepted.")
            }

            override fun onFailure(reason: Int) {
                context?.errorToast(failedReason(reason))
            }
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // The toolbar is shared with the other pages, which do not reset it.
        view<Toolbar>(R.id.toolbar)?.subtitle = null
        // Leaving the page stops scanning for good; otherwise the framework's
        // own stop would be auto-restarted in the background.
        viewModel.endDiscover()
        // Leaving mid-connect would otherwise leave the framework retrying.
        viewModel.cancelConnect()
    }

    fun jump() {
        // Start the peer services while this fragment is still attached; leaving
        // the screen first detaches it and makes requireContext() throw.
        val context = requireContext()
        viewModel.startService(context, MessageService::class.java)
        viewModel.startService(context, FileService::class.java)

        val needToTarget = arguments?.containsKey("target") ?: false
        if (needToTarget) {
            val target = requireArguments().getInt("target")
            navigation(target, args = { putAll(arguments) }, options = {
                setPopUpTo(R.id.discoverFragment, true)
            })
        } else {
            mParent?.onBackPressedDispatcher?.onBackPressed()
        }
    }

    companion object {
        private const val TAG = "DiscoverFragment"
    }
}