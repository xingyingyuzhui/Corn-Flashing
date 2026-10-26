package dev.corn.flashing.core.stlink

/**
 * ST-LINK/V2 (and compatible) command builders and response parsers.
 * Pure Kotlin so JVM unit tests can cover packing without USB hardware.
 *
 * Protocol reference: stlink-org/stlink USB API v2 (16-byte command packets,
 * bulk OUT then bulk IN). Covers SWD identify plus STM32F1 Flash page erase /
 * half-word program helpers (register addresses and CR/SR bits). Never touches
 * option bytes / RDP.
 */
object StLinkProtocol {
    const val CMD_SIZE = 16
    const val VID_ST = 0x0483

    const val GET_VERSION = 0xF1
    const val DEBUG_COMMAND = 0xF2
    const val DFU_COMMAND = 0xF3
    const val GET_CURRENT_MODE = 0xF5
    const val GET_TARGET_VOLTAGE = 0xF7

    const val DFU_EXIT = 0x07
    const val DEBUG_READMEM_32BIT = 0x07
    const val DEBUG_WRITEMEM_32BIT = 0x08
    const val DEBUG_READMEM_8BIT = 0x0C
    const val DEBUG_WRITEMEM_8BIT = 0x0D
    const val DEBUG_WRITEMEM_16BIT = 0x48
    const val DEBUG_EXIT = 0x21
    const val DEBUG_APIV2_ENTER = 0x30
    const val DEBUG_APIV2_READ_IDCODES = 0x31
    const val DEBUG_APIV2_RESETSYS = 0x32
    const val DEBUG_APIV2_WRITEDEBUGREG = 0x35
    const val DEBUG_APIV2_READDEBUGREG = 0x36
    const val DEBUG_APIV2_GETLASTRWSTATUS = 0x3B
    const val DEBUG_APIV2_GETLASTRWSTATUS2 = 0x3E
    const val DEBUG_APIV2_DRIVE_NRST = 0x3C
    const val DEBUG_APIV2_SWD_SET_FREQ = 0x43
    const val DEBUG_APIV2_INIT_AP = 0x4B
    const val DEBUG_ENTER_SWD = 0xA3

    const val MODE_DFU = 0
    const val MODE_MASS = 1
    const val MODE_DEBUG = 2

    /** First byte of many V2 debug replies when OK. */
    const val STATUS_OK = 0x80

    // System memory / PPB addresses on STM32F1
    const val ADDR_DBGMCU_IDCODE = 0xE0042000L
    const val ADDR_FLASH_SIZE = 0x1FFFF7E0L
    const val ADDR_UID = 0x1FFFF7E8L
    const val ADDR_DHCSR = 0xE000EDF0L

    const val DHCSR_DBGKEY = 0xA05F0000L
    const val DHCSR_C_DEBUGEN = 0x1
    const val DHCSR_C_HALT = 0x2

    // STM32F1 embedded Flash interface (RM0008) — never touch OPTKEYR / option bytes.
    const val ADDR_FLASH_KEYR = 0x40022004L
    const val ADDR_FLASH_SR = 0x4002200CL
    const val ADDR_FLASH_CR = 0x40022010L
    const val ADDR_FLASH_AR = 0x40022014L
    const val FLASH_KEY1 = 0x45670123L
    const val FLASH_KEY2 = 0xCDEF89ABL
    const val FLASH_CR_PG = 0x1
    const val FLASH_CR_PER = 0x2
    const val FLASH_CR_MER = 0x4
    const val FLASH_CR_STRT = 0x40
    const val FLASH_CR_LOCK = 0x80
    const val FLASH_SR_BSY = 0x1
    const val FLASH_SR_PGERR = 0x4
    const val FLASH_SR_WRPRTERR = 0x10
    const val FLASH_SR_EOP = 0x20
    const val NRST_LOW = 0
    const val NRST_HIGH = 1
    const val NRST_PULSE = 2

    fun cmd(vararg bytes: Int): ByteArray {
        require(bytes.size <= CMD_SIZE) { "ST-LINK command too long" }
        val out = ByteArray(CMD_SIZE)
        bytes.forEachIndexed { i, b -> out[i] = (b and 0xFF).toByte() }
        return out
    }

    fun getVersion() = cmd(GET_VERSION)
    fun getCurrentMode() = cmd(GET_CURRENT_MODE)
    fun getTargetVoltage() = cmd(GET_TARGET_VOLTAGE)
    fun exitDfu() = cmd(DFU_COMMAND, DFU_EXIT)
    fun exitDebug() = cmd(DEBUG_COMMAND, DEBUG_EXIT)
    fun enterSwd() = cmd(DEBUG_COMMAND, DEBUG_APIV2_ENTER, DEBUG_ENTER_SWD)
    fun initAp0() = cmd(DEBUG_COMMAND, DEBUG_APIV2_INIT_AP, 0)
    fun readIdCodes() = cmd(DEBUG_COMMAND, DEBUG_APIV2_READ_IDCODES)
    fun getLastRwStatus() = cmd(DEBUG_COMMAND, DEBUG_APIV2_GETLASTRWSTATUS)
    fun getLastRwStatus2() = cmd(DEBUG_COMMAND, DEBUG_APIV2_GETLASTRWSTATUS2)

    fun readMem32(address: Long, length: Int, ap: Int = 0): ByteArray {
        require(length in 1..1024 && length % 4 == 0) { "READMEM_32BIT length must be 4..1024 and 4-aligned" }
        require(address and 3L == 0L) { "READMEM_32BIT address must be 4-aligned" }
        val out = ByteArray(CMD_SIZE)
        out[0] = DEBUG_COMMAND.toByte()
        out[1] = DEBUG_READMEM_32BIT.toByte()
        writeU32Le(out, 2, address)
        writeU16Le(out, 6, length)
        out[8] = (ap and 0xFF).toByte()
        return out
    }

    fun readMem8(address: Long, length: Int): ByteArray {
        require(length in 1..64) { "V2 READMEM_8BIT length must be 1..64" }
        require(address in 0..0xFFFF_FFFFL && address + length <= 0x1_0000_0000L) { "READMEM_8BIT address overflow" }
        return ByteArray(CMD_SIZE).also {
            it[0] = DEBUG_COMMAND.toByte(); it[1] = DEBUG_READMEM_8BIT.toByte()
            writeU32Le(it, 2, address); writeU16Le(it, 6, length)
        }
    }

    /** Wire quirk: the command requests one byte but the bulk response occupies two. */
    fun readMem8ReplySize(length: Int): Int {
        require(length in 1..64)
        return maxOf(2, length)
    }

    fun writeDebugReg(address: Long, value: Long): ByteArray {
        val out = ByteArray(CMD_SIZE)
        out[0] = DEBUG_COMMAND.toByte()
        out[1] = DEBUG_APIV2_WRITEDEBUGREG.toByte()
        writeU32Le(out, 2, address)
        writeU32Le(out, 6, value and 0xFFFF_FFFFL)
        return out
    }

    fun readDebugReg(address: Long): ByteArray {
        val out = ByteArray(CMD_SIZE)
        out[0] = DEBUG_COMMAND.toByte()
        out[1] = DEBUG_APIV2_READDEBUGREG.toByte()
        writeU32Le(out, 2, address)
        return out
    }

    /** Halt the core via DHCSR (V2 WRITEDEBUGREG). */
    fun haltCore() = writeDebugReg(ADDR_DHCSR, DHCSR_DBGKEY or DHCSR_C_HALT.toLong() or DHCSR_C_DEBUGEN.toLong())

    /** Keep debug enabled but clear C_HALT so the core can run after reset. */
    fun unhaltCore() = writeDebugReg(ADDR_DHCSR, DHCSR_DBGKEY or DHCSR_C_DEBUGEN.toLong())

    fun resetSys() = cmd(DEBUG_COMMAND, DEBUG_APIV2_RESETSYS)
    fun driveNrst(action: Int) = cmd(DEBUG_COMMAND, DEBUG_APIV2_DRIVE_NRST, action and 0xFF)

    fun writeMem32(address: Long, length: Int, ap: Int = 0): ByteArray {
        require(length in 1..1024 && length % 4 == 0) { "WRITEMEM_32BIT length must be 4..1024 and 4-aligned" }
        require(address and 3L == 0L) { "WRITEMEM_32BIT address must be 4-aligned" }
        val out = ByteArray(CMD_SIZE)
        out[0] = DEBUG_COMMAND.toByte()
        out[1] = DEBUG_WRITEMEM_32BIT.toByte()
        writeU32Le(out, 2, address)
        writeU16Le(out, 6, length)
        out[8] = (ap and 0xFF).toByte()
        return out
    }

    /** V2 J26+: the width is a protocol command, not inferred from payload size. */
    fun writeMem16(address: Long, length: Int, ap: Int = 0): ByteArray {
        require(length in 2..1024 && length % 2 == 0) { "WRITEMEM_16BIT length must be even, 2..1024" }
        require(address in 0..0xFFFF_FFFFL && address % 2 == 0L && address + length <= 0x1_0000_0000L) {
            "WRITEMEM_16BIT address must be aligned and inside 32-bit memory"
        }
        return ByteArray(CMD_SIZE).also {
            it[0] = DEBUG_COMMAND.toByte()
            it[1] = DEBUG_WRITEMEM_16BIT.toByte()
            writeU32Le(it, 2, address)
            writeU16Le(it, 6, length)
            it[8] = ap.toByte()
        }
    }

    fun writeMem8(address: Long, length: Int): ByteArray {
        require(length in 1..1024) { "WRITEMEM_8BIT length must be 1..1024" }
        val out = ByteArray(CMD_SIZE)
        out[0] = DEBUG_COMMAND.toByte()
        out[1] = DEBUG_WRITEMEM_8BIT.toByte()
        writeU32Le(out, 2, address)
        writeU16Le(out, 6, length)
        return out
    }

    /** Clear EOP / PGERR / WRPRTERR in FLASH_SR (write-1-to-clear). */
    fun flashSrClearMask(): Long = (FLASH_SR_EOP or FLASH_SR_PGERR or FLASH_SR_WRPRTERR).toLong()

    fun parseVersion(raw: ByteArray): StLinkVersion {
        require(raw.size >= 6) { "GET_VERSION reply too short" }
        val v = (((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF))
        val stlink = (v shr 12) and 0x0F
        val jtag = (v shr 6) and 0x3F
        val swim = v and 0x3F
        return StLinkVersion(stlink, jtag, swim, raw.copyOf())
    }

    fun parseMode(raw: ByteArray): Int {
        require(raw.isNotEmpty()) { "GET_CURRENT_MODE reply empty" }
        return raw[0].toInt() and 0xFF
    }

    /** Millivolts; 0 when the probe could not read a factor. */
    fun parseVoltageMv(raw: ByteArray): Int {
        require(raw.size >= 8) { "GET_TARGET_VOLTAGE reply too short" }
        val factor = readU32Le(raw, 0)
        val reading = readU32Le(raw, 4)
        if (factor == 0L || reading == 0L) return 0
        return ((2400L * reading) / factor).toInt()
    }

    fun parseIdCodes(raw: ByteArray): Long {
        require(raw.size >= 8) { "READ_IDCODES reply too short" }
        // V2: status byte + pad, then 32-bit core id at offset 4
        return readU32Le(raw, 4)
    }

    fun parseDebugReg(raw: ByteArray): Long {
        require(raw.size >= 8) { "READDEBUGREG reply too short" }
        return readU32Le(raw, 4)
    }

    /**
     * F1 Flash size register holds KiB in the low 16 bits.
     * Returns bytes; throws when the reading is not a known medium-density size.
     */
    fun flashSizeBytesFromRegister(word: Long): Long {
        val kib = word and 0xFFFF
        require(kib in setOf(16L, 32L, 64L, 128L, 256L, 384L, 512L, 768L, 1024L)) {
            "芯片报告的 Flash 容量异常：${kib} KiB"
        }
        return kib * 1024L
    }

    /** DBGMCU_IDCODE: [11:0] DEV_ID, [31:16] REV_ID. */
    fun parseDbgmcuIdCode(word: Long): Pair<Int, Int> {
        val deviceId = (word and 0xFFF).toInt()
        val revisionId = ((word ushr 16) and 0xFFFF).toInt()
        return deviceId to revisionId
    }

    fun formatUid(bytes: ByteArray): String {
        require(bytes.size >= 12) { "UID needs 12 bytes" }
        return buildString {
            for (i in 0 until 12) {
                if (i > 0 && i % 4 == 0) append('-')
                append("%02X".format(bytes[i].toInt() and 0xFF))
            }
        }
    }

    fun familyLabel(deviceId: Int): String = when (deviceId) {
        0x412 -> "STM32F1 小容量"
        0x410 -> "STM32F1 中容量"
        0x414 -> "STM32F1 大容量"
        0x430 -> "STM32F1 超大容量"
        else -> "未知设备组 0x${deviceId.toString(16)}"
    }

    fun requireStatusOk(raw: ByteArray, label: String) {
        require(raw.isNotEmpty()) { "$label：空应答" }
        val status = raw[0].toInt() and 0xFF
        require(status == STATUS_OK) {
            "$label：ST-LINK 状态 0x${status.toString(16)}（期望 0x80）"
        }
    }

    fun writeU32Le(buf: ByteArray, offset: Int, value: Long) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    fun writeU16Le(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    fun readU32Le(buf: ByteArray, offset: Int): Long {
        val b0 = buf[offset].toLong() and 0xFF
        val b1 = buf[offset + 1].toLong() and 0xFF
        val b2 = buf[offset + 2].toLong() and 0xFF
        val b3 = buf[offset + 3].toLong() and 0xFF
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }
}

data class StLinkVersion(val stlink: Int, val jtag: Int, val swim: Int, val raw: ByteArray) {
    val label: String get() = "V$stlink J$jtag"
    val supportsHalfWordWrite: Boolean get() = stlink == 2 && jtag >= 26
    val needsApInit: Boolean get() = stlink == 2 && jtag >= 28
    val supportsTargetVoltage: Boolean get() = stlink == 2 && jtag >= 13 || stlink == 3

    fun requireF1ProgrammingSupport() {
        require(supportsHalfWordWrite) {
            "当前探针固件 $label 不支持本版本的 F1 半字编程；需要 ST-LINK/V2 J26 或更高固件。未执行擦除，请先更新探针固件"
        }
    }
    override fun equals(other: Any?) = other is StLinkVersion && raw.contentEquals(other.raw)
    override fun hashCode() = raw.contentHashCode()
}
