package dev.corn.flashing.usb

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Discovery and permission only. No interface claiming or USB writes in the framework. */
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
    private val _message = MutableStateFlow("USB 设备仅用于发现与授权，真实烧录后端待接入")
    val message = _message.asStateFlow()
    val hostSupported = context.packageManager.hasSystemFeature("android.hardware.usb.host")

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == permissionAction) {
                // Check UsbManager's actual grant; do not trust broadcast extras as authorization.
                _message.value = "USB 授权结果已更新"
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
        _devices.value = manager.deviceList.values.sortedBy { it.deviceId }.map { device ->
            val granted = manager.hasPermission(device)
            UsbProbe(
                device.deviceId, device.productName ?: "USB 设备",
                device.vendorId, device.productId, granted,
                if (granted) runCatching { device.serialNumber }.getOrNull() else null,
                device.vendorId == 0x0483 && device.productId in STLINK_PRODUCTS,
            )
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

    companion object {
        // Candidate IDs only. Matching a VID/PID is not a compatibility guarantee.
        private val STLINK_PRODUCTS = setOf(0x3748, 0x374B, 0x374A, 0x374D, 0x374E, 0x374F, 0x3753, 0x3754)
    }
}
