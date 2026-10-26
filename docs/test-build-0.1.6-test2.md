# Corn Flashing 0.1.6-test2 — USB 握手诊断

日期：2026-10-26。versionCode 9，基于本地修复版 test1。尚未发布 GitHub Release。

## 用户实测反馈

手机能够发现 PID 0x3748、取得 USB 访问权限，但第一条 GET_VERSION 写命令失败；旧错误最后只显示“期望 16 字节，实际 0”。此时还没有识别目标芯片或执行擦写。

端点 OUT 0x02 / IN 0x81 和 16 字节命令符合经典 ST-LINK/V2；乱码序列号本身不能证明探针损坏。截图不足以确认手机/OTG/探针的最终根因。

## 本次修正

- 所有真实探测/擦写后端共享 USB 会话互斥，阻止多次识别或换板轮询同时打开同一探针。
- 识别时显示忙状态并禁用重复识别/开始按钮。
- 先通过 GET_CONFIGURATION 读取真实 bConfigurationValue。已生效时不再重复设置配置，避免重置端点。
- 默认使用同步 bulk；只有在连接阶段的只读 GET_VERSION 握手失败时，才关闭连接并以 direct UsbRequest OUT/IN 尝试兼容路径。
- 已选择策略后整个会话保持固定；正式命令或应答失败立即停止，不自动重发未知结果的命令。
- 只对已确认发送成功的正数 payload 前缀续传剩余数据；不重放命令头。
- 关闭失败或正常会话只释放请求、接口与连接，不再发送额外 DEBUG_EXIT。
- EXIT_DEBUG/电压读取的传输失败直接传播，不继续使用可能错帧的会话；旧版不支持的电压命令按已知固件能力跳过。
- 保留每种握手方式的返回值、耗时、异常、配置值、端点包长、Android 型号/API 与设备是否仍连接。
- 识别失败提示简短；设备页新增“复制 USB 诊断”，预览有长度限制，复制完整文本不截断。

Direct buffer 是兼容尝试，不宣称 heap buffer 就是根因；Android 官方允许 heap buffer，完成字节计数通过 position 获取符合 API。

## 验证

- 完整构建、core/app 单元测试、Android lint 通过。
- 共 93 项测试（核心 89、应用 4），0 失败、0 跳过。
- 新增 10 项传输边界测试：OUT -1/0、命令短传输不重发、payload 仅续正数已知剩余、IN 短包、异常 cause、取消异常。
- 配置选择测试覆盖已生效配置不得重设、未配置/不同配置需要切换、未知配置的兼容策略。
- 追加旧探针电压能力边界测试。
- 本机 Pixel_9_Pro AVD 安装并启动 test2，触发无已授权设备负例，验证简短提示、设备页诊断卡片和完整复制按钮反馈。
- AVD 本次 crash buffer 为空。模拟器无法验证真实 USB 传输；没有控制实体手机、探针或目标板。
- 独立只读检查发现的失败会话吞错点已修正，未对本轮做重复评审。

## 下一次实测

1. 安装本测试包，拔插一次 ST-LINK 并重新授权，保持模拟模式关闭。
2. 先只点击“识别芯片”，暂不反复点开始烧录。
3. 若仍失败，进入设备页，点“复制 USB 诊断”，发送完整文本。该操作不执行 Flash 擦写。
4. 识别成功后再确认芯片容量和固件范围，然后按实际需要执行烧录。

## 本地产物

- `dist/Corn-Flashing-0.1.6-test2.apk`，包名 dev.corn.flashing，沿用本机 Debug 签名。
- SHA-256：`d4b6991c143e2f971ac1a3bafd2f15ba4fdb0be6bc7ef4e38e20354a6e59b7e7`。
- 用户固件、截图及私有测试数据不提交 GitHub。

## 参考

- [Android UsbRequest](https://developer.android.com/reference/android/hardware/usb/UsbRequest)
- [Android setConfiguration](https://developer.android.com/reference/android/hardware/usb/UsbDeviceConnection#setConfiguration(android.hardware.usb.UsbConfiguration))
- [AOSP UsbRequest 缓冲区实现](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/core/java/android/hardware/usb/UsbRequest.java)
- [pyOCD ST-LINK USB 实现](https://github.com/pyocd/pyOCD/blob/main/pyocd/probe/stlink/usb.py)
- [libusb 设置已有配置的状态重置说明](https://libusb.sourceforge.io/api-1.0/group__libusb__dev.html)
