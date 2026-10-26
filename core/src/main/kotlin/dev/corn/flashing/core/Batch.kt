package dev.corn.flashing.core

/** What to do when a board whose UID already succeeded in this batch is connected again. */
enum class DuplicatePolicy { SKIP_AND_WARN, ALLOW }

data class SerialFormat(val prefix: String = "CF-", val start: Long = 1, val digits: Int = 6) {
    init {
        require(start >= 0) { "序列号起始值不能为负数" }
        require(digits in 1..12) { "序列号位数应在 1–12 之间" }
    }
    fun format(number: Long): String = prefix + number.toString().padStart(digits, '0')
}

data class BatchConfig(
    val workOrder: String,
    val planned: Int,
    val duplicatePolicy: DuplicatePolicy = DuplicatePolicy.SKIP_AND_WARN,
    /** Pause after this many consecutive failures; 0 never pauses. */
    val pauseAfterFailures: Int = 3,
    /** Null: no serial numbers. */
    val serial: SerialFormat? = SerialFormat(),
) {
    init {
        require(workOrder.isNotBlank()) { "请填写工单号" }
        require(planned in 1..100_000) { "计划数量应在 1–100000 之间" }
        require(pauseAfterFailures in 0..99) { "连续失败次数应在 0–99 之间" }
    }
}

/** One finished row of a batch, as stored. [uid] is null when the target could not be read. */
enum class BoardOutcome { SUCCEEDED, FAILED, INTERRUPTED, SKIPPED_DUPLICATE }

data class BoardRow(val seq: Int, val outcome: BoardOutcome, val uid: String?)

/**
 * Counters derived from stored rows only, so a batch restored after a process death
 * resumes with exactly the same numbers, serials and duplicate set.
 */
data class BatchTally(
    val attempts: Int = 0,
    val succeeded: Int = 0,
    val failed: Int = 0,
    val interrupted: Int = 0,
    val skippedDuplicates: Int = 0,
    val consecutiveFailures: Int = 0,
    val succeededUids: Set<String> = emptySet(),
    val lastSeq: Int = 0,
) {
    val yield: Float? get() = (succeeded + failed).takeIf { it > 0 }?.let { succeeded.toFloat() / it }
}

sealed interface BoardDecision {
    data class Flash(val seq: Int) : BoardDecision
    data class SkipDuplicate(val seq: Int, val uid: String) : BoardDecision
}

enum class BatchStop { COMPLETED, TOO_MANY_FAILURES }

/** Why a restored batch cannot simply continue. */
enum class BatchResumeBlock { NO_FIRMWARE, FIRMWARE_CHANGED, PROFILE_CHANGED }

/** Inputs frozen into a batch when it was created. [configSha256] is [ProductProfile.configSha256]. */
data class BatchFingerprint(val sha256: String, val configSha256: String)

object BatchRules {
    fun tally(rows: List<BoardRow>): BatchTally {
        var tally = BatchTally()
        rows.sortedBy { it.seq }.forEach { row ->
            tally = when (row.outcome) {
                BoardOutcome.SUCCEEDED -> tally.copy(
                    attempts = tally.attempts + 1, succeeded = tally.succeeded + 1, consecutiveFailures = 0,
                    succeededUids = row.uid?.let { tally.succeededUids + it } ?: tally.succeededUids,
                )
                BoardOutcome.FAILED -> tally.copy(attempts = tally.attempts + 1, failed = tally.failed + 1,
                    consecutiveFailures = tally.consecutiveFailures + 1)
                // An interruption says nothing about the board, so it neither counts as a failure nor resets the streak.
                BoardOutcome.INTERRUPTED -> tally.copy(attempts = tally.attempts + 1, interrupted = tally.interrupted + 1)
                BoardOutcome.SKIPPED_DUPLICATE -> tally.copy(skippedDuplicates = tally.skippedDuplicates + 1)
            }.copy(lastSeq = maxOf(tally.lastSeq, row.seq))
        }
        return tally
    }

    fun decide(config: BatchConfig, tally: BatchTally, uid: String?): BoardDecision {
        val seq = tally.lastSeq + 1
        if (uid != null && config.duplicatePolicy == DuplicatePolicy.SKIP_AND_WARN && uid in tally.succeededUids) {
            return BoardDecision.SkipDuplicate(seq, uid)
        }
        return BoardDecision.Flash(seq)
    }

    /** Serials are handed out only after success: failed or interrupted boards never consume a number. */
    fun nextSerial(config: BatchConfig, tally: BatchTally): String? =
        config.serial?.let { it.format(it.start + tally.succeeded) }

    fun serialRange(config: BatchConfig, tally: BatchTally): Pair<String, String>? {
        val format = config.serial ?: return null
        if (tally.succeeded == 0) return null
        return format.format(format.start) to format.format(format.start + tally.succeeded - 1)
    }

    fun stop(config: BatchConfig, tally: BatchTally): BatchStop? = when {
        tally.succeeded >= config.planned -> BatchStop.COMPLETED
        config.pauseAfterFailures > 0 && tally.consecutiveFailures >= config.pauseAfterFailures -> BatchStop.TOO_MANY_FAILURES
        else -> null
    }

    /**
     * A restored batch may only continue when both the firmware bytes and the product profile are
     * still exactly the ones it was started with. Otherwise serials/counters would describe a
     * mixture of two inputs, so the batch must be ended and a new one started.
     */
    fun resumeBlock(batch: BatchFingerprint, current: BatchFingerprint?): BatchResumeBlock? = when {
        current == null -> BatchResumeBlock.NO_FIRMWARE
        current.sha256 != batch.sha256 -> BatchResumeBlock.FIRMWARE_CHANGED
        current.configSha256 != batch.configSha256 -> BatchResumeBlock.PROFILE_CHANGED
        else -> null
    }
}

/** Merges byte-adjacent segments; HEX files store one segment per record. */
fun FirmwareImage.regions(): List<AddressRange> {
    val merged = mutableListOf<AddressRange>()
    segments.sortedBy { it.address }.forEach { segment ->
        val last = merged.lastOrNull()
        if (last != null && last.endExclusive == segment.address) {
            merged[merged.lastIndex] = AddressRange(last.start, segment.endAddressExclusive)
        } else {
            merged += AddressRange(segment.address, segment.endAddressExclusive)
        }
    }
    return merged
}
