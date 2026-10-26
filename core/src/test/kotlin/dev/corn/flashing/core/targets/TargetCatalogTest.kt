package dev.corn.flashing.core.targets

import dev.corn.flashing.core.*
import org.junit.Assert.*
import org.junit.Test

class TargetCatalogTest {
    @Test fun builtinCatalogSelectsPerCoreRegistersAndReadOnlyFamilies() {
        assertEquals(6, TargetCatalog.descriptors.size)
        val f0 = TargetCatalog.find(0x448)!!
        assertEquals(0xC20, f0.corePart)
        assertEquals(0x40015800L, f0.dbgmcuAddress)
        assertEquals(0x1FFFF7CCL, f0.flashSizeAddress)
        assertEquals(TargetSupport.IDENTIFY_ONLY, f0.support)
        val f4 = TargetCatalog.find(0x413)!!
        assertEquals(0xC24, f4.corePart)
        assertEquals(0x1FFF7A22L, f4.flashSizeAddress) // 16-bit, not a word-aligned register.
        assertNull(f4.algorithmId)
        assertEquals(2, TargetCatalog.identificationAddresses.size)
        assertNull(TargetCatalog.match(0x448, 0xE0042000))
        assertEquals(f0, TargetCatalog.match(0x448, 0x40015800))
    }

    @Test fun readOnlyTargetCanDisplayButCannotPlanAnErase() {
        for (id in listOf(0x430, 0x448, 0x413)) {
            val descriptor = TargetCatalog.find(id)!!
            val target = target(descriptor, descriptor.allowedCapacityKiB.min())
            val profile = ProductProfiles.alignedTo(ProductProfiles.AUTO_DETECT, target)
            assertEquals(descriptor.flashStart, profile.flashStart)
            val failure = assertThrows(IllegalArgumentException::class.java) {
                FlashPlanner.plan(image(descriptor.flashStart), profile, target)
            }
            assertTrue(failure.message!!.contains("仅支持识别"))
        }
    }

    @Test fun nonUniformGeometryFindsActualSectorsAcrossTwoBoundaries() {
        val descriptor = programmableF4()
        val target = target(descriptor, 512)
        val image = FirmwareImage(FirmwareFormat.BIN, listOf(
            FirmwareSegment(descriptor.flashStart + 0xFFFF, byteArrayOf(1, 2)),
            FirmwareSegment(descriptor.flashStart + 0x20000, byteArrayOf(3)),
        ), "test")
        val plan = FlashPlanner.plan(image, ProductProfiles.AUTO_DETECT, target, TargetRegistry(listOf(descriptor)))
        assertEquals(listOf(0x0800C000L, 0x08010000L, 0x08020000L), plan.erasePageAddresses)
        assertEquals(listOf(16384, 65536, 131072), plan.erasePageAddresses.map { plan.eraseSectorSizes[it] })
    }

    @Test fun nonUniformEraseStillProtectsReservedBytesOutsideWrittenBytes() {
        val descriptor = programmableF4()
        val target = target(descriptor, 512)
        val policy = ProductProfiles.AUTO_DETECT.copy(reservedRanges = listOf(AddressRange(0x08028000, 0x08028001)))
        val failure = assertThrows(IllegalArgumentException::class.java) {
            FlashPlanner.plan(image(0x08020000), policy, target, TargetRegistry(listOf(descriptor)))
        }
        assertTrue(failure.message!!.contains("边界对齐"))
    }

    @Test fun geometryRejectsTruncatedSectorsGapsAndOverlaps() {
        val descriptor = TargetCatalog.find(0x413)!!
        assertEquals(8, descriptor.geometry.sectors(descriptor.flashStart, 512L * 1024).size)
        assertEquals(12, descriptor.geometry.sectors(descriptor.flashStart, 1024L * 1024).size)
        assertThrows(IllegalArgumentException::class.java) { descriptor.geometry.sectors(descriptor.flashStart, 513L * 1024) }
        assertThrows(IllegalArgumentException::class.java) {
            FlashGeometry(listOf(SectorRegion(0, 1024, 1), SectorRegion(2048, 1024, 0)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FlashGeometry(listOf(SectorRegion(0, 1024, 2), SectorRegion(1024, 1024, 0)))
        }
    }

    @Test fun malformedCatalogAndDuplicateIdentitiesFailClosed() {
        val raw = requireNotNull(javaClass.getResourceAsStream("/dev/corn/flashing/targets/builtin.properties"))
            .bufferedReader().use { it.readText() }
        assertThrows(IllegalArgumentException::class.java) {
            TargetRegistry.read(raw.replace("stm32f0-448.deviceId=0x448", "stm32f0-448.deviceId=0x410").reader())
        }
        assertThrows(IllegalArgumentException::class.java) {
            TargetRegistry.read(raw.replace("stm32f1-low.sectors=0:1024:*", "stm32f1-low.sectors=0:1000:*").reader())
        }
        assertThrows(IllegalArgumentException::class.java) {
            TargetRegistry.read(raw.replace("stm32f0-448.deviceId=0x448", "stm32f0-448.deviceId=0x100000448").reader())
        }
    }

    @Test fun legacyF1ProfileIdentityAndHashRemainStableWhileAutoProfileIsGeneric() {
        val target = TargetInfo(0x410, 1, 64L * 1024, null, "F1")
        val legacy = ProductProfiles.alignedTo(ProductProfiles.F103_OPEN, target)
        val expected = ProductProfiles.F103_OPEN.copy(id = "f103-0x410-64k", name = "STM32F1 中容量 · 64 KB", expectedDeviceIds = setOf(0x410))
        assertEquals(expected.configSha256, legacy.configSha256)
        assertEquals(setOf(0x412, 0x410, 0x414, 0x430), ProductProfiles.F103_OPEN.expectedDeviceIds)
        assertTrue(ProductProfiles.AUTO_DETECT.expectedDeviceIds.containsAll(listOf(0x448, 0x413)))
        val aligned = ProductProfiles.alignedTo(ProductProfiles.AUTO_DETECT, target)
        assertEquals(legacy.id, aligned.id)
        assertEquals(legacy.configSha256, aligned.configSha256)
    }

    @Test fun autoPolicyUsesDescriptorBaseWhileExplicitPolicyRemainsBounded() {
        val descriptor = TargetCatalog.find(0x410)!!.copy(key = "test-non-st-base", flashStart = 0x10000000L)
        val target = target(descriptor, 64)
        val plan = FlashPlanner.plan(image(0x10000000), ProductProfiles.AUTO_DETECT, target, TargetRegistry(listOf(descriptor)))
        assertEquals(0x10000000L, plan.profile.flashStart)
        assertThrows(IllegalArgumentException::class.java) {
            FlashPlanner.plan(image(0x10000000), ProductProfiles.F103_OPEN, target, TargetRegistry(listOf(descriptor)))
        }
    }

    private fun target(descriptor: TargetDescriptor, kib: Long) = TargetInfo(descriptor.deviceId, 1, kib * 1024, null, descriptor.family)
    private fun image(address: Long) = FirmwareImage(FirmwareFormat.BIN, listOf(FirmwareSegment(address, byteArrayOf(1, 2))), "test")
    private fun programmableF4() = TargetCatalog.find(0x413)!!.copy(
        // A test-only implementation proves planner geometry; it does not enable F4 in the shipped catalog.
        algorithmId = "test-only", support = TargetSupport.FLASH_ALGORITHM,
    )
}
