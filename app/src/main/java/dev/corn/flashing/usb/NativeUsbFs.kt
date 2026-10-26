package dev.corn.flashing.usb

import dev.corn.flashing.core.stlink.UsbKernelErrors
import java.io.IOException

/** Borrows Android's authorized connection FD. Never opens /dev/bus/usb or owns/closes the FD. */
internal object NativeUsbFs {
    private val loaded: Result<Unit> by lazy {
        runCatching {
            System.loadLibrary("corn_usbfs_diag")
            check(errorText(0).isNotEmpty()) { "Native errno decoder unavailable" }
            // Exercise JNI exports and errno results without opening or touching a USB device.
            check(bulk(-1, 2, ByteArray(16), 1).contentEquals(intArrayOf(-1, 9))) { "Native bulk self-test failed" }
            check(clearHalt(-1, 2).contentEquals(intArrayOf(-1, 9))) { "Native clear-halt self-test failed" }
        }
    }
    val available: Boolean get() = loaded.isSuccess
    val availabilityDescription: String get() = if (available) "USBFS 模块已加载，JNI/errno 无设备自检通过"
        else "USBFS 模块不可用：${loaded.exceptionOrNull()?.javaClass?.simpleName}: ${loaded.exceptionOrNull()?.message}"

    fun transfer(fd: Int, endpoint: Int, data: ByteArray, timeoutMs: Int): Int {
        check(available) { availabilityDescription }
        val result = bulk(fd, endpoint, data, timeoutMs)
        check(result.size == 2) { "USBFS 返回结构无效" }
        if (result[0] < 0) throw KernelUsbException(result[1], endpoint, data.size, errorText(result[1]))
        return result[0]
    }

    fun recoverHalt(fd: Int, endpoint: Int): String {
        check(available) { availabilityDescription }
        val result = clearHalt(fd, endpoint)
        check(result.size == 2) { "CLEAR_HALT 返回结构无效" }
        check(result[0] == 0) {
            "USBDEVFS_CLEAR_HALT ep=0x${endpoint.toString(16)} errno=${result[1]} ${UsbKernelErrors.name(result[1])}: ${errorText(result[1])}"
        }
        return "USBDEVFS_CLEAR_HALT ep=0x${endpoint.toString(16)} result=0"
    }

    private external fun bulk(fd: Int, endpoint: Int, buffer: ByteArray, timeoutMs: Int): IntArray
    private external fun clearHalt(fd: Int, endpoint: Int): IntArray
    private external fun errorText(errno: Int): String
}

internal class KernelUsbException(val code: Int, val endpoint: Int, requested: Int, detail: String) : IOException(
    "USBFS ${if (endpoint and 0x80 != 0) "IN" else "OUT"} ep=0x${endpoint.toString(16)} requested=$requested " +
        "ioctl=-1 errno=$code ${UsbKernelErrors.name(code)}: $detail；${UsbKernelErrors.explanation(code)}",
)
