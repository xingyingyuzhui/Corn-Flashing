package dev.corn.flashing.core

import dev.corn.flashing.core.targets.*
import org.junit.Assert.*
import org.junit.Test

class ReservedRegionPolicyTest {
    private val target = TargetInfo(0x414, 0, 256L * 1024, "test", "F1")
    private val descriptor = TargetCatalog.find(target.deviceId)!!
    private val profile = descriptor.profileFor(target, ProductProfiles.AUTO_DETECT)
    private val tail = AddressRange(0x0803F800, 0x08040000)

    @Test fun disabledByDefaultAndSavedUserRangesDoNotChangeProfileOrFingerprint() {
        assertFalse(ReservedRegionPolicy().enabled)
        val intrinsic = profile.copy(reservedRanges = listOf(AddressRange(0x08000000, 0x08000800)))
        val result = ReservedRegionPolicy(ranges = listOf(tail)).apply(intrinsic, target, descriptor)
        assertSame(intrinsic, result)
        assertEquals(intrinsic.configSha256, result.configSha256)
    }

    @Test fun enablingAppliesOnlyWholePagesAndChangesFingerprint() {
        val result = ReservedRegionPolicy(true, listOf(tail)).apply(profile, target, descriptor)
        assertEquals(listOf(tail), result.reservedRanges)
        assertNotEquals(profile.configSha256, result.configSha256)
        assertEquals(profile.id, result.id)
        assertThrows(IllegalArgumentException::class.java) { ReservedRegionPolicy(true).apply(profile, target, descriptor) }
    }

    @Test fun enabledUserRangesPreserveIntrinsicRangesAndSortWithoutMerging() {
        val head = AddressRange(0x08000000, 0x08000800)
        val intrinsic = profile.copy(reservedRanges = listOf(tail))
        val result = ReservedRegionPolicy(true, listOf(head)).apply(intrinsic, target, descriptor)
        assertEquals(listOf(head, tail), result.reservedRanges)
        assertThrows(IllegalArgumentException::class.java) {
            ReservedRegionPolicy(true, listOf(tail)).apply(intrinsic, target, descriptor)
        }
    }

    @Test fun inclusiveAddressInputsRemainLosslessAt32BitEnd() {
        assertEquals(tail, ReservedRegionPolicy.parseRange("0x0803F800", "0803FFFF"))
        assertEquals(AddressRange(0xFFFFFFFEL, 0x1_0000_0000L), ReservedRegionPolicy.parseRange("FFFFFFFE", "FFFFFFFF"))
        assertEquals(AddressRange(0, 1), ReservedRegionPolicy.parseRange("0", "0"))
        for ((start, end) in listOf("08001000" to "08000000", "xyz" to "0803FFFF", "0803F800" to "100000000", "" to "1", "-1" to "1")) {
            assertThrows(IllegalArgumentException::class.java) { ReservedRegionPolicy.parseRange(start, end) }
        }
    }

    @Test fun outOfFlashPartialPagesAndOverlapsAreRejectedWithoutRounding() {
        val invalid = listOf(
            listOf(AddressRange(0x07FFF800, 0x08000000)),
            listOf(AddressRange(0x0803F800, 0x08040800)),
            listOf(AddressRange(0x0803F801, 0x08040000)),
            listOf(AddressRange(0x0803F800, 0x0803FFFF)),
            listOf(tail, tail),
            listOf(AddressRange(0x0803F000, 0x08040000), tail),
        )
        invalid.forEach { ranges ->
            assertThrows(IllegalArgumentException::class.java) { ReservedRegionPolicy(true, ranges).apply(profile, target, descriptor) }
        }
    }

    @Test fun nonUniformSectorsUseActualBoundariesInsteadOfMinimumPageSize() {
        val f4 = TargetCatalog.find(0x413)!!
        val actual = TargetInfo(0x413, 0, 512L * 1024, "test", f4.family)
        val template = f4.profileFor(actual, ProductProfiles.AUTO_DETECT)
        val valid = listOf(AddressRange(0x08010000, 0x08040000)) // one64KB + one128KB sector
        assertEquals(valid, ReservedRegionPolicy(true, valid).apply(template, actual, f4).reservedRanges)
        assertThrows(IllegalArgumentException::class.java) {
            // 16KB aligned but it divides a physical 128KB sector.
            ReservedRegionPolicy(true, listOf(AddressRange(0x08024000, 0x08040000))).apply(template, actual, f4)
        }
    }

    @Test fun plannerRejectsFirmwareInsideEnabledRegionAndKeepsOtherPages() {
        val guarded = ReservedRegionPolicy(true, listOf(tail)).apply(profile, target, descriptor)
        val firmware = FirmwareParser.parse("safe.bin", byteArrayOf(1, 2), 0x08000000)
        val plan = FlashPlanner.plan(firmware, guarded, target)
        assertEquals(listOf(0x08000000L), plan.erasePageAddresses)
        assertTrue(plan.erasePageAddresses.none { address -> tail.overlaps(AddressRange(address, address + 2048)) })
        val conflict = FirmwareParser.parse("conflict.bin", byteArrayOf(1, 2), tail.start)
        val failure = assertThrows(IllegalArgumentException::class.java) { FlashPlanner.plan(conflict, guarded, target) }
        assertTrue(failure.message!!.contains("覆盖产品保留区"))
    }

    /**
     * The task/batch fingerprint must react to the reserved region itself, otherwise a batch could
     * keep writing after the operator changed what is protected. Mirrors the app-layer check that
     * compares `expectedProfileSha256` against the profile re-derived at erase time.
     */
    @Test fun fingerprintDetectsReservedRegionChangesAtTheSameDeviceAndCapacity() {
        val disabled = ReservedRegionPolicy(false, listOf(tail)).apply(profile, target, descriptor)
        val enabled = ReservedRegionPolicy(true, listOf(tail)).apply(profile, target, descriptor)
        val otherPage = ReservedRegionPolicy(true, listOf(AddressRange(0x0803F000, 0x0803F800)))
            .apply(profile, target, descriptor)
        assertEquals(profile.configSha256, disabled.configSha256)
        assertNotEquals(disabled.configSha256, enabled.configSha256)
        assertNotEquals(enabled.configSha256, otherPage.configSha256)
        // Stable: the same policy re-derived for the same target must reproduce the frozen hash.
        assertEquals(enabled.configSha256, ReservedRegionPolicy(true, listOf(tail)).apply(profile, target, descriptor).configSha256)
    }
}
