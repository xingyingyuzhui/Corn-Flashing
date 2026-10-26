package dev.corn.flashing.core

import org.junit.Assert.*
import org.junit.Test

class ProductProfilesTest {
    @Test fun openTemplateAcceptsEveryKnownGroup() {
        assertEquals(setOf(0x412, 0x410, 0x414, 0x430), ProductProfiles.F103_OPEN.expectedDeviceIds)
        assertEquals(ProductProfiles.ALL_DEVICE_IDS, ProductProfiles.F103_OPEN.expectedDeviceIds)
    }

    @Test fun alignsEachDeviceGroup() {
        val cases = listOf(
            Triple(0x412, 16L * 1024, 1024),
            Triple(0x412, 32L * 1024, 1024),
            Triple(0x410, 64L * 1024, 1024),
            Triple(0x410, 128L * 1024, 1024),
            Triple(0x414, 256L * 1024, 2048),
            Triple(0x414, 512L * 1024, 2048),
            Triple(0x430, 1024L * 1024, 2048),
        )
        cases.forEach { (id, bytes, page) ->
            val target = TargetInfo(id, 0x2003, bytes, "UID", ProductProfiles.familyName(id))
            val aligned = ProductProfiles.alignedTo(ProductProfiles.F103_OPEN, target)
            assertEquals(setOf(id), aligned.expectedDeviceIds)
            assertEquals(bytes, aligned.flashSizeBytes)
            assertEquals(page, aligned.pageSize)
            assertTrue(aligned.name.contains("${bytes / 1024} KB"))
            if (id == 0x430) {
                assertThrows(IllegalArgumentException::class.java) {
                    FlashPlanner.plan(FirmwareParser.parse("t.bin", byteArrayOf(1), 0x08000000L), ProductProfiles.F103_OPEN, target)
                }
                return@forEach
            }
            val plan = FlashPlanner.plan(
                FirmwareParser.parse("t.bin", byteArrayOf(1), 0x08000000L),
                ProductProfiles.F103_OPEN,
                target,
            )
            assertEquals(bytes, plan.profile.flashSizeBytes)
            assertEquals(page, plan.profile.pageSize)
        }
    }

    @Test fun rejectsIllegalCapacityForGroup() {
        val target = TargetInfo(0x410, 0, 32L * 1024, null, "bad") // 32K not legal for 0x410
        try {
            ProductProfiles.alignedTo(ProductProfiles.F103_OPEN, target)
            fail("expected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("容量异常"))
        }
        try {
            FlashPlanner.plan(
                FirmwareParser.parse("t.bin", byteArrayOf(1), 0x08000000L),
                ProductProfiles.F103_OPEN,
                target,
            )
            fail("expected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("容量异常"))
        }
    }

    @Test fun rejectsUnknownDeviceGroup() {
        val target = TargetInfo(0x999, 0, 64L * 1024, null, "x")
        try {
            ProductProfiles.alignedTo(ProductProfiles.F103_OPEN, target)
            fail("expected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("未知") || e.message!!.contains("尚未支持"))
        }
    }
}
