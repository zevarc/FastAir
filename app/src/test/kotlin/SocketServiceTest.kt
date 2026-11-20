import com.google.common.truth.Truth.assertThat
import com.mob.lee.fastair.io.ConnectionState
import com.mob.lee.fastair.io.ProtocolByte
import com.mob.lee.fastair.io.ProtocolType
import com.mob.lee.fastair.io.SocketService
import com.mob.lee.fastair.io.StringWriter
import com.mob.lee.fastair.io.Writer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.net.ServerSocket
import java.nio.channels.SocketChannel
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Exercises [SocketService] over the loopback interface. These cover the
 * reliability guarantees the connection layer is expected to hold: the port is
 * always released, a dropped peer does not kill the listener, the client keeps
 * retrying, heartbeats keep an idle link alive without reaching application code.
 */
class SocketServiceTest {

    /** Records everything a listener is told, so tests can assert on history. */
    private class Recorder : SocketService.SocketStateListener {
        val states = Collections.synchronizedList(ArrayList<ConnectionState>())
        val payloads = Collections.synchronizedList(ArrayList<ProtocolByte>())
        val channels = Collections.synchronizedList(ArrayList<SocketChannel>())

        val connected = CountDownLatch(1)
        @Volatile
        var connectedCount = 0

        override fun onConnected(channel: SocketChannel, extra: Any?) {
            connectedCount++
            channels.add(channel)
            connected.countDown()
        }

        override fun onDisconnected(exception: Exception?) {}

        override fun onStateChanged(state: ConnectionState) {
            states.add(state)
        }

        override fun onReadStart(channel: SocketChannel) {}

        override fun onReadFinished(channel: SocketChannel, data: ProtocolByte) {
            payloads.add(data)
        }

        override fun onReadError(channel: SocketChannel, exception: Exception) {}

        override fun onWriteStart(channel: SocketChannel, writer: Writer) {}

        override fun onWriteFinished(channel: SocketChannel, writer: Writer) {}

        override fun onWriteError(channel: SocketChannel, exception: Exception, writer: Writer?) {}

        fun awaitConnected(seconds: Long = 10) = connected.await(seconds, TimeUnit.SECONDS)

        /** Waits until the service has reported at least [count] separate connections. */
        fun awaitConnectedCount(count: Int, seconds: Long = 10): Boolean {
            val deadline = System.currentTimeMillis() + seconds * 1000
            while (System.currentTimeMillis() < deadline) {
                if (connectedCount >= count) {
                    return true
                }
                Thread.sleep(20)
            }
            return false
        }

        fun awaitState(state: ConnectionState, seconds: Long = 10, fromIndex: Int = 0): Boolean {
            val deadline = System.currentTimeMillis() + seconds * 1000
            while (System.currentTimeMillis() < deadline) {
                synchronized(states) {
                    if (states.drop(fromIndex).contains(state)) {
                        return true
                    }
                }
                Thread.sleep(20)
            }
            return false
        }

        fun stateCount() = states.size
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Sends [value] on [service] and waits for the peer to surface it. */
    private fun sendAndAwait(service: SocketService, recorder: Recorder, value: String) = runBlocking {
        service.write(StringWriter(value))
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (recorder.payloads.any { it.getType() == ProtocolType.W && it.getString() == value }) {
                return@runBlocking
            }
            kotlinx.coroutines.delay(20)
        }
        throw AssertionError("Peer never received \"$value\"")
    }

    @Test
    fun backoffDelayGrowsThenCaps() {
        assertThat(SocketService.backoffDelayOf(1)).isEqualTo(500L)
        assertThat(SocketService.backoffDelayOf(2)).isEqualTo(1_000L)
        assertThat(SocketService.backoffDelayOf(3)).isEqualTo(2_000L)
        assertThat(SocketService.backoffDelayOf(4)).isEqualTo(4_000L)
        assertThat(SocketService.backoffDelayOf(5)).isEqualTo(8_000L)
        assertThat(SocketService.backoffDelayOf(6)).isEqualTo(10_000L)
        assertThat(SocketService.backoffDelayOf(50)).isEqualTo(10_000L)
        assertThat(SocketService.backoffDelayOf(0)).isEqualTo(500L)
    }

    @Test
    fun serverReleasesPortOnClose() {
        val port = freePort()
        val scope = scope()
        val server = SocketService()
        scope.launch { server.open(scope, port) }
        Thread.sleep(500)
        server.close()
        Thread.sleep(500)
        // A leaked listening socket would make this bind fail.
        ServerSocket(port).use { assertThat(it.localPort).isEqualTo(port) }
        scope.cancel()
    }

    @Test
    fun serverAcceptsClientAndExchangesData() {
        val port = freePort()
        val scope = scope()
        val serverRecorder = Recorder()
        val clientRecorder = Recorder()
        val server = SocketService().apply { addListener(serverRecorder) }
        val client = SocketService().apply { addListener(clientRecorder) }

        scope.launch { server.open(scope, port) }
        Thread.sleep(300)
        scope.launch { client.open(scope, port, HOST) }

        assertThat(serverRecorder.awaitConnected()).isTrue()
        assertThat(clientRecorder.awaitConnected()).isTrue()

        sendAndAwait(client, serverRecorder, "ping")
        sendAndAwait(server, clientRecorder, "pong")

        client.close()
        server.close()
        scope.cancel()
    }

    @Test
    fun clientKeepsRetryingWhenPeerIsAbsent() {
        val port = freePort()
        val scope = scope()
        val recorder = Recorder()
        val client = SocketService().apply { addListener(recorder) }
        scope.launch { client.open(scope, port, HOST) }

        // Nothing is listening, so the client must not give up after a fixed
        // number of attempts: it should keep cycling through CONNECTING and
        // RECONNECTING rather than falling silent.
        assertThat(recorder.awaitState(ConnectionState.RECONNECTING, seconds = 15)).isTrue()
        val afterFirst = recorder.stateCount()
        assertThat(recorder.awaitState(ConnectionState.CONNECTING, seconds = 15, fromIndex = afterFirst))
            .isTrue()

        client.close()
        scope.cancel()
    }

    @Test
    fun clientConnectsAfterPeerStartsLate() {
        val port = freePort()
        val scope = scope()
        val clientRecorder = Recorder()
        val serverRecorder = Recorder()
        val client = SocketService().apply { addListener(clientRecorder) }

        scope.launch { client.open(scope, port, HOST) }
        Thread.sleep(1500)
        assertThat(clientRecorder.connectedCount).isEqualTo(0)

        val server = SocketService().apply { addListener(serverRecorder) }
        scope.launch { server.open(scope, port) }

        assertThat(clientRecorder.awaitConnected(seconds = 20)).isTrue()
        assertThat(serverRecorder.awaitConnected()).isTrue()

        client.close()
        server.close()
        scope.cancel()
    }

    @Test
    fun serverSurvivesPeerDropAndAcceptsNext() {
        val port = freePort()
        val scope = scope()
        val serverRecorder = Recorder()
        val firstRecorder = Recorder()
        val server = SocketService().apply { addListener(serverRecorder) }
        val first = SocketService().apply { addListener(firstRecorder) }

        scope.launch { server.open(scope, port) }
        Thread.sleep(300)
        scope.launch { first.open(scope, port, HOST) }
        assertThat(serverRecorder.awaitConnected()).isTrue()

        // The server socket must stay open, so a second peer can still get in.
        first.close()
        assertThat(serverRecorder.awaitState(ConnectionState.CONNECTING, seconds = 10)).isTrue()

        val secondRecorder = Recorder()
        val second = SocketService().apply { addListener(secondRecorder) }
        scope.launch { second.open(scope, port, HOST) }
        assertThat(secondRecorder.awaitConnected(seconds = 20)).isTrue()

        second.close()
        server.close()
        scope.cancel()
    }

    @Test
    fun clientReconnectsAfterPeerDrops() {
        val port = freePort()
        val scope = scope()
        val clientRecorder = Recorder()
        val serverRecorder = Recorder()
        val client = SocketService().apply { addListener(clientRecorder) }

        val firstServer = SocketService().apply { addListener(serverRecorder) }
        scope.launch { firstServer.open(scope, port) }
        Thread.sleep(300)
        scope.launch { client.open(scope, port, HOST) }
        assertThat(clientRecorder.awaitConnected()).isTrue()
        assertThat(serverRecorder.awaitConnected()).isTrue()

        // Drop the whole server: the client must retry rather than stay dead.
        val seen = clientRecorder.stateCount()
        firstServer.close()
        assertThat(clientRecorder.awaitState(ConnectionState.RECONNECTING, seconds = 15, fromIndex = seen))
            .isTrue()

        // And it must land on the replacement once one exists.
        val secondServer = SocketService().apply { addListener(Recorder()) }
        scope.launch { secondServer.open(scope, port) }
        assertThat(clientRecorder.awaitConnectedCount(2, seconds = 25)).isTrue()

        client.close()
        secondServer.close()
        scope.cancel()
    }

    @Test
    fun heartbeatIsConsumedInternallyAndKeepsLinkAlive() {
        val port = freePort()
        val scope = scope()
        val serverRecorder = Recorder()
        val clientRecorder = Recorder()
        val server = SocketService().apply { addListener(serverRecorder) }
        val client = SocketService().apply { addListener(clientRecorder) }

        scope.launch { server.open(scope, port) }
        Thread.sleep(300)
        scope.launch { client.open(scope, port, HOST) }
        assertThat(serverRecorder.awaitConnected()).isTrue()
        assertThat(clientRecorder.awaitConnected()).isTrue()

        // Idle for longer than the heartbeat interval so heartbeats are exchanged.
        Thread.sleep(SocketService.HEARTBEAT_INTERVAL_MS * 2)

        assertThat(serverRecorder.states).doesNotContain(ConnectionState.RECONNECTING)
        assertThat(clientRecorder.states).doesNotContain(ConnectionState.RECONNECTING)
        // Application code must never see a heartbeat frame: unknown types are
        // written into the received file by the file reader.
        assertThat(serverRecorder.payloads.any { it.getType() == ProtocolType.HEARTBEAT }).isFalse()
        assertThat(clientRecorder.payloads.any { it.getType() == ProtocolType.HEARTBEAT }).isFalse()

        client.close()
        server.close()
        scope.cancel()
    }

    @Test
    fun closeIsIdempotentAndReportsClosed() {
        val port = freePort()
        val scope = scope()
        val recorder = Recorder()
        val server = SocketService().apply { addListener(recorder) }
        scope.launch { server.open(scope, port) }
        Thread.sleep(300)

        server.close()
        server.close()
        server.close()

        assertThat(recorder.awaitState(ConnectionState.CLOSED, seconds = 5)).isTrue()
        assertThat(server.state).isEqualTo(ConnectionState.CLOSED)
        assertThat(server.isConnected).isFalse()
        scope.cancel()
    }

    @Test
    fun oversizedFrameLengthDropsConnection() {
        val port = freePort()
        val scope = scope()
        val recorder = Recorder()
        val server = SocketService().apply { addListener(recorder) }
        scope.launch { server.open(scope, port) }
        Thread.sleep(300)

        // Hand-rolled peer that announces a length past the frame cap.
        SocketChannel.open().use { raw ->
            raw.configureBlocking(true)
            raw.connect(java.net.InetSocketAddress(HOST, port))
            val head = java.nio.ByteBuffer.allocate(5)
            head.put(ProtocolType.B)
            head.putInt(Int.MAX_VALUE)
            head.flip()
            raw.write(head)
        }

        assertThat(recorder.awaitState(ConnectionState.CONNECTING, seconds = 10)).isTrue()
        assertThat(recorder.payloads).isEmpty()

        server.close()
        scope.cancel()
    }

    private companion object {
        const val HOST = "127.0.0.1"
    }
}
