package dev.corn.flashing.core

import java.security.MessageDigest
import java.util.Locale

/** Parses data only. It never opens USB or executes firmware. */
object FirmwareParser {
    // Largest F1 part is 1 MiB of Flash; 4 MiB leaves room for HEX text overhead.
    const val MAX_FILE_BYTES = 4 * 1024 * 1024

    fun isBin(name: String): Boolean = name.substringAfterLast('.', "").lowercase(Locale.ROOT) == "bin"

    /** Strict: a typo must be reported, not silently treated as "no address". */
    fun parseAddress(text: String): Long {
        val digits = text.trim().removePrefix("0x").removePrefix("0X")
        val value = digits.takeIf { it.length in 1..8 && it.all { c -> c.digitToIntOrNull(16) != null } }?.toLong(16)
        return requireNotNull(value) { "BIN 起始地址「${text.trim()}」不是有效的十六进制地址" }
    }

    fun parse(name: String, bytes: ByteArray, binAddress: Long? = null): FirmwareImage {
        require(bytes.isNotEmpty()) { "固件文件为空" }
        require(bytes.size <= MAX_FILE_BYTES) { "固件文件最大支持 4 MiB" }
        val suffix = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val isElf = bytes.size >= 4 && bytes[0] == 0x7f.toByte() &&
            bytes[1] == 'E'.code.toByte() && bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte()
        if (isElf || suffix == "elf" || suffix == "axf") {
            throw IllegalArgumentException("ELF / AXF 解析尚未接入，请先导出 HEX 或 BIN；基础框架不会猜测加载地址")
        }
        val format: FirmwareFormat
        val segments: List<FirmwareSegment>
        when {
            suffix == "hex" -> {
                format = FirmwareFormat.HEX
                segments = parseHex(bytes)
            }
            suffix == "bin" -> {
                format = FirmwareFormat.BIN
                val address = requireNotNull(binAddress) { "BIN 不含地址，请先设置明确的烧录起始地址" }
                require(address in 0..0xffff_ffffL && address + bytes.size <= 0x1_0000_0000L) {
                    "BIN 地址超出 32 位地址空间"
                }
                segments = listOf(FirmwareSegment(address, bytes.copyOf()))
            }
            else -> throw IllegalArgumentException("不支持的文件格式，请选择 HEX、BIN、ELF 或 AXF")
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return FirmwareImage(format, segments, digest)
    }

    private fun parseHex(bytes: ByteArray): List<FirmwareSegment> {
        require(bytes.all { it.toInt() in 0..127 }) { "HEX 必须是 ASCII 文本" }
        val segments = mutableListOf<FirmwareSegment>()
        var baseAddress = 0L
        var ended = false
        bytes.toString(Charsets.US_ASCII).lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEachIndexed
            val label = "HEX 第 ${index + 1} 行"
            require(!ended) { "$label：结束记录后仍有数据" }
            require(line.startsWith(":")) { "$label：缺少冒号" }
            require(line.length in 11..521 && line.length % 2 == 1) { "$label：记录长度无效" }
            val record = IntArray((line.length - 1) / 2) { byteIndex ->
                val offset = 1 + byteIndex * 2
                val hi = line[offset].digitToIntOrNull(16)
                val lo = line[offset + 1].digitToIntOrNull(16)
                require(hi != null && lo != null) { "$label：包含非十六进制字符" }
                (hi shl 4) or lo
            }
            val size = record[0]
            val address = (record[1] shl 8) or record[2]
            val type = record[3]
            require(record.size == size + 5) { "$label：数据长度与记录不符" }
            require(record.sum() and 0xff == 0) { "$label：校验和错误" }
            fun controlRecord(expectedSize: Int) {
                require(size == expectedSize && address == 0) { "$label：控制记录长度或地址无效" }
            }
            when (type) {
                0 -> if (size > 0) {
                    val start = baseAddress + address
                    require(address + size <= 0x10000) { "$label：数据记录跨越 64 KB 边界" }
                    require(start + size <= 0x1_0000_0000L) { "$label：地址溢出" }
                    require(segments.size < 65536) { "HEX 数据区段过多" }
                    segments += FirmwareSegment(start, ByteArray(size) { record[4 + it].toByte() })
                }
                1 -> { controlRecord(0); ended = true }
                2 -> {
                    controlRecord(2)
                    baseAddress = (((record[4] shl 8) or record[5]).toLong()) shl 4
                }
                4 -> {
                    controlRecord(2)
                    baseAddress = (((record[4] shl 8) or record[5]).toLong()) shl 16
                }
                3, 5 -> controlRecord(4) // Execution entry records do not select Flash addresses.
                else -> throw IllegalArgumentException("$label：不支持记录类型 $type")
            }
        }
        require(ended) { "HEX 缺少结束记录" }
        require(segments.isNotEmpty()) { "HEX 没有可烧录数据" }
        val sorted = segments.sortedBy { it.address }
        sorted.zipWithNext().forEach { (previous, next) ->
            require(previous.endAddressExclusive <= next.address) { "HEX 存在重叠数据区段：${next.address.hexAddress()}" }
        }
        return sorted
    }
}
