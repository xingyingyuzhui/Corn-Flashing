package dev.corn.flashing.ui

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.corn.flashing.CornApplication
import dev.corn.flashing.core.BatchConfig
import dev.corn.flashing.service.FlashService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FlashViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as CornApplication
    private val repository = app.repository
    private val batches = app.batches
    val session = repository.state
    val history = repository.history
    val devices = app.usb.devices
    val usbMessage = app.usb.message
    val usbDenied = app.usb.lastDenied
    val hostSupported = app.usb.hostSupported
    val reservedSettings = app.reservedRegions.state
    val reservedPolicy get() = app.reservedRegions.policy(session.value.target)
    fun profileFor(target: dev.corn.flashing.core.TargetInfo) = repository.profileFor(target)
    val algorithmLibrary get() = app.algorithms
    val installedAlgorithms = app.algorithms.installed
    val algorithmBindings = app.algorithms.bindings
    val experimentalAlgorithmsEnabled = app.algorithms.experimentalEnabled
    val targetRegistry get() = app.algorithms.snapshot().targets
    val profile get() = repository.profile
    val prefs = Prefs(application)
    val batch = batches.active
    val resumeOffer = batches.resumeOffer
    val batchList = batches.batches
    private val _notice = MutableStateFlow<String?>(null)
    val notice = _notice.asStateFlow()
    /** Set when a batch has ended; the UI opens its report. */
    private val _report = MutableStateFlow<String?>(null)
    val report = _report.asStateFlow()
    private val _needNotificationPermission = MutableStateFlow(false)
    val needNotificationPermission = _needNotificationPermission.asStateFlow()

    init { refreshNotificationPermission() }

    fun clearNotice() { _notice.value = null }
    fun notify(message: String) { _notice.value = message }
    fun openReport(id: String?) { _report.value = id }
    fun loadDemo() { if (batchGuard()) viewModelScope.launch { repository.loadDemo() } }
    fun import(uri: Uri, addressText: String) {
        if (batchGuard()) viewModelScope.launch { repository.importFile(uri, addressText) }
    }
    fun clearImportError() = repository.clearImportError()
    fun refreshUsb() = app.usb.refresh()
    fun authorize(id: Int) {
        try { app.usb.requestPermission(id) }
        catch (error: Exception) { _notice.value = "USB 授权失败：${error.message}" }
    }
    fun authorizeDenied() {
        try { app.usb.requestPermissionForDenied() }
        catch (error: Exception) { _notice.value = "USB 授权失败：${error.message}" }
    }

    fun notificationsGranted(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun refreshNotificationPermission() {
        _needNotificationPermission.value = Build.VERSION.SDK_INT >= 33 && !notificationsGranted()
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        _needNotificationPermission.value = Build.VERSION.SDK_INT >= 33 && !granted
        if (!granted) _notice.value = "未授予通知权限：烧录进行中时通知栏看不到进度和取消按钮"
    }

    /**
     * Ensures notification permission is settled before starting work on API 33+.
     * Returns true when the caller should proceed; false when a permission prompt was launched.
     */
    fun ensureNotificationsThen(request: () -> Unit, proceed: () -> Unit) {
        if (notificationsGranted()) {
            proceed()
        } else {
            request()
        }
    }

    fun startSingle() {
        if (reservedSettings.value.error != null) { notify(reservedSettings.value.error!!); return }
        stopWatchingNextBoard()
        if (session.value.probing || !batchGuard()) return
        viewModelScope.launch {
            val id = repository.prepareJob(singleRequest()) ?: return@launch
            if (!app.launcher.start(id)) repository.startFailed("系统拒绝启动任务服务")
        }
    }

    private var probingJob: kotlinx.coroutines.Job? = null

    /** Identify the chip on the authorized ST-LINK without erasing. */
    fun probeChip() {
        if (probingJob?.isActive == true || session.value.running || session.value.probing) return
        probingJob = viewModelScope.launch {
            try {
                val target = repository.probeChip()
                _notice.value = "已识别 ${target.family} · ${target.flashSizeBytes / 1024} KB"
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _notice.value = "识别失败：${error.message ?: error.javaClass.simpleName}"
            }
        }
    }

    private val _algorithmCandidates = MutableStateFlow<List<dev.corn.flashing.pack.AlgorithmCandidate>>(emptyList())
    val algorithmCandidates = _algorithmCandidates.asStateFlow()
    private val _algorithmBusy = MutableStateFlow(false)
    val algorithmBusy = _algorithmBusy.asStateFlow()
    private fun algorithmChangeAllowed(): Boolean {
        if (session.value.running || session.value.probing || session.value.importing || batch.value != null || _algorithmBusy.value) {
            notify("请先结束烧录、识别、导入或批次，再修改算法")
            return false
        }
        stopWatchingNextBoard()
        return true
    }
    fun importAlgorithm(uri: Uri) {
        if (!algorithmChangeAllowed()) return
        _algorithmBusy.value = true
        viewModelScope.launch {
            try {
                _algorithmCandidates.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val name = app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: error("无法读取算法文件名")
                    val bytes = app.contentResolver.openInputStream(uri)?.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(out.size().toLong() + count <= 64L * 1024 * 1024) { "算法包最大64MiB" }
                            out.write(buffer, 0, count)
                        }
                        out.toByteArray()
                    } ?: error("无法打开算法包")
                    app.algorithms.candidates(name, bytes)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                notify("导入算法失败：${error.message}")
            } finally { _algorithmBusy.value = false }
        }
    }
    fun dismissAlgorithmCandidates() { _algorithmCandidates.value = emptyList() }
    fun installAlgorithm(candidate: dev.corn.flashing.pack.AlgorithmCandidate) {
        if (!algorithmChangeAllowed()) return
        _algorithmBusy.value = true
        viewModelScope.launch {
            try {
                val item = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.algorithms.install(candidate) }
                dismissAlgorithmCandidates()
                notify("算法已导入：${item.name}。尚未绑定芯片，不会执行擦写。")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                notify("算法解析失败：${error.message}")
            } finally { _algorithmBusy.value = false }
        }
    }
    fun setExperimentalAlgorithmsEnabled(enabled: Boolean) {
        if (!algorithmChangeAllowed()) return
        app.algorithms.setExperimentalEnabled(enabled)
        if (!enabled) notify("外部 FLM 实验执行已关闭：已保存的绑定停用，恢复内置支持。")
    }
    fun bindAlgorithm(hash: String, expectedTarget: dev.corn.flashing.core.TargetInfo) {
        if (!algorithmChangeAllowed()) return
        try {
            val target = requireNotNull(session.value.target) { "请先识别实际芯片" }
            require(target == expectedTarget) { "确认期间目标已变化，请重新核对后绑定" }
            require(!prefs.simulator) { "请关闭模拟模式并识别实际芯片" }
            app.algorithms.bind(hash, target)
            notify("已为设备组0x${target.deviceId.toString(16)}绑定此算法。首次实板使用仍需验证。")
        } catch (error: Exception) { notify("绑定算法失败：${error.message}") }
    }
    fun restoreBuiltInAlgorithm() {
        if (!algorithmChangeAllowed()) return
        try { session.value.target?.let { app.algorithms.useBuiltIn(it.deviceId) } }
        catch (error: Exception) { notify("恢复算法失败：${error.message}") }
    }
    fun removeAlgorithm(hash: String) {
        if (!algorithmChangeAllowed()) return
        try { app.algorithms.remove(hash) }
        catch (error: Exception) { notify("移除算法失败：${error.message}") }
    }

    private fun reservedChangeAllowed(): Boolean {
        if (session.value.running || session.value.probing || batch.value != null || algorithmBusy.value) {
            notify("请先结束烧录、识别或批次，再修改保留区")
            return false
        }
        stopWatchingNextBoard()
        return true
    }
    fun setReservedPolicy(enabled: Boolean, ranges: List<dev.corn.flashing.core.AddressRange>) {
        if (!reservedChangeAllowed()) return
        try {
            val target = requireNotNull(session.value.target) { "请先识别目标芯片" }
            val descriptor = requireNotNull(targetRegistry.find(target.deviceId)) { "目标芯片未登记" }
            app.reservedRegions.update(target, descriptor, dev.corn.flashing.core.ReservedRegionPolicy(enabled, ranges))
        } catch (error: Exception) { notify("保留区设置失败：${error.message}") }
    }
    fun resetReservedSettings() {
        if (!reservedChangeAllowed()) return
        try { app.reservedRegions.reset() } catch (error: Exception) { notify("重置保留区失败：${error.message}") }
    }

    private var singleCounter = 0L

    /** Simulator path uses demo boards; hardware path leaves the board blank for the ST-LINK backend. */
    private fun singleRequest(): dev.corn.flashing.storage.JobRequest {
        val options = dev.corn.flashing.core.FlashExecuteOptions(
            resetAfter = prefs.resetAfter,
            eraseMode = prefs.eraseMode,
            verifyMode = prefs.verifyMode,
        )
        if (!prefs.simulator) {
            return dev.corn.flashing.storage.JobRequest(useSimulator = false, executeOptions = options)
        }
        singleCounter++
        val target = dev.corn.flashing.core.DemoFlashBackend.DEFAULT_TARGET.copy(
            uid = "DEMO-%08X".format(0x35FF_D605L + singleCounter * 0x10F))
        // Fixed cycle so a demo shows every result: boards 3 and 7 fail verify, board 9 has no target.
        val roll = when ((singleCounter % 10).toInt()) { 3, 7 -> 0; 9 -> 3; else -> 9 }
        val failure = when {
            !prefs.injectFaults -> null
            roll in 0..2 -> dev.corn.flashing.core.DemoFailure(dev.corn.flashing.core.FlashStage.VERIFYING, "模拟校验失败：0x08003C00 期望 0x4A，读回 0xFF")
            roll == 3 -> dev.corn.flashing.core.DemoFailure(dev.corn.flashing.core.FlashStage.PROBING, "模拟目标未响应：目标电压 0.00 V")
            else -> null
        }
        return dev.corn.flashing.storage.JobRequest(
            board = dev.corn.flashing.core.SimulatedBoards.Board(target, failure),
            useSimulator = true,
            executeOptions = options,
        )
    }

    fun cancel() {
        app.startService(Intent(app, FlashService::class.java).setAction(FlashService.ACTION_CANCEL))
    }

    fun startBatch(config: BatchConfig, injectFaults: Boolean, onStarted: () -> Unit) {
        viewModelScope.launch {
            val error = batches.start(config, injectFaults, useSimulator = prefs.simulator)
            if (error == null) onStarted() else _notice.value = error
        }
    }

    fun pauseBatch() = batches.pause()
    fun resumeBatch() { viewModelScope.launch { batches.resume()?.let { _notice.value = it } } }
    fun dismissResumeOffer() = batches.dismissResumeOffer()
    fun endBatch() {
        viewModelScope.launch {
            val id = batches.end()
            if (id != null) _report.value = id
            else batches.active.value?.notice?.let { _notice.value = it }
        }
    }
    fun batchRows(id: String) = batches.rows(id)
    fun batchById(id: String) = batches.batch(id)
    fun batchConfig(entity: dev.corn.flashing.storage.BatchEntity) = batches.config(entity)

    fun export(uri: Uri) = exporting("记录已导出") { repository.exportHistory(uri) }
    fun exportCsv(batchId: String, uri: Uri) = exporting("批次报告已导出") { batches.exportCsv(batchId, uri) }

    private fun exporting(done: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block(); _notice.value = done }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                _notice.value = "导出失败：${error.message}"
            }
        }
    }


    private val nextBoardWatcher = NextBoardWatcher(viewModelScope)

    /** Called only while the successful result page is visible and the app is resumed. */
    fun watchNextBoard(jobId: String, previousUid: String?) {
        nextBoardWatcher.start(
            jobId = jobId,
            previousUid = previousUid,
            canContinue = {
                val current = session.value
                prefs.autoNextBoard && !prefs.simulator && !current.running &&
                    current.job?.id == jobId && current.job?.status == "SUCCEEDED" &&
                    batches.active.value == null
            },
            probeUid = { repository.probeChip().uid },
            onNewBoard = { uid ->
                _notice.value = "检测到新板 $uid，开始烧录"
                startSingle()
            },
            onTimeout = { _notice.value = "等待新板超时：请手动点「立即再烧一块」" },
        )
    }

    fun stopWatchingNextBoard() = nextBoardWatcher.stop()

    /** Firmware and single runs are locked while a batch is open, so a batch never mixes inputs. */
    private fun batchGuard(): Boolean {
        if (batches.active.value == null) return true
        _notice.value = "批次未结束：结束批次后才能更换固件或单路烧录"
        return false
    }
}
