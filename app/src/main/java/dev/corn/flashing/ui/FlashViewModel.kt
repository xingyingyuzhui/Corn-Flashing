package dev.corn.flashing.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
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
    val hostSupported = app.usb.hostSupported
    val profile = repository.profile
    val prefs = Prefs(application)
    val batch = batches.active
    val resumeOffer = batches.resumeOffer
    val batchList = batches.batches
    private val _notice = MutableStateFlow<String?>(null)
    val notice = _notice.asStateFlow()
    /** Set when a batch has ended; the UI opens its report. */
    private val _report = MutableStateFlow<String?>(null)
    val report = _report.asStateFlow()

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

    fun startSingle() {
        if (!batchGuard()) return
        viewModelScope.launch {
            val id = repository.prepareJob(singleRequest()) ?: return@launch
            if (!app.launcher.start(id)) repository.startFailed("系统拒绝启动任务服务")
        }
    }

    private var singleCounter = 0L

    /** Single runs use the default demo board; with fault injection on, some boards fail like in a batch. */
    private fun singleRequest(): dev.corn.flashing.storage.JobRequest {
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
        return dev.corn.flashing.storage.JobRequest(dev.corn.flashing.core.SimulatedBoards.Board(target, failure))
    }

    fun cancel() {
        app.startService(Intent(app, FlashService::class.java).setAction(FlashService.ACTION_CANCEL))
    }

    fun startBatch(config: BatchConfig, injectFaults: Boolean, onStarted: () -> Unit) {
        viewModelScope.launch {
            val error = batches.start(config, injectFaults)
            if (error == null) onStarted() else _notice.value = error
        }
    }

    fun pauseBatch() = batches.pause()
    fun resumeBatch() { viewModelScope.launch { batches.resume()?.let { _notice.value = it } } }
    fun dismissResumeOffer() = batches.dismissResumeOffer()
    fun endBatch() { viewModelScope.launch { _report.value = batches.end() } }
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

    /** Firmware and single runs are locked while a batch is open, so a batch never mixes inputs. */
    private fun batchGuard(): Boolean {
        if (batches.active.value == null) return true
        _notice.value = "批次未结束：结束批次后才能更换固件或单路烧录"
        return false
    }
}
