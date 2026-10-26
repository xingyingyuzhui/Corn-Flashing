package dev.corn.flashing.usb

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Discovery and permission. Claiming / SWD I/O lives in [StLinkUsbSession]. */
data class UsbProbe(
    val connectionId: Int,
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val permissionGranted: Boolean,
    val serial: String?,
    val candidate: Boolean,
)

class UsbProbeMonitor(private val context: Context) {
    private val manager = context.getSystemService(UsbManager::class.java)
    private val permissionAction = "${context.packageName}.USB_PERMISSION"
    private val _devices = MutableStateFlow<List<UsbProbe>>(emptyList())
    val devices = _devices.asStateFlow()
    private val _message = MutableStateFlow("USB 发现与授权；授权后可在设备页或工作台点「识别芯片」读真芯片")
    val message = _message.asStateFlow()
    private val _lastDenied = MutableStateFlow(false)
    /** True after the user denied permission for an ST-LINK candidate that is still attached. */
    val lastDenied = _lastDenied.asStateFlow()
    val hostSupported = context.packageManager.hasSystemFeature("android.hardware.usb.host")

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == permissionAction) {
                // Check UsbManager's actual grant; do not trust broadcast extras as authorization.
                val device: UsbDevice? = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                if (device != null && isStLink(device) && !manager.hasPermission(device)) {
                    _lastDenied.value = true
                    _message.value = "USB 授权被拒绝"
                } else {
                    if (device != null && manager.hasPermission(device)) _lastDenied.value = false
                    _message.value = "USB 授权结果已更新"
                }
            }
            refresh()
        }
    }

    init {
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(permissionAction)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
    }

    fun refresh() {
        val list = manager.deviceList.values.sortedBy { it.deviceId }.map { device ->
            val granted = manager.hasPermission(device)
            UsbProbe(
                device.deviceId, device.productName ?: "USB 设备",
                device.vendorId, device.productId, granted,
                if (granted) runCatching { device.serialNumber }.getOrNull() else null,
                isStLink(device),
            )
        }
        _devices.value = list
        // Clear the denial flag once every attached ST-LINK is authorized, or none remain.
        val candidates = list.filter { it.candidate }
        if (candidates.isEmpty() || candidates.all { it.permissionGranted }) {
            _lastDenied.value = false
        }
    }

    fun requestPermission(connectionId: Int) {
        val device = manager.deviceList.values.find { it.deviceId == connectionId }
            ?: return refresh()
        if (manager.hasPermission(device)) return refresh()
        val pending = PendingIntent.getBroadcast(
            context, connectionId,
            Intent(permissionAction).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.requestPermission(device, pending)
    }

    /** Request permission for the first unauthorized ST-LINK candidate, if any. */
    fun requestPermissionForDenied() {
        val target = _devices.value.firstOrNull { it.candidate && !it.permissionGranted } ?: return
        requestPermission(target.connectionId)
    }

    companion object {
        // Candidate IDs only. Matching a VID/PID is not a compatibility guarantee.
        private val STLINK_PRODUCTS = setOf(0x3748, 0x374B, 0x374A, 0x374D, 0x374E, 0x374F, 0x3753, 0x3754)
        private fun isStLink(device: UsbDevice) =
            device.vendorId == 0x0483 && device.productId in STLINK_PRODUCTS
    }
}
