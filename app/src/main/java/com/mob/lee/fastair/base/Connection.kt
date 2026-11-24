package com.mob.lee.fastair.base

import android.util.Log
import com.zevarc.zevhub.ZevHub
import com.zevarc.zevhub.ZevHubFactory
import com.zevarc.zevhub.device.Device
import com.zevarc.zevhub.session.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Owns the single [ZevHub] runtime and the one [Session] every feature shares.
 *
 * FastAir used to open a separate TCP connection per feature (port 9527 for
 * files, 9528 for chat), so the radio carried two competing streams. ZevHub
 * already multiplexes by context id, so one session is enough and every
 * service observes the same connection instead of racing to establish its own.
 */
object Connection {

    private const val TAG = "Connection"

    /** The current session, or null when there is none. Survives reconnects. */
    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session

    private var hub: ZevHub? = null
    private var scope: CoroutineScope? = null
    private var collector: Job? = null
    private var startJob: Job? = null

    /** True once [start] succeeded; used by services to avoid double init. */
    @Volatile
    var started: Boolean = false
        private set

    /**
     * Starts the runtime and begins publishing sessions. Idempotent: services
     * may call this from whichever one comes up first.
     *
     * [factory] is a parameter so the runtime can be replaced in tests: nothing
     * below `ZevHub` is reachable without a real radio.
     */
    fun start(
        scope: CoroutineScope,
        factory: (CoroutineScope) -> ZevHub = { ZevHubFactory.create(ApplicationHolder.context, it) }
    ) {
        if (started) {
            return
        }
        started = true
        this.scope = scope

        val instance = factory(scope)
        hub = instance
        startJob = scope.launch {
            runCatching { instance.start() }
                .onFailure { Log.e(TAG, "ZevHub start failed", it) }
        }
        collector = scope.launch {
            instance.incomingSessions().collect { established ->
                Log.i(TAG, "session established with ${established.device.id.value}")
                _session.value = established
            }
        }
    }

    /** Dials [device]. [session] publishes the result on success. */
    fun connect(device: Device) {
        val instance = hub ?: return
        val s = scope ?: return
        s.launch {
            runCatching { instance.connect(device) }
                .onSuccess { _session.value = it }
                .onFailure { Log.e(TAG, "connect failed", it) }
        }
    }

    /**
     * Drops the current session but keeps the runtime alive: the other feature
     * may still be using it, and reclaiming the radio is a UI-level decision.
     */
    fun disconnect() {
        val current = _session.value
        _session.value = null
        val s = scope
        if (current != null && s != null) {
            s.launch { runCatching { current.close() } }
        }
    }

    /** Tears down the runtime. Call when the app itself is going away. */
    fun shutdown() {
        collector?.cancel()
        startJob?.cancel()
        _session.value = null
        runCatching { hub?.close() }
        hub = null
        scope = null
        started = false
    }
}
