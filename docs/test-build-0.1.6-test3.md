# 0.1.6-test3 — USB 内核错误诊断

日期：2026-10-26。versionCode 10，包名 dev.corn.flashing。

用户test2日志显示GET_CONFIGURATION=1、claim=true，但BULK OUT立即-1、DIRECT_REQUEST OUT立即0。
这不能确定是STALL、权限还是链路错误；Java API不暴露底层errno，异步完成还可能隐藏URB错误状态。

## 改动

- 加入使用Android已授权连接FD的USBFS JNI传输，暴露精确errno、错误名称和说明。不申请Root，不自行打开/dev/bus/usb，不关闭借来的FD。
- 只在前两种只读GET_VERSION握手失败后尝试native策略；选定策略后正式擦写命令仍不重放。
- 读取端点GET_STATUS；仅观察到halt位或真实EPIPE才调用USBDEVFS_CLEAR_HALT，每端点最多一次。
- native恢复后最多重建一次连接重试GET_VERSION；不恢复/重放正式Flash写命令。
- 无硬件JNI自检覆盖模块加载、errno解码以及fd=-1时的bulk/clearHalt返回EBADF。
- NDK 27.2.12479018 + SDK CMake 3.22.1，构建arm64-v8a、armeabi-v7a、x86、x86_64，16KB最大页链接。

## 验证

- core/app共96项单元测试通过，构建与lint通过。
- 四ABI库均打包；zipalign -c -P 16验证通过。
- 本机配置AVD成功安装启动，日志确认“USBFS模块已加载，JNI/errno无设备自检通过”；本次crash buffer为空。
- 没有执行真实USB传输或控制板擦写，不能宣称已修复该小米手机的实体根因。

## 实测

覆盖安装后拔插并重新授权，关闭模拟模式，只点“识别芯片”。若仍失败，在设备页复制完整USB诊断。
重点需要USBFS_NATIVE部分的errno；如果识别成功，再确认容量和固件范围后决定烧录。

## 产物

`dist/Corn-Flashing-0.1.6-test3.apk`，SHA-256 `d5d2b0a94bc002a30a31b22cb42e582d2b4b0425b7ed5c1f92084d6b533e70b7`。沿用本机Debug签名。

## 依据

- [AOSP USB JNI](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/jni/android_hardware_UsbDeviceConnection.cpp)
- [AOSP libusbhost](https://android.googlesource.com/platform/system/core/+/master/libusbhost/usbhost.c)
- [libusb Android授权FD](https://github.com/libusb/libusb/blob/master/android/README)
- [Linux USBFS CLEAR_HALT](https://cdn.kernel.org/doc/html/latest/driver-api/usb/usb.html)
