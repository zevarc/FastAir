package com.mob.lee.fastair.service

import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.mob.lee.fastair.R
import com.mob.lee.fastair.base.AppService
import com.mob.lee.fastair.base.Connection
import com.mob.lee.fastair.base.WifiLocks
import com.mob.lee.fastair.service.Notification.Companion.channel
import com.mob.lee.fastair.service.Notification.Companion.foreground
import com.zevarc.zevhub.session.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Base for the features that ride the single shared [Session].
 *
 * Everything about establishing a link now belongs to
 * [com.mob.lee.fastair.base.Connection]; a service only reacts to the session
 * appearing and disappearing and then does its own work on it. This replaced a
 * ten-callback listener interface that mirrored the old socket implementation.
 */
abstract class TransferService : AppService() {

    /** Fires once per established session. */
    abstract suspend fun onSessionReady(session: Session)

    /**
     * Fires each time the service is started or bound while a session already
     * exists.
     *
     * A session is published once, so features that queue work later — sending
     * a second file after the first finished — would otherwise never be woken:
     * onSessionReady has already run and there is no further emission.
     */
    open suspend fun onWorkRequested(session: Session) {}

    /** Notification channel this feature keeps in the foreground, if any. */
    open val foregroundChannel: ForegroundChannel? = null

    /**
     * Resolved lazily: `getString` is only valid once the service is attached,
     * which happens after the constructor has run.
     */
    data class ForegroundChannel(
        val id: String,
        val code: Int,
        private val nameOf: () -> String
    ) {
        val name: String get() = nameOf()
    }

    /**
     * Watches the shared session. Re-collected on every emission so a reconnect
     * starts this feature's work again on the new session.
     */
    private var sessionJob: kotlinx.coroutines.Job? = null

    private fun watchSession() {
        sessionJob = launch {
            Connection.session.collect { current ->
                if (current == null) {
                    Log.i(TAG, "$this session gone")
                    return@collect
                }
                Log.i(TAG, "$this session ready")
                val decision = WorkTrigger.decide(hasSession = true, isNewSession = true)
                if (decision.onSessionReady) {
                    launch(Dispatchers.IO) {
                        runCatching { onSessionReady(current) }
                            .onFailure { Log.e(TAG, "$this session work failed", it) }
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        // Binding happens when the user opens the feature's screen, which is how
        // a second batch of work arrives after the first one finished.
        requestWork("onBind")
        return BinderImpl(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Start $this")
        // The radio must stay awake for the whole session, whichever feature
        // happened to come up first.
        WifiLocks.acquire(this)
        Connection.start(this)
        watchSession()
        // `startService` also lands here when the service is already running,
        // which is exactly the "send another file" case.
        requestWork("onStartCommand")
        foregroundChannel?.let {
            channel(it.id, it.name)
            foreground(it.id, it.code) {
                setContentTitle(getString(R.string.transfer_service))
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /**
     * Re-checks for work against the live session. See [WorkTrigger] for why a
     * plain "session appeared" callback is not enough.
     */
    private fun requestWork(reason: String) {
        val current = Connection.session.value
        val decision = WorkTrigger.decide(
            hasSession = current != null,
            isNewSession = false
        )
        if (!decision.onWorkRequested || current == null) {
            return
        }
        launch(Dispatchers.IO) {
            runCatching { onWorkRequested(current) }
                .onFailure { Log.e(TAG, "$this work request ($reason) failed", it) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "$this has destroyed.")
        sessionJob?.cancel()
        // The runtime is shared, so tearing it down here would break whichever
        // feature is still alive; the app owns that decision.
        WifiLocks.release()
    }

    companion object {
        @JvmStatic
        val TAG = "TransferService"
    }
}
