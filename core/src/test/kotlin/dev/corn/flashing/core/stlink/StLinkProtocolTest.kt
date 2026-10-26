package dev.corn.flashing.core.stlink

import org.junit.Assert.*
import org.junit.Test

class StLinkProtocolTest {
    @Test fun packsReadMem32LittleEndian() {
        val cmd = StLinkProtocol.readMem32(0x1FFFF7E0L, 4)
        assertEquals(16, cmd.size)
        assertEquals(0xF2.toByte(), cmd[0])
        assertEquals(0x07.toByte(), cmd[1])
        assertEquals(0xE0.toByte(), cmd[2])
        assertEquals(0xF7.toByte(), cmd[3])
        assertEquals(0xFF.toByte(), cmd[4])
        assertEquals(0x1F.toByte(), cmd[5])
        assertEquals(4.toByte(), cmd[6])
        assertEquals(0.toByte(), cmd[7])
    }

    @Test fun recognizesWireModeValues() {
        assertEquals(StLinkProtocol.MODE_DFU, StLinkProtocol.parseMode(byteArrayOf(0, 0)))
        assertEquals(StLinkProtocol.MODE_MASS, StLinkProtocol.parseMode(byteArrayOf(1, 0)))
        assertEquals(StLinkProtocol.MODE_DEBUG, StLinkProtocol.parseMode(byteArrayOf(2, 0)))
    }

    @Test fun packsEnterSwd() {
        assertArrayEquals(StLinkProtocol.cmd(0xF2, 0x30, 0xA3), StLinkProtocol.enterSwd())
    }

    @Test fun parsesVersionJtagApi() {
        // stlink=2, jtag=26, swim=4 → packed ((2<<12)|(26<<6)|4) = 0x2694
        val packed = (2 shl 12) or (26 shl 6) or 4
        val raw = byteArrayOf(
            ((packed shr 8) and 0xFF).toByte(), (packed and 0xFF).toByte(),
            0, 0, 0, 0,
        )
        val v = StLinkProtocol.parseVersion(raw)
        assertEquals(2, v.stlink)
        assertEquals(26, v.jtag)
        assertEquals(4, v.swim)
    }

    @Test fun flashSize64And128FromRegister() {
        assertEquals(64L * 1024, StLinkProtocol.flashSizeBytesFromRegister(64))
        assertEquals(128L * 1024, StLinkProtocol.flashSizeBytesFromRegister(128))
        assertEquals(128L * 1024, StLinkProtocol.flashSizeBytesFromRegister(0x12340080)) // low 16 bits
    }

    @Test fun rejectsBogusFlashSize() {
        try {
            StLinkProtocol.flashSizeBytesFromRegister(99)
            fail("expected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("容量异常"))
        }
    }

    @Test fun parsesDbgmcuAndUid() {
        val (dev, rev) = StLinkProtocol.parseDbgmcuIdCode(0x20036410)
        assertEquals(0x410, dev)
        assertEquals(0x2003, rev)
        val uid = StLinkProtocol.formatUid(byteArrayOf(
            0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte(),
            0x99.toByte(), 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(),
        ))
        assertEquals("11223344-55667788-99AABBCC", uid)
    }

    @Test fun voltageFromFactorAndReading() {
        // 2400 * reading / factor
        val raw = ByteArray(8)
        StLinkProtocol.writeU32Le(raw, 0, 1000)
        StLinkProtocol.writeU32Le(raw, 4, 1375)
        assertEquals(3300, StLinkProtocol.parseVoltageMv(raw))
    }

    @Test fun packsWriteMem32AndWriteMem8() {
        val w32 = StLinkProtocol.writeMem32(0x08000000L, 16)
        assertEquals(0xF2.toByte(), w32[0])
        assertEquals(0x08.toByte(), w32[1])
        assertEquals(0x00.toByte(), w32[2])
        assertEquals(0x00.toByte(), w32[3])
        assertEquals(0x00.toByte(), w32[4])
        assertEquals(0x08.toByte(), w32[5])
        assertEquals(16.toByte(), w32[6])
        val w8 = StLinkProtocol.writeMem8(0x08000000L, 2)
        assertEquals(0x0D.toByte(), w8[1])
        assertEquals(2.toByte(), w8[6])
    }

    @Test fun packsHaltUnhaltAndFlashKeys() {
        assertEquals(0x35.toByte(), StLinkProtocol.haltCore()[1])
        assertEquals(0x35.toByte(), StLinkProtocol.unhaltCore()[1])
        assertEquals(0x45670123L, StLinkProtocol.FLASH_KEY1)
        assertEquals(0xCDEF89ABL, StLinkProtocol.FLASH_KEY2)
        assertEquals(0x40022010L, StLinkProtocol.ADDR_FLASH_CR)
        assertTrue(StLinkProtocol.flashSrClearMask() and StLinkProtocol.FLASH_SR_EOP.toLong() != 0L)
    }

    @Test fun packsResetSys() {
        assertArrayEquals(StLinkProtocol.cmd(0xF2, 0x32), StLinkProtocol.resetSys())
    }
    @Test fun parsesRealWireOrderAndFirmwareCapability() {
        val v = StLinkProtocol.parseVersion(byteArrayOf(0x2B, 0x47, 0, 0, 0, 0))
        assertEquals(2, v.stlink); assertEquals(45, v.jtag); assertEquals(7, v.swim)
        assertTrue(v.supportsHalfWordWrite)
        v.requireF1ProgrammingSupport()
        assertFalse(StLinkVersion(2, 25, 0, byteArrayOf()).supportsHalfWordWrite)
        assertThrows(IllegalArgumentException::class.java) {
            StLinkVersion(2, 25, 0, byteArrayOf()).requireF1ProgrammingSupport()
        }
        assertFalse(StLinkVersion(3, 1, 0, byteArrayOf()).supportsHalfWordWrite)
    }

    @Test fun packsHalfWordWriteWithExplicit16BitOpcode() {
        assertArrayEquals(byteArrayOf(0xF2.toByte(), 0x48, 0x02, 0, 0, 0x08, 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            StLinkProtocol.writeMem16(0x08000002L, 2))
        assertThrows(IllegalArgumentException::class.java) { StLinkProtocol.writeMem16(0x08000001L, 2) }
        assertThrows(IllegalArgumentException::class.java) { StLinkProtocol.writeMem16(0x08000000L, 3) }
    }

    @Test fun resetWireValuesAndFaultStatusAreStrict() {
        assertArrayEquals(StLinkProtocol.cmd(0xF2, 0x3C, 2), StLinkProtocol.driveNrst(StLinkProtocol.NRST_PULSE))
        assertEquals(0, StLinkProtocol.NRST_LOW); assertEquals(1, StLinkProtocol.NRST_HIGH)
        StLinkProtocol.requireStatusOk(byteArrayOf(0x80.toByte(), 0), "ok")
        assertThrows(IllegalArgumentException::class.java) { StLinkProtocol.requireStatusOk(byteArrayOf(0, 0), "not SWIM") }
        assertThrows(IllegalArgumentException::class.java) { StLinkProtocol.requireStatusOk(byteArrayOf(0x81.toByte(), 0), "fault") }
    }

    @Test fun readMem8SingleByteReplyHasWirePadding() {
        val command = StLinkProtocol.readMem8(0x08000001, 1)
        assertEquals(0x0C.toByte(), command[1])
        assertEquals(1.toByte(), command[6])
        assertEquals(2, StLinkProtocol.readMem8ReplySize(1))
        assertEquals(2, StLinkProtocol.readMem8ReplySize(2))
        assertEquals(64, StLinkProtocol.readMem8ReplySize(64))
        assertThrows(IllegalArgumentException::class.java) { StLinkProtocol.readMem8(0x08000001, 65) }
    }

    @Test fun skipsUnsupportedVoltageCommandInsteadOfIgnoringTransportFailure() {
        assertFalse(StLinkVersion(2, 12, 0, byteArrayOf()).supportsTargetVoltage)
        assertTrue(StLinkVersion(2, 13, 0, byteArrayOf()).supportsTargetVoltage)
        assertTrue(StLinkVersion(2, 26, 0, byteArrayOf()).supportsTargetVoltage)
    }

}
