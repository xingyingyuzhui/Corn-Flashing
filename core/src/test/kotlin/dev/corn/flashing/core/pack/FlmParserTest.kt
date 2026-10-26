package dev.corn.flashing.core.pack

import org.junit.Assert.*
import org.junit.Test

class FlmParserTest {
    private fun fixture() = requireNotNull(javaClass.getResourceAsStream("/fixtures/fixture.flm")).use { it.readBytes() }
    private fun u16(bytes: ByteArray, at: Int) = (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8)
    private fun u32(bytes: ByteArray, at: Int): Int = (0..3).fold(0) { v, i -> v or ((bytes[at + i].toInt() and 255) shl (8 * i)) }
    private fun put32(bytes: ByteArray, at: Int, value: Long) { repeat(4) { bytes[at + it] = (value ushr (8 * it)).toByte() } }
    private fun sh(bytes: ByteArray, index: Int) = u32(bytes, 32) + index * 40
    private fun metadata(bytes: ByteArray) = u32(bytes, sh(bytes, 4) + 16)
    private fun symbol(bytes: ByteArray, index: Int) = u32(bytes, sh(bytes, 5) + 16) + index * 16
    private fun reject(change: (ByteArray) -> Unit) {
        val bytes = fixture(); change(bytes)
        assertThrows(IllegalArgumentException::class.java) { FlmParser.parse(bytes) }
    }

    @Test fun parsesOriginalExecutableFixtureAndFlashLayout() {
        val image = FlmParser.parse(fixture())
        assertEquals(0L, image.imageBase)
        assertEquals(20, image.image.size)
        assertEquals(16, image.codeSize)
        assertEquals(16, image.rwOffset)
        assertEquals(4, image.rwSize)
        assertEquals(0, image.ziSize)
        assertEquals(1, image.initOffset)
        assertEquals(5, image.unInitOffset)
        assertEquals(9, image.eraseSectorOffset)
        assertEquals(13, image.programPageOffset)
        assertEquals(16, image.staticBaseOffset)
        assertEquals(0x08000000L, image.flash.start)
        assertEquals(256 * 1024L, image.flash.size)
        assertEquals(256, image.flash.pageSize)
        assertEquals(255, image.flash.erasedByte)
        assertEquals(128, image.flash.geometry().sectors(image.flash.start, image.flash.size).size)
        assertEquals(64, image.sha256.length)
    }

    @Test fun rejectsWrongArchitectureEndianClassAndExecutableType() {
        reject { it[4] = 2 }
        reject { it[5] = 2 }
        reject { it[18] = 0 }
        reject { it[16] = 1 }
        reject { put32(it, 36, 0x05000400) }
    }
    @Test fun rejectsOutOfBoundsOrExtendedSectionTables() {
        reject { put32(it, 32, 0xFFFFFFF0) }
        reject { it[48] = 0; it[49] = 0 }
        reject { put32(it, sh(it, 1) + 16, 0xFFFFFFF0) }
        reject { put32(it, sh(it, 1) + 20, 0xFFFFFFF0) }
        assertThrows(IllegalArgumentException::class.java) { FlmParser.parse(ByteArray(FlmParser.MAX_FILE_BYTES + 1)) }
        assertThrows(IllegalArgumentException::class.java) { FlmParser.parse(fixture().copyOf(80)) }
    }
    @Test fun rejectsOverlappingHugeAndWritableCodeLayouts() {
        reject { put32(it, sh(it, 3) + 12, 4) }
        reject { put32(it, sh(it, 3) + 12, FlmParser.MAX_IMAGE_BYTES.toLong()) }
        reject { put32(it, sh(it, 1) + 8, 7) }
        reject { put32(it, sh(it, 1) + 12, 0x20000000) }
    }
    @Test fun zeroInitializesNobitsDataAndAllowsAbsentReadWriteSection() {
        val bytes = fixture()
        put32(bytes, sh(bytes, 3) + 4, 8)
        val image = FlmParser.parse(bytes)
        assertEquals(0, image.rwSize)
        assertEquals(4, image.ziSize)
        assertArrayEquals(ByteArray(4), image.image.copyOfRange(16, 20))
    }
    @Test fun rejectsUnresolvedDuplicateMissingAndNonThumbEntrypoints() {
        reject { it[symbol(it, 2) + 14] = 0 }
        reject { put32(it, symbol(it, 3), u32(it, symbol(it, 2)).toLong()) }
        reject { put32(it, symbol(it, 2) + 4, 2) }
        reject { put32(it, symbol(it, 2) + 4, 0x11) }
        reject { put32(it, symbol(it, 2) + 8, 17) }
        reject { put32(it, symbol(it, 2), 0) }
    }
    @Test fun rejectsUnsupportedRelocationAndUnaccountedWritableSections() {
        reject { put32(it, sh(it, 2) + 4, 9); put32(it, sh(it, 2) + 20, 4) }
        reject { put32(it, sh(it, 4) + 8, 3) }
        reject { put32(it, sh(it, 4) + 8, 6) }
        reject { put32(it, sh(it, 2) + 8, 2); put32(it, sh(it, 2) + 20, 4) }
    }
    @Test fun rejectsInvalidFlashDeviceBoundsVersionPageAndTimeouts() {
        reject { put32(it, symbol(it, 7) + 8, 159) }
        reject { it[metadata(it)] = 0; it[metadata(it) + 1] = 0 }
        reject { put32(it, metadata(it) + 132, 0xFFFFF000) }
        reject { put32(it, metadata(it) + 136, 0) }
        reject { put32(it, metadata(it) + 140, 0) }
        reject { put32(it, metadata(it) + 144, 1) }
        reject { put32(it, metadata(it) + 152, 0) }
        reject { put32(it, metadata(it) + 156, 120001) }
    }
    @Test fun rejectsMalformedAndUnterminatedSectorTables() {
        reject { put32(it, metadata(it) + 164, 2048) }
        reject { put32(it, metadata(it) + 160, 2049) }
        reject { put32(it, metadata(it) + 168, 2048); put32(it, metadata(it) + 172, 0) }
        reject { put32(it, metadata(it) + 168, 0xFFFFFFFF); put32(it, metadata(it) + 172, 0) }
    }
    @Test fun rejectsUnterminatedNamesAndBadStringIndexes() {
        reject { val at = metadata(it) + 2; (at until at + 128).forEach { i -> it[i] = 65 } }
        reject { put32(it, symbol(it, 2), 0xFFFFFFFF) }
        reject { put32(it, sh(it, 1), 0xFFFFFFFF) }
    }
}
