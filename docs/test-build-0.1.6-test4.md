# 0.1.6-test4 — 首选精确内核错误路径

用户test3返回DIRECT_REQUEST读取8字节为0。按identify顺序，该位置是GET_TARGET_VOLTAGE，说明GET_VERSION和GET_CURRENT_MODE已经通过，尚未进入芯片ID/Flash操作。

- native可用时优先选择USBFS_NATIVE，随后才是BULK和DIRECT_REQUEST，避免只用6字节握手选中隐藏URB错误的异步路径。
- native第一次握手不再做无必要的IN drain；只有此前握手失败重连时才清理可能残留的GET_VERSION应答。
- xfer异常加入命令名称和头字节，GET_TARGET_VOLTAGE失败明确标识。底层cause保留，取消异常保持原样。
- 没有吞掉电压传输失败后继续使用同一连接，也没有重放擦写命令。
- 版本0.1.6-test4 / versionCode11。构建、lint及96项现有测试通过。未实测手机USB，此版目的为得到电压读取失败的精确errno，不能宣称最终兼容问题已修复。

APK SHA-256：cab500d497e2ee1924fd3c0da802a79ef1e9c91387afc4201fab6feefd76cd12

用户先只执行识别芯片；若失败请复制完整诊断，重点GET_TARGET_VOLTAGE和errno，不必反复尝试烧录。
