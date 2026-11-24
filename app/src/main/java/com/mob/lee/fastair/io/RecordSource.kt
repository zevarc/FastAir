package com.mob.lee.fastair.io

import com.mob.lee.fastair.model.Record
import com.mob.lee.fastair.io.state.FailedState
import com.mob.lee.fastair.io.state.StartRecordState
import com.mob.lee.fastair.io.state.SuccessState
import com.mob.lee.fastair.io.state.speed
import com.mob.lee.fastair.io.state.TransmitState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.zevarc.zevhub.transfer.TransferMetadata
import com.zevarc.zevhub.transfer.TransferResult
import com.zevarc.zevhub.transfer.TransferSource
import java.io.File
import java.io.FileInputStream

/**
 * Sends one [Record] through the ZevHub transfer layer.
 *
 * Replaces the old `FileWriter`, which encoded the file protocol inline
 * (empty / long / string / bytes frames) and therefore could only ever speak
 * FastAir's own framing. Here the file is just a byte stream: the protocol
 * carries it, and the metadata tells the peer what it is.
 */
internal class RecordSource(
    private val record: Record,
    private val listener: ProgressListener? = null
) : TransferSource {

    /**
     * The receiver needs the name before it can pick a destination file, so it
     * travels in the metadata rather than as a dedicated wire frame.
     */
    override val metadata = TransferMetadata(
        type = PAYLOAD_TYPE,
        data = record.name.toByteArray(Charsets.UTF_8)
    )

    override val totalBytes: Long? = record.size.takeIf { it > 0 }

    private val stream by lazy { FileInputStream(record.path) }
    private var streamOpened = false
    private var sent = 0L
    private var startTime = 0L
    private var announced = false
    private var released = false

    override suspend fun read(maxBytes: Int): ByteArray? {
        // The Core calls this on whatever dispatcher the session was built with,
        // which for this app is Dispatchers.Main. File IO has to be moved off it
        // explicitly or every read blocks the UI.
        return withContext(Dispatchers.IO) { readChunk(maxBytes) }
    }

    private fun readChunk(maxBytes: Int): ByteArray? {
        if (!announced) {
            announced = true
            startTime = System.currentTimeMillis()
            record.state = Record.STATE_TRANSPORT
            listener?.invoke(StartRecordState(record))
        }

        val buffer = ByteArray(maxBytes.coerceAtMost(MAX_READ))
        streamOpened = true
        val count = stream.read(buffer)
        if (count <= 0) {
            // -1 means EOF; 0 would mean a zero-length buffer, which cannot
            // happen here. Either way the data is done.
            finish()
            return null
        }

        sent += count
        record.duration = System.currentTimeMillis() - startTime
        listener?.invoke(TransmitState(sent, record.duration, record))
        return if (count == buffer.size) buffer else buffer.copyOf(count)
    }

    /**
     * Called by the Core once, with the outcome it reached. The result decides
     * how the record is recorded: a cancelled file that is marked success stays
     * "delivered" in the database forever, which is worse than a retry.
     */
    override suspend fun close(result: TransferResult) {
        if (released) {
            return
        }
        released = true
        // The stream is opened lazily: a transfer cancelled before the first
        // read never touched the file, so closing must not open it now.
        if (streamOpened) {
            runCatching { stream.close() }
        }
        when (result) {
            is TransferResult.Success -> {
                record.state = Record.STATE_SUCCESS
                listener?.invoke(
                    SuccessState(record, fromRemote = false, speed = speed(sent, record.duration))
                )
            }

            TransferResult.Cancelled -> {
                record.state = Record.STATE_FAILED
                listener?.invoke(FailedState(record, IllegalStateException("cancelled")))
            }
        }
    }

    private fun finish() {
        record.duration = System.currentTimeMillis() - startTime
    }

    companion object {
        /** Marks the payload as a FastAir file; the data is the file name. */
        const val PAYLOAD_TYPE = "fastair/file"

        /**
         * Upper bound on one read. The Core asks for 256 KiB; honouring it keeps
         * the frame count (and therefore the per-frame cost on both ends) low.
         * The old 9 KiB was chosen when every frame paid a flush and a progress
         * update; that cost is gone, so this is only a safety cap.
         */
        const val MAX_READ = 256 * 1024
    }
}

/** Convenience for callers that only have a path. */
internal fun Record.isTransferable(): Boolean = size > 0 && File(path).canRead()
