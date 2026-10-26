package dev.corn.flashing.core

import dev.corn.flashing.core.BoardOutcome.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class BatchRulesTest {
    private val config = BatchConfig("WO-1", planned = 5, pauseAfterFailures = 3, serial = SerialFormat("CF-", 1, 6))

    @Test fun serialsAreOnlyConsumedBySuccessesSoNoGapsAfterFailuresOrInterruptions() {
        val tally = BatchRules.tally(listOf(
            BoardRow(1, SUCCEEDED, "A"), BoardRow(2, FAILED, "B"), BoardRow(3, INTERRUPTED, "C"), BoardRow(4, SUCCEEDED, "D"),
        ))
        assertEquals(2, tally.succeeded)
        assertEquals("CF-000003", BatchRules.nextSerial(config, tally))
        assertEquals("CF-000001" to "CF-000002", BatchRules.serialRange(config, tally))
    }

    @Test fun serialRangeIsEmptyBeforeTheFirstSuccessAndNullWhenDisabled() {
        assertNull(BatchRules.serialRange(config, BatchTally()))
        assertNull(BatchRules.nextSerial(config.copy(serial = null), BatchTally(succeeded = 3)))
    }

    @Test fun aRestoredTallyMatchesTheOneBuiltLiveIncludingOrder() {
        val rows = listOf(BoardRow(3, FAILED, "X"), BoardRow(1, SUCCEEDED, "A"), BoardRow(2, FAILED, "B"))
        val tally = BatchRules.tally(rows)
        assertEquals(2, tally.consecutiveFailures)
        assertEquals(3, tally.lastSeq)
        assertEquals(3, tally.attempts)
    }

    @Test fun duplicateUidIsSkippedWithoutConsumingAnAttemptButStillGetsASequenceNumber() {
        val tally = BatchRules.tally(listOf(BoardRow(1, SUCCEEDED, "A")))
        val decision = BatchRules.decide(config, tally, "A")
        assertEquals(BoardDecision.SkipDuplicate(2, "A"), decision)
        val after = BatchRules.tally(listOf(BoardRow(1, SUCCEEDED, "A"), BoardRow(2, SKIPPED_DUPLICATE, "A")))
        assertEquals(1, after.attempts)
        assertEquals(1, after.skippedDuplicates)
        assertEquals(BoardDecision.Flash(3), BatchRules.decide(config, after, "B"))
    }

    @Test fun allowPolicyFlashesAKnownUidAgain() {
        val tally = BatchRules.tally(listOf(BoardRow(1, SUCCEEDED, "A")))
        assertEquals(BoardDecision.Flash(2), BatchRules.decide(config.copy(duplicatePolicy = DuplicatePolicy.ALLOW), tally, "A"))
    }

    @Test fun failedBoardsAreNotRememberedAsDoneSoTheyCanBeRetried() {
        val tally = BatchRules.tally(listOf(BoardRow(1, FAILED, "A")))
        assertEquals(BoardDecision.Flash(2), BatchRules.decide(config, tally, "A"))
    }

    @Test fun pausesAfterConsecutiveFailuresAndInterruptionsDoNotBreakOrExtendTheStreak() {
        val rows = listOf(BoardRow(1, FAILED, "A"), BoardRow(2, INTERRUPTED, "B"), BoardRow(3, FAILED, "C"))
        assertNull(BatchRules.stop(config, BatchRules.tally(rows)))
        val third = rows + BoardRow(4, FAILED, "D")
        assertEquals(BatchStop.TOO_MANY_FAILURES, BatchRules.stop(config, BatchRules.tally(third)))
        val recovered = third + BoardRow(5, SUCCEEDED, "E")
        assertNull(BatchRules.stop(config, BatchRules.tally(recovered)))
        assertNull(BatchRules.stop(config.copy(pauseAfterFailures = 0), BatchRules.tally(third)))
    }

    @Test fun completesWhenPlannedSuccessesAreReached() {
        val rows = (1..5).map { BoardRow(it, SUCCEEDED, "U$it") }
        assertEquals(BatchStop.COMPLETED, BatchRules.stop(config, BatchRules.tally(rows)))
        assertNull(BatchRules.stop(config, BatchRules.tally(rows.dropLast(1))))
    }

    @Test fun yieldIgnoresInterruptionsAndSkips() {
        val tally = BatchRules.tally(listOf(BoardRow(1, SUCCEEDED, "A"), BoardRow(2, FAILED, "B"),
            BoardRow(3, INTERRUPTED, "C"), BoardRow(4, SKIPPED_DUPLICATE, "A")))
        assertEquals(0.5f, tally.yield!!, 0.0001f)
        assertNull(BatchTally().yield)
    }

    @Test fun configRejectsNonsense() {
        assertThrows(IllegalArgumentException::class.java) { BatchConfig(" ", 10) }
        assertThrows(IllegalArgumentException::class.java) { BatchConfig("WO", 0) }
        assertThrows(IllegalArgumentException::class.java) { SerialFormat(start = -1) }
    }

    @Test fun simulatedBoardsGetDistinctUidsWithoutFaults() {
        val boards = SimulatedBoards(Random(7), injectFaults = false)
        val made = (1..200).map { boards.next(emptyList()) }
        assertEquals(200, made.map { it.target.uid }.toSet().size)
        assertTrue(made.all { it.failure == null && it.target.uid!!.startsWith("DEMO-") })
    }

    @Test fun injectedFaultsProduceFailuresAndDuplicates() {
        val boards = SimulatedBoards(Random(11), injectFaults = true)
        val done = mutableListOf<String>()
        var failures = 0
        var duplicates = 0
        repeat(500) {
            val board = boards.next(done)
            val uid = board.target.uid!!
            when {
                uid in done -> duplicates++
                board.failure != null -> failures++
                else -> done += uid
            }
        }
        assertTrue("failures=$failures", failures in 15..60)
        assertTrue("duplicates=$duplicates", duplicates in 5..40)
    }

    @Test fun demoFailureStopsAtItsStageAndNeverReportsSuccess() = runBlocking {
        val backend = DemoFlashBackend(0, failure = DemoFailure(FlashStage.VERIFYING, "bad"))
        val plan = FlashPlanner.plan(FirmwareParser.parse("a.bin", byteArrayOf(1), 0x08000000), ProductProfile(), backend.probe())
        val stages = mutableListOf<FlashStage>()
        val error = assertThrows(FlashFailure::class.java) { runBlocking { backend.execute(plan, FlashExecuteOptions()) { stages += it.stage } } }
        assertEquals(FlashStage.VERIFYING, error.stage)
        assertFalse(FlashStage.SUCCEEDED in stages)
    }

    @Test fun regionsMergeAdjacentHexRecords() {
        val image = FirmwareImage(FirmwareFormat.HEX, listOf(
            FirmwareSegment(0x08000010, ByteArray(16)), FirmwareSegment(0x08000000, ByteArray(16)),
            FirmwareSegment(0x08000400, ByteArray(4)),
        ), "x")
        assertEquals(listOf(AddressRange(0x08000000, 0x08000020), AddressRange(0x08000400, 0x08000404)), image.regions())
    }

    @Test fun binAddressParsingIsStrict() {
        assertEquals(0x08002000L, FirmwareParser.parseAddress(" 0x08002000 "))
        assertEquals(0x0800L, FirmwareParser.parseAddress("800"))
        listOf("", "0x", "08OO2000", "-1", "123456789", "0x-1").forEach { bad ->
            val error = assertThrows(IllegalArgumentException::class.java) { FirmwareParser.parseAddress(bad) }
            assertTrue(error.message!!.contains("不是有效的十六进制地址"))
        }
    }

    @Test fun resumeBlockedWhenProfileContentChangesEvenIfIdStaysTheSame() {
        val a = ProductProfile(id = "f103-64k", reservedRanges = emptyList())
        val b = ProductProfile(id = "f103-64k", reservedRanges = listOf(AddressRange(0x08000000, 0x08002000)))
        assertNotEquals(a.configSha256, b.configSha256)
        val frozen = BatchFingerprint("fw", a.configSha256)
        assertNull(BatchRules.resumeBlock(frozen, BatchFingerprint("fw", a.configSha256)))
        assertEquals(BatchResumeBlock.PROFILE_CHANGED,
            BatchRules.resumeBlock(frozen, BatchFingerprint("fw", b.configSha256)))
        assertEquals(BatchResumeBlock.FIRMWARE_CHANGED,
            BatchRules.resumeBlock(frozen, BatchFingerprint("other", a.configSha256)))
        assertEquals(BatchResumeBlock.NO_FIRMWARE, BatchRules.resumeBlock(frozen, null))
    }

    @Test fun configSha256IsStableAcrossFieldOrderOfSetsAndRanges() {
        val left = ProductProfile(
            reservedRanges = listOf(AddressRange(0x0800F800, 0x08010000), AddressRange(0x08000000, 0x08002000)),
            expectedDeviceIds = setOf(0x414, 0x410),
        )
        val right = ProductProfile(
            reservedRanges = listOf(AddressRange(0x08000000, 0x08002000), AddressRange(0x0800F800, 0x08010000)),
            expectedDeviceIds = setOf(0x410, 0x414),
        )
        assertEquals(left.configSha256, right.configSha256)
    }
}
