package dev.corn.flashing.core.pack

import java.security.MessageDigest

/** Strict supported subset: linked ELF32/ARM/LE, zero-based PrgCode, optional PrgData RW/ZI. */
object FlmParser {
    const val MAX_FILE_BYTES = 4 * 1024 * 1024
    const val MAX_IMAGE_BYTES = 256 * 1024
    private const val MAX_SECTIONS = 1024
    private val requiredFunctions = setOf("Init", "UnInit", "EraseSector", "ProgramPage")

    fun parse(bytes: ByteArray): FlmImage {
        require(bytes.size in 52..MAX_FILE_BYTES) { "FLM 文件大小无效（最大 4 MiB）" }
        val r = Reader(bytes)
        require(bytes.take(4) == listOf(0x7F.toByte(), 69.toByte(), 76.toByte(), 70.toByte()) && r.u8(4) == 1 && r.u8(5) == 1 && r.u8(6) == 1) {
            "FLM 必须是 ELF32 小端文件"
        }
        require(r.u16(16) == 2 && r.u16(18) == 40 && r.u32(20) == 1L) { "FLM 仅支持已链接 ARM ELF（ET_EXEC）" }
        require(r.u16(40) == 52 && r.u16(46) == 40) { "FLM ELF 头或节表格式无效" }
        require(r.u32(36) and 0x400L == 0L) { "FLM 暂不支持硬浮点调用约定" }
        val sectionCount = r.u16(48)
        require(sectionCount in 2..MAX_SECTIONS) { "FLM 节数量无效或使用了不支持的扩展节表" }
        val sectionTable = r.u32(32)
        r.range(sectionTable, sectionCount.toLong() * 40)
        val nameIndex = r.u16(50)
        require(nameIndex in 1 until sectionCount) { "FLM 节名称表索引无效" }
        val sections = (0 until sectionCount).map { index ->
            val at = sectionTable.toInt() + index * 40
            Section(index, r.u32(at).toInt(), r.u32(at + 4), r.u32(at + 8), r.u32(at + 12),
                r.u32(at + 16), r.u32(at + 20), r.u32(at + 24).toInt(), r.u32(at + 36))
        }
        val names = sections[nameIndex]
        require(names.type == 3L) { "FLM 节名称表不是字符串表" }
        r.range(names.offset, names.size)
        sections.forEach { s ->
            require(s.address + s.size <= 0x1_0000_0000L) { "FLM 节地址溢出" }
            if (s.type != 8L) r.range(s.offset, s.size)
            s.name = r.string(names.offset, names.size, s.nameIndex)
            require(s.type !in setOf(4L, 9L) || s.size == 0L) { "FLM 包含未支持的重定位节" }
        }
        val code = sections.singleOrNull { it.name == "PrgCode" && it.type == 1L }
        require(code != null && code.address == 0L && code.size in 2..MAX_IMAGE_BYTES.toLong() && code.flags and 6L == 6L && code.flags and 1L == 0L) {
            "FLM 需要唯一从零开始的只读可执行 PrgCode 节"
        }
        require(sections.count { it.name == "PrgCode" } == 1) { "FLM PrgCode 节重复" }
        val rwList = sections.filter { it.name == "PrgData" && it.type == 1L }
        val ziList = sections.filter { it.name == "PrgData" && it.type == 8L }
        require(rwList.size <= 1 && ziList.size <= 1 && sections.none { it.name == "PrgData" && it.type !in setOf(1L, 8L) }) { "FLM PrgData 节重复或类型无效" }
        val rw = rwList.singleOrNull()
        val zi = ziList.singleOrNull()
        for (data in listOfNotNull(rw, zi)) require(data.flags and 3L == 3L && data.flags and 4L == 0L) { "FLM PrgData 必须是可写数据节" }
        val loaded = listOfNotNull(code, rw, zi).filter { it.size > 0 }
        val ordered = loaded.sortedBy { it.address }
        ordered.zipWithNext().forEach { (left, right) -> require(left.address + left.size <= right.address) { "FLM 代码/数据节重叠" } }
        require((rw?.address ?: code.end) >= code.end && (zi?.address ?: (rw?.end ?: code.end)) >= (rw?.end ?: code.end)) { "FLM RO/RW/ZI 顺序无效" }
        val imageSize = loaded.maxOf { it.end }
        require(imageSize in 2..MAX_IMAGE_BYTES.toLong()) { "FLM RAM 镜像过大或布局空洞过大" }
        // Metadata can be allocated in another read-only section; executable/writable strays cannot be silently omitted.
        require(sections.none { it.size > 0 && it.flags and 2L != 0L && it !in loaded && it.flags and 5L != 0L }) { "FLM 包含未支持的可执行/可写加载节" }
        val symbols = linkedMapOf<String, Symbol>()
        val symtabs = sections.filter { it.type == 2L }
        require(symtabs.size == 1) { "FLM 需要唯一符号表" }
        val table = symtabs.single()
        require(table.entrySize == 16L && table.size % 16 == 0L && table.size / 16 <= 65536 && table.link in sections.indices) { "FLM 符号表无效" }
        val strings = sections[table.link]
        require(strings.type == 3L) { "FLM 符号名称表无效" }
        repeat((table.size / 16).toInt()) { index ->
            val at = table.offset.toInt() + index * 16
            val name = r.string(strings.offset, strings.size, r.u32(at).toInt())
            if (name in requiredFunctions || name == "FlashDevice") {
                require(name !in symbols) { "FLM 必需符号重复：$name" }
                val section = r.u16(at + 14)
                require(section in 1 until sectionCount) { "FLM 必需符号未定义：$name" }
                symbols[name] = Symbol(r.u32(at + 4), r.u32(at + 8), section, r.u8(at + 12) and 15)
            }
        }
        requiredFunctions.forEach { name ->
            val symbol = requireNotNull(symbols[name]) { "FLM 缺少入口：$name" }
            require(symbol.section == code.index && symbol.type == 2 && symbol.value and 1L == 1L &&
                symbol.value and -2L >= code.address && (symbol.value and -2L) + maxOf(2, symbol.size) <= code.end) {
                "FLM 入口不在 Thumb 代码范围：$name"
            }
        }
        val deviceSymbol = requireNotNull(symbols["FlashDevice"]) { "FLM 缺少 FlashDevice" }
        val deviceSection = sections[deviceSymbol.section]
        require(deviceSection.type == 1L && deviceSection.flags and 1L == 0L && deviceSymbol.type == 1 &&
            deviceSymbol.value >= deviceSection.address && deviceSymbol.value + 160 <= deviceSection.end) { "FLM FlashDevice 不在有效只读数据区" }
        require(sections.none { it.size > 0 && it.flags and 2L != 0L && it !in loaded && it != deviceSection }) {
            "FLM 包含镜像以外的未支持加载节"
        }
        val deviceOffset = deviceSection.offset + deviceSymbol.value - deviceSection.address
        val deviceLimit = if (deviceSymbol.size > 0) deviceOffset + deviceSymbol.size else deviceSection.offset + deviceSection.size
        require(deviceLimit <= deviceSection.offset + deviceSection.size && deviceLimit >= deviceOffset + 160) { "FLM FlashDevice 符号长度无效" }
        val flash = parseFlashDevice(r, deviceOffset.toInt(), deviceLimit.toInt())
        val image = ByteArray(imageSize.toInt())
        for (section in listOfNotNull(code, rw)) bytes.copyInto(image, section.address.toInt(), section.offset.toInt(), (section.offset + section.size).toInt())
        val rwOffset = (rw?.address ?: zi?.address ?: code.end).toInt()
        val ziOffset = (zi?.address ?: rw?.end ?: code.end).toInt()
        return FlmImage(image, 0, 0, code.size.toInt(), rwOffset, rw?.size?.toInt() ?: 0,
            ziOffset, zi?.size?.toInt() ?: 0, symbols.getValue("Init").value.toInt(), symbols.getValue("UnInit").value.toInt(),
            symbols.getValue("EraseSector").value.toInt(), symbols.getValue("ProgramPage").value.toInt(), rwOffset, flash,
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) })
    }

    private fun parseFlashDevice(r: Reader, at: Int, limit: Int): FlmFlashDevice {
        fun field(offset: Int, length: Int) { require(at.toLong() + offset + length <= limit) { "FLM FlashDevice 被截断" } }
        field(0, 160)
        val version = r.u16(at)
        require(version in 0x100..0x1FF) { "FLM FlashDevice 版本不受支持" }
        val name = r.string((at + 2).toLong(), 128, 0)
        val type = r.u16(at + 130)
        require(name.isNotBlank() && type in 1..3) { "FLM FlashDevice 名称或类型无效" }
        val start = r.u32(at + 132)
        val size = r.u32(at + 136)
        val page = r.u32(at + 140)
        require(size in 1..(16L * 1024 * 1024) && start + size <= 0x1_0000_0000L && page in 1..65536 && page <= size && size % page == 0L) { "FLM Flash 容量或页大小无效" }
        require(r.u32(at + 144) == 0L) { "FLM FlashDevice 保留字段非零" }
        val programTimeout = r.u32(at + 152)
        val eraseTimeout = r.u32(at + 156)
        require(programTimeout in 1..120000 && eraseTimeout in 1..120000) { "FLM 执行超时参数超出支持范围" }
        val sectors = mutableListOf<FlmSectorRegion>()
        var cursor = at + 160
        var ended = false
        while (cursor.toLong() + 8 <= limit && sectors.size <= 1024) {
            val sectorSize = r.u32(cursor)
            val offset = r.u32(cursor + 4)
            cursor += 8
            if (sectorSize == 0xFFFF_FFFFL && offset == 0xFFFF_FFFFL) { ended = true; break }
            require(sectorSize in 1..size && offset < size && offset % page == 0L && sectorSize % page == 0L) { "FLM 扇区参数无效" }
            require((sectors.isEmpty() && offset == 0L) || (sectors.isNotEmpty() && offset > sectors.last().offset)) { "FLM 扇区未从零开始或顺序无效" }
            sectors += FlmSectorRegion(offset, sectorSize.toInt())
        }
        require(ended && sectors.isNotEmpty() && sectors.size <= 1024) { "FLM 扇区表缺少终止标记或过大" }
        val flash = FlmFlashDevice(version, name, type, start, size, page.toInt(), r.u8(at + 148), programTimeout, eraseTimeout, sectors)
        flash.geometry().sectors(start, size)
        return flash
    }

    private data class Section(val index: Int, val nameIndex: Int, val type: Long, val flags: Long, val address: Long,
                               val offset: Long, val size: Long, val link: Int, val entrySize: Long, var name: String = "") {
        val end: Long get() = address + size
    }
    private data class Symbol(val value: Long, val size: Long, val section: Int, val type: Int)
    private class Reader(private val bytes: ByteArray) {
        fun range(offset: Long, size: Long) { require(offset >= 0 && size >= 0 && offset <= bytes.size && size <= bytes.size - offset) { "FLM 文件区间越界" } }
        fun u8(at: Int): Int { range(at.toLong(), 1); return bytes[at].toInt() and 255 }
        fun u16(at: Int) = u8(at) or (u8(at + 1) shl 8)
        fun u32(at: Int): Long = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
        fun string(tableOffset: Long, tableSize: Long, index: Int): String {
            range(tableOffset, tableSize)
            require(index >= 0 && index < tableSize) { "FLM 字符串索引越界" }
            val begin = (tableOffset + index).toInt()
            var end = begin
            val limit = (tableOffset + tableSize).toInt()
            while (end < limit && bytes[end] != 0.toByte()) end++
            require(end < limit) { "FLM 字符串没有结束符" }
            require(end - begin <= 256 && bytes.sliceArray(begin until end).all { it.toInt() in 32..126 }) { "FLM 字符串无效" }
            return bytes.copyOfRange(begin, end).toString(Charsets.US_ASCII)
        }
    }
}
