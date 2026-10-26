package dev.corn.flashing.core

import org.junit.Assert.*
import org.junit.Test

class FlashPlannerTest {
    private val profile = ProductProfile()
    private val target = TargetInfo(0x410, 0x2003, 65536, "test", "F1 medium-density")

    @Test fun plansOnlyTouchedPagesAndIncludesPartialBoundaryPages() {
        val plan = FlashPlanner.plan(bin(1023, 2), profile, target)
        assertEquals(listOf(0x08000000L, 0x08000400L), plan.erasePageAddresses)
        assertEquals(2L, plan.writeBytes)
    }

    @Test fun permitsLastFlashByteButRejectsOneByteBeyond() {
        assertEquals(listOf(0x0800fc00L), FlashPlanner.plan(bin(65535, 1), profile, target).erasePageAddresses)
        failsContaining("超出") { FlashPlanner.plan(bin(65535, 2), profile, target) }
    }

    @Test fun refusesEraseOfReservedBytesInSamePageEvenWithoutDirectWriteOverlap() {
        val guarded = profile.copy(reservedRanges = listOf(AddressRange(0x08000300, 0x08000400)))
        failsContaining("边界对齐") { FlashPlanner.plan(bin(0, 16), guarded, target) }
        failsContaining("边界对齐") { FlashPlanner.plan(bin(0x300, 1), guarded, target) }
        val aligned = profile.copy(reservedRanges = listOf(AddressRange(0x08000000, 0x08000400)))
        failsContaining("覆盖产品保留区") { FlashPlanner.plan(bin(0x300, 1), aligned, target) }
        assertEquals(listOf(0x08000400L), FlashPlanner.plan(bin(0x400, 1), aligned, target).erasePageAddresses)
    }

    @Test fun rejectsUnknownDeviceBogusCapacityAndLockedTemplate() {
        failsContaining("未知") { FlashPlanner.plan(bin(0, 1), profile, target.copy(deviceId = 0x999)) }
        failsContaining("容量异常") { FlashPlanner.plan(bin(0, 1), profile, target.copy(flashSizeBytes = 123)) }
        // Wrong template pageSize is overwritten by alignedTo; a locked expectedDeviceIds still rejects.
        val locked = profile.copy(expectedDeviceIds = setOf(0x414))
        failsContaining("不符合产品配置") { FlashPlanner.plan(bin(0, 1), locked, target) }
        // Open template + wrong pageSize still plans: alignment fixes page size from the chip.
        val plan = FlashPlanner.plan(bin(0, 1), profile.copy(pageSize = 2048), target)
        assertEquals(1024, plan.profile.pageSize)
    }

    @Test fun acceptsProbed128kForMediumDensityAndPlansBeyond64k() {
        val target128 = target.copy(flashSizeBytes = 128L * 1024)
        val plan = FlashPlanner.plan(bin(0x10000, 1), profile, target128)
        assertEquals(128L * 1024, plan.profile.flashSizeBytes)
        assertEquals(listOf(0x08010000L), plan.erasePageAddresses)
        // Same firmware is rejected when the chip only has 64 KB
        failsContaining("超出") { FlashPlanner.plan(bin(0x10000, 1), profile, target) }
    }

    @Test fun rejectsRamAndOverlappingManuallyConstructedImages() {
        failsContaining("超出") {
            FlashPlanner.plan(FirmwareParser.parse("ram.bin", byteArrayOf(1), 0x20000000), profile, target)
        }
        val image = FirmwareImage(FirmwareFormat.BIN, listOf(
            FirmwareSegment(0x08000000, byteArrayOf(1, 2)),
            FirmwareSegment(0x08000001, byteArrayOf(2)),
        ), "test")
        failsContaining("重叠") { FlashPlanner.plan(image, profile, target) }
    }

    private fun bin(offset: Int, size: Int): FirmwareImage =
        FirmwareParser.parse("app.bin", ByteArray(size) { it.toByte() }, 0x08000000L + offset)
}
