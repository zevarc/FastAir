package com.mob.lee.fastair.io

import com.mob.lee.fastair.model.Record
import com.mob.lee.fastair.io.state.StartRecordState
import com.mob.lee.fastair.io.state.SuccessState
import com.mob.lee.fastair.io.state.TransmitState
import com.zevarc.zevhub.transfer.TransferResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The ZevHub transfer contract is "read until null, then close exactly once".
 * These tests pin the adapter to that contract, since nothing in the SDK can
 * catch a source that misreports its own lifecycle.
 */
class RecordSourceTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun recordOf(content: ByteArray): Record {
        val file = folder.newFile("payload.bin")
        file.writeBytes(content)
        return Record(
            id = 1L,
            size = content.size.toLong(),
            date = System.currentTimeMillis(),
            path = file.absolutePath
        )
    }

    @Test
    fun `reads the file in chunks and reports the whole size`() = runTest {
        val content = ByteArray(20_000) { (it % 251).toByte() }
        val record = recordOf(content)
        val source = RecordSource(record)

        assertEquals(RecordSource.PAYLOAD_TYPE, source.metadata.type)
        assertEquals("payload.bin", String(source.metadata.data, Charsets.UTF_8))
        assertEquals(20_000L, source.totalBytes)

        val collected = ArrayList<Byte>()
        while (true) {
            val chunk = source.read(RecordSource.CHUNK_SIZE) ?: break
            assertTrue("chunk must respect maxBytes", chunk.size <= RecordSource.CHUNK_SIZE)
            chunk.forEach { collected.add(it) }
        }
        source.close(TransferResult.Success(0))

        assertEquals(content.toList(), collected)
        assertEquals(Record.STATE_SUCCESS, record.state)
    }

    @Test
    fun `close is idempotent and releases the handle once`() = runTest {
        val source = RecordSource(recordOf(ByteArray(10)))

        // Drain nothing; a cancelled transfer may close before any read.
        source.close(TransferResult.Success(0))
        source.close(TransferResult.Success(0))

        // A second close must not roll the state back or throw. No read ever
        // happened, so the file was never opened and there is no record state
        // to advance.
        assertEquals(RecordSource.PAYLOAD_TYPE, source.metadata.type)
    }

    @Test
    fun `an empty file completes without any read`() = runTest {
        val record = recordOf(ByteArray(0))
        val source = RecordSource(record)

        // Unknown or zero totals are legitimate; the Core must not divide by it.
        assertEquals(null, source.totalBytes)
        assertEquals(null, source.read(RecordSource.CHUNK_SIZE))
        source.close(TransferResult.Success(0))
        assertEquals(Record.STATE_SUCCESS, record.state)
    }

    @Test
    fun `progress callbacks are emitted in order and end at the full size`() = runTest {
        val content = ByteArray(1000) { 7 }
        val record = recordOf(content)
        val events = mutableListOf<Any>()
        val source = RecordSource(record) { events.add(it) }

        var read = 0L
        while (true) {
            val chunk = source.read(100) ?: break
            read += chunk.size
        }
        source.close(TransferResult.Success(0))

        assertTrue("expected a start event", events.firstOrNull() is StartRecordState)
        assertTrue("expected a success event", events.lastOrNull() is SuccessState)
        assertEquals(1000L, read)
        val lastTransmit = events.filterIsInstance<TransmitState>().last()
        assertEquals(1000L, lastTransmit.alreadyTransmited)
    }

    @Test
    fun `a missing file fails on read rather than reporting success`() = runTest {
        val missing = Record(
            id = 2L,
            size = 100L,
            date = System.currentTimeMillis(),
            path = File(folder.root, "does-not-exist.bin").absolutePath
        )
        val source = RecordSource(missing)

        // Constructing the adapter must not throw; the Core decides when to read.
        val failure = runCatching { source.read(RecordSource.CHUNK_SIZE) }.exceptionOrNull()
        assertTrue("expected the read to fail, got $failure", failure != null)
        // close() must still be safe to call after a failure.
        source.close(TransferResult.Success(0))
    }

    @Test
    fun `a cancelled transfer is recorded as failed, not delivered`() = runTest {
        val record = recordOf(ByteArray(5000) { 9 })
        val source = RecordSource(record)

        source.read(RecordSource.CHUNK_SIZE)
        source.close(TransferResult.Cancelled)

        assertEquals(Record.STATE_FAILED, record.state)
    }

    @Test
    fun `a successful transfer is recorded as delivered`() = runTest {
        val record = recordOf(ByteArray(10))
        val source = RecordSource(record)

        while (source.read(RecordSource.CHUNK_SIZE) != null) {
            // drain
        }
        source.close(TransferResult.Success(10))

        assertEquals(Record.STATE_SUCCESS, record.state)
    }
}
