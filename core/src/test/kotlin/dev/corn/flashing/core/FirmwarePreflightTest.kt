package dev.corn.flashing.core

import org.junit.Assert.*
import org.junit.Test

class FirmwarePreflightTest {
    private val image = FirmwareImage(FirmwareFormat.BIN,
        listOf(FirmwareSegment(0x08000000, ByteArray(69668))), "test")
    private fun target(kib: Long) = TargetInfo(0x410, 0x2003, kib * 1024, "TEST", "F1")

    @Test fun unknownTargetDoesNotClaimCompatibilityOrRejectAgainstPlaceholder() {
        val check = FirmwarePreflight.inspect(image, ProductProfiles.F103_OPEN, null)
        assertFalse(check.targetKnown)
        assertTrue(check.canStart)
        assertNull(check.error)
    }

    @Test fun known64KiBRejectsTheUserSizedImageBeforeStart() {
        val check = FirmwarePreflight.inspect(image, ProductProfiles.F103_OPEN, target(64))
        assertTrue(check.targetKnown)
        assertFalse(check.canStart)
        assertTrue(check.error!!.contains("超出允许范围"))
    }

    @Test fun known128KiBAcceptsTheSameImage() {
        val check = FirmwarePreflight.inspect(image, ProductProfiles.F103_OPEN, target(128))
        assertTrue(check.targetKnown)
        assertTrue(check.canStart)
    }

    @Test fun checksWholeErasePageAgainstReservedBytes() {
        val shortImage = FirmwareImage(FirmwareFormat.BIN,
            listOf(FirmwareSegment(0x08000000, byteArrayOf(1, 2))), "test")
        val reserved = ProductProfiles.F103_OPEN.copy(reservedRanges = listOf(AddressRange(0x08000020, 0x08000040)))
        val check = FirmwarePreflight.inspect(shortImage, reserved, target(128))
        assertFalse(check.canStart)
        assertTrue(check.error!!.contains("边界对齐"))
    }
    @Test fun configurationFailuresDoNotMasqueradeAsUsbFailures() {
        assertTrue(isFirmwareConfigurationError("固件地址超出允许范围：0x08010000"))
        assertTrue(isFirmwareConfigurationError("擦除页 0x08000000 包含保留数据，当前不支持自动读改写"))
        assertFalse(isFirmwareConfigurationError("未找到已授权的 ST-LINK，请在设备页授权后再试"))
        assertFalse(isFirmwareConfigurationError("进入 SWD：ST-LINK 状态 0x10"))
    }

}
