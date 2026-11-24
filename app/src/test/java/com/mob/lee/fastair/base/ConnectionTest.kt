package com.mob.lee.fastair.base

import com.zevarc.zevhub.ZevHub
import com.zevarc.zevhub.device.Device
import com.zevarc.zevhub.device.DeviceId
import com.zevarc.zevhub.session.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The shared runtime is the one place where "one session, many features" is
 * decided, so it is pinned here without a radio.
 */
class ConnectionTest {

    private class FakeHub(
        private val incoming: MutableSharedFlow<Session>,
        private val session: Session
    ) : ZevHub {
        // Replay so an emission that lands before the collector subscribes is
        // still observed, the way a real conncurrent flow would behave.

        override val device = Device(DeviceId("self"), "test")
        var started = false
        var closed = false
        var connectAttempts = 0

        override suspend fun start() {
            started = true
        }

        override fun discover(): Flow<Device> = MutableSharedFlow()

        override suspend fun connect(device: Device): Session {
            connectAttempts++
            return session
        }

        fun deliver(session: Session) {
            incoming.tryEmit(session)
        }

        override fun incomingSessions(): Flow<Session> = incoming

        override fun close() {
            closed = true
        }
    }

    private fun fakeSession(id: String): Session =
        FakeSession(DeviceId(id))

    @Before
    fun reset() {
        // Connection is a process-wide object; a test that leaves it started
        // poisons every test after it.
        Connection.shutdown()
    }

    @Test
    fun `starting twice keeps a single runtime`() = runTest {
        val incoming = MutableSharedFlow<Session>(replay = 1)
        val session = fakeSession("peer")
        var builds = 0
        val hub = FakeHub(incoming, session)

        Connection.start(backgroundScope) { builds++; hub }
        Connection.start(backgroundScope) { builds++; hub }
        runCurrent()

        assertEquals(1, builds)
        assertTrue(hub.started)
        Connection.shutdown()
    }

    @Test
    fun `an incoming session is published to every observer`() = runTest {
        val incoming = MutableSharedFlow<Session>(replay = 1)
        val session = fakeSession("peer")
        val hub = FakeHub(incoming, session)

        Connection.start(backgroundScope) { hub }
        runCurrent()
        hub.deliver(session)
        runCurrent()

        assertEquals(session, Connection.session.value)
        Connection.shutdown()
    }

    @Test
    fun `connect publishes the session it obtains`() = runTest {
        val incoming = MutableSharedFlow<Session>(replay = 1)
        val session = fakeSession("dialed")
        val hub = FakeHub(incoming, session)

        Connection.start(backgroundScope) { hub }
        runCurrent()
        Connection.connect(Device(DeviceId("dialed"), null))
        runCurrent()

        assertEquals(session, Connection.session.value)
        assertEquals(1, hub.connectAttempts)
        Connection.shutdown()
    }

    @Test
    fun `disconnect clears the session but keeps the runtime alive`() = runTest {
        val incoming = MutableSharedFlow<Session>(replay = 1)
        val session = fakeSession("peer")
        val hub = FakeHub(incoming, session)

        Connection.start(backgroundScope) { hub }
        runCurrent()
        hub.deliver(session)
        runCurrent()

        Connection.disconnect()
        runCurrent()

        assertNull(Connection.session.value)
        // The other feature may still be using the runtime, so it must survive.
        assertTrue(!hub.closed)
        Connection.shutdown()
    }

    @Test
    fun `shutdown releases the runtime so it can start again`() = runTest {
        val incoming = MutableSharedFlow<Session>(replay = 1)
        val session = fakeSession("peer")
        val first = FakeHub(incoming, session)

        Connection.start(backgroundScope) { first }
        runCurrent()
        Connection.shutdown()
        assertTrue(first.closed)

        val second = FakeHub(incoming, session)
        Connection.start(backgroundScope) { second }
        runCurrent()

        assertTrue("a restart must build a new runtime", second.started)
        Connection.shutdown()
    }

    /** Minimal stand-in; only identity matters to Connection. */
    private class FakeSession(private val peerId: DeviceId) : Session {
        override val device: Device = Device(peerId, null)
        override suspend fun send(message: com.zevarc.zevhub.message.Message) = Unit
        override fun messages(): Flow<com.zevarc.zevhub.message.Message> = MutableSharedFlow()
        override suspend fun request(
            request: com.zevarc.zevhub.request.Request,
            timeoutMs: Long
        ): com.zevarc.zevhub.request.Response =
            com.zevarc.zevhub.request.Response.Success(ByteArray(0))

        override fun requests(): Flow<com.zevarc.zevhub.request.IncomingRequest> =
            MutableSharedFlow()

        override fun transfer(
            source: com.zevarc.zevhub.transfer.TransferSource
        ): com.zevarc.zevhub.transfer.Transfer = throw UnsupportedOperationException()

        override fun transfers(): Flow<com.zevarc.zevhub.transfer.IncomingTransfer> =
            MutableSharedFlow()

        override suspend fun openStream(
            type: com.zevarc.zevhub.stream.StreamType
        ): com.zevarc.zevhub.stream.Stream = throw UnsupportedOperationException()

        override fun streams(): Flow<com.zevarc.zevhub.stream.IncomingStream> =
            MutableSharedFlow()

        override suspend fun close() = Unit
    }
}
