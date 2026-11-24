package com.mob.lee.fastair.fragment

import android.view.View
import android.widget.Button
import android.widget.TextView
import com.mob.lee.fastair.R
import com.mob.lee.fastair.bench.TransferBench
import com.mob.lee.fastair.viewmodel.AppViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A bench screen: run the same synthetic transfer several times and print the
 * numbers, so a device can confirm a throughput change in one sitting instead of
 * being asked to eyeball a progress bar.
 */
class BenchFragment : ConnectFragment() {

    override val layout: Int = R.layout.fragment_bench

    private val viewModel by lazy { viewModel<BenchViewModel>() }

    override fun setting() {
        title(R.string.bench_title)

        val output = view<TextView>(R.id.tv_bench_output)
        val run = view<Button>(R.id.btn_bench_run)

        run?.setOnClickListener {
            run.isEnabled = false
            output?.text = ""
            viewLifecycleOwner.lifecycleScope.launch {
                val report = viewModel.runSuite { line ->
                    withContext(Dispatchers.Main) {
                        output?.append(line + "\n")
                    }
                }
                withContext(Dispatchers.Main) {
                    output?.append("\n--- DONE ---\n")
                    run.isEnabled = true
                    report.takeIf { it.isNotEmpty() }?.let { output?.append(it) }
                }
            }
        }
    }
}

/**
 * Runs the benchmark sequence against whatever session is live.
 *
 * Kept out of the fragment so the sequence, and the report text it produces,
 * can be read and changed without touching the view layer.
 */
class BenchViewModel : AppViewModel() {

    /** Bytes per single transfer. 8 MiB is long enough to be stable, short enough to repeat. */
    private val sampleBytes = 8L * 1024 * 1024

    /** Several chunk sizes, because the best one depends on the link. */
    private val chunkSizes = listOf(64 * 1024, 256 * 1024, 1024 * 1024)

    private val rounds = 2

    suspend fun runSuite(log: suspend (String) -> Unit): String = withContext(Dispatchers.IO) {
        val session = com.mob.lee.fastair.base.Connection.session.value
        if (session == null) {
            log("No session. Connect to a peer first.")
            return@withContext ""
        }

        val lines = mutableListOf<String>()
        lines += "peer=${session.device.id.value}"
        lines += "sample=${sampleBytes / 1024 / 1024} MiB x $rounds rounds"
        lines += ""
        lines += String.format("%-8s %-8s %-8s %-8s", "chunk", "run", "ms", "MB/s")

        for (chunk in chunkSizes) {
            for (round in 1..rounds) {
                log("running chunk=${chunk / 1024}K round=$round ...")
                val report = TransferBench.run(
                    scope = benchScope,
                    session = session,
                    bytes = sampleBytes,
                    chunk = chunk
                )
                val line = String.format(
                    "%-8s %-8d %-8d %-8.2f%s",
                    "${chunk / 1024}K",
                    round,
                    report.millis,
                    report.mbPerSecond,
                    if (report.complete) "" else "  (incomplete ${report.receivedBytes})"
                )
                lines += line
                log(line)
            }
        }

        lines += ""
        lines += "note: loopback-free measurement over the real Wi-Fi Direct link."
        lines.joinToString("\n")
    }

    /** A scope that outlives the io block but dies with the view model. */
    /**
     * Runs the bench off the UI thread. viewModelScope alone is
     * Dispatchers.Main.immediate, which would put transfer progress collection
     * and the timing loop on the thread that draws the screen.
     */
    private val benchScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
    )
}
