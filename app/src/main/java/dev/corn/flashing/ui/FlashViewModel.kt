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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
        if (!batchGuard()) return
        viewModelScope.launch {
            val id = repository.prepareJob(singleRequest()) ?: return@launch
            if (!app.launcher.start(id)) repository.startFailed("系统拒绝启动任务服务")
        }
    }

    /** Identify the chip on the authorized ST-LINK without erasing. */
    fun probeChip() {
        viewModelScope.launch {
            try {
                val target = repository.probeChip()
                _notice.value = "已识别 ${target.family} · ${target.flashSizeBytes / 1024} KB"
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _notice.value = "识别失败：${error.message}"
            }
        }
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


    private var nextBoardJob: Job? = null

    /** After a real success, poll probe until UID changes then start the next single run. */
    fun watchNextBoard(previousUid: String?) {
        nextBoardJob?.cancel()
        nextBoardJob = viewModelScope.launch {
            repeat(90) { // ~3 minutes at 2s
                delay(2000)
                if (session.value.running) return@launch
                if (batches.active.value != null) return@launch
                try {
                    val target = repository.probeChip()
                    val uid = target.uid
                    if (uid != null && uid != previousUid) {
                        _notice.value = "检测到新板 $uid，开始烧录"
                        startSingle()
                        return@launch
                    }
                } catch (_: Exception) {
                    // keep waiting
                }
            }
            _notice.value = "等待新板超时：请手动点「立即再烧一块」"
        }
    }

    /** Firmware and single runs are locked while a batch is open, so a batch never mixes inputs. */
    private fun batchGuard(): Boolean {
        if (batches.active.value == null) return true
        _notice.value = "批次未结束：结束批次后才能更换固件或单路烧录"
        return false
    }
}
