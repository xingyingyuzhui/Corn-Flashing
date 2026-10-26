package dev.corn.flashing.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import dev.corn.flashing.core.TargetInfo
import dev.corn.flashing.core.stlink.StLinkProtocol
import dev.corn.flashing.core.stlink.StLinkVersion
import java.io.Closeable

/**
 * Claims the ST-LINK bulk interface and runs the identify sequence.
 * Does not erase or program Flash.
 */
class StLinkUsbSession private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val epOut: UsbEndpoint,
    private val epIn: UsbEndpoint,
) : Closeable {
    var version: StLinkVersion? = null
        private set
    var voltageMv: Int = 0
        private set

    fun identify(): TargetInfo {
        version = StLinkProtocol.parseVersion(xfer(StLinkProtocol.getVersion(), 6))
        val mode = StLinkProtocol.parseMode(xfer(StLinkProtocol.getCurrentMode(), 2))
        if (mode == StLinkProtocol.MODE_DFU) {
            xferOut(StLinkProtocol.exitDfu())
        } else if (mode == StLinkProtocol.MODE_DEBUG) {
            // Re-enter cleanly
            runCatching { xferOut(StLinkProtocol.exitDebug()) }
        }
        voltageMv = runCatching {
            StLinkProtocol.parseVoltageMv(xfer(StLinkProtocol.getTargetVoltage(), 8))
        }.getOrDefault(0)

        val enter = xfer(StLinkProtocol.enterSwd(), 2)
        StLinkProtocol.requireStatusOk(enter, "进入 SWD")

        // Newer firmware wants INIT_AP; ignore failures on older sticks.
        runCatching {
            val init = xfer(StLinkProtocol.initAp0(), 2)
            StLinkProtocol.requireStatusOk(init, "INIT_AP")
        }

        runCatching {
            StLinkProtocol.requireStatusOk(xfer(StLinkProtocol.haltCore(), 2), "停核")
        }

        val idCodes = xfer(StLinkProtocol.readIdCodes(), 12)
        StLinkProtocol.requireStatusOk(idCodes, "读 Core ID")
        // core id available for diagnostics; F1 identity comes from DBGMCU
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
        // Prefer 32-bit access when aligned; fall back to 8-bit for odd lengths.
        val aligned = address and 3L == 0L && length % 4 == 0
        val cmd = if (aligned) {
            StLinkProtocol.readMem32(address, length)
        } else {
            // 8-bit path: F2 0C addr le32 len le16
            val out = ByteArray(StLinkProtocol.CMD_SIZE)
            out[0] = StLinkProtocol.DEBUG_COMMAND.toByte()
            out[1] = StLinkProtocol.DEBUG_READMEM_8BIT.toByte()
            StLinkProtocol.writeU32Le(out, 2, address)
            StLinkProtocol.writeU16Le(out, 6, length)
            out
        }
        val data = xfer(cmd, length)
        // Check last RW status when possible (V2)
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
        val n = connection.bulkTransfer(epIn, buf, rxSize, TIMEOUT_MS)
        require(n == rxSize) { "ST-LINK 读应答失败：期望 $rxSize 字节，实际 $n" }
        return buf
    }

    private fun xferOut(cmd: ByteArray) {
        val n = connection.bulkTransfer(epOut, cmd, cmd.size, TIMEOUT_MS)
        require(n == cmd.size) { "ST-LINK 写命令失败：期望 ${cmd.size} 字节，实际 $n" }
    }

    override fun close() {
        runCatching { xferOut(StLinkProtocol.exitDebug()) }
        runCatching { connection.releaseInterface(usbInterface) }
        connection.close()
    }

    companion object {
        private const val TIMEOUT_MS = 3000

        fun open(manager: UsbManager, device: UsbDevice): StLinkUsbSession {
            require(manager.hasPermission(device)) { "尚未获得 USB 授权" }
            val connection = manager.openDevice(device) ?: error("无法打开 USB 设备")
            try {
                val iface = pickInterface(device)
                if (!connection.claimInterface(iface, true)) {
                    error("无法占用 ST-LINK 接口（可能被其他应用占用）")
                }
                val (epOut, epIn) = pickEndpoints(device, iface)
                return StLinkUsbSession(connection, iface, epOut, epIn)
            } catch (error: Throwable) {
                connection.close()
                throw error
            }
        }

        private fun pickInterface(device: UsbDevice): UsbInterface {
            // ST-LINK/V2: interface 0 holds the bulk pair we need.
            require(device.interfaceCount > 0) { "USB 设备没有接口" }
            return device.getInterface(0)
        }

        private fun pickEndpoints(device: UsbDevice, iface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint> {
            var out: UsbEndpoint? = null
            var inn: UsbEndpoint? = null
            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_OUT) out = ep
                if (ep.direction == UsbConstants.USB_DIR_IN) {
                    // Prefer 0x81; ignore extra IN endpoints (trace / swim)
                    if (inn == null || ep.address == 0x81) inn = ep
                }
            }
            // Classic V2 clones: OUT 0x02, IN 0x81. V2-1 / Nucleo: OUT 0x01, IN 0x81.
            require(out != null && inn != null) {
                "找不到 ST-LINK bulk 端点（pid=0x${device.productId.toString(16)}）"
            }
            return out to inn
        }
    }
}
