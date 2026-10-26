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
    /** When false, each board is probed on the authorized ST-LINK (single station). */
    private var useSimulator: Boolean = true
    private var lastRealUid: String? = null

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
    suspend fun start(config: BatchConfig, injectFaults: Boolean, useSimulator: Boolean = true): String? {
        if (_active.value != null) return "已有未结束的批次"
        val session = repository.state.value
        val firmware = session.firmware ?: return "请先选择固件"
        if (session.running || session.importing) return "当前有任务在执行"
        runCatching { repository.validateReservedSettings() }.onFailure { return it.message ?: "保留区配置无效" }
        this.useSimulator = useSimulator
        this.lastRealUid = null
        val planTarget = if (useSimulator) DemoFlashBackend.DEFAULT_TARGET
        else session.target ?: return "真实批次请先在设备页或工作台「识别芯片」"
        runCatching { FlashPlanner.plan(firmware.image, repository.profile, planTarget, if (useSimulator) dev.corn.flashing.core.targets.TargetCatalog.registry else repository.targets) }
            .onFailure { return "固件不能用于当前产品配置：${it.message}" }
        val profile = repository.profile
        val batch = BatchEntity(
            id = UUID.randomUUID().toString(), workOrder = config.workOrder.trim(), planned = config.planned,
            duplicatePolicy = config.duplicatePolicy.name, pauseAfterFailures = config.pauseAfterFailures,
            serialPrefix = config.serial?.prefix, serialStart = config.serial?.start ?: 1, serialDigits = config.serial?.digits ?: 6,
            firmwareName = firmware.name, sha256 = firmware.image.sourceSha256,
            profileId = profile.id, profileName = profile.name, configSha256 = profile.configSha256,
            injectFaults = injectFaults, startedAt = System.currentTimeMillis(),
            mode = if (useSimulator) "DEMO" else "STLINK",
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

    /**
     * Ends the batch and returns its id for the report.
     * If a board is still flashing, cancels it and waits until the final row is persisted.
     * Returns null and leaves the batch active when that wait times out.
     */
    suspend fun end(): String? {
        val view = _active.value ?: return null
        if (repository.state.value.running) {
            cancelRunning()
            val settled = withTimeoutOrNull(8_000) { repository.state.first { !it.running } }
            if (settled == null) {
                _active.update { it?.copy(notice = "结束失败：当前板仍在烧录，请稍后再试或长按中止") }
                return null
            }
        }
        loop?.cancel()
        loop = null
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
        val current = firmware?.let {
            BatchFingerprint(it.image.sourceSha256, repository.profile.configSha256)
        }
        return when (BatchRules.resumeBlock(BatchFingerprint(batch.sha256, batch.configSha256), current)) {
            BatchResumeBlock.NO_FIRMWARE -> "当前没有固件，无法继续；只能结束此批次"
            BatchResumeBlock.FIRMWARE_CHANGED -> "固件已更换（SHA 不同），不能继续；请结束此批次后另开一批"
            BatchResumeBlock.PROFILE_CHANGED -> "产品配置已更换，不能继续；请结束此批次后另开一批"
            null -> null
        }
    }


    private suspend fun waitForRealBoard(succeededUids: Collection<String>): TargetInfo {
        // Poll probe until we see a chip; prefer a UID not just completed (or first board).
        while (true) {
            currentCoroutineContext().ensureActive()
            if (_active.value?.batch?.status != BatchStatus.RUNNING) error("批次已暂停")
            try {
                val target = repository.probeChip()
                val uid = target.uid
                if (uid != null && uid == lastRealUid) {
                    delay(800)
                    continue
                }
                if (uid != null && uid in succeededUids) {
                    // Duplicate will be handled by BatchRules; still return so decide can SkipDuplicate.
                }
                lastRealUid = uid
                return target
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                delay(1200)
            }
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
                val board = if (useSimulator) {
                    val generator = boards ?: SimulatedBoards(
                        Random(view.batch.startedAt + view.tally.lastSeq), view.batch.injectFaults, view.tally.lastSeq * 3L,
                    ).also { boards = it }
                    generator.next(view.tally.succeededUids)
                } else {
                    // Wait for a (new) live target on the ST-LINK.
                    refresh(BatchPhase.WAITING_BOARD, notice = "等待下一块板…请接好目标后保持不动")
                    val live = waitForRealBoard(view.tally.succeededUids)
                    SimulatedBoards.Board(live, null)
                }
                val profileMatches = runCatching {
                    repository.profileFor(board.target, registry = if (useSimulator) dev.corn.flashing.core.targets.TargetCatalog.registry else repository.targets)
                        .configSha256 == view.batch.configSha256
                }.getOrDefault(false)
                if (!profileMatches) { pauseNow("新板芯片、算法或保留区与批次冻结配置不一致，未执行擦除"); break }
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
                        val request = JobRequest(
                            board = board,
                            batchId = view.batch.id,
                            seq = decision.seq,
                            serialOnSuccess = BatchRules.nextSerial(config, view.tally),
                            expectedProfileSha256 = view.batch.configSha256,
                            useSimulator = useSimulator,
                            executeOptions = FlashExecuteOptions(
                                // Batch uses repository profile defaults; reset on for real line.
                                resetAfter = true,
                                eraseMode = 0,
                                verifyMode = 0,
                            ),
                        )
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
        val text = BatchCsv.render(rows.map { row ->
            BatchCsvRow(
                seq = row.seq, station = "工位 1", uid = row.targetUid, serial = row.serial,
                result = statusLabel(row.status),
                durationMillis = row.finishedAt?.let { it - row.startedAt },
                stage = row.stage, message = row.message, startedAt = row.startedAt,
            )
        }) { time.format(Date(it)) }
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: error("无法写入导出文件")
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
    }
}
