package com.mob.lee.fastair.service

import com.mob.lee.fastair.io.ProgressListener
import com.mob.lee.fastair.io.state.MessageState
import com.zevarc.zevhub.message.Message
import com.zevarc.zevhub.message.MessageType
import com.zevarc.zevhub.session.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Chat over the shared session.
 *
 * The old version needed its own socket on port 9528 because a frame had no way
 * to say which feature it belonged to. ZevHub multiplexes by context, so chat
 * rides the same connection as file transfer.
 */
class MessageService : TransferService() {

    var mMessageListener: ProgressListener? = null

    private var session: Session? = null

    fun write(content: String) {
        val current = session
        if (current == null) {
            return
        }
        launch(Dispatchers.IO) {
            runCatching {
                current.send(Message(MessageType(TYPE), content.toByteArray()))
            }
        }
    }

    override suspend fun onSessionReady(session: Session) {
        this.session = session
        launch(Dispatchers.IO) {
            runCatching {
                session.messages().collect { message ->
                    if (message.type.value != TYPE) {
                        // Other features share this flow.
                        return@collect
                    }
                    launch(Dispatchers.Main) {
                        mMessageListener?.invoke(
                            MessageState(
                                String(message.data, Charsets.UTF_8),
                                System.currentTimeMillis()
                            )
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        session = null
        super.onDestroy()
    }

    companion object {
        /** Message type that carries chat text, so other kinds can share the flow. */
        const val TYPE = "fastair/chat"
    }
}
