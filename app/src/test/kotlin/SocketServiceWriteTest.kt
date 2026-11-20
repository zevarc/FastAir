import com.google.common.truth.Truth.assertThat
import com.mob.lee.fastair.io.ConnectionState
import com.mob.lee.fastair.io.ProtocolByte
import com.mob.lee.fastair.io.SocketService
import com.mob.lee.fastair.io.Writer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Test
import java.net.ServerSocket
import java.nio.channels.SocketChannel
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Covers what happens to a write task that can no longer be delivered. The
 * connection layer is responsible for telling the producer, because a task that
 * is silently dropped leaves a file handle open and a transfer stuck in
 * "in progress" forever.
 */
class SocketServiceWriteTest {

    /** Minimal listener; only the write side matters here. */
    private class Recorder : SocketService.SocketStateListener {
        val states = Collections.synchronizedList(ArrayList<ConnectionState>())
        val writeErrors = Collections.synchronizedList(ArrayList<Writer>())
        val connected = CountDownLatch(1)

        override fun onConnected(channel: SocketChannel, extra: Any?) {
            connected.countDown()
        }

        override fun onDisconnected(exception: Exception?) {}

        override fun onStateChanged(state: ConnectionState) {
            states.add(state)
        }

        override fun onReadStart(channel: SocketChannel) {}

        override fun onReadFinished(channel: SocketChannel, data: ProtocolByte) {}

        override fun onReadError(channel: SocketChannel, exception: Exception) {}

        override fun onWriteStart(channel: SocketChannel, writer: Writer) {}

        override fun onWriteFinished(channel: SocketChannel, writer: Writer) {}

        override fun onWriteError(channel: SocketChannel, exception: Exception, writer: Writer?) {
            writer?.let { writeErrors.add(it) }
        }

        fun awaitConnected(seconds: Long = 10) = connected.await(seconds, TimeUnit.SECONDS)
    }

    /**
     * A writer that never finishes unless it is told to stop, and records
     * whether it was released. Stands in for FileWriter, whose stream must be
     * closed on cancellation.
     */
    private class EndlessWriter : Writer {
        val cancelled = CountDownLatch(1)
        val cancelCause = Collections.synchronizedList(ArrayList<Exception>())
        private var produced = 0

        override fun hasNext() = true

        override fun next(): ProtocolByte {
            produced++
            return ProtocolByte.bytes(ByteArray(9 * 1024))
        }

        override fun cancel(cause: Exception) {
            cancelCause.add(cause)
            cancelled.countDown()
        }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Test
    fun writeIsRejectedBeforeTheConnectionIsReady() {
        val scope = scope()
        val service = SocketService()
        scope.launch { service.open(scope, freePort()) }
        Thread.sleep(300)

        // No peer yet: the task must be refused outright so the caller can fail
        // the transfer instead of waiting for a connection that may not come.
        var accepted = true
        kotlinx.coroutines.runBlocking { accepted = service.write(EndlessWriter()) }

        assertThat(accepted).isFalse()
        service.close()
        scope.cancel()
    }

    @Test
    fun queuedTaskIsReleasedWhenThePeerDrops() {
        val port = freePort()
        val scope = scope()
        val serverRecorder = Recorder()
        val clientRecorder = Recorder()
        val server = SocketService().apply { addListener(serverRecorder) }
        val client = SocketService().apply { addListener(clientRecorder) }

        scope.launch { server.open(scope, port) }
        Thread.sleep(300)
        scope.launch { client.open(scope, port, HOST) }
        assertThat(clientRecorder.awaitConnected()).isTrue()
        assertThat(serverRecorder.awaitConnected()).isTrue()

        // First task occupies the single write loop, the rest sit in the queue.
        val active = EndlessWriter()
        val queued = EndlessWriter()
        kotlinx.coroutines.runBlocking {
            assertThat(client.write(active)).isTrue()
            assertThat(client.write(queued)).isTrue()
        }
        // Let the writer loop pick up the first task.
        Thread.sleep(200)

        // Drop the server: the client must release whatever it still holds.
        server.close()

        assertThat(queued.cancelled.await(15, TimeUnit.SECONDS)).isTrue()
        assertThat(queued.cancelCause).isNotEmpty()

        client.close()
        scope.cancel()
    }

    @Test
    fun closeReleasesTasksThatWereNeverConsumed() {
        val port = freePort()
        val scope = scope()
        val serverRecorder = Recorder()
        val clientRecorder = Recorder()
        val server = SocketService().apply { addListener(serverRecorder) }
        val client = SocketService().apply { addListener(clientRecorder) }

        scope.launch { server.open(scope, port) }
        Thread.sleep(300)
        scope.launch { client.open(scope, port, HOST) }
        assertThat(clientRecorder.awaitConnected()).isTrue()

        val first = EndlessWriter()
        val second = EndlessWriter()
        kotlinx.coroutines.runBlocking {
            client.write(first)
            client.write(second)
        }
        Thread.sleep(200)

        client.close()

        // `close` drains the queue, so the untouched task is released too.
        assertThat(second.cancelled.await(10, TimeUnit.SECONDS)).isTrue()
        assertThat(client.state).isEqualTo(ConnectionState.CLOSED)

        server.close()
        scope.cancel()
    }

    @Test
    fun writeAfterCloseIsRejected() {
        val scope = scope()
        val service = SocketService()
        service.close()

        var accepted = true
        kotlinx.coroutines.runBlocking { accepted = service.write(EndlessWriter()) }

        assertThat(accepted).isFalse()
        scope.cancel()
    }

    @Test
    fun activeTaskIsReleasedWhenThePeerStopsReadingAndDisconnects() {
        val port = freePort()
        val scope = scope()
        val serverRecorder = Recorder()
        val clientRecorder = Recorder()
        val server = SocketService().apply { addListener(serverRecorder) }
        val client = SocketService().apply { addListener(clientRecorder) }

        scope.launch { server.open(scope, port) }
        Thread.sleep(300)
        scope.launch { client.open(scope, port, HOST) }
        assertThat(clientRecorder.awaitConnected()).isTrue()

        // The peer is connected but never reads, so the send can never finish.
        val active = EndlessWriter()
        kotlinx.coroutines.runBlocking { assertThat(client.write(active)).isTrue() }
        Thread.sleep(500)
        // Still blocked in the send at this point, so nothing has released it yet.
        assertThat(active.cancelCause).isEmpty()

        client.close()

        // The task that was mid-send must be released, not left holding its stream.
        assertThat(active.cancelled.await(15, TimeUnit.SECONDS)).isTrue()

        server.close()
        scope.cancel()
    }

    private companion object {
        const val HOST = "127.0.0.1"
    }
}
