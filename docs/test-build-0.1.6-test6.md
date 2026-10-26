# 0.1.6-test6 — 修正 ST-LINK 模式编号，手机识别通过

根因：GET_CURRENT_MODE 的线协议编号应为 DFU=0、MASS=1、DEBUG=2，原实现整体加一。手机实际返回 00 01 时未发送 DFU_EXIT，随后 ENTER_SWD 的 IN 端点返回 EPIPE。

依据：https://github.com/stlink-org/stlink/blob/develop/inc/stlink.h ，STLINK modes 定义。修正模式常量，补充针对实际线值的回归测试。增加握手、模式切换与 SWD 初始化日志；不记录用户固件内容，不逐半字记录编程日志。

## 验证

- core/app 单元测试、assembleDebug、lintDebug 通过；独立审查无阻断项。
- 用户授权无线 ADB，安装到 Xiaomi 25060RK16C Android 17/API37。
- 首次识别：清理先前遗留端点停顿后，GET_VERSION=25 C4 83 04 48 37（V2J23S4），GET_CURRENT_MODE=00 01，发送 F3 07，ENTER_SWD=80 00，READ_IDCODES=80 ... 77 14 A0 1B ...，界面显示 STM32F1 大容量、256 KB。
- 再次识别：GET_CURRENT_MODE=02 00，发送 F2 21，ENTER_SWD=80 00；重复识别成功。
- 用户 Template.hex 为约 68 KB，容量检查通过，可规划 35 个 2 KB 页。
- 未实际擦除或烧录。探针固件 J23 不满足当前半字编程路径的 J26 能力要求，原有烧录前保护保留。需要更新探针固件或另外实现旧版探针的 RAM loader 编程路径。

APK：dist/Corn-Flashing-0.1.6-test6.apk；versionCode=13。已通过无线 ADB 安装到用户手机。
