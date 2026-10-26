package dev.corn.flashing.storage

import dev.corn.flashing.core.BoardOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JobOutcomeTest {
    private fun job(status: String) = JobEntity("1", "f", "sha", "p", startedAt = 1L, status = status)

    @Test fun mapsTerminalStatuses() {
        assertEquals(BoardOutcome.SUCCEEDED, job("SIMULATED").outcome())
        assertEquals(BoardOutcome.SUCCEEDED, job("SUCCEEDED").outcome())
        assertEquals(BoardOutcome.FAILED, job("FAILED").outcome())
        assertEquals(BoardOutcome.INTERRUPTED, job("CANCELLED").outcome())
        assertEquals(BoardOutcome.INTERRUPTED, job("INTERRUPTED").outcome())
        assertEquals(BoardOutcome.SKIPPED_DUPLICATE, job("SKIPPED").outcome())
        assertNull(job("RUNNING").outcome())
        assertNull(job("QUEUED").outcome())
    }

    @Test fun tallyIgnoresInFlightRows() {
        val rows = listOf(
            job("SIMULATED").copy(seq = 1, targetUid = "A"),
            job("RUNNING").copy(seq = 2),
            job("FAILED").copy(seq = 3, targetUid = "B"),
        )
        val tally = BatchController.tally(rows)
        assertEquals(1, tally.succeeded)
        assertEquals(1, tally.failed)
        assertEquals(3, tally.lastSeq)
    }
}
