package dev.corn.flashing.core.engine

import dev.corn.flashing.core.targets.*
import org.junit.Assert.*
import org.junit.Test

class TargetIdentifierTest {
    private val f1 = TargetCatalog.descriptors.first { it.deviceId == 0x410 }
    private val f0 = f1.copy(key = "test-f0", family = "STM32F0", deviceId = 0x448, corePart = 0xC20,
        dbgmcuAddress = 0x40015800, flashSizeAddress = 0x1FFFF7CC, uidAddress = 0x1FFFF7AC,
        allowedCapacityKiB = setOf(64, 128), support = TargetSupport.IDENTIFY_ONLY, algorithmId = null)
    private val f4 = f1.copy(key = "test-f4", family = "STM32F4", deviceId = 0x413, corePart = 0xC24,
        flashSizeAddress = 0x1FFF7A22, uidAddress = 0x1FFF7A10, allowedCapacityKiB = setOf(1024),
        geometry = FlashGeometry(listOf(SectorRegion(0, 16384, 4), SectorRegion(65536, 65536, 1), SectorRegion(131072, 131072, 0))),
        support = TargetSupport.IDENTIFY_ONLY, algorithmId = null)
    private val registry = TargetRegistry(listOf(f1, f0, f4))

    @Test fun readsF1WithoutWritingOrTouchingOtherCoreAddresses() {
        val probe = FakeProbe(f1, 128)
        val target = TargetIdentifier(registry).identify(probe)
        assertEquals(0x410, target.deviceId)
        assertEquals(0x2003, target.revisionId)
        assertEquals(128L * 1024, target.flashSizeBytes)
        assertEquals("00010203-04050607-08090A0B", target.uid)
        assertEquals(listOf(TargetIdentifier.CPUID_ADDRESS to 4, f1.dbgmcuAddress to 4,
            f1.flashSizeAddress to 2, f1.uidAddress to 12), probe.reads)
    }

    @Test fun cortexM0SelectsOnlyF0IdentificationRegisters() {
        val probe = FakeProbe(f0, 64)
        assertEquals("STM32F0", TargetIdentifier(registry).identify(probe).family)
        assertTrue(probe.reads.contains(f0.dbgmcuAddress to 4))
        assertFalse(probe.reads.contains(f1.dbgmcuAddress to 4))
        assertTrue(probe.reads.contains(f0.flashSizeAddress to 2))
    }

    @Test fun f4ReadsUnalignedHalfwordCapacityRegisterExactly() {
        val probe = FakeProbe(f4, 1024)
        assertEquals(1024L * 1024, TargetIdentifier(registry).identify(probe).flashSizeBytes)
        assertEquals(2L, f4.flashSizeAddress % 4)
        assertTrue(probe.reads.contains(0x1FFF7A22L to 2))
    }

    @Test fun unknownCpuStopsBeforePeripheralAddressReads() {
        val probe = FakeProbe(f1, 128, partOverride = 0xC27)
        val error = assertThrows(IllegalArgumentException::class.java) { TargetIdentifier(registry).identify(probe) }
        assertTrue(error.message!!.contains("核心类型"))
        assertEquals(listOf(TargetIdentifier.CPUID_ADDRESS to 4), probe.reads)
    }

    @Test fun unknownDeviceDoesNotDefaultToF1OrReadCapacity() {
        val probe = FakeProbe(f1, 128, idOverride = 0x999)
        val error = assertThrows(IllegalArgumentException::class.java) { TargetIdentifier(registry).identify(probe) }
        assertTrue(error.message!!.contains("0x999"))
        assertEquals(listOf(TargetIdentifier.CPUID_ADDRESS to 4, f1.dbgmcuAddress to 4), probe.reads)
    }

    @Test fun deviceIdForDifferentCoreIsRejected() {
        val probe = FakeProbe(f1, 128, idOverride = f4.deviceId)
        assertThrows(IllegalArgumentException::class.java) { TargetIdentifier(registry).identify(probe) }
        assertEquals(2, probe.reads.size)
    }

    @Test fun invalidCapacityStopsBeforeUidRead() {
        val probe = FakeProbe(f1, 99)
        val error = assertThrows(IllegalArgumentException::class.java) { TargetIdentifier(registry).identify(probe) }
        assertTrue(error.message!!.contains("容量异常"))
        assertFalse(probe.reads.any { it.first == f1.uidAddress })
    }

    @Test fun truncatedCapacityResponseIsNotAccepted() {
        val probe = FakeProbe(f1, 128, shortSize = true)
        assertThrows(IllegalStateException::class.java) { TargetIdentifier(registry).identify(probe) }
        assertEquals(3, probe.reads.size)
    }

    private class FakeProbe(
        val descriptor: TargetDescriptor,
        val sizeKiB: Int,
        val partOverride: Int = descriptor.corePart,
        val idOverride: Int = descriptor.deviceId,
        val shortSize: Boolean = false,
    ) : DebugProbe {
        val reads = mutableListOf<Pair<Long, Int>>()
        override val probeInfo = ProbeInfo(ProbeKind.UNKNOWN, "test", emptySet(), "fake")
        override fun readMemory(address: Long, length: Int): ByteArray {
            reads += address to length
            return when (address) {
                TargetIdentifier.CPUID_ADDRESS -> le32(0x410F0000L or (partOverride.toLong() shl 4))
                descriptor.dbgmcuAddress -> le32((0x2003L shl 16) or idOverride.toLong())
                descriptor.flashSizeAddress -> if (shortSize) byteArrayOf(0) else byteArrayOf(sizeKiB.toByte(), (sizeKiB ushr 8).toByte())
                descriptor.uidAddress -> ByteArray(12) { it.toByte() }
                else -> error("Unexpected read 0x${address.toString(16)}")
            }
        }
        private fun le32(value: Long) = ByteArray(4) { (value ushr (it * 8)).toByte() }
        override fun writeMemory32(address: Long, data: ByteArray): Unit = error("Identification must not write")
        override fun writeHalfWord(address: Long, value: Int): Unit = error("Identification must not write")
        override fun halt(): Unit = error("Identification must not halt")
        override fun isHalted(): Boolean = error("Identification must only read registered memory")
        override fun resume(maskInterrupts: Boolean): Unit = error("Identification must not resume")
        override fun readCoreRegister(index: Int): Long = error("Unexpected register access")
        override fun writeCoreRegister(index: Int, value: Long): Unit = error("Identification must not write registers")
        override fun resetAndRun(): Unit = error("Identification must not reset")
    }
}
