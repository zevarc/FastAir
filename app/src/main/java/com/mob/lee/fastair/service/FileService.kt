package com.mob.lee.fastair.service

import android.util.Log
import com.mob.lee.fastair.R
import com.mob.lee.fastair.io.ProgressListener
import com.mob.lee.fastair.io.RecordDestination
import com.mob.lee.fastair.io.RecordSource
import com.mob.lee.fastair.io.state.FailedState
import com.mob.lee.fastair.io.state.StartRecordState
import com.mob.lee.fastair.io.state.SuccessState
import com.mob.lee.fastair.io.state.TransmitState
import com.mob.lee.fastair.io.state.State
import com.mob.lee.fastair.model.DataWrap
import com.mob.lee.fastair.model.Record
import com.mob.lee.fastair.repository.DataBaseDataSource
import com.mob.lee.fastair.service.Notification.Companion.FILE_TRANSFER
import com.mob.lee.fastair.service.Notification.Companion.FILE_TRANSFER_CODE
import com.mob.lee.fastair.service.Notification.Companion.easyNotify
import com.mob.lee.fastair.utils.updateStorage
import com.zevarc.zevhub.session.Session
import com.zevarc.zevhub.transfer.TransferResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Queued file transfer over the shared session.
 *
 * The queue, the progress throttling and the database bookkeeping are business
 * concerns and stay here; the wire protocol now belongs entirely to ZevHub.
 * Files are sent one at a time on purpose: the P2P radio has one pipe, so
 * concurrent transfers would only make each other slower and the progress UI
 * unreadable.
 */
class FileService : TransferService(), ProgressListener {

    override val foregroundChannel = ForegroundChannel(
        FILE_TRANSFER,
        FILE_TRANSFER_CODE,
        nameOf = { getString(R.string.transfer_service) }
    )

    var mListener: ProgressListener? = null

    private var lastId = 0L
    private var mLastDate = 0L
    private var lastTransmitTime = 0L
    private var lastTransmitBytes = 0L

    private val database by lazy { DataBaseDataSource() }

    /**
     * The session this feature is currently working against. A reconnect brings
     * a new one, and work started for the old one must stop rather than race the
     * new queue drain.
     */
    @Volatile
    private var active: Session? = null

    override suspend fun onSessionReady(session: Session) {
        active = session
        launch { receiveLoop(session) }
        launch { sendNext(session) }
    }

    override fun onDestroy() {
        active = null
        super.onDestroy()
    }

    /** Accepts every file the peer offers, one at a time. */
    private suspend fun receiveLoop(session: Session) {
        runCatching {
            session.transfers().collect { offer ->
                if (active !== session) {
                    return@collect
                }
                if (offer.metadata.type != RecordSource.PAYLOAD_TYPE) {
                    offer.reject()
                    return@collect
                }
                val incoming = offer.accept(
                    RecordDestination(this, offer.metadata.data, offer.totalBytes, this)
                )
                launch(Dispatchers.IO) {
                    runCatching { incoming.await() }
                        .onFailure { Log.e(TAG, "receive failed", it) }
                }
            }
        }.onFailure { Log.i(TAG, "receive loop ended: ${it.message}") }
    }

    /**
     * Sends the next queued record. Called on session start and after every
     * finished transfer, so the queue drains by itself.
     */
    private suspend fun sendNext(session: Session) {
        if (active !== session) {
            return
        }
        val records = database.recordDao(this@FileService) { DataWrap.success(waitRecords()) }
        if (records.isSuccess() && records.data.isNullOrEmpty()) {
            return
        }
        records.data?.firstOrNull { lastId != it.id }?.let { record ->
            Log.i(TAG, "Write File $record")
            lastId = record.id
            val transfer = session.transfer(RecordSource(record, this))
            runCatching { transfer.await() }
                .onFailure { Log.e(TAG, "send failed", it) }
            // Either way the queue moves on; a failure is recorded by the
            // source itself and must not stall the remaining files.
            if (active === session) {
                launch { sendNext(session) }
            }
        }
    }

    fun onNewFile(state: StartRecordState) {
        easyNotify(FILE_TRANSFER, FILE_TRANSFER_CODE) {
            setContentTitle(getString(R.string.file_transfer))
            setContentText(state.record.name)
        }
        mLastDate = System.currentTimeMillis()
        // A new file restarts the byte count, so the previous sample must be
        // dropped or the first instantaneous speed comes out negative.
        lastTransmitTime = 0L
        lastTransmitBytes = 0L
        Log.i(TAG, "New File start state = ${state.record}")

        launch(Dispatchers.Main) {
            mListener?.invoke(state)
        }
    }

    fun onProgressFile(state: TransmitState) {
        if (System.currentTimeMillis() - mLastDate > 1500) {
            easyNotify(FILE_TRANSFER, FILE_TRANSFER_CODE) {
                setProgress(100, (state.percentage() * 100).toInt(), false)
                setContentTitle(getString(R.string.file_transfer))
                setContentText(state.record.name)
            }
            mLastDate = System.currentTimeMillis()
        }
        // Throttle UI progress callbacks so a large transfer (a notify per 9 KB chunk)
        // does not flood the RecyclerView on the main thread and cause ANRs.
        val now = System.currentTimeMillis()
        val elapsed = now - lastTransmitTime
        if (elapsed >= PROGRESS_THROTTLE_MS) {
            val speedBytesPerSec = if (elapsed > 0L && lastTransmitTime > 0L) {
                (state.alreadyTransmited - lastTransmitBytes) * 1000L / elapsed
            } else {
                0L
            }
            lastTransmitTime = now
            lastTransmitBytes = state.alreadyTransmited
            val throttled = TransmitState(
                state.alreadyTransmited, state.duration, state.record, speedBytesPerSec
            )
            launch(Dispatchers.Main) {
                mListener?.invoke(throttled)
            }
        }
    }

    fun onFinishedFile(state: SuccessState) {
        easyNotify(FILE_TRANSFER, FILE_TRANSFER_CODE) {
            setContentTitle(getString(R.string.transfer_success))
            setContentText(state.record.name)
        }
        if (state.fromRemote) {
            Log.i(TAG, "Success receive file state = ${state.record}")
            updateStorage(state.record.path)
        } else {
            Log.i(TAG, "Success send file state = ${state.record}")
        }
        launch(Dispatchers.Main) {
            mListener?.invoke(state)
        }
        onUpdateRecord(state.fromRemote, state.record)
    }

    fun onFailedFile(state: FailedState) {
        val record = state.record
        easyNotify(FILE_TRANSFER, FILE_TRANSFER_CODE) {
            setContentTitle(getString(R.string.transfer_failed))
            setContentText(state.record.name)
        }
        Log.e(
            TAG,
            "Failed ${if (state.record.valid()) "send" else "receive"}" +
                " file state = ${state.record}, exception = ${state.exception}"
        )
        if (0L != record.date && 0L != record.size) {
            onUpdateRecord(false, record)
        }
        launch(Dispatchers.Main) {
            mListener?.invoke(state)
        }
    }

    fun onUpdateRecord(fromRemote: Boolean, record: Record) {
        launch(Dispatchers.IO) {
            database.recordDao(this@FileService) {
                if (fromRemote) {
                    insert(record)
                } else {
                    update(record)
                }
                DataWrap.success(null)
            }
        }
    }

    override fun invoke(state: State) {
        when (state) {
            is StartRecordState -> onNewFile(state)
            is TransmitState -> onProgressFile(state)
            is SuccessState -> onFinishedFile(state)
            else -> onFailedFile(state as FailedState)
        }
    }

    companion object {
        /** Minimum interval between UI progress callbacks, in milliseconds. */
        const val PROGRESS_THROTTLE_MS = 300L
    }
}
