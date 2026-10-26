package dev.corn.flashing.core.stlink

import java.util.concurrent.CancellationException

/** Transport strategy supplied by Android: synchronous bulk or direct UsbRequest. */
interface StLinkBulkIo {
    fun write(data: ByteArray): Int
    fun read(buffer: ByteArray): Int
}

/**
 * Enforces protocol boundaries without replaying commands after an uncertain USB result.
 * A positive payload prefix is the only operation that can continue with its known remainder.
 */
class StLinkBulkTransfers(private val io: StLinkBulkIo, private val label: String) {
    fun command(bytes: ByteArray) {
        require(bytes.size == StLinkProtocol.CMD_SIZE) { "ST-LINK 命令必须是 ${StLinkProtocol.CMD_SIZE} 字节" }
        val actual = write(bytes, "写命令")
        check(actual == bytes.size) { failure("OUT 写命令", bytes.size, actual) }
    }

    fun payload(bytes: ByteArray) {
        require(bytes.isNotEmpty()) { "ST-LINK 数据不能为空" }
        var sent = 0
        while (sent < bytes.size) {
            val remaining = bytes.size - sent
            val data = if (sent == 0) bytes else bytes.copyOfRange(sent, bytes.size)
            val actual = write(data, "写数据（已发送 $sent/${bytes.size}）")
            check(actual in 1..remaining) { failure("OUT 写数据（已发送 $sent/${bytes.size}）", remaining, actual) }
            sent += actual
        }
    }

    fun response(length: Int): ByteArray {
        require(length > 0) { "ST-LINK 应答长度必须大于 0" }
        val buffer = ByteArray(length)
        val actual = try {
            io.read(buffer)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw transportError("IN 读应答", length, error)
        }
        check(actual == length) { failure("IN 读应答", length, actual) }
        return buffer
    }

    private fun write(data: ByteArray, operation: String): Int = try {
        io.write(data)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        throw transportError("OUT $operation", data.size, error)
    }

    private fun failure(operation: String, expected: Int, actual: Int): String =
        "ST-LINK USB $operation 失败：策略=$label，期望 $expected 字节，实际 $actual；未自动重发命令"

    private fun transportError(operation: String, expected: Int, error: Exception) =
        IllegalStateException(
            "ST-LINK USB $operation 异常：策略=$label，期望 $expected 字节，实际未知；${error.javaClass.simpleName}: ${error.message ?: "无错误详情"}；未自动重发命令",
            error,
        )
}
