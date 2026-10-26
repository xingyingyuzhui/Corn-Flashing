package dev.corn.flashing.core.stlink

import org.junit.Assert.*
import org.junit.Test

class StLinkUsbLayoutTest {
    @Test fun prefersClassicV2EndpointsOn3748() {
        assertEquals(0x02 to 0x81, StLinkUsbLayout.preferredOutIn(0x3748))
        val pick = StLinkUsbLayout.pick(
            0x3748,
            listOf(
                0 to listOf(
                    StLinkUsbLayout.BulkEp(0x02, true),
                    StLinkUsbLayout.BulkEp(0x81, false),
                    StLinkUsbLayout.BulkEp(0x83, false),
                ),
            ),
        )
        assertEquals(0, pick.interfaceIndex)
        assertEquals(0x02, pick.outAddr)
        assertEquals(0x81, pick.inAddr)
    }

    @Test fun prefersV21EndpointsOn374B() {
        assertEquals(0x01 to 0x81, StLinkUsbLayout.preferredOutIn(0x374B))
        val pick = StLinkUsbLayout.pick(
            0x374B,
            listOf(
                0 to listOf(
                    StLinkUsbLayout.BulkEp(0x01, true),
                    StLinkUsbLayout.BulkEp(0x81, false),
                ),
            ),
        )
        assertEquals(0x01, pick.outAddr)
        assertEquals(0x81, pick.inAddr)
    }

    @Test fun scansLaterInterfaceWhenFirstHasNoBulkPair() {
        val pick = StLinkUsbLayout.pick(
            0x3748,
            listOf(
                0 to listOf(StLinkUsbLayout.BulkEp(0x81, false)), // IN only
                1 to listOf(
                    StLinkUsbLayout.BulkEp(0x02, true),
                    StLinkUsbLayout.BulkEp(0x81, false),
                ),
            ),
        )
        assertEquals(1, pick.interfaceIndex)
        assertEquals(0x02, pick.outAddr)
    }

    @Test fun rejectsDeviceWithNoBulkPair() {
        try {
            StLinkUsbLayout.pick(0x3748, listOf(0 to emptyList()))
            fail("expected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("bulk"))
        }
    }
    @Test fun doesNotResetTheAlreadyActiveConfiguration() {
        assertFalse(StLinkUsbLayout.needsConfigurationSwitch(1, 1))
        assertFalse(StLinkUsbLayout.needsConfigurationSwitch(1, 1, tryIfUnknown = true))
        assertTrue(StLinkUsbLayout.needsConfigurationSwitch(0, 1))
        assertTrue(StLinkUsbLayout.needsConfigurationSwitch(2, 1))
    }

    @Test fun unknownConfigurationUsesSystemDefaultBeforeExplicitCompatibilityAttempt() {
        assertFalse(StLinkUsbLayout.needsConfigurationSwitch(null, 1))
        assertTrue(StLinkUsbLayout.needsConfigurationSwitch(null, 1, tryIfUnknown = true))
        assertThrows(IllegalArgumentException::class.java) { StLinkUsbLayout.needsConfigurationSwitch(1, 0) }
    }

}
