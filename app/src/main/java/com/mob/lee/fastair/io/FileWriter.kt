package com.mob.lee.fastair.io

import com.mob.lee.fastair.io.state.*
import com.mob.lee.fastair.model.Record
import java.io.FileInputStream

class FileWriter(val record: Record, val listener: ProgressListener? = null) : Writer {
    var state = 0
    var alreadyFinished = 0L
    var tempLength = 0
    var startTime: Long = 0

    //TODO Need a policy to determine what size it should be
    val size = 1024 * 9

    val bytes: ByteArray by lazy {
        ByteArray(size)
    }

    private val streamHolder = lazy { FileInputStream(record.path) }

    val stream: FileInputStream by streamHolder


    override fun hasNext(): Boolean {
        return state != -1
    }

    override fun next(): ProtocolByte =
        when (state) {
            0 -> {
                state = 1
                ProtocolByte.empty()
            }

            1 -> {
                state = 2
                ProtocolByte.long(record.size)
            }

            2 -> {
                state = 3
                startTime = System.currentTimeMillis()
                record.state = Record.STATE_TRANSPORT
                listener?.invoke(StartRecordState(record))
                ProtocolByte.string(record.name)
            }

            else -> {
                try {
                    tempLength = stream.read(bytes)

                    if (-1 == tempLength || 0L == record.size) {
                        record.state = Record.STATE_SUCCESS
                        finished(SuccessState(record, speed = speed(record.size,System.currentTimeMillis()-startTime)))
                    } else {
                        alreadyFinished += tempLength
                        record.duration = System.currentTimeMillis() - startTime
                        listener?.invoke(TransmitState(alreadyFinished, record.duration,record))
                        ProtocolByte.bytes(bytes.sliceArray(0 until tempLength))
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    record.state = Record.STATE_FAILED
                    listener?.invoke(FailedState(record, e))
                    finished(FailedState(record))
                }
            }
        }

    fun finished(s: State): ProtocolByte {
        state = -1
        try {
            listener?.invoke(s)
            stream.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return ProtocolByte.empty()
    }

    /**
     * 传输被中断：关闭文件句柄并把记录标成失败，避免它一直停在
     * [Record.STATE_TRANSPORT]——那样既不会被重试，也不会显示为失败。
     */
    override fun cancel(cause: Exception) {
        if (state == -1) {
            return
        }
        state = -1
        record.state = Record.STATE_FAILED
        try {
            listener?.invoke(FailedState(record, cause))
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            // Opening the file only to close it would leak a descriptor for nothing.
            if (streamHolder.isInitialized()) {
                stream.close()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}