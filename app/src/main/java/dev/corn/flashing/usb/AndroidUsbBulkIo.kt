package dev.corn.flashing.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbRequest
import dev.corn.flashing.core.stlink.StLinkBulkIo
import java.io.Closeable
import java.nio.ByteBuffer

internal enum class UsbTransferMode { BULK, DIRECT_REQUEST, USBFS_NATIVE }

/** One strategy for the whole session. Never switches APIs or replays a failed command mid-session. */
internal class AndroidUsbBulkIo(
    private val connection: UsbDeviceConnection,
    private val epOut: UsbEndpoint,
    private val epIn: UsbEndpoint,
    private val mode: UsbTransferMode,
    private val trace: (String) -> Unit,
) : StLinkBulkIo, Closeable {
    private var outRequest: UsbRequest? = null
    private var inRequest: UsbRequest? = null
    private val outBuffer by lazy { ByteBuffer.allocateDirect(1024) }
    private val inBuffer by lazy { ByteBuffer.allocateDirect(1024) }
    var traceEnabled = true

    override fun write(data: ByteArray): Int = transfer(data, output = true)
    override fun read(buffer: ByteArray): Int = transfer(buffer, output = false)

    private fun transfer(data: ByteArray, output: Boolean): Int {
        val direction = if (output) "OUT" else "IN"
        val start = System.nanoTime()
        try {
            val endpoint = if (output) epOut else epIn
            val count = when (mode) {
                UsbTransferMode.BULK -> connection.bulkTransfer(endpoint, data, data.size, TIMEOUT_MS)
                UsbTransferMode.DIRECT_REQUEST -> directTransfer(data, output)
                UsbTransferMode.USBFS_NATIVE -> NativeUsbFs.transfer(connection.fileDescriptor, endpoint.address, data, TIMEOUT_MS)
            }
            if (traceEnabled) trace("$mode $direction requested=${data.size} actual=$count elapsedMs=${(System.nanoTime() - start) / 1_000_000}")
            return count
        } catch (error: Exception) {
            if (traceEnabled) trace("$mode $direction ${error.javaClass.simpleName}: ${error.message}")
            throw error
        }
    }

    private fun directTransfer(data: ByteArray, output: Boolean): Int {
        require(data.size in 1..1024) { "USB request buffer length invalid" }
        val request = if (output) outRequest ?: createRequest(epOut).also { outRequest = it }
            else inRequest ?: createRequest(epIn).also { inRequest = it }
        val buffer = if (output) outBuffer else inBuffer
        buffer.clear()
        buffer.limit(data.size)
        if (output) { buffer.put(data); buffer.flip() }
        check(request.queue(buffer)) { "UsbRequest.queue returned false (${if (output) "OUT" else "IN"})" }
        try {
            val completed = connection.requestWait(TIMEOUT_MS.toLong())
            check(completed === request) { "UsbRequest completion did not match queued request" }
            val count = buffer.position()
            check(count in 0..data.size) { "UsbRequest reported invalid byte count $count" }
            if (!output && count > 0) { buffer.flip(); buffer.get(data, 0, count) }
            return count
        } catch (error: Exception) {
            request.cancel()
            throw error
        }
    }

    private fun createRequest(endpoint: UsbEndpoint): UsbRequest {
        val request = UsbRequest()
        if (!request.initialize(connection, endpoint)) {
            request.close()
            error("UsbRequest.initialize returned false (ep=0x${endpoint.address.toString(16)})")
        }
        return request
    }

    override fun close() {
        listOfNotNull(outRequest, inRequest).forEach { request ->
            runCatching { request.cancel() }
            runCatching { request.close() }
        }
        outRequest = null; inRequest = null
    }

    companion object { private const val TIMEOUT_MS = 3000 }
}
