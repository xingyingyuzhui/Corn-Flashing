package dev.corn.flashing.core.stlink

/** Linux usbfs errno values; Java bulkTransfer's -1 alone is not an errno. */
object UsbKernelErrors {
    const val EPIPE = 32
    fun name(errno: Int): String = when (errno) {
        1 -> "EPERM"; 2 -> "ENOENT"; 4 -> "EINTR"; 5 -> "EIO"; 9 -> "EBADF"
        11 -> "EAGAIN"; 12 -> "ENOMEM"; 13 -> "EACCES"; 16 -> "EBUSY"
        19 -> "ENODEV"; 22 -> "EINVAL"; 28 -> "ENOSPC"; 32 -> "EPIPE"
        71 -> "EPROTO"; 75 -> "EOVERFLOW"; 84 -> "EILSEQ"; 108 -> "ESHUTDOWN"
        110 -> "ETIMEDOUT"; else -> "ERRNO_$errno"
    }
    fun explanation(errno: Int): String = when (errno) {
        1, 13 -> "内核拒绝该传输；不能仅凭应用已授权就断定 bulk 权限正常"
        9 -> "USB 文件描述符无效"
        16 -> "USB 接口或管道忙"
        19, 108 -> "USB 设备或连接已失效"
        22 -> "内核拒绝传输参数或当前端点状态"
        32 -> "USB 端点停顿/STALL"
        71, 84 -> "USB 链路协议/数据完整性错误"
        110 -> "USB 传输超时"
        else -> "USB 内核传输失败，需结合完整诊断判断"
    }
    fun shouldRecoverHalt(errno: Int?, endpointStatus: Int?): Boolean =
        errno == EPIPE || endpointStatus?.let { it and 1 != 0 } == true
}
