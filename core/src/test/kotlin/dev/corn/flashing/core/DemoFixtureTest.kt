package dev.corn.flashing.core

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class DemoFixtureTest {
    @Test fun `bundled demo is parseable and fits default profile`() {
        val bytes = File(System.getProperty("corn.demoHex")).readBytes()
        val image = FirmwareParser.parse("demo.hex", bytes)
        val target = TargetInfo(0x410, 0x2003, 65536, "DEMO-00000001", "simulation")
        val plan = FlashPlanner.plan(image, ProductProfile(), target)
        assertEquals(16L, image.totalBytes)
        assertEquals(listOf(0x08000000L), plan.erasePageAddresses)
    }
}
