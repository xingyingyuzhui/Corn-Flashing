package dev.corn.flashing.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A result-page-owned watch. Re-entering a result never renews its original time budget. */
internal class NextBoardWatcher(
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val timeoutMillis: Long = 180_000,
    private val pollMillis: Long = 2_000,
) {
    private var watchedJobId: String? = null
    private var deadline = 0L
    private var finished = false
    private var job: Job? = null

    fun start(
        jobId: String,
        previousUid: String?,
        canContinue: () -> Boolean,
        probeUid: suspend () -> String?,
        onNewBoard: (String) -> Unit,
        onTimeout: () -> Unit,
    ) {
        // An unknown previous board cannot safely be distinguished from a replacement.
        if (previousUid == null) { stop(); return }
        if (watchedJobId != jobId) {
            stop()
            watchedJobId = jobId
            deadline = nowMillis() + timeoutMillis
            finished = false
        }
        if (job?.isActive == true || finished) return
        val remaining = deadline - nowMillis()
        if (remaining <= 0) {
            finished = true
            return // Returning to an expired result must not restart polling or show a stale alert.
        }
        job = scope.launch {
            val result = withTimeoutOrNull(remaining) {
                poll(previousUid, canContinue, probeUid)
            }
            currentCoroutineContext().ensureActive()
            if (!canContinue()) return@launch
            when (result) {
                null -> { finished = true; onTimeout() }
                is Outcome.NewBoard -> { finished = true; onNewBoard(result.uid) }
                Outcome.Stopped -> Unit
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun poll(
        previousUid: String,
        canContinue: () -> Boolean,
        probeUid: suspend () -> String?,
    ): Outcome {
        while (true) {
            delay(pollMillis)
            if (!canContinue()) return Outcome.Stopped
            val uid = try {
                probeUid()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null // A temporarily disconnected board is expected while swapping it.
            }
            // USB I/O may finish after cancellation. It must never start a job after leaving the page.
            currentCoroutineContext().ensureActive()
            if (!canContinue()) return Outcome.Stopped
            if (uid != null && uid != previousUid) return Outcome.NewBoard(uid)
        }
    }

    private sealed interface Outcome {
        data class NewBoard(val uid: String) : Outcome
        data object Stopped : Outcome
    }
}
