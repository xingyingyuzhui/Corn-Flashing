package dev.corn.flashing.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import dev.corn.flashing.core.TargetInfo
import dev.corn.flashing.core.FlashPlan
import dev.corn.flashing.core.FlashExecuteOptions
import dev.corn.flashing.core.stlink.F1FlashProgrammer
import dev.corn.flashing.core.stlink.F1TargetMemory
import dev.corn.flashing.core.stlink.F1RamLoader
import dev.corn.flashing.core.stlink.F1LoaderTarget
import dev.corn.flashing.core.stlink.StLinkProtocol
import dev.corn.flashing.core.stlink.StLinkUsbLayout
import dev.corn.flashing.core.stlink.StLinkVersion
import dev.corn.flashing.core.stlink.StLinkBulkTransfers
import android.util.Log
import android.os.Build
import dev.corn.flashing.core.stlink.UsbKernelErrors
import java.io.Closeable

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
    private val io: AndroidUsbBulkIo,
) : Closeable {
    private val transfers = StLinkBulkTransfers(io, layoutLabel)
    var version: StLinkVersion? = null
        private set
    var voltageMv: Int = 0
        private set

    /** First GET_VERSION after open — proves bulk I/O works. */
    fun smokeGetVersion() {
        val raw = xfer(StLinkProtocol.getVersion(), 6)
        val parsed = StLinkProtocol.parseVersion(raw)
        require(parsed.stlink in 2..3 && parsed.jtag > 0) {
            "GET_VERSION 应答异常：${raw.joinToString(" ") { "%02X".format(it.toInt() and 255) }}"
        }
        version = parsed
        Log.i("CornUsb", "GET_VERSION V${parsed.stlink}J${parsed.jtag}S${parsed.swim}")
    }

    fun identify(): TargetInfo {
        if (version == null) smokeGetVersion()
        val mode = StLinkProtocol.parseMode(xfer(StLinkProtocol.getCurrentMode(), 2))
        if (mode == StLinkProtocol.MODE_DFU) {
            xferOut(StLinkProtocol.exitDfu())
        } else if (mode == StLinkProtocol.MODE_DEBUG) {
            xferOut(StLinkProtocol.exitDebug())
        }
        // Optional telemetry is deliberately not queried on this compatibility build.
        // Some V2 probes advertise firmware support but fail F7; do not poison the SWD session.
        // Zero means unmeasured, never zero-voltage or a disconnected target.
        voltageMv = 0

        val enter = xfer(StLinkProtocol.enterSwd(), 2)
        StLinkProtocol.requireStatusOk(enter, "进入 SWD")

        // Identification and automatic board polling must not halt a running application.
        initializeAp()

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
        else if (mode == StLinkProtocol.MODE_DEBUG) xferOut(StLinkProtocol.exitDebug())
        StLinkProtocol.requireStatusOk(xfer(StLinkProtocol.enterSwd(), 2), "进入 SWD")
        initializeApAndHalt()
    }

    private fun initializeAp() {
        if (version?.needsApInit == true) {
            StLinkProtocol.requireStatusOk(xfer(StLinkProtocol.initAp0(), 2), "INIT_AP")
        }
    }

    private fun initializeApAndHalt() {
        initializeAp()
        StLinkProtocol.requireStatusOk(xfer(StLinkProtocol.haltCore(), 2), "停核")
        val deadline = System.nanoTime() + 500_000_000L
        while (readMem32(StLinkProtocol.ADDR_DHCSR) and (1L shl 17) == 0L) {
            check(System.nanoTime() < deadline) { "目标停核超时，未执行擦除" }
            Thread.sleep(1)
        }
    }

    fun programF1(
        plan: FlashPlan,
        pages: List<Long>,
        options: FlashExecuteOptions,
        checkCancelled: () -> Unit,
        onProgress: (dev.corn.flashing.core.FlashStage, Float, String) -> Unit,
    ) {
        val probeVersion = requireNotNull(version) { "探针版本未知，未执行擦除" }
        val useRamLoader = !probeVersion.supportsHalfWordWrite
        if (useRamLoader) {
            require(probeVersion.stlink == 2 && probeVersion.jtag >= 13) {
                "旧版 RAM 编程路径需要 ST-LINK/V2 J13 或更高；未执行擦除"
            }
        } else probeVersion.requireF1ProgrammingSupport()
        checkCancelled()
        ensureDebug()
        val memory = object : F1TargetMemory {
            override fun readWord(address: Long) = readMem32(address)
            override fun writeWord(address: Long, value: Long) = writeMem32Word(address, value)
            override fun writeHalfWord(address: Long, value: Int) {
                val data = byteArrayOf(value.toByte(), (value ushr 8).toByte())
                xferOut(StLinkProtocol.writeMem16(address, 2))
                xferOutRaw(data)
                requireMemoryStatus("半字写入 ${address.hex()}")
            }
            override fun readBytes(address: Long, length: Int) = readMemBytes(address, length)
            override fun resetAndRun() {
                // Explicit AIRCR SYSRESETREQ works even when ST-LINK NRST is not wired.
                // The probe's F2 32 reset command may only pulse external reset on old V2 firmware.
                val demcr = readMem32(0xE000EDFCL)
                var resetFailure: Throwable? = null
                try {
                    writeMem32Word(0xE000EDFCL, demcr and 1L.inv()) // Clear reset vector catch.
                    writeMem32Word(0xE000ED30L, 0x1F) // Clear stale debug fault/ BKPT flags (W1C).
                    readMem32(StLinkProtocol.ADDR_DHCSR) // Clear sticky S_RESET_ST before reset.
                    writeMem32Word(0xE000ED0CL, 0x05FA0004L)
                    val deadline = System.nanoTime() + 500_000_000L
                    while (readMem32(StLinkProtocol.ADDR_DHCSR) and (1L shl 25) != 0L) {
                        check(System.nanoTime() < deadline) { "软件复位未完成" }
                        Thread.sleep(1)
                    }
                    StLinkProtocol.requireStatusOk(xfer(StLinkProtocol.unhaltCore(), 2), "解除停核")
                    val runDeadline = System.nanoTime() + 500_000_000L
                    while (readMem32(StLinkProtocol.ADDR_DHCSR) and (1L shl 17) != 0L) {
                        check(System.nanoTime() < runDeadline) { "复位后目标仍停核" }
                        Thread.sleep(1)
                    }
                    Log.i("CornUsb", "AIRCR SYSRESETREQ completed; target running")
                } catch (error: Throwable) {
                    resetFailure = error
                    throw error
                } finally {
                    try { writeMem32Word(0xE000EDFCL, demcr) } catch (cleanup: Throwable) {
                        if (resetFailure != null) resetFailure.addSuppressed(cleanup) else throw cleanup
                    }
                }
            }
        }
        val loader = if (useRamLoader) F1RamLoader(loaderTarget(), checkCancelled) else null
        Log.i("CornUsb", "F1 programming: ${if (useRamLoader) "RAM loader" else "native half-word"} ${probeVersion.label}")
        val debugControl = if (useRamLoader) readMem32(0xE0042004L) else null
        var programmingFailure: Throwable? = null
        try {
            if (debugControl != null) {
                // F1 DBGMCU_CR: freeze IWDG/WWDG while the core waits for USB between chunks.
                writeMem32Word(0xE0042004L, debugControl or 0x300L)
                check(readMem32(0xE0042004L) and 0x300L == 0x300L) { "无法冻结调试态看门狗；未执行擦除" }
            }
            F1FlashProgrammer(memory, ramLoader = loader, checkCancelled = checkCancelled)
                .execute(plan, pages, options, onProgress)
        } catch (error: Throwable) {
            programmingFailure = error
            Log.e("CornUsb", "F1 programming failed", error)
            throw error
        } finally {
            if (debugControl != null) try {
                writeMem32Word(0xE0042004L, debugControl)
            } catch (cleanup: Throwable) {
                if (programmingFailure != null) programmingFailure.addSuppressed(cleanup) else throw cleanup
            }
        }
    }

    /** Cortex-M3 debug register transfer handshake; no newer ST-LINK opcodes required. */
    private fun loaderTarget(): F1LoaderTarget = object : F1LoaderTarget {
        override fun readWord(address: Long) = readMem32(address)
        override fun writeRam(address: Long, data: ByteArray) {
            require(address >= 0x20000000L && address + data.size <= 0x20000400L &&
                address % 4 == 0L && data.size % 4 == 0) { "RAM loader 上传范围/对齐无效" }
            writeMemBytes(address, data)
        }
        override fun readBytes(address: Long, length: Int) = readMemBytes(address, length)
        override fun readCoreRegister(index: Int): Long {
            require(index in 0..16)
            check(isHalted()) { "读核心寄存器 R$index 前目标未停核；RCC_CSR=0x${readMem32(0x40021024L).toString(16)} DBGMCU_CR=0x${readMem32(0xE0042004L).toString(16)}" }
            writeMem32Word(0xE000EDF4L, index.toLong())
            waitRegisterReady()
            return readMem32(0xE000EDF8L)
        }
        override fun writeCoreRegister(index: Int, value: Long) {
            require(index in 0..16)
            check(isHalted()) { "写核心寄存器前目标未停核" }
            writeMem32Word(0xE000EDF8L, value)
            writeMem32Word(0xE000EDF4L, 0x10000L or index.toLong())
            waitRegisterReady()
        }
        override fun resume() {
            check(isHalted()) { "RAM loader 启动前目标未停核" }
            // C_MASKINTS must change while halted; keep application ISRs out of the loader.
            writeMem32Word(StLinkProtocol.ADDR_DHCSR, 0xA05F000BL)
            writeMem32Word(StLinkProtocol.ADDR_DHCSR, 0xA05F0009L)
        }
        override fun halt() {
            writeMem32Word(StLinkProtocol.ADDR_DHCSR, 0xA05F000BL)
            val deadline = System.nanoTime() + 500_000_000L
            while (!isHalted()) {
                check(System.nanoTime() < deadline) { "RAM loader 停核超时" }
                Thread.sleep(1)
            }
            writeMem32Word(StLinkProtocol.ADDR_DHCSR, 0xA05F0003L)
        }
        override fun isHalted() = readMem32(StLinkProtocol.ADDR_DHCSR) and (1L shl 17) != 0L
        private fun waitRegisterReady() {
            val deadline = System.nanoTime() + 500_000_000L
            while (readMem32(StLinkProtocol.ADDR_DHCSR) and (1L shl 16) == 0L) {
                check(System.nanoTime() < deadline) { "核心寄存器传输超时" }
                Thread.sleep(1)
            }
        }
    }

    private fun requireMemoryStatus(label: String) {
        StLinkProtocol.requireStatusOk(xfer(StLinkProtocol.getLastRwStatus(), 2), label)
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
            requireMemoryStatus("写内存状态 ${addr.hex()}")
            offset += len
        }
    }

    private fun readMemBytes(address: Long, length: Int): ByteArray {
        require(length > 0) { "读长度必须 > 0" }
        val out = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val addr = address + offset
            val remaining = length - offset
            val len = when {
                addr % 4 == 0L && remaining >= 4 -> minOf(1024, remaining) and 3.inv()
                addr % 2 != 0L -> 1
                else -> minOf(2, remaining)
            }
            val aligned = addr and 3L == 0L && len % 4 == 0
            val cmd = if (aligned) {
                StLinkProtocol.readMem32(addr, len)
            } else {
                StLinkProtocol.readMem8(addr, len)
            }
            // V2 returns a minimum of two bytes for READMEM_8BIT, even when requesting one.
            val data = xfer(cmd, if (aligned) len else StLinkProtocol.readMem8ReplySize(len))
            System.arraycopy(data, 0, out, offset, len)
            requireMemoryStatus("读内存状态 ${addr.hex()}")
            offset += len
        }
        return out
    }

    private fun xferOutRaw(data: ByteArray) = transfers.payload(data)

    private fun Long.hex(): String = "0x" + toString(16).uppercase().padStart(8, '0')

    private fun xfer(cmd: ByteArray, rxSize: Int): ByteArray {
        try {
            transfers.command(cmd)
            return (if (rxSize <= 0) ByteArray(0) else transfers.response(rxSize)).also { reply ->
                if (cmd[0].toInt() and 255 != StLinkProtocol.DEBUG_COMMAND ||
                    cmd[1].toInt() and 255 in listOf(0x30, 0x31, 0x4B)) {
                    Log.i("CornUsb", "CMD ${cmd.take(3).hexBytes()} RX ${reply.hexBytes()}")
                }
            }
        } catch (cancelled: java.util.concurrent.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val name = when (cmd[0].toInt() and 255) {
                StLinkProtocol.GET_VERSION -> "GET_VERSION"
                StLinkProtocol.GET_CURRENT_MODE -> "GET_CURRENT_MODE"
                StLinkProtocol.GET_TARGET_VOLTAGE -> "GET_TARGET_VOLTAGE"
                else -> "STLINK_CMD"
            }
            val code = cmd.take(2).joinToString(" ") { "%02X".format(it.toInt() and 255) }
            Log.e("CornUsb", "$name [$code] failed", error)
            throw IllegalStateException("$name [$code]：${error.message}", error)
        }
    }

    private fun Iterable<Byte>.hexBytes() = joinToString(" ") { "%02X".format(it.toInt() and 255) }
    private fun ByteArray.hexBytes() = asIterable().hexBytes()

    private fun xferOut(cmd: ByteArray) {
        if (cmd.contentEquals(StLinkProtocol.exitDebug()) || cmd.contentEquals(StLinkProtocol.exitDfu())) {
            Log.i("CornUsb", "OUT ${cmd.take(3).hexBytes()}")
        }
        transfers.command(cmd)
    }

    override fun close() {
        // Releasing an unopened/broken transport must never send another ST-LINK command.
        io.close()
        runCatching { connection.releaseInterface(usbInterface) }
        connection.close()
    }

    companion object {
        private const val CONTROL_TIMEOUT_MS = 1000

        fun open(manager: UsbManager, device: UsbDevice): StLinkUsbSession {
            require(manager.hasPermission(device)) { "尚未获得 USB 授权" }
            val diagnostics = mutableListOf(
                "手机=${Build.MANUFACTURER}/${Build.MODEL} Android=${Build.VERSION.RELEASE} API=${Build.VERSION.SDK_INT} ABI=${Build.SUPPORTED_ABIS.joinToString()}",
                NativeUsbFs.availabilityDescription,
                "USB VID=0x${device.vendorId.toString(16)} PID=0x${device.productId.toString(16)} configs=${device.configurationCount}",
            )
            var lastError: Exception? = null
            // Compatibility selection is ONLY a read-only GET_VERSION handshake on a fresh connection.
            // Once selected, the entire session uses that strategy and never replays commands.
            val preferredModes = if (NativeUsbFs.available) {
                listOf(UsbTransferMode.USBFS_NATIVE, UsbTransferMode.BULK, UsbTransferMode.DIRECT_REQUEST)
            } else listOf(UsbTransferMode.BULK, UsbTransferMode.DIRECT_REQUEST)
            val attempts = java.util.ArrayDeque(preferredModes)
            val recoveredEndpoints = mutableSetOf<Int>()
            var recoveryRetryAdded = false
            while (attempts.isNotEmpty()) {
                val mode = attempts.removeFirst()
                var endpointAddresses: List<Int> = emptyList()
                var connection: UsbDeviceConnection? = null
                var iface: UsbInterface? = null
                var io: AndroidUsbBulkIo? = null
                try {
                    diagnostics += "尝试=$mode"
                    connection = manager.openDevice(device) ?: error("openDevice 返回 null")
                    diagnostics += "openDevice fd=${connection.fileDescriptor}"
                    selectConfiguration(connection, device, mode != UsbTransferMode.BULK, diagnostics)
                    val pick = pickBulk(device)
                    iface = pick.iface
                    val claimed = connection.claimInterface(iface, true)
                    diagnostics += "claim=${claimed} ${pick.label} OUTpacket=${pick.epOut.maxPacketSize} INpacket=${pick.epIn.maxPacketSize}"
                    check(claimed) { "无法占用 ST-LINK 接口" }
                    if (iface.alternateSetting != 0) {
                        check(connection.setInterface(iface)) { "无法选择接口 alternate=${iface.alternateSetting}" }
                    }
                    if (lastError != null) {
                        // Discard only possible replies from the failed, read-only handshake, never from a flash session.
                        for (index in 0 until 2) {
                            val drained = connection.bulkTransfer(pick.epIn, ByteArray(64), 64, 50)
                            if (drained <= 0) break
                            diagnostics += "清理 GET_VERSION 残留应答=$drained"
                        }
                    }
                    endpointAddresses = listOf(pick.epOut.address, pick.epIn.address)
                    val label = "$mode ${pick.label}"
                    io = AndroidUsbBulkIo(connection, pick.epOut, pick.epIn, mode) { diagnostics += it }
                    val session = StLinkUsbSession(connection, iface, pick.epOut, pick.epIn, label, io)
                    session.smokeGetVersion()
                    io.traceEnabled = false
                    return session
                } catch (error: Exception) {
                    lastError = error
                    diagnostics += "失败=${error.message}"
                    diagnostics += "仍连接=${manager.deviceList.values.any { it.deviceId == device.deviceId }}"
                    io?.close() // Cancel/release any URB before issuing native endpoint recovery.
                    if (io != null && connection != null) {
                        val kernelFailure = generateSequence<Throwable>(error) { it.cause }
                            .filterIsInstance<KernelUsbException>().firstOrNull()
                        val recovered = recoverObservedHalts(connection, endpointAddresses, kernelFailure, recoveredEndpoints, diagnostics)
                        if (mode == UsbTransferMode.USBFS_NATIVE && recovered && !recoveryRetryAdded) {
                            recoveryRetryAdded = true
                            attempts.addLast(UsbTransferMode.USBFS_NATIVE)
                            diagnostics += "停顿恢复后仅重建一次连接重试 GET_VERSION"
                        }
                    }
                    iface?.let { selected -> runCatching { connection?.releaseInterface(selected) } }
                    runCatching { connection?.close() }
                }
            }
            throw IllegalStateException(
                "ST-LINK USB 握手未通过，尚未识别或擦写芯片。\n" + diagnostics.joinToString("\n"), lastError)
        }

        private fun recoverObservedHalts(
            connection: UsbDeviceConnection,
            endpoints: List<Int>,
            failure: KernelUsbException?,
            recoveredEndpoints: MutableSet<Int>,
            trace: MutableList<String>,
        ): Boolean {
            var recovered = false
            for (endpoint in endpoints) {
                try {
                    val status = ByteArray(2)
                    val count = connection.controlTransfer(0x82, 0, 0, endpoint, status, 2, CONTROL_TIMEOUT_MS)
                    val bits = if (count == 2) (status[0].toInt() and 255) or ((status[1].toInt() and 255) shl 8) else null
                    trace += "GET_STATUS ep=0x${endpoint.toString(16)} count=$count status=${bits ?: "未知"}"
                    val errno = failure?.takeIf { it.endpoint == endpoint }?.code
                    if (!UsbKernelErrors.shouldRecoverHalt(errno, bits)) continue
                    if (!recoveredEndpoints.add(endpoint)) {
                        trace += "端点再次停顿，不重复恢复 ep=0x${endpoint.toString(16)}"
                        continue
                    }
                    if (!NativeUsbFs.available) {
                        trace += "已检测端点停顿，但底层恢复模块不可用"
                        continue
                    }
                    trace += NativeUsbFs.recoverHalt(connection.fileDescriptor, endpoint)
                    recovered = true
                } catch (error: Exception) {
                    trace += "端点诊断/恢复失败：${error.message}"
                }
            }
            return recovered
        }

        private fun currentConfiguration(connection: UsbDeviceConnection, trace: MutableList<String>): Int? {
            val data = ByteArray(1)
            val count = connection.controlTransfer(0x80, 8, 0, 0, data, 1, CONTROL_TIMEOUT_MS)
            val value = if (count == 1) data[0].toInt() and 255 else null
            trace += "GET_CONFIGURATION count=$count value=${value ?: "未知"}"
            return value
        }

        private fun selectConfiguration(
            connection: UsbDeviceConnection,
            device: UsbDevice,
            tryIfUnknown: Boolean,
            trace: MutableList<String>,
        ) {
            require(device.configurationCount > 0) { "USB 设备没有 configuration" }
            val desired = device.getConfiguration(0)
            val current = currentConfiguration(connection, trace)
            trace += "配置 index=0 value=${desired.id} interfaces=${desired.interfaceCount}"
            if (!StLinkUsbLayout.needsConfigurationSwitch(current, desired.id, tryIfUnknown)) {
                trace += if (current == desired.id) "保留当前已生效配置" else "配置不可读，先使用系统已选择的配置"
                return
            }
            val changed = connection.setConfiguration(desired)
            trace += "SET_CONFIGURATION(${desired.id}) result=$changed"
            val after = currentConfiguration(connection, trace)
            check(after == desired.id || changed && after == null) { "USB 配置选择失败，不能开始传输" }
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
                "pid=0x${device.productId.toString(16)} cfgIndex=0 cfgValue=${device.getConfiguration(0).id} ${chosen.describe()} alt=${iface.alternateSetting}"
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
