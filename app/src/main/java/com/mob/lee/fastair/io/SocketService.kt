package com.mob.lee.fastair.io

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean


/**
 * Created by Andy on 2017/11/29.
 */
class SocketService {

    /**
     * 待发送任务队列。只装 [Writer] 对象，不装字节：帧是 [handleWrite] 逐帧惰性
     * 驱动的，堆内存占用不随文件大小增长。
     *
     * 容量有界，满了让 [write] 挂起，把背压传回调用方；否则一次批量排队会在
     * 对端不读时无限堆积。
     */
    private val writers = Channel<Writer>(WRITE_QUEUE_CAPACITY)

    /**
     * 指示当前连接是否已经开启
     */
    @Volatile
    var isConnected: Boolean = false
        private set

    /**
     * 当前连接所处的生命周期阶段
     * @see ConnectionState
     */
    @Volatile
    var state: ConnectionState = ConnectionState.IDLE
        private set

    /**
     * 通信通道
     */
    @Volatile
    private var channel: SocketChannel? = null

    @Volatile
    private var server: ServerSocketChannel? = null

    private val listeners = CopyOnWriteArrayList<SocketStateListener>()

    /** 是否仍应维持连接；[close] 置为false后所有循环退出 */
    @Volatile
    private var running = false

    private val closed = AtomicBoolean(false)

    /** 最近一次读到任何字节的时间，用于心跳判死 */
    @Volatile
    private var lastReadAt: Long = 0L

    private var host: String? = null
    private var extra: Any? = null

    /**
     * 建立连接，host为空作为服务器，否则作为客户端。
     *
     * 客户端在连接失败后按指数退避重试，服务器在对端断开后回到监听状态等待下一个对端，
     * 两者都不会因为一次中断而放弃。该方法在 [close] 被调用前不会返回。
     *
     * @param context 承载读写循环的作用域
     * @param port 端口号
     * @param host 主机地址，当该参数为null时，则开启一个socket监听
     * @param extra 连接建立后随 [SocketStateListener.onConnected] 下发的附加数据
     **/
    suspend fun open(context: CoroutineScope, port: Int, host: String? = null, extra: Any? = null) {
        if (closed.get()) {
            return
        }
        Log.i(TAG, "try to connect ${host}:${port}")
        this.host = host
        this.extra = extra
        this.running = true
        try {
            if (null != host) {
                clientLoop(context, port, host)
            } else {
                serverLoop(context, port)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connect exception: ${e.message}")
            close(e)
        }
    }

    /**
     * 客户端：连接失败或对端断开后按指数退避重试，直到 [close]。
     */
    private suspend fun clientLoop(context: CoroutineScope, port: Int, host: String) {
        var attempt = 0
        // Give the peer's group a moment to finish bringing its interface up.
        delay(INITIAL_DELAY_MS)
        while (running) {
            setState(ConnectionState.CONNECTING)
            var c: SocketChannel? = null
            try {
                c = SocketChannel.open()
                c.socket().reuseAddress = true
                c.socket().tcpNoDelay = true
                c.configureBlocking(true)
                c.connect(InetSocketAddress(host, port))
                attempt = 0
                Log.i(TAG, "Connect ${host} : ${port} success")
                onPeerReady(context, c)
            } catch (e: Exception) {
                Log.w(TAG, "Connect ${host} : ${port} failed: ${e.message}")
                closeQuietly(c)
                if (!running) {
                    break
                }
                attempt++
            }
            if (running) {
                setState(ConnectionState.RECONNECTING)
                val wait = backoffDelay(attempt)
                Log.i(TAG, "Reconnect in ${wait}ms")
                delay(wait)
            }
        }
    }

    /**
     * 服务器：常驻监听。对端断开后不关闭监听socket，而是回到accept等待下一个对端。
     */
    private suspend fun serverLoop(context: CoroutineScope, port: Int) {
        setState(ConnectionState.CONNECTING)
        try {
            val s = ServerSocketChannel.open()
            s.socket().reuseAddress = true
            s.socket().bind(InetSocketAddress(port))
            s.configureBlocking(true)
            server = s
        } catch (e: Exception) {
            Log.e(TAG, "Bind ${port} failed: ${e.message}")
            close(e)
            return
        }
        Log.i(TAG, "Start listen ${port}")
        while (running) {
            val c = try {
                server?.accept()
            } catch (e: Exception) {
                // Closing the server socket is how `close` unblocks this accept.
                Log.i(TAG, "Accept interrupted: ${e.message}")
                null
            }
            if (null == c || !running) {
                break
            }
            Log.i(TAG, "Accept connect ${port} success")
            c.socket().tcpNoDelay = true
            c.configureBlocking(true)
            onPeerReady(context, c)
            if (running) {
                setState(ConnectionState.CONNECTING)
            }
        }
    }

    /**
     * 为一个已建立的对端运行读写与心跳，直到该对端断开。
     * 返回后调用方决定是重连还是回到监听。
     */
    private suspend fun onPeerReady(context: CoroutineScope, c: SocketChannel) {
        // A close may have landed while the connect was still completing.
        if (!running) {
            closeQuietly(c)
            return
        }
        channel = c
        lastReadAt = System.currentTimeMillis()
        isConnected = true
        setState(ConnectionState.CONNECTED)
        val perConnection = Job(context.coroutineContext[Job])
        val readJob = context.launch(perConnection + Dispatchers.IO) { handleRead(c) }
        context.launch(perConnection + Dispatchers.IO) { handleWrite(c) }
        context.launch(perConnection + Dispatchers.IO) { handleHeartbeat(c) }
        listeners.forEach { it.onConnected(c, extra) }

        readJob.join()
        perConnection.cancelAndJoin()
        // Anything still queued belongs to this connection and can never be sent
        // now. Letting it survive into the next connection would put bare body
        // frames on the wire, and the peer would splice them into a file.
        discardPendingWriters()
        closeQuietly(c)
        if (channel === c) {
            channel = null
        }
        isConnected = false
    }

    /**
     * 提交一个写任务。
     *
     * 队列满时挂起等待，把写压力传回调用方，避免无限缓冲；对端长时间不消费
     * （超过 [WRITE_STALL_TIMEOUT_MS]）则放弃并返回 false。
     *
     * @return false 表示这批数据确定无法送达，调用方应自行收尾
     *         （关闭流、把记录标记为失败），不能指望重连后补发。
     */
    suspend fun write(writer: Writer): Boolean {
        if (!running || closed.get() || !isConnected) {
            return false
        }
        val accepted = withTimeoutOrNull(WRITE_STALL_TIMEOUT_MS) {
            writers.send(writer)
            true
        }
        if (true != accepted) {
            Log.w(TAG, "Write queue stayed full for ${WRITE_STALL_TIMEOUT_MS}ms, dropping task.")
            return false
        }
        return true
    }

    /**
     * 永久关闭连接，释放端口。可重复调用。
     */
    fun close(e: Exception? = null) {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        running = false
        state = ConnectionState.CLOSED
        writers.close()
        // Nobody will drain the queue any more; release what is left in it.
        discardPendingWriters()
        closeQuietly(channel)
        channel = null
        closeQuietly(server)
        server = null
        isConnected = false
        listeners.forEach { it.onStateChanged(ConnectionState.CLOSED) }
        listeners.forEach { it.onDisconnected(e) }
    }

    /**
     * 读取数据
     *
     * 1、读取固定的5个字节，里面包含4个字节的内容长度，和1个字节的类型标识
     * 2、分解类型和长度
     * 3、读取真正内容
     * 4、组装成消息对象，通知每个监听
     *
     * 心跳帧在此处消化，不会下发给监听者。
     *
     * @see ProtocolType
     * @see ProtocolByte
     */
    private suspend fun handleRead(c: SocketChannel) {
        Log.i(TAG, "Start handle read.")
        while (running && c.isOpen) {
            try {
                listeners.forEach { it.onReadStart(c) }
                val head = readFix(c, ProtocolByte.HEAD_LENGTH)
                if (head.remaining() < ProtocolByte.HEAD_LENGTH) {
                    Log.i(TAG, "Peer closed the connection.")
                    break
                }
                val type = head.get()
                val length = head.getInt()
                if (length < 0 || length > MAX_FRAME_BYTES) {
                    Log.e(TAG, "Invalid frame length $length, dropping connection.")
                    listeners.forEach {
                        it.onReadError(c, IllegalStateException("Invalid frame length $length"))
                    }
                    break
                }
                lastReadAt = System.currentTimeMillis()
                if (ProtocolType.HEARTBEAT == type) {
                    continue
                }
                val bytes = readFix(c, length)
                if (bytes.remaining() < length) {
                    Log.i(TAG, "Peer closed mid-payload.")
                    break
                }
                listeners.forEach {
                    it.onReadFinished(c, ProtocolByte.wrap(bytes.array(), type))
                }
            } catch (e: Exception) {
                if (!running || !c.isOpen) {
                    Log.i(TAG, "Read loop stopped: ${e.message}")
                    break
                }
                Log.e(TAG, "Read exception: ${e.message}")
                listeners.forEach { it.onReadError(c, e) }
                break
            }
        }
    }

    /**
     * 发送数据。帧是逐帧惰性从 [Writer] 拉取的，堆占用不随文件大小增长。
     */
    private suspend fun handleWrite(c: SocketChannel) {
        Log.i(TAG, "Start handle write.")
        while (running && c.isOpen) {
            val writer = try {
                writers.receive()
            } catch (e: Exception) {
                Log.i(TAG, "Write loop stopped: ${e.message}")
                return
            }
            // The connection may have been replaced while this task sat in the
            // queue; sending it now would corrupt the new peer's stream.
            if (!running || channel !== c) {
                Log.i(TAG, "Discarding queued writer for a stale connection.")
                notifyCancelled(c, writer, IllegalStateException("connection no longer active"))
                continue
            }
            try {
                listeners.forEach { it.onWriteStart(c, writer) }
                for (d in writer) {
                    // A peer that closed gracefully keeps accepting bytes into the
                    // socket buffer, so a blocking write would never notice the
                    // cancellation that tears this connection down.
                    currentCoroutineContext().ensureActive()
                    val bytes = d.bytes
                    while (bytes.hasRemaining()) {
                        c.write(bytes)
                    }
                }
                listeners.forEach { it.onWriteFinished(c, writer) }
            } catch (e: Exception) {
                // Also reached when the connection is being torn down: a peer that
                // closed gracefully keeps accepting into its socket buffer, so the
                // blocking write only ends when ensureActive() throws above.
                if (e !is CancellationException) {
                    Log.e(TAG, "Write exception: ${e.message}")
                }
                listeners.forEach { it.onWriteError(c, e, writer) }
                writer.cancel(e)
                // Unblock the read loop so the connection is torn down once.
                closeQuietly(c)
                return
            }
        }
    }

    /**
     * 把队列里剩余的任务逐个作废，让它们释放持有的资源并标记为失败。
     * 不清理的话，一个被中断的发送会一直停在传输中状态。
     */
    private fun discardPendingWriters() {
        while (true) {
            val writer = writers.tryReceive().getOrNull() ?: break
            notifyCancelled(channel, writer, IllegalStateException("connection lost before the task was sent"))
        }
    }

    private fun notifyCancelled(c: SocketChannel?, writer: Writer, cause: Exception) {
        val target = c
        if (null != target) {
            listeners.forEach { it.onWriteError(target, cause, writer) }
        }
        try {
            writer.cancel(cause)
        } catch (e: Exception) {
            Log.e(TAG, "Writer cancel failed: ${e.message}")
        }
    }

    /**
     * 周期性发送心跳；超过判死窗口未收到任何字节则关闭通道以触发重连。
     * 关闭通道是打断阻塞读的手段——阻塞中的read会因此抛出异常而返回。
     */
    private suspend fun handleHeartbeat(c: SocketChannel) {
        while (running && c.isOpen) {
            delay(HEARTBEAT_INTERVAL_MS)
            if (!running || !c.isOpen) {
                return
            }
            if (System.currentTimeMillis() - lastReadAt > HEARTBEAT_TIMEOUT_MS) {
                Log.w(TAG, "Heartbeat timeout, dropping connection.")
                closeQuietly(c)
                return
            }
            // Heartbeats must never take the slot a real task is waiting for, so
            // they stay out of the queued path entirely: on a full queue this
            // round is simply skipped and the next one tries again.
            writers.trySend(HeartbeatWriter())
        }
    }

    /**
     * 读取特定长度的字节，可能会阻塞
     */
    private fun readFix(channel: SocketChannel, targetSize: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(targetSize)
        while (buffer.hasRemaining() && running) {
            // A positive count means data was read; the channel is blocking so a
            // call parks until data arrives. -1 (EOF) means the peer closed.
            if (-1 == channel.read(buffer)) {
                break
            }
        }
        buffer.flip()
        return buffer
    }

    /**
     * 指数退避：500ms起，每次翻倍，封顶[MAX_BACKOFF_MS]
     */
    private fun backoffDelay(attempt: Int): Long = backoffDelayOf(attempt)

    private fun setState(next: ConnectionState) {
        if (state == next) {
            return
        }
        state = next
        Log.i(TAG, "Connection state -> $next")
        listeners.forEach { it.onStateChanged(next) }
    }

    private fun closeQuietly(c: Closeable?) {
        try {
            c?.close()
        } catch (e: Exception) {
            // Already closed, or the peer vanished first.
        }
    }

    fun addListener(listener: SocketStateListener): SocketStateListener {
        listeners.add(listener)
        return listener
    }

    fun removeListener(listener: SocketStateListener?) {
        listeners.remove(listener)
    }

    fun isGroupOwner() = null != server

    companion object {
        const val TAG = "SocketService"

        /** 心跳发送间隔 */
        const val HEARTBEAT_INTERVAL_MS = 3_000L

        /** 判死窗口：超过该时长未收到任何字节即认为对端已消失 */
        const val HEARTBEAT_TIMEOUT_MS = 10_000L

        /** 首次连接前的等待，留给对端网络接口就绪 */
        const val INITIAL_DELAY_MS = 500L

        const val BASE_BACKOFF_MS = 500L

        const val MAX_BACKOFF_MS = 10_000L

        /** 单个协议帧的上限，防止错误长度导致巨额分配 */
        const val MAX_FRAME_BYTES = 8 * 1024 * 1024

        /**
         * 待发送任务队列容量。满后 [write] 挂起，把背压传回调用方。
         * 队列只装 Writer 对象，不装字节，所以这个数字很小就够。
         */
        const val WRITE_QUEUE_CAPACITY = 32

        /** 队列持续满这么久就放弃该任务，避免调用方永久挂起 */
        const val WRITE_STALL_TIMEOUT_MS = HEARTBEAT_TIMEOUT_MS

        /**
         * 第[attempt]次重试前应等待的毫秒数。从[BASE_BACKOFF_MS]起每次翻倍，
         * 封顶[MAX_BACKOFF_MS]。
         */
        @JvmStatic
        fun backoffDelayOf(attempt: Int): Long {
            val shift = (attempt - 1).coerceIn(0, MAX_BACKOFF_SHIFT)
            return (BASE_BACKOFF_MS shl shift).coerceAtMost(MAX_BACKOFF_MS)
        }

        private const val MAX_BACKOFF_SHIFT = 5
    }

    /** 心跳帧：一个类型为[ProtocolType.HEARTBEAT]、内容长度为0的空帧 */
    private class HeartbeatWriter : Writer {
        private var remaining = true

        override fun hasNext() = remaining

        override fun next(): ProtocolByte =
            ProtocolByte.wrap(EMPTY, ProtocolType.HEARTBEAT).also { remaining = false }

        companion object {
            private val EMPTY = ByteArray(0)
        }
    }

    interface SocketStateListener {
        fun onConnected(channel: SocketChannel, extra: Any?)

        /**
         * 连接被永久关闭时回调，瞬时断开不会触发。
         * 瞬时断开请使用[onStateChanged]。
         */
        fun onDisconnected(exception: Exception?)

        /**
         * 连接状态变化，包括自动重连过程中的[ConnectionState.RECONNECTING]。
         */
        fun onStateChanged(state: ConnectionState) {}

        fun onReadStart(channel: SocketChannel)
        fun onReadFinished(channel: SocketChannel, data: ProtocolByte)
        fun onReadError(channel: SocketChannel, exception: Exception)

        fun onWriteStart(channel: SocketChannel, writer: Writer)
        fun onWriteFinished(channel: SocketChannel, writer: Writer)

        fun onWriteError(channel: SocketChannel, exception: Exception, writer: Writer?)
    }
}
