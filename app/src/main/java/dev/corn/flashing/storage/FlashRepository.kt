package dev.corn.flashing.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.corn.flashing.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID

data class ImportedFirmware(val name: String, val image: FirmwareImage)
data class SessionState(
    val ready: Boolean = false,
    val importing: Boolean = false,
    val firmware: ImportedFirmware? = null,
    val running: Boolean = false,
    val progress: Float = 0f,
    val stage: String = "等待固件",
    val message: String = "导入文件或加载演示固件，开始验证工作流程",
    val target: TargetInfo? = null,
    val plan: FlashPlan? = null,
)

/** Application-scoped state survives Activity recreation; Room stores durable results. */
class FlashRepository(private val context: Context, private val dao: FlashDao) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(SessionState())
    val state = _state.asStateFlow()
    val history = dao.recentJobs()
    val profile = ProductProfile()
    private var pending: Pair<JobEntity, ImportedFirmware>? = null

    init {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { dao.recoverInterrupted(System.currentTimeMillis()) }
                val restored = withContext(Dispatchers.IO) {
                    dao.firmware()?.let { saved ->
                        val bytes = File(context.filesDir, "firmware/${saved.fileName}").readBytes()
                        require(bytes.size <= MAX_BYTES) { "已存固件超过大小限制" }
                        val image = FirmwareParser.parse(saved.name, bytes, saved.binAddress)
                        require(image.sourceSha256 == saved.sha256) { "已存固件哈希不匹配，请重新导入" }
                        ImportedFirmware(saved.name, image)
                    }
                }
                _state.update { it.copy(ready = true, firmware = restored,
                    stage = if (restored == null) "等待固件" else "固件已就绪") }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _state.update { it.copy(ready = true, message = "恢复失败：${error.message}；请重新导入固件") }
            }
        }
    }

    suspend fun importFile(uri: Uri, binAddress: Long?) = importSafely {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: error("无法取得文件名")
        val bytes = context.contentResolver.openInputStream(uri)?.use(::readBounded) ?: error("无法读取文件")
        persistFirmware(name, bytes, binAddress)
    }

    suspend fun loadDemo() = importSafely {
        val bytes = context.assets.open("demo.hex").use(::readBounded)
        persistFirmware("demo.hex", bytes, null)
    }

    private suspend fun importSafely(load: suspend () -> ImportedFirmware) {
        if (!_state.value.ready || _state.value.running || _state.value.importing) return
        _state.update { it.copy(importing = true) }
        try {
            val imported = withContext(Dispatchers.IO) { load() }
            _state.update { it.copy(firmware = imported, stage = "固件已就绪", progress = 0f,
                target = null, plan = null, message = "文件解析成功；执行时将检查目标与擦除范围") }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            _state.update { it.copy(message = "导入失败：${error.message}；保留此前选择的固件") }
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
            require(output.size() + count <= MAX_BYTES) { "固件文件最大支持 4 MiB" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    /** Freeze the image before service start; another import cannot replace this job's inputs. */
    suspend fun prepareDemo(): String? {
        val current = _state.value
        if (!current.ready || current.running || current.importing) return null
        val firmware = current.firmware ?: return null
        val record = JobEntity(UUID.randomUUID().toString(), firmware.name,
            firmware.image.sourceSha256, profile.name, startedAt = System.currentTimeMillis())
        pending = record to firmware
        _state.update { it.copy(running = true, progress = 0f, stage = "正在排队",
            message = "正在保存任务，不操作 USB 设备", target = null, plan = null) }
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
        try {
            queued?.let { dao.saveJob(it.first.copy(status = "FAILED", stage = "启动失败",
                message = message, finishedAt = System.currentTimeMillis())) }
        } catch (_: Exception) {
            // A surviving QUEUED row is recovered as INTERRUPTED on process restart.
        }
        _state.update { it.copy(running = false, stage = "启动失败", message = message) }
    }

    suspend fun runDemo(id: String) {
        val queued = pending?.takeIf { it.first.id == id } ?: return
        pending = null
        val firmware = queued.second
        var record = queued.first
        try {
            dao.saveJob(record)
            record = record.copy(status = "RUNNING", stage = "探测模拟目标")
            dao.saveJob(record)
            val backend: FlashBackend = DemoFlashBackend(stepDelayMillis = 700)
            val target = backend.probe()
            val plan = FlashPlanner.plan(firmware.image, profile, target)
            record = record.copy(targetUid = target.uid)
            _state.update { it.copy(target = target, plan = plan) }
            backend.execute(plan) { event ->
                _state.update { it.copy(progress = event.fraction, stage = stageLabel(event.stage), message = event.message) }
            }
            record = record.copy(status = "SIMULATED", stage = "模拟完成", message = "模拟流程完成；未进行真实擦写或硬件校验")
        } catch (cancelled: CancellationException) {
            record = record.copy(status = "CANCELLED", stage = "已取消", message = "模拟任务已停止")
            _state.update { it.copy(stage = record.stage, message = record.message) }
            throw cancelled
        } catch (error: Exception) {
            record = record.copy(status = "FAILED", stage = "任务失败", message = error.message ?: "未知错误")
            _state.update { it.copy(stage = record.stage, message = record.message) }
        } finally {
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    dao.saveJob(record.copy(finishedAt = System.currentTimeMillis()))
                }
            } catch (error: Exception) {
                _state.update { it.copy(message = "记录保存失败：${error.message}；请勿将本次任务视为已归档") }
            }
            _state.update { it.copy(running = false) }
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
            })
        }
        val output = JSONObject().put("schemaVersion", 1).put("records", records).toString(2)
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(output.toByteArray()) }
            ?: error("无法写入导出文件")
    }

    companion object {
        private const val MAX_BYTES = 4 * 1024 * 1024
        fun stageLabel(stage: FlashStage) = when (stage) {
            FlashStage.CONNECTING -> "模拟连接"
            FlashStage.PROBING -> "模拟探测"
            FlashStage.ERASING -> "模拟擦除"
            FlashStage.WRITING -> "模拟写入"
            FlashStage.VERIFYING -> "模拟校验"
            FlashStage.RESETTING -> "模拟复位"
            FlashStage.SUCCEEDED -> "模拟完成"
        }
    }
}
