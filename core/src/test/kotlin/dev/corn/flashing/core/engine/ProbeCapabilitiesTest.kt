package dev.corn.flashing.core.engine

import dev.corn.flashing.core.*
import dev.corn.flashing.core.stlink.StLinkVersion
import dev.corn.flashing.core.targets.TargetCatalog
import org.junit.Assert.*
import org.junit.Test

class ProbeCapabilitiesTest {
    private fun probe(jtag: Int, hardware: Int = 2) = StLinkProbeCapabilities.describe(StLinkVersion(hardware, jtag, 4, byteArrayOf()), "USBFS_NATIVE")

    @Test fun oldFirmwareUsesRamWithoutClaimingNativeHalfwords() {
        assertTrue(probe(12).capabilities.isEmpty())
        assertTrue(ProbeCapability.CORE_REGISTERS in probe(13).capabilities)
        assertEquals("RAM 算法执行", probe(23).programmingPath)
        assertFalse(ProbeCapability.NATIVE_HALFWORD_WRITE in probe(23).capabilities)
        assertTrue(ProbeCapability.NATIVE_HALFWORD_WRITE in probe(26).capabilities)
    }
    @Test fun v3DoesNotInheritUnvalidatedV2ProgrammingSupport() {
        assertEquals(ProbeKind.ST_LINK_V3, probe(45, 3).kind)
        assertTrue(probe(45, 3).capabilities.isEmpty())
    }
    @Test fun missingAndReadOnlyAlgorithmsCannotResolve() {
        val registry = FlashAlgorithmRegistry(emptyList())
        val f1 = TargetInfo(0x414, 0, 256L * 1024, "test", "F1")
        assertThrows(IllegalArgumentException::class.java) { registry.resolve(TargetCatalog.find(0x414)!!, probe(23), f1) }
        val f4 = TargetInfo(0x413, 0, 1024L * 1024, "test", "F4")
        assertThrows(IllegalArgumentException::class.java) { registry.resolve(TargetCatalog.find(0x413)!!, probe(23), f4) }
    }
}
