package dev.corn.flashing.core.stlink

import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class StLinkBulkTransfersTest {
    private class Io(
        val writeResults: MutableList<Int> = mutableListOf(),
        val reply: ByteArray = byteArrayOf(0x2B, 0x47, 0, 0, 0, 0),
        var readResult: Int = reply.size,
    ) : StLinkBulkIo {
        val writes = mutableListOf<ByteArray>()
        var reads = 0
        var writeFailure: Exception? = null
        var readFailure: Exception? = null
        override fun write(data: ByteArray): Int {
            writes += data.copyOf()
            writeFailure?.let { throw it }
            return if (writeResults.isEmpty()) data.size else writeResults.removeAt(0)
        }
        override fun read(buffer: ByteArray): Int {
            reads++
            readFailure?.let { throw it }
            reply.copyInto(buffer, endIndex = minOf(buffer.size, reply.size))
            return readResult
        }
    }

    @Test fun getVersionCommandAndReplyKeepTheirExactBoundaries() {
        val io = Io()
        val transfer = StLinkBulkTransfers(io, "sync")
        transfer.command(StLinkProtocol.getVersion())
        assertArrayEquals(io.reply, transfer.response(6))
        assertEquals(1, io.writes.size)
        assertArrayEquals(StLinkProtocol.getVersion(), io.writes.single())
        assertEquals(1, io.reads)
    }

    @Test fun negativeOutResultIsPreservedAndCommandIsNotReplayed() {
        val io = Io(mutableListOf(-1, 16))
        val error = assertThrows(IllegalStateException::class.java) {
            StLinkBulkTransfers(io, "sync").command(StLinkProtocol.getVersion())
        }
        assertTrue(error.message!!.contains("OUT 写命令"))
        assertTrue(error.message!!.contains("策略=sync"))
        assertTrue(error.message!!.contains("期望 16 字节，实际 -1"))
        assertEquals(1, io.writes.size)
        assertEquals(0, io.reads)
    }

    @Test fun zeroCompletionIsFailureRatherThanSuccessOrHiddenRetry() {
        val io = Io(mutableListOf(0, 16))
        val error = assertThrows(IllegalStateException::class.java) {
            StLinkBulkTransfers(io, "direct").command(StLinkProtocol.getVersion())
        }
        assertTrue(error.message!!.contains("期望 16 字节，实际 0"))
        assertEquals(1, io.writes.size)
    }

    @Test fun partialCommandDoesNotSendAnotherProtocolPacket() {
        val io = Io(mutableListOf(8, 8))
        assertThrows(IllegalStateException::class.java) {
            StLinkBulkTransfers(io, "sync").command(StLinkProtocol.getVersion())
        }
        assertEquals(1, io.writes.size)
        assertEquals(listOf(8), io.writeResults)
    }

    @Test fun payloadContinuationContainsOnlyTheConfirmedRemainder() {
        val io = Io(mutableListOf(2, 1, 2))
        StLinkBulkTransfers(io, "sync").payload(byteArrayOf(1, 2, 3, 4, 5))
        assertEquals(3, io.writes.size)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), io.writes[0])
        assertArrayEquals(byteArrayOf(3, 4, 5), io.writes[1])
        assertArrayEquals(byteArrayOf(4, 5), io.writes[2])
    }

    @Test fun stalledPayloadStopsAfterItsConfirmedPrefix() {
        val io = Io(mutableListOf(2, 0, 2))
        val error = assertThrows(IllegalStateException::class.java) {
            StLinkBulkTransfers(io, "direct").payload(byteArrayOf(1, 2, 3, 4))
        }
        assertTrue(error.message!!.contains("已发送 2/4"))
        assertTrue(error.message!!.contains("期望 2 字节，实际 0"))
        assertEquals(2, io.writes.size)
    }

    @Test fun shortResponseIsNotJoinedToTheNextResponse() {
        val io = Io(readResult = 2)
        val error = assertThrows(IllegalStateException::class.java) {
            StLinkBulkTransfers(io, "sync").response(6)
        }
        assertTrue(error.message!!.contains("IN 读应答"))
        assertTrue(error.message!!.contains("期望 6 字节，实际 2"))
        assertEquals(1, io.reads)
    }

    @Test fun zeroNegativeAndOversizedReadCountsAreAllRejected() {
        for (count in listOf(0, -1, 7)) {
            val io = Io(readResult = count)
            val error = assertThrows(IllegalStateException::class.java) {
                StLinkBulkTransfers(io, "direct").response(6)
            }
            assertTrue(error.message!!.contains("实际 $count"))
            assertEquals(1, io.reads)
        }
    }

    @Test fun transportExceptionsPreserveCauseAndDirection() {
        val outFailure = IOException("requestWait timeout")
        val output = Io().apply { writeFailure = outFailure }
        val outError = assertThrows(IllegalStateException::class.java) {
            StLinkBulkTransfers(output, "direct").command(StLinkProtocol.getVersion())
        }
        assertSame(outFailure, outError.cause)
        assertTrue(outError.message!!.contains("OUT 写命令"))
        assertTrue(outError.message!!.contains("requestWait timeout"))
        val inFailure = IOException("request was cancelled")
        val input = Io().apply { readFailure = inFailure }
        val inError = assertThrows(IllegalStateException::class.java) {
            StLinkBulkTransfers(input, "direct").response(6)
        }
        assertSame(inFailure, inError.cause)
        assertTrue(inError.message!!.contains("IN 读应答"))
    }

    @Test fun cancellationKeepsItsIdentityAndStopsWithoutRetry() {
        val cancelled = CancellationException("cancelled by user")
        val io = Io().apply { writeFailure = cancelled }
        val actual = assertThrows(CancellationException::class.java) {
            StLinkBulkTransfers(io, "direct").command(StLinkProtocol.getVersion())
        }
        assertSame(cancelled, actual)
        assertEquals(1, io.writes.size)
    }
}
