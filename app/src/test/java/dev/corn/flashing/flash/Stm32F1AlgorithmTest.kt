package dev.corn.flashing.flash

import dev.corn.flashing.core.TargetInfo
import dev.corn.flashing.core.engine.*
import dev.corn.flashing.core.stlink.StLinkVersion
import dev.corn.flashing.core.targets.TargetCatalog
import org.junit.Assert.*
import org.junit.Test

class Stm32F1AlgorithmTest {
    private val target = TargetInfo(0x414, 0, 256L * 1024, "test", "F1")
    private fun info(jtag: Int) = StLinkProbeCapabilities.describe(StLinkVersion(2, jtag, 4, byteArrayOf()), "USBFS_NATIVE")
    @Test fun resolvesBothLegacyAndNativeProbePaths() {
        val driver = Stm32F1Algorithm()
        val registry = FlashAlgorithmRegistry(listOf(driver))
        for (jtag in listOf(13, 23, 26, 45)) {
            assertSame(driver, registry.resolve(TargetCatalog.find(0x414)!!, info(jtag), target))
        }
    }
    @Test fun missingProbeCapabilitiesAndOtherTargetFamiliesAreRejected() {
        val driver = Stm32F1Algorithm()
        assertThrows(IllegalArgumentException::class.java) { driver.validate(info(12), target) }
        assertThrows(IllegalArgumentException::class.java) { driver.validate(info(23), target.copy(deviceId = 0x413, flashSizeBytes = 1024L * 1024)) }
        assertThrows(IllegalArgumentException::class.java) { driver.validate(info(23), target.copy(deviceId = 0x430, flashSizeBytes = 1024L * 1024)) }
    }
}
