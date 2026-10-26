package dev.corn.flashing.core

import kotlin.random.Random
import org.junit.Test
import org.junit.Assert.assertTrue

class SimulatedBoardsTest {
    /** A batch restored after process death restarts the generator at lastSeq * 3; it must not reuse UIDs. */
    @Test
    fun restartedGeneratorNeverRepeatsEarlierUids() {
        repeat(20) { seed ->
            val first = SimulatedBoards(Random(seed), injectFaults = false)
            val seen = (1..40).map { first.next(emptySet()).target.uid }.toSet()
            val resumed = SimulatedBoards(Random(seed + 40), injectFaults = false, counterStart = 40 * 3L)
            val next = (1..40).map { resumed.next(emptySet()).target.uid }
            assertTrue("seed $seed reused a UID", next.none { it in seen })
        }
    }
}
