package dev.corn.flashing.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.corn.flashing.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID

data class ImportedFirmware(val name: String, val image: FirmwareImage)

/** One board to run. [serialOnSuccess] is stored only if the run succeeds. */
data class JobRequest(
    val board: SimulatedBoards.Board = SimulatedBoards.Board(DemoFlashBackend.DEFAULT_TARGET, null),
    val batchId: String? = null,
    val seq: Int? = null,
    val serialOnSuccess: String? = null,
)

data class SessionState(
    val ready: Boolean = false,
    val importing: Boolean = false,
    val firmware: ImportedFirmware? = null,
    val importError: String? = null,
    val running: Boolean = false,
    val progress: Float = 0f,
    val flashStage: FlashStage? = null,
    val stage: String = "等待固件",
    val message: String = "导入文件或加载演示固件，开始验证工作流程",
    val target: TargetInfo? = null,
    val plan: FlashPlan? = null,
    /** The job currently running, or the last finished one until the next start. */
    val job: JobEntity? = null,
    /** Stage a failed job stopped at, for the result view. */
    val failedStage: FlashStage? = null,
)

/** Starts the foreground service for a prepared job; false if Android refused (e.g. app in background). */
fun interface JobLauncher { fun start(id: String): Boolean }

/** Application-scoped state survives Activity recreation; Room stores durable results. */
class FlashRepository(private val context: Context, private val dao: FlashDao) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(SessionState())
    val state = _state.asStateFlow()
    val history = dao.recentJobs()
    val profile = ProductProfile()
    private var pending: Pair<JobEntity, Pair<ImportedFirmware, JobRequest>>? = null
    private val _finished = MutableSharedFlow<JobEntity>(extraBufferCapacity = 16)
    /** Every job's final row, once saved. */
    val finished = _finished.asSharedFlow()
    /** Jobs that were still queued or running when the previous process died; set before [SessionState.ready]. */
    var recoveredJobs: List<JobEntity> = emptyList()
        private set

    init {
        scope.launch {
            try {
                recoveredJobs = withContext(Dispatchers.IO) {
                    dao.unfinishedJobs().also { dao.recoverInterrupted(System.currentTimeMillis()) }
                }
                val restored = withContext(Dispatchers.IO) {
                    dao.firmware()?.let { saved ->
                        val bytes = File(context.filesDir, "firmware/${saved.fileName}").readBytes()
                        require(bytes.size <= FirmwareParser.MAX_FILE_BYTES) { "已存固件超过大小限制" }
                        val image = FirmwareParser.parse(saved.name, bytes, saved.binAddress)
                        require(image.sourceSha256 == saved.sha256) { "已存固件哈希不匹配，请重新导入" }
                        ImportedFirmware(saved.name, image)
                    }
                }
                _state.update { it.copy(ready = true, firmware = restored,
                    stage = if (restored == null) "等待固件" else "固件已就绪") }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _state.update { it.copy(ready = true, importError = "恢复失败：${error.message}；请重新导入固件") }
            }
        }
    }

    suspend fun importFile(uri: Uri, binAddressText: String) = importSafely {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: error("无法取得文件名")
        val address = if (FirmwareParser.isBin(name)) FirmwareParser.parseAddress(binAddressText) else null
        val bytes = context.contentResolver.openInputStream(uri)?.use(::readBounded) ?: error("无法读取文件")
        persistFirmware(name, bytes, address)
    }

    suspend fun loadDemo() = importSafely {
        val bytes = context.assets.open("demo.hex").use(::readBounded)
        persistFirmware("demo.hex", bytes, null)
    }

    fun clearImportError() = _state.update { it.copy(importError = null) }

    private suspend fun importSafely(load: suspend () -> ImportedFirmware) {
        if (!_state.value.ready || _state.value.running || _state.value.importing) return
        _state.update { it.copy(importing = true, importError = null) }
        try {
            val imported = withContext(Dispatchers.IO) { load() }
            _state.update { it.copy(firmware = imported, stage = "固件已就绪", progress = 0f, flashStage = null,
                target = null, plan = null, job = null, message = "文件解析成功；执行时将检查目标与擦除范围") }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            _state.update { it.copy(importError = error.message ?: error.javaClass.simpleName) }
        } finally {
            _state.update { it.copy(importing = false) }
        }
    }

    private suspend fun persistFirmware(name: String, bytes: ByteArray, binAddress: Long?): ImportedFirmware {
        val image = FirmwareParser.parse(name, bytes, binAddress)
        val directory = File(context.filesDir, "firmware").apply { mkdirs() }
        val safeFileName = image.sourceSha256
        val target = File(directory, safeFileName)
        if (!target.exists()) {
            val temporary = File.createTempFile("import-", ".tmp", directory)
            try {
                temporary.outputStream().use { it.write(bytes) }
                check(temporary.renameTo(target)) { "无法保存固件副本" }
            } finally { temporary.delete() }
        }
        dao.saveFirmware(FirmwareEntity(name = name, fileName = safeFileName,
            sha256 = image.sourceSha256, binAddress = binAddress))
        // Only the latest artifact is selectable in the framework. History retains its hash.
        directory.listFiles()?.filter { it.name != safeFileName }?.forEach { it.delete() }
        return ImportedFirmware(name, image)
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= FirmwareParser.MAX_FILE_BYTES) { "固件文件最大支持 4 MiB" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    /** Freeze the image before service start; another import cannot replace this job's inputs. */
    suspend fun prepareJob(request: JobRequest = JobRequest()): String? {
        val current = _state.value
        if (!current.ready || current.running || current.importing) return null
        val firmware = current.firmware ?: return null
        val record = JobEntity(UUID.randomUUID().toString(), firmware.name,
            firmware.image.sourceSha256, profile.name, startedAt = System.currentTimeMillis(),
            targetUid = request.board.target.uid, batchId = request.batchId, seq = request.seq)
        pending = record to (firmware to request)
        _state.update { it.copy(running = true, progress = 0f, flashStage = null, failedStage = null, stage = "正在排队",
            message = "正在保存任务，不操作 USB 设备", target = null, plan = null, job = record) }
        try {
            dao.saveJob(record)
            _state.update { it.copy(stage = "等待启动", message = "模拟任务已排队，不操作 USB 设备") }
            return record.id
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { startFailed("排队操作已取消") }
            throw cancelled
        } catch (error: Exception) {
            startFailed("无法保存排队任务：${error.message}")
            return null
        }
    }

    suspend fun startFailed(message: String) {
        val queued = pending
        pending = null
        val failed = queued?.first?.copy(status = "FAILED", stage = "启动失败", message = message, finishedAt = System.currentTimeMillis())
        try {
            failed?.let { dao.saveJob(it) }
        } catch (_: Exception) {
            // A surviving QUEUED row is recovered as INTERRUPTED on process restart.
        }
        _state.update { it.copy(running = false, stage = "启动失败", message = message, job = failed ?: it.job) }
        failed?.let { _finished.tryEmit(it) }
    }

    suspend fun runJob(id: String) {
        val queued = pending?.takeIf { it.first.id == id } ?: return
        pending = null
        val (firmware, request) = queued.second
        var record = queued.first
        var failedStage: FlashStage? = null
        try {
            record = record.copy(status = "RUNNING", stage = "探测模拟目标")
            dao.saveJob(record)
            _state.update { it.copy(job = record, flashStage = FlashStage.CONNECTING, stage = stageLabel(FlashStage.CONNECTING)) }
            val backend: FlashBackend = DemoFlashBackend(stepDelayMillis = 700, request.board.target, request.board.failure)
            val target = backend.probe()
            val plan = FlashPlanner.plan(firmware.image, profile, target)
            record = record.copy(targetUid = target.uid)
            _state.update { it.copy(target = target, plan = plan) }
            backend.execute(plan) { event ->
                _state.update { it.copy(progress = event.fraction, flashStage = event.stage, stage = stageLabel(event.stage), message = event.message) }
            }
            record = record.copy(status = "SIMULATED", stage = "模拟完成", serial = request.serialOnSuccess,
                message = "模拟流程完成；未进行真实擦写或硬件校验")
        } catch (cancelled: CancellationException) {
            record = record.copy(status = "CANCELLED", stage = "已中止",
                message = "任务在「${_state.value.stage}」时停止；这块板的程序可能不完整，需要重新烧录")
            throw cancelled
        } catch (failure: FlashFailure) {
            failedStage = failure.stage
            record = record.copy(status = "FAILED", stage = "停在「${stageLabel(failure.stage)}」", message = failure.message ?: "未知错误")
        } catch (error: Exception) {
            failedStage = _state.value.flashStage
            record = record.copy(status = "FAILED", stage = "任务失败", message = error.message ?: "未知错误")
        } finally {
            record = record.copy(finishedAt = System.currentTimeMillis())
            try {
                withContext(NonCancellable + Dispatchers.IO) { dao.saveJob(record) }
            } catch (error: Exception) {
                record = record.copy(message = "记录保存失败：${error.message}；请勿将本次任务视为已归档")
            }
            _state.update { it.copy(running = false, job = record, failedStage = failedStage, stage = record.stage, message = record.message) }
            _finished.tryEmit(record)
        }
    }

    suspend fun exportHistory(uri: Uri) = withContext(Dispatchers.IO) {
        val records = JSONArray()
        dao.allJobs().forEach { item ->
            records.put(JSONObject().apply {
                put("id", item.id); put("firmware", item.firmwareName); put("sha256", item.sha256)
                put("profile", item.profileName); put("mode", item.mode); put("status", item.status)
                put("startedAt", item.startedAt); put("finishedAt", item.finishedAt ?: JSONObject.NULL)
                put("uid", item.targetUid ?: JSONObject.NULL); put("message", item.message)
                put("batchId", item.batchId ?: JSONObject.NULL); put("seq", item.seq ?: JSONObject.NULL)
                put("serial", item.serial ?: JSONObject.NULL)
            })
        }
        val output = JSONObject().put("schemaVersion", 2).put("records", records).toString(2)
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(output.toByteArray()) }
            ?: error("无法写入导出文件")
    }

    companion object {
        fun stageLabel(stage: FlashStage) = when (stage) {
            FlashStage.CONNECTING -> "连接探针"
            FlashStage.PROBING -> "识别芯片"
            FlashStage.ERASING -> "擦除"
            FlashStage.WRITING -> "写入"
            FlashStage.VERIFYING -> "校验"
            FlashStage.RESETTING -> "复位运行"
            FlashStage.SUCCEEDED -> "完成"
        }
    }
}

/** Maps a stored job status to the batch outcome it counts as; null for jobs still in flight. */
fun JobEntity.outcome(): BoardOutcome? = when (status) {
    "SIMULATED", "SUCCEEDED" -> BoardOutcome.SUCCEEDED
    "FAILED" -> BoardOutcome.FAILED
    "CANCELLED", "INTERRUPTED" -> BoardOutcome.INTERRUPTED
    "SKIPPED" -> BoardOutcome.SKIPPED_DUPLICATE
    else -> null
}
