package com.mob.lee.fastair.viewmodel

/**
 * Lifecycle of a Wi-Fi Direct group formation attempt, reported through
 * [DeviceViewModel.p2pState].
 *
 * [CONNECTING] is held until the group is actually reported as formed; a
 * successful connect request alone does not mean the group exists.
 */
enum class P2pState {
    IDLE, CONNECTING, CONNECTED, FAILED
}
