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
 * Claims the ST-LINK bulk interface, identifies the chip, and programs STM32F1 Flash
 * (page erase + half-word program + readback). Never touches option bytes / RDP.
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


    fun ensureDebug() {
        if (version == null) smokeGetVersion()
        val mode = StLinkProtocol.parseMode(xfer(StLinkProtocol.getCurrentMode(), 2))
        if (mode == StLinkProtocol.MODE_DFU) xferOut(StLinkProtocol.exitDfu())
        else if (mode == StLinkProtocol.MODE_DEBUG) runCatching { xferOut(StLinkProtocol.exitDebug()) }
        StLinkProtocol.requireStatusOk(xfer(StLinkProtocol.enterSwd(), 2), "进入 SWD")
        runCatching { StLinkProtocol.requireStatusOk(xfer(StLinkProtocol.initAp0(), 2), "INIT_AP") }
        runCatching { StLinkProtocol.requireStatusOk(xfer(StLinkProtocol.haltCore(), 2), "停核") }
    }

    /**
     * Erase [pages], program [image] segments, optionally verify and reset.
     * Caller must have already [identify]d or [ensureDebug].
     */
    fun programF1(
        image: dev.corn.flashing.core.FirmwareImage,
        pages: List<Long>,
        pageSize: Int,
        resetAfter: Boolean,
        verify: Boolean,
        onProgress: (dev.corn.flashing.core.FlashStage, Float, String) -> Unit,
    ) {
        ensureDebug()
        unlockFlash()
        try {
            val totalPages = pages.size.coerceAtLeast(1)
            pages.forEachIndexed { i, page ->
                onProgress(
                    dev.corn.flashing.core.FlashStage.ERASING,
                    0.25f + 0.20f * i / totalPages,
                    "擦除页 ${page.hex()}",
                )
                erasePage(page)
            }
            val segments = image.segments.sortedBy { it.address }
            val totalBytes = image.totalBytes.coerceAtLeast(1L)
            var written = 0L
            segments.forEach { segment ->
                programSegment(segment.address, segment.data) { chunk ->
                    written += chunk
                    onProgress(
                        dev.corn.flashing.core.FlashStage.WRITING,
                        0.45f + 0.30f * written.toFloat() / totalBytes,
                        "写入 ${written}/${totalBytes} 字节",
                    )
                }
            }
            if (verify) {
                onProgress(dev.corn.flashing.core.FlashStage.VERIFYING, 0.80f, "回读校验")
                segments.forEach { segment ->
                    verifySegment(segment.address, segment.data)
                }
                onProgress(dev.corn.flashing.core.FlashStage.VERIFYING, 0.90f, "回读校验通过")
            }
            if (resetAfter) {
                onProgress(dev.corn.flashing.core.FlashStage.RESETTING, 0.95f, "复位并运行")
                resetAndRun()
            }
            onProgress(dev.corn.flashing.core.FlashStage.SUCCEEDED, 1f, "烧录完成")
        } finally {
            runCatching { lockFlash() }
        }
    }

    private fun unlockFlash() {
        val cr = readMem32(StLinkProtocol.ADDR_FLASH_CR)
        if (cr and StLinkProtocol.FLASH_CR_LOCK.toLong() == 0L) return
        writeMem32Word(StLinkProtocol.ADDR_FLASH_KEYR, StLinkProtocol.FLASH_KEY1)
        writeMem32Word(StLinkProtocol.ADDR_FLASH_KEYR, StLinkProtocol.FLASH_KEY2)
        val after = readMem32(StLinkProtocol.ADDR_FLASH_CR)
        require(after and StLinkProtocol.FLASH_CR_LOCK.toLong() == 0L) {
            "无法解锁 Flash（可能读保护未解除，本应用不修改 Option Bytes）"
        }
    }

    private fun lockFlash() {
        val cr = readMem32(StLinkProtocol.ADDR_FLASH_CR)
        writeMem32Word(StLinkProtocol.ADDR_FLASH_CR, cr or StLinkProtocol.FLASH_CR_LOCK.toLong())
    }

    private fun erasePage(pageAddress: Long) {
        waitFlashNotBusy()
        clearFlashFlags()
        var cr = readMem32(StLinkProtocol.ADDR_FLASH_CR)
        cr = (cr and 0x7.inv()) or StLinkProtocol.FLASH_CR_PER.toLong()
        writeMem32Word(StLinkProtocol.ADDR_FLASH_CR, cr)
        writeMem32Word(StLinkProtocol.ADDR_FLASH_AR, pageAddress)
        cr = readMem32(StLinkProtocol.ADDR_FLASH_CR) or StLinkProtocol.FLASH_CR_STRT.toLong()
        writeMem32Word(StLinkProtocol.ADDR_FLASH_CR, cr)
        waitFlashNotBusy(timeoutMs = 5000)
        val sr = readMem32(StLinkProtocol.ADDR_FLASH_SR)
        require(sr and StLinkProtocol.FLASH_SR_WRPRTERR.toLong() == 0L) {
            "擦除页 ${pageAddress.hex()} 写保护错误"
        }
        clearFlashFlags()
        cr = readMem32(StLinkProtocol.ADDR_FLASH_CR) and StLinkProtocol.FLASH_CR_PER.toLong().inv()
        writeMem32Word(StLinkProtocol.ADDR_FLASH_CR, cr)
    }

    private fun programSegment(address: Long, data: ByteArray, onChunk: (Int) -> Unit) {
        var offset = 0
        while (offset < data.size) {
            waitFlashNotBusy()
            clearFlashFlags()
            var cr = readMem32(StLinkProtocol.ADDR_FLASH_CR)
            cr = (cr and 0x7.inv()) or StLinkProtocol.FLASH_CR_PG.toLong()
            writeMem32Word(StLinkProtocol.ADDR_FLASH_CR, cr)

            // F1 programs half-words only.
            val remaining = data.size - offset
            val chunk = if (remaining >= 2) 2 else 1
            val addr = address + offset
            if (chunk == 2) {
                val lo = data[offset].toInt() and 0xFF
                val hi = data[offset + 1].toInt() and 0xFF
                writeMemBytes(addr, byteArrayOf(lo.toByte(), hi.toByte()))
            } else {
                // Pad odd final byte with 0xFF (erased state) as the high byte.
                writeMemBytes(addr, byteArrayOf(data[offset], 0xFF.toByte()))
            }
            waitFlashNotBusy(timeoutMs = 2000)
            val sr = readMem32(StLinkProtocol.ADDR_FLASH_SR)
            require(sr and StLinkProtocol.FLASH_SR_PGERR.toLong() == 0L) {
                "编程失败 ${addr.hex()}：PGERR（目标可能未擦除或非半字对齐）"
            }
            require(sr and StLinkProtocol.FLASH_SR_WRPRTERR.toLong() == 0L) {
                "编程失败 ${addr.hex()}：写保护"
            }
            clearFlashFlags()
            cr = readMem32(StLinkProtocol.ADDR_FLASH_CR) and StLinkProtocol.FLASH_CR_PG.toLong().inv()
            writeMem32Word(StLinkProtocol.ADDR_FLASH_CR, cr)
            offset += chunk
            onChunk(chunk)
        }
    }

    private fun verifySegment(address: Long, expected: ByteArray) {
        var offset = 0
        while (offset < expected.size) {
            var len = minOf(256, expected.size - offset)
            val addr = address + offset
            if (addr and 3L == 0L && len >= 4) len = len and 3.inv()
            else if (addr and 1L != 0L) len = 1
            else if (len >= 2) len = len and 1.inv()
            val actual = readMemBytes(addr, len)
            for (i in actual.indices) {
                if (i >= expected.size - offset) break
                val exp = expected[offset + i]
                val got = actual[i]
                if (exp != got) {
                    val a = addr + i
                    error(
                        "校验失败：${a.hex()} 期望 0x%02X，读回 0x%02X".format(
                            exp.toInt() and 0xFF, got.toInt() and 0xFF,
                        ),
                    )
                }
            }
            offset += actual.size
        }
    }

    private fun resetAndRun() {
        runCatching { StLinkProtocol.requireStatusOk(xfer(StLinkProtocol.resetSys(), 2), "系统复位") }
        runCatching {
            StLinkProtocol.requireStatusOk(xfer(StLinkProtocol.driveNrst(StLinkProtocol.NRST_PULSE), 2), "NRST")
        }
        runCatching { StLinkProtocol.requireStatusOk(xfer(StLinkProtocol.unhaltCore(), 2), "解除停核") }
    }

    private fun waitFlashNotBusy(timeoutMs: Long = 3000) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (true) {
            val sr = readMem32(StLinkProtocol.ADDR_FLASH_SR)
            if (sr and StLinkProtocol.FLASH_SR_BSY.toLong() == 0L) return
            if (System.nanoTime() > deadline) error("等待 Flash 空闲超时")
            try {
                Thread.sleep(1)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                error("等待 Flash 被中断")
            }
        }
    }

    private fun clearFlashFlags() {
        writeMem32Word(StLinkProtocol.ADDR_FLASH_SR, StLinkProtocol.flashSrClearMask())
    }

    private fun readMem32(address: Long): Long {
        val data = readMemBytes(address, 4)
        return StLinkProtocol.readU32Le(data, 0)
    }

    private fun writeMem32Word(address: Long, value: Long) {
        val payload = ByteArray(4)
        StLinkProtocol.writeU32Le(payload, 0, value and 0xFFFF_FFFFL)
        writeMemBytes(address, payload)
    }

    private fun writeMemBytes(address: Long, data: ByteArray) {
        require(data.isNotEmpty()) { "写内存数据为空" }
        var offset = 0
        while (offset < data.size) {
            val len = minOf(1024, data.size - offset)
            val chunk = data.copyOfRange(offset, offset + len)
            val addr = address + offset
            val cmd = if (addr and 3L == 0L && len % 4 == 0) {
                StLinkProtocol.writeMem32(addr, len)
            } else {
                StLinkProtocol.writeMem8(addr, len)
            }
            xferOut(cmd)
            xferOutRaw(chunk)
            runCatching {
                val st = xfer(StLinkProtocol.getLastRwStatus(), 2)
                StLinkProtocol.requireStatusOk(st, "写内存状态")
            }
            offset += len
        }
    }

    private fun readMemBytes(address: Long, length: Int): ByteArray {
        require(length > 0) { "读长度必须 > 0" }
        val out = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val len = minOf(1024, length - offset)
            val addr = address + offset
            val aligned = addr and 3L == 0L && len % 4 == 0
            val cmd = if (aligned) {
                StLinkProtocol.readMem32(addr, len)
            } else {
                val c = ByteArray(StLinkProtocol.CMD_SIZE)
                c[0] = StLinkProtocol.DEBUG_COMMAND.toByte()
                c[1] = StLinkProtocol.DEBUG_READMEM_8BIT.toByte()
                StLinkProtocol.writeU32Le(c, 2, addr)
                StLinkProtocol.writeU16Le(c, 6, len)
                c
            }
            val data = xfer(cmd, len)
            System.arraycopy(data, 0, out, offset, len)
            runCatching {
                val st = xfer(StLinkProtocol.getLastRwStatus(), 2)
                StLinkProtocol.requireStatusOk(st, "读内存状态")
            }
            offset += len
        }
        return out
    }

    private fun xferOutRaw(data: ByteArray) {
        var sent = 0
        while (sent < data.size) {
            val n = connection.bulkTransfer(epOut, data, sent, data.size - sent, TIMEOUT_MS)
            require(n > 0) {
                "ST-LINK 写数据失败：实际 $n（$layoutLabel）"
            }
            sent += n
        }
    }

    private fun Long.hex(): String = "0x" + toString(16).uppercase().padStart(8, '0')



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
