package dev.corn.flashing.core

import org.junit.Assert.*
import org.junit.Test

class FirmwareParserTest {
    @Test fun parsesExtendedLinearAddressAndSparseSegments() {
        val image = hex(
            record(4, 0, 8, 0),
            record(0, 0x100, 0x12, 0x34),
            record(0, 0, 0xab, 0xcd),
            record(1, 0),
        )
        assertEquals(FirmwareFormat.HEX, image.format)
        assertEquals(0x08000000L, image.startAddress)
        assertEquals(0x08000102L, image.endAddressExclusive)
        assertEquals(4L, image.totalBytes)
        assertArrayEquals(byteArrayOf(0xab.toByte(), 0xcd.toByte()), image.segments.first().data)
        assertEquals(64, image.sourceSha256.length)
    }

    @Test fun rejectsWrongChecksum() {
        failsContaining("校验和") { hex(":010000000100", record(1, 0)) }
    }

    @Test fun rejectsTruncatedRecord() {
        failsContaining("长度") { hex(":0200000001FD", record(1, 0)) }
    }

    @Test fun rejectsOverlappingRecordsEvenIfDataIsIdentical() {
        failsContaining("重叠") { hex(record(0, 0, 1, 2), record(0, 1, 2), record(1, 0)) }
    }

    @Test fun requiresEndRecordAndRejectsTrailingData() {
        failsContaining("结束") { hex(record(0, 0, 1)) }
        failsContaining("结束") { hex(record(0, 0, 1), record(1, 0), record(0, 1, 2)) }
    }

    @Test fun rejectsDataCrossingAddressWindow() {
        failsContaining("64 KB") { hex(record(0, 0xffff, 1, 2), record(1, 0)) }
    }

    @Test fun parsesSegmentAddressAndIgnoresExecutionEntryAsData() {
        val image = hex(record(2, 0, 0x12, 0x34), record(0, 0x10, 9), record(3, 0, 0, 0, 0, 0), record(1, 0))
        assertEquals(0x12350L, image.startAddress)
        assertEquals(1L, image.totalBytes)
    }

    @Test fun binRequiresAnExplicitAddressAndRejectsOverflow() {
        failsContaining("BIN 不含地址") { FirmwareParser.parse("app.bin", byteArrayOf(1)) }
        failsContaining("32 位") { FirmwareParser.parse("app.bin", byteArrayOf(1, 2), 0xffff_ffffL) }
        assertEquals(0x08004000L, FirmwareParser.parse("app.bin", byteArrayOf(1), 0x08004000).startAddress)
    }

    @Test fun copiesBinInputAndRecognizesElfMagicEvenWithBinSuffix() {
        val source = byteArrayOf(1, 2)
        val image = FirmwareParser.parse("app.bin", source, 0x08000000)
        source[0] = 9
        assertEquals(1.toByte(), image.segments.single().data[0])
        failsContaining("尚未接入") { FirmwareParser.parse("wrong.bin", byteArrayOf(0x7f, 69, 76, 70), 0x08000000) }
        failsContaining("尚未接入") { FirmwareParser.parse("app.axf", byteArrayOf(1)) }
    }

    @Test fun refusesUnsupportedFormatEmptyDataAndNonAsciiHex() {
        failsContaining("不支持") { FirmwareParser.parse("app.txt", byteArrayOf(1)) }
        failsContaining("为空") { FirmwareParser.parse("app.bin", byteArrayOf(), 0x08000000) }
        failsContaining("ASCII") { FirmwareParser.parse("app.hex", byteArrayOf(0xff.toByte())) }
        failsContaining("没有可烧录数据") { hex(record(1, 0)) }
    }

    private fun hex(vararg records: String): FirmwareImage =
        FirmwareParser.parse("test.hex", records.joinToString("\n").toByteArray())

    private fun record(type: Int, address: Int, vararg data: Int): String {
        val values = listOf(data.size, address shr 8, address and 255, type) + data.toList()
        val checksum = (-values.sum()) and 255
        return ":" + (values + checksum).joinToString("") { "%02X".format(it) }
    }
}

internal fun failsContaining(text: String, block: () -> Unit) {
    try {
        block()
        fail("Expected rejection containing: $text")
    } catch (error: IllegalArgumentException) {
        assertTrue("Actual: ${error.message}", error.message.orEmpty().contains(text))
    }
}
