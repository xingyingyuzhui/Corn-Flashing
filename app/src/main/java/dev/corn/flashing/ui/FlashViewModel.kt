package dev.corn.flashing.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.corn.flashing.CornApplication
import dev.corn.flashing.service.FlashService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FlashViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as CornApplication
    private val repository = app.repository
    val session = repository.state
    val history = repository.history
    val devices = app.usb.devices
    val usbMessage = app.usb.message
    val hostSupported = app.usb.hostSupported
    val profile = repository.profile
    private val _notice = MutableStateFlow<String?>(null)
    val notice = _notice.asStateFlow()

    fun clearNotice() { _notice.value = null }
    fun loadDemo() { viewModelScope.launch { repository.loadDemo() } }
    fun import(uri: Uri, addressText: String) {
        val address = addressText.trim().removePrefix("0x").removePrefix("0X").toLongOrNull(16)
        // Only BIN needs this value; malformed input must not block HEX imports.
        val validAddress = address?.takeIf { it in 0..0xFFFFFFFFL }
        viewModelScope.launch { repository.importFile(uri, validAddress) }
    }
    fun refreshUsb() = app.usb.refresh()
    fun authorize(id: Int) {
        try { app.usb.requestPermission(id) }
        catch (error: Exception) { _notice.value = "USB 授权失败：${error.message}" }
    }
    fun startDemo() {
        viewModelScope.launch {
            val id = repository.prepareDemo() ?: return@launch
            try {
                ContextCompat.startForegroundService(app, Intent(app, FlashService::class.java).putExtra(FlashService.EXTRA_JOB_ID, id))
            } catch (error: Exception) { repository.startFailed("无法启动任务服务：${error.message}") }
        }
    }
    fun cancel() {
        app.startService(Intent(app, FlashService::class.java).setAction(FlashService.ACTION_CANCEL))
    }
    fun export(uri: Uri) {
        viewModelScope.launch {
            try { repository.exportHistory(uri); _notice.value = "记录已导出" }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                _notice.value = "导出失败：${error.message}"
            }
        }
    }
}
