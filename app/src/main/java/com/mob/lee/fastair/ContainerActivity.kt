package com.mob.lee.fastair

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import com.mob.lee.fastair.utils.errorToast
import com.mob.lee.fastair.viewmodel.DeviceViewModel
import com.mob.lee.fastair.viewmodel.HomeViewModel
import com.mob.lee.fastair.viewmodel.P2pState

/**
 * Created by Andy on 2017/6/2.
 */
class ContainerActivity : AppCompatActivity() {

    val mNavController by lazy {
        findNavController(R.id.containerFragment)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity)

        handleIntent(intent)

        val viewModel: DeviceViewModel by viewModels()
        viewModel.run {
            init(this@ContainerActivity)
            p2pState.observe(this@ContainerActivity) {
                if (P2pState.CONNECTED == it) {
                    Log.i(TAG, "P2P group formed.")
                }
            }
            connectState.observe(this@ContainerActivity) {
                if (!it) {
                    // The Wi-Fi Direct group drops on screen-off and app switches and
                    // re-forms on its own. Do not tear the UI down for a transient loss.
                    Log.i(TAG, "P2P group is not formed.")
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    fun handleIntent(intent: Intent?) {
        intent ?: return
        if (Intent.ACTION_SEND != intent.action && Intent.ACTION_SEND_MULTIPLE != intent.action) {
            return
        }
        val v: HomeViewModel by viewModels()
        v.parseSharedIntent(this, intent).observe(this) {
            if (it.data ?: 0 > 0) {
                mNavController.navigate(R.id.beforeFragment)
            } else {
                errorToast(R.string.no_file_to_send)
            }
        }
    }

    companion object {
        private const val TAG = "ContainerActivity"
    }
}