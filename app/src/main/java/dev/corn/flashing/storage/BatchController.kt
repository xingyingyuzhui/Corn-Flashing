package dev.corn.flashing.storage

import android.content.Context
import android.net.Uri
import dev.corn.flashing.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

enum class BatchPhase { WAITING_BOARD, FLASHING, PAUSED, FINISHED }

data class BatchView(
    val batch: BatchEntity,
    val tally: BatchTally,
    val rows: List<JobEntity>,
    val phase: BatchPhase,
    /** Short-lived message such as a skipped duplicate UID. */
    val notice: String? = null,
)

/** Offered once after a process death left a batch unfinished. */
data class ResumeOffer(
    val batch: BatchEntity,
    val tally: BatchTally,
    val interrupted: List<JobEntity>,
    /** Why the batch cannot continue (firmware or profile changed); null if it can. */
    val blocker: String?,
)

/**
 * Single-station continuous batch. One board at a time: wait for a board, decide (flash or
 * skip a duplicate), run it through [FlashRepository], record, then apply stop rules.
 * All counters are re-derived from stored rows after every board, so a restored batch
 * continues with the same numbers and serials.
 */
class BatchController(
    private val context: Context,
    private val dao: FlashDao,
    private val repository: FlashRepository,
    private val launcher: JobLauncher,
    private val foreground: StateFlow<Boolean>,
    private val cancelRunning: () -> Unit = {},
    private val boardSwapMillis: Long = 1800,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _active = MutableStateFlow<BatchView?>(null)
    val active = _active.asStateFlow()
    private val _resumeOffer = MutableStateFlow<ResumeOffer?>(null)
    val resumeOffer = _resumeOffer.asStateFlow()
    val batches = dao.batches()
    private var loop: Job? = null
    private var boards: SimulatedBoards? = null

    init {
        scope.launch {
            repository.state.first { it.ready }
            val batch = withContext(Dispatchers.IO) { dao.activeBatch() } ?: return@launch
            val paused = batch.copy(status = BatchStatus.PAUSED, pauseReason = "应用上次被关闭，批次未结束")
            withContext(Dispatchers.IO) { dao.saveBatch(paused) }
            val view = load(paused, BatchPhase.PAUSED)
            _active.value = view
            _resumeOffer.value = ResumeOffer(paused, view.tally,
                repository.recoveredJobs.filter { it.batchId == batch.id }, blocker(paused))
        }
    }

    fun config(batch: BatchEntity) = BatchConfig(
        workOrder = batch.workOrder, planned = batch.planned,
        duplicatePolicy = DuplicatePolicy.valueOf(batch.duplicatePolicy),
        pauseAfterFailures = batch.pauseAfterFailures,
        serial = batch.serialPrefix?.let { SerialFormat(it, batch.serialStart, batch.serialDigits) },
    )

    /** Returns an error message, or null once the batch is running. */
    suspend fun start(config: BatchConfig, injectFaults: Boolean): String? {
        if (_active.value != null) return "已有未结束的批次"
        val session = repository.state.value
        val firmware = session.firmware ?: return "请先选择固件"
        if (session.running || session.importing) return "当前有任务在执行"
        runCatching { FlashPlanner.plan(firmware.image, repository.profile, DemoFlashBackend.DEFAULT_TARGET) }
            .onFailure { return "固件不能用于当前产品配置：${it.message}" }
        val profile = repository.profile
        val batch = BatchEntity(
            id = UUID.randomUUID().toString(), workOrder = config.workOrder.trim(), planned = config.planned,
            duplicatePolicy = config.duplicatePolicy.name, pauseAfterFailures = config.pauseAfterFailures,
            serialPrefix = config.serial?.prefix, serialStart = config.serial?.start ?: 1, serialDigits = config.serial?.digits ?: 6,
            firmwareName = firmware.name, sha256 = firmware.image.sourceSha256, profileId = profile.id, profileName = profile.name,
            injectFaults = injectFaults, startedAt = System.currentTimeMillis(),
        )
        withContext(Dispatchers.IO) { dao.saveBatch(batch) }
        _active.value = BatchView(batch, BatchTally(), emptyList(), BatchPhase.WAITING_BOARD)
        boards = null
        runLoop()
        return null
    }

    fun pause(reason: String = "已手动暂停") {
        val view = _active.value ?: return
        if (view.phase == BatchPhase.PAUSED || view.phase == BatchPhase.FINISHED) return
        // A board already flashing finishes first; the loop stops before the next one.
        scope.launch { setStatus(view.batch.copy(status = BatchStatus.PAUSED, pauseReason = reason)) }
    }

    /** Returns an error message, or null once running again. */
    suspend fun resume(): String? {
        val view = _active.value ?: return "没有未结束的批次"
        if (view.phase != BatchPhase.PAUSED) return null
        blocker(view.batch)?.let { return it }
        _resumeOffer.value = null
        setStatus(view.batch.copy(status = BatchStatus.RUNNING, pauseReason = null))
        runLoop()
        return null
    }

    fun dismissResumeOffer() { _resumeOffer.value = null }

    fun clearNotice() { _active.update { it?.copy(notice = null) } }

    /** Ends the batch (completed or not) and returns its id for the report. */
    suspend fun end(): String? {
        val view = _active.value ?: return null
        // Stop the board in flight so its final row is stored before the report is opened.
        if (repository.state.value.running) {
            cancelRunning()
            withTimeoutOrNull(6_000) { repository.state.first { !it.running } }
        }
        loop?.cancel()
        val status = if (view.phase == BatchPhase.FINISHED) BatchStatus.COMPLETED else BatchStatus.ENDED
        val ended = view.batch.copy(status = status, endedAt = view.batch.endedAt ?: System.currentTimeMillis())
        withContext(Dispatchers.IO) { dao.saveBatch(ended) }
        _resumeOffer.value = null
        _active.value = null
        return ended.id
    }

    fun rows(batchId: String) = dao.batchJobs(batchId)
    fun batch(batchId: String) = dao.batch(batchId)

    private fun blocker(batch: BatchEntity): String? {
        val firmware = repository.state.value.firmware
        return when {
            firmware == null -> "当前没有固件，无法继续；只能结束此批次"
            firmware.image.sourceSha256 != batch.sha256 -> "固件已更换（SHA 不同），不能继续；请结束此批次后另开一批"
            repository.profile.id != batch.profileId -> "产品配置已更换，不能继续；请结束此批次后另开一批"
            else -> null
        }
    }

    private fun runLoop() {
        loop?.cancel()
        loop = scope.launch {
            while (isActive) {
                // A board started before a pause may still be running; never hand out its sequence number again.
                repository.state.first { !it.running }
                if (_active.value?.batch?.status != BatchStatus.RUNNING) break
                refresh(BatchPhase.WAITING_BOARD)
                delay(boardSwapMillis) // simulated unplug of the last board and plug-in of the next
                foreground.first { it } // Android refuses to start the service from the background
                val view = _active.value ?: break
                if (view.batch.status != BatchStatus.RUNNING) break
                val config = config(view.batch)
                // Resume after a process death must not replay the same simulated boards.
                // Each board advances the UID counter by at most 3, so lastSeq * 3 is past every UID handed out so far.
                val generator = boards ?: SimulatedBoards(
                    Random(view.batch.startedAt + view.tally.lastSeq), view.batch.injectFaults, view.tally.lastSeq * 3L,
                ).also { boards = it }
                val board = generator.next(view.tally.succeededUids)
                when (val decision = BatchRules.decide(config, view.tally, board.target.uid)) {
                    is BoardDecision.SkipDuplicate -> {
                        withContext(Dispatchers.IO) {
                            val now = System.currentTimeMillis()
                            dao.saveJob(JobEntity(UUID.randomUUID().toString(), view.batch.firmwareName, view.batch.sha256,
                                view.batch.profileName, startedAt = now, finishedAt = now, status = "SKIPPED", stage = "跳过重复",
                                message = "UID ${decision.uid} 在本批次已烧录成功，未重复烧录", targetUid = decision.uid,
                                batchId = view.batch.id, seq = decision.seq))
                        }
                        refresh(BatchPhase.WAITING_BOARD, notice = "UID ${decision.uid} 已烧录过，已跳过；请换下一块板")
                    }
                    is BoardDecision.Flash -> {
                        val request = JobRequest(board, view.batch.id, decision.seq, BatchRules.nextSerial(config, view.tally))
                        val id = repository.prepareJob(request)
                        if (id == null) { pauseNow("无法开始下一块：当前有其他任务或固件未就绪"); break }
                        val result = async(start = CoroutineStart.UNDISPATCHED) { repository.finished.first { it.id == id } }
                        if (!launcher.start(id)) {
                            repository.startFailed("系统拒绝启动任务服务")
                            result.await()
                            pauseNow("应用在后台时系统不允许开始烧录；回到前台后点「继续」")
                            break
                        }
                        _active.update { it?.copy(phase = BatchPhase.FLASHING, notice = null) }
                        val finished = result.await()
                        val after = refresh(BatchPhase.WAITING_BOARD)
                        if (finished.status == "CANCELLED") { pauseNow("#${decision.seq} 已中止，这块板需要重新烧录"); break }
                        when (BatchRules.stop(config, after.tally)) {
                            BatchStop.COMPLETED -> { finish(after); break }
                            BatchStop.TOO_MANY_FAILURES -> { pauseNow("连续失败 ${config.pauseAfterFailures} 次，已暂停；请检查后继续"); break }
                            null -> Unit
                        }
                    }
                }
            }
        }
    }

    private suspend fun pauseNow(reason: String) {
        val view = _active.value ?: return
        setStatus(view.batch.copy(status = BatchStatus.PAUSED, pauseReason = reason))
    }

    private suspend fun finish(view: BatchView) {
        val done = view.batch.copy(status = BatchStatus.COMPLETED, endedAt = System.currentTimeMillis())
        withContext(Dispatchers.IO) { dao.saveBatch(done) }
        _active.value = view.copy(batch = done, phase = BatchPhase.FINISHED)
    }

    private suspend fun setStatus(batch: BatchEntity) {
        withContext(Dispatchers.IO) { dao.saveBatch(batch) }
        val phase = when (batch.status) {
            BatchStatus.PAUSED -> if (_active.value?.phase == BatchPhase.FLASHING) BatchPhase.FLASHING else BatchPhase.PAUSED
            else -> BatchPhase.WAITING_BOARD
        }
        _active.update { it?.copy(batch = batch, phase = phase) }
        if (batch.status == BatchStatus.PAUSED && phase == BatchPhase.PAUSED) loop?.cancel()
    }

    private suspend fun refresh(phase: BatchPhase, notice: String? = _active.value?.notice): BatchView {
        val current = _active.value!!
        val next = load(current.batch, if (current.batch.status == BatchStatus.PAUSED) BatchPhase.PAUSED else phase).copy(notice = notice)
        _active.value = next
        return next
    }

    private suspend fun load(batch: BatchEntity, phase: BatchPhase): BatchView {
        val rows = withContext(Dispatchers.IO) { dao.batchJobsNow(batch.id) }
        return BatchView(batch, tally(rows), rows, phase)
    }

    suspend fun exportCsv(batchId: String, uri: Uri) = withContext(Dispatchers.IO) {
        val rows = dao.batchJobsNow(batchId)
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        val text = buildString {
            append("﻿序号,工位,UID,序列号,结果,用时(ms),阶段,说明,开始时间\n")
            rows.forEach { row ->
                val duration = row.finishedAt?.let { it - row.startedAt }?.toString().orEmpty()
                listOf(row.seq?.toString().orEmpty(), "工位 1", row.targetUid.orEmpty(), row.serial.orEmpty(), statusLabel(row.status),
                    duration, row.stage, row.message, time.format(Date(row.startedAt)))
                    .joinTo(this, ",") { csv(it) }
                append('\n')
            }
        }
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: error("无法写入导出文件")
    }

    companion object {
        fun tally(rows: List<JobEntity>): BatchTally = BatchRules.tally(rows.mapNotNull { row ->
            val seq = row.seq ?: return@mapNotNull null
            row.outcome()?.let { BoardRow(seq, it, row.targetUid) }
        })

        fun statusLabel(status: String) = when (status) {
            "SIMULATED" -> "模拟成功"
            "SUCCEEDED" -> "成功"
            "FAILED" -> "失败"
            "CANCELLED" -> "已中止"
            "INTERRUPTED" -> "中断"
            "SKIPPED" -> "跳过重复"
            "RUNNING" -> "执行中"
            else -> "排队中"
        }

        private fun csv(value: String): String =
            if (value.any { it == ',' || it == '"' || it == '\n' }) "\"" + value.replace("\"", "\"\"") + "\"" else value
    }
}
