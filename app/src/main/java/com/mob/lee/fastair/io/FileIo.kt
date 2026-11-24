package com.mob.lee.fastair.io

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * A dispatcher for file reads and writes, separate from `Dispatchers.IO`.
 *
 * The Core already runs every source and destination on its own IO pool. Calling
 * withContext(Dispatchers.IO) from inside an adapter asks that same pool for a
 * second thread while holding the first, and with a transfer in flight the pool
 * also holds the session pump, the socket loops and the receiving consumer. Under
 * a large transfer that pool runs dry and everything stops — which is exactly how
 * the benchmark used to hang on its first round.
 *
 * Two threads is deliberate: one read and one write at a time, matching how a
 * transfer actually uses them.
 */
internal val FileIo = Executors
    .newFixedThreadPool(2) { r ->
        Thread(r, "fastair-file-io").apply { isDaemon = true }
    }
    .asCoroutineDispatcher()
