package dev.corn.flashing.core.stlink

import org.junit.Assert.*
import org.junit.Test

class UsbKernelErrorsTest {
    @Test fun javaNegativeOneDoesNotIdentifyTheKernelError() {
        assertFalse(UsbKernelErrors.shouldRecoverHalt(-1, null))
        assertFalse(UsbKernelErrors.shouldRecoverHalt(null, null))
        assertEquals("ERRNO_-1", UsbKernelErrors.name(-1))
    }
    @Test fun recoveryRequiresMeasuredPipeErrorOrHaltBit() {
        assertTrue(UsbKernelErrors.shouldRecoverHalt(32, 0))
        assertTrue(UsbKernelErrors.shouldRecoverHalt(null, 1))
        assertFalse(UsbKernelErrors.shouldRecoverHalt(13, 0))
        assertFalse(UsbKernelErrors.shouldRecoverHalt(22, 0))
        assertFalse(UsbKernelErrors.shouldRecoverHalt(null, 2))
    }
    @Test fun permissionsDeviceLossAndProtocolErrorsRemainDistinct() {
        assertEquals("EACCES", UsbKernelErrors.name(13))
        assertEquals("EINVAL", UsbKernelErrors.name(22))
        assertEquals("ENODEV", UsbKernelErrors.name(19))
        assertEquals("EPROTO", UsbKernelErrors.name(71))
        assertEquals("EPIPE", UsbKernelErrors.name(32))
        assertEquals("ERRNO_9999", UsbKernelErrors.name(9999))
    }
}
