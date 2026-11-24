package com.mob.lee.fastair.io

import android.content.Context
import com.mob.lee.fastair.io.state.FailedState
import com.mob.lee.fastair.io.state.StartRecordState
import com.mob.lee.fastair.io.state.SuccessState
import com.mob.lee.fastair.io.state.speed
import com.mob.lee.fastair.io.state.TransmitState
import com.mob.lee.fastair.model.Record
import com.mob.lee.fastair.utils.createFile
import kotlinx.coroutines.withContext
import com.zevarc.zevhub.transfer.TransferDestination
import java.io.BufferedOutputStream
import java.io.FileOutputStream

/**
 * Writes one incoming file to the download directory.
 *
 * Replaces the old `FileReader`, which had to interpret four different wire
 * frame types (empty / long / string / bytes) to reconstruct a file. With
 * ZevHub the size arrives as `totalBytes` and the name as metadata, so this
 * only has to stream bytes.
 */
internal class RecordDestination(
    private val context: Context,
    private val metadataData: ByteArray,
    private val totalBytes: Long?,
    private val listener: ProgressListener? = null
) : TransferDestination {

    private var stream: BufferedOutputStream? = null
    private var received = 0L
    private var startTime = 0L
    private var record: Record? = null
    private var released = false

    private companion object {
        /** One flush per chunk is plenty; this holds a whole chunk. */
        const val BUFFER_BYTES = 256 * 1024
    }

    override suspend fun write(data: ByteArray) {
        // Runs on the session's dispatcher, which is Dispatchers.Main here.
        // Writing a file there freezes the UI and stalls every other feature
        // sharing the session.
        withContext(FileIo) { writeChunk(data) }
    }

    private fun writeChunk(data: ByteArray) {
        val out = stream ?: openStream() ?: return
        out.write(data)
        received += data.size
        record?.let { to ->
            to.duration = System.currentTimeMillis() - startTime
            listener?.invoke(TransmitState(received, to.duration, to))
        }
    }

    override suspend fun close() {
        if (released) {
            return
        }
        released = true
        withContext(FileIo) {
            // close() flushes the buffer; runCatching keeps a broken disk from
            // hiding the transfer result.
            runCatching { stream?.close() }
        }
        val to = record
        if (to == null) {
            return
        }
        // The Core already verified the delivered size against totalBytes, so
        // reaching here means the payload is complete.
        to.state = Record.STATE_SUCCESS
        to.duration = System.currentTimeMillis() - startTime
        listener?.invoke(SuccessState(to, fromRemote = true, speed = speed(to.size, to.duration)))
    }

    /**
     * Creates the destination on the first chunk: the name is known up front,
     * but an empty file still needs a path even though no chunk ever arrives.
     */
    private fun openStream(): BufferedOutputStream? {
        val name = String(metadataData, Charsets.UTF_8)
        startTime = System.currentTimeMillis()
        return try {
            val file = context.createFile(name)
            record = Record(
                id = 0L,
                size = totalBytes ?: 0L,
                date = System.currentTimeMillis(),
                path = file.absolutePath,
                state = Record.STATE_TRANSPORT,
                duration = 0L
            )
            // Buffered: every write otherwise becomes its own syscall straight
            // to the page cache, and a 256 KiB chunk per call showed up as the
            // dominant cost of receiving a file.
            val opened = BufferedOutputStream(file.outputStream(), BUFFER_BYTES)
            stream = opened
            listener?.invoke(StartRecordState(record!!))
            opened
        } catch (e: Exception) {
            listener?.invoke(FailedState(record, e))
            null
        }
    }
}
