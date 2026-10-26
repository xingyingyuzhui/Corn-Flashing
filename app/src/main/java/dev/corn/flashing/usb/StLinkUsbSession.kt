package dev.corn.flashing.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import dev.corn.flashing.core.TargetInfo
import dev.corn.flashing.core.stlink.StLinkProtocol
import dev.corn.flashing.core.stlink.StLinkUsbLayout
import dev.corn.flashing.core.stlink.StLinkVersion
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Claims the ST-LINK bulk interface and runs the identify sequence.
 * Does not erase or program Flash.
 */
class StLinkUsbSession private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val epOut: UsbEndpoint,
    private val epIn: UsbEndpoint,
    private val layoutLabel: String,
) : Closeable {
    var version: StLinkVersion? = null
        private set
    var voltageMv: Int = 0
        private set

    /** First GET_VERSION after open — proves bulk I/O works. */
    fun smokeGetVersion() {
        version = StLinkProtocol.parseVersion(xfer(StLinkProtocol.getVersion(), 6))
    }

    fun identify(): TargetInfo {
        if (version == null) smokeGetVersion()
        val mode = StLinkProtocol.parseMode(xfer(StLinkProtocol.getCurrentMode(), 2))
        if (mode == StLinkProtocol.MODE_DFU) {
            xferOut(StLinkProtocol.exitDfu())
        } else if (mode == StLinkProtocol.MODE_DEBUG) {
            runCatching { xferOut(StLinkProtocol.exitDebug()) }
        }
        voltageMv = runCatching {
            StLinkProtocol.parseVoltageMv(xfer(StLinkProtocol.getTargetVoltage(), 8))
        }.getOrDefault(0)

        val enter = xfer(StLinkProtocol.enterSwd(), 2)
        StLinkProtocol.requireStatusOk(enter, "进入 SWD")

        runCatching {
            val init = xfer(StLinkProtocol.initAp0(), 2)
            StLinkProtocol.requireStatusOk(init, "INIT_AP")
        }

        runCatching {
            StLinkProtocol.requireStatusOk(xfer(StLinkProtocol.haltCore(), 2), "停核")
        }

        val idCodes = xfer(StLinkProtocol.readIdCodes(), 12)
        StLinkProtocol.requireStatusOk(idCodes, "读 Core ID")
        StLinkProtocol.parseIdCodes(idCodes)

        val idcodeWord = readMem32(StLinkProtocol.ADDR_DBGMCU_IDCODE)
        val (deviceId, revisionId) = StLinkProtocol.parseDbgmcuIdCode(idcodeWord)
        require(deviceId != 0) { "DBGMCU_IDCODE 无效，请检查 SWD 接线与目标供电" }

        val flashWord = readMem32(StLinkProtocol.ADDR_FLASH_SIZE)
        val flashBytes = StLinkProtocol.flashSizeBytesFromRegister(flashWord)

        val uidBytes = readMemBytes(StLinkProtocol.ADDR_UID, 12)
        val uid = StLinkProtocol.formatUid(uidBytes)

        return TargetInfo(
            deviceId = deviceId,
            revisionId = revisionId,
            flashSizeBytes = flashBytes,
            uid = uid,
            family = StLinkProtocol.familyLabel(deviceId),
        )
    }

    private fun readMem32(address: Long): Long {
        val data = readMemBytes(address, 4)
        return StLinkProtocol.readU32Le(data, 0)
    }

    private fun readMemBytes(address: Long, length: Int): ByteArray {
        val aligned = address and 3L == 0L && length % 4 == 0
        val cmd = if (aligned) {
            StLinkProtocol.readMem32(address, length)
        } else {
            val out = ByteArray(StLinkProtocol.CMD_SIZE)
            out[0] = StLinkProtocol.DEBUG_COMMAND.toByte()
            out[1] = StLinkProtocol.DEBUG_READMEM_8BIT.toByte()
            StLinkProtocol.writeU32Le(out, 2, address)
            StLinkProtocol.writeU16Le(out, 6, length)
            out
        }
        val data = xfer(cmd, length)
        runCatching {
            val st = xfer(StLinkProtocol.getLastRwStatus(), 2)
            StLinkProtocol.requireStatusOk(st, "读内存状态")
        }
        return data
    }

    private fun xfer(cmd: ByteArray, rxSize: Int): ByteArray {
        xferOut(cmd)
        if (rxSize <= 0) return ByteArray(0)
        val buf = ByteArray(rxSize)
        val n = bulkIn(buf, rxSize)
        require(n == rxSize) {
            "ST-LINK 读应答失败：期望 $rxSize 字节，实际 $n（$layoutLabel）"
        }
        return buf
    }

    private fun xferOut(cmd: ByteArray) {
        var n = connection.bulkTransfer(epOut, cmd, cmd.size, TIMEOUT_MS)
        if (n < 0) {
            try {
                Thread.sleep(RETRY_SLEEP_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            n = connection.bulkTransfer(epOut, cmd, cmd.size, TIMEOUT_MS)
        }
        if (n < 0) {
            n = bulkOutViaRequest(cmd)
        }
        require(n == cmd.size) {
            "ST-LINK 写命令失败：期望 ${cmd.size} 字节，实际 $n（$layoutLabel）。" +
                "请拔插 ST-LINK 后重试，确认没有电脑端占用"
        }
    }

    private fun bulkIn(buf: ByteArray, length: Int): Int {
        var n = connection.bulkTransfer(epIn, buf, length, TIMEOUT_MS)
        if (n < 0) {
            try {
                Thread.sleep(RETRY_SLEEP_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            n = connection.bulkTransfer(epIn, buf, length, TIMEOUT_MS)
        }
        return n
    }

    /** Fallback when [UsbDeviceConnection.bulkTransfer] returns -1 (Android gives no errno). */
    private fun bulkOutViaRequest(cmd: ByteArray): Int {
        val req = UsbRequest()
        return try {
            if (!req.initialize(connection, epOut)) return -1
            val buf = ByteBuffer.allocate(cmd.size).order(ByteOrder.LITTLE_ENDIAN)
            buf.put(cmd)
            buf.rewind()
            if (!req.queue(buf)) return -1
            val done = connection.requestWait() ?: return -1
            if (done !== req) return -1
            buf.position().coerceAtLeast(0)
        } catch (_: Exception) {
            -1
        } finally {
            runCatching { req.close() }
        }
    }

    override fun close() {
        runCatching { xferOut(StLinkProtocol.exitDebug()) }
        runCatching { connection.releaseInterface(usbInterface) }
        connection.close()
    }

    companion object {
        private const val TIMEOUT_MS = 3000
        private const val RETRY_SLEEP_MS = 80L

        fun open(manager: UsbManager, device: UsbDevice): StLinkUsbSession {
            require(manager.hasPermission(device)) { "尚未获得 USB 授权" }
            val connection = manager.openDevice(device)
                ?: error("无法打开 USB 设备（openDevice 返回 null）")
            var handedOff = false
            try {
                selectConfiguration(connection, device)
                val (iface, epOut, epIn, label) = pickBulk(device)
                if (!connection.claimInterface(iface, true)) {
                    error(
                        "无法占用 ST-LINK 接口（$label）。请拔插 ST-LINK 后重试，确认没有电脑端占用",
                    )
                }
                val session = StLinkUsbSession(connection, iface, epOut, epIn, label)
                try {
                    session.smokeGetVersion()
                } catch (error: Throwable) {
                    runCatching { session.close() }
                    handedOff = true // connection already closed by session.close()
                    throw IllegalStateException(
                        "ST-LINK 无法通信（$label）：${error.message ?: error.javaClass.simpleName}。" +
                            "请拔插 ST-LINK 后重试，确认没有电脑端占用",
                        error,
                    )
                }
                handedOff = true
                return session
            } catch (error: Throwable) {
                if (!handedOff) runCatching { connection.close() }
                throw error
            }
        }

        /** Many Android hosts fail bulkTransfer with -1 if configuration was never selected. */
        private fun selectConfiguration(connection: UsbDeviceConnection, device: UsbDevice) {
            if (device.configurationCount <= 0) return
            val config = device.getConfiguration(0)
            // false often means "already this configuration" — not fatal.
            connection.setConfiguration(config)
        }

        private data class BulkPick(
            val iface: UsbInterface,
            val epOut: UsbEndpoint,
            val epIn: UsbEndpoint,
            val label: String,
        )

        private fun pickBulk(device: UsbDevice): BulkPick {
            require(device.interfaceCount > 0) { "USB 设备没有接口" }
            val scanned = (0 until device.interfaceCount).map { idx ->
                val iface = device.getInterface(idx)
                val eps = (0 until iface.endpointCount).mapNotNull { e ->
                    val ep = iface.getEndpoint(e)
                    if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) return@mapNotNull null
                    StLinkUsbLayout.BulkEp(
                        address = ep.address and 0xFF,
                        directionOut = ep.direction == UsbConstants.USB_DIR_OUT,
                    )
                }
                idx to eps
            }
            val chosen = StLinkUsbLayout.pick(device.productId, scanned)
            val iface = device.getInterface(chosen.interfaceIndex)
            val epOut = findEp(iface, chosen.outAddr, UsbConstants.USB_DIR_OUT)
                ?: error("接口上找不到 OUT 0x${chosen.outAddr.toString(16)}")
            val epIn = findEp(iface, chosen.inAddr, UsbConstants.USB_DIR_IN)
                ?: error("接口上找不到 IN 0x${chosen.inAddr.toString(16)}")
            val label =
                "pid=0x${device.productId.toString(16)} cfg=0 ${chosen.describe()}"
            return BulkPick(iface, epOut, epIn, label)
        }

        private fun findEp(iface: UsbInterface, address: Int, direction: Int): UsbEndpoint? {
            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction != direction) continue
                if ((ep.address and 0xFF) == (address and 0xFF)) return ep
            }
            return null
        }
    }
}
