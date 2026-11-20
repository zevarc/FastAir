package com.mob.lee.fastair.io

import android.content.Context
import com.mob.lee.fastair.io.state.*
import com.mob.lee.fastair.model.Record
import com.mob.lee.fastair.utils.createFile
import java.io.FileOutputStream

class FileReader(val context: Context, var listener: ProgressListener? = null) : Reader {
    private var alreadyFinished = 0L
    private var startTime = 0L
    private var stream: FileOutputStream? = null
    private var record: Record? = null

    override fun invoke(data: ProtocolByte) {
        when (data.getType()) {
            ProtocolType.E -> receiveOver()

            ProtocolType.L -> {
                record = Record(System.currentTimeMillis(), data.getLong(), System.currentTimeMillis(), "")
                alreadyFinished = 0
                startTime = System.currentTimeMillis()
                if (0L == record?.size) {
                    // Empty file: no body payload follows, finish as soon as a name arrives.
                    // Keep `record` around so the W packet can still set a valid path.
                }
            }

            ProtocolType.W -> {
                val name = data.getString()
                try {
                    val f = context.createFile(name)
                    stream = f.outputStream()
                    record?.apply {
                        path = f.absolutePath
                        state = Record.STATE_TRANSPORT
                        duration = System.currentTimeMillis() - startTime
                    }
                    listener?.invoke(StartRecordState(record!!))
                } catch (e: Exception) {
                    e.printStackTrace()
                    listener?.invoke(FailedState(record, e))
                }
            }

            else -> {
                val buffer = data.bytes()
                // `bytes()` is a view sliced at the 5-byte protocol header.
                // The underlying array still holds the header first, so writes
                // must start at buffer.arrayOffset() + buffer.position().
                stream?.write(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining())
                alreadyFinished += data.getContentLength()
                record?.apply {
                    duration = System.currentTimeMillis() - startTime
                }
                listener?.invoke(TransmitState(alreadyFinished, System.currentTimeMillis()-startTime,record!!))
            }
        }
    }

    private fun receiveOver() {
        try {
            record?.let { r ->
                stream?.close()

                // Verify the received bytes match the size the sender announced.
                val complete = r.size == alreadyFinished
                if (!complete && r.size != 0L) {
                    r.state = Record.STATE_FAILED
                    listener?.invoke(FailedState(r, IllegalStateException(
                        "Incomplete transfer: expected ${r.size} bytes, got $alreadyFinished")))
                    return
                }

                r.duration = System.currentTimeMillis() - startTime
                r.state = Record.STATE_SUCCESS
                listener?.invoke(SuccessState(r, true, speed(r.size, r.duration)))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}