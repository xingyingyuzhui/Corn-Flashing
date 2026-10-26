# Corn Flashing

Android 离线单片机烧录工具的基础框架。目标为 ST-LINK/V2 + STM32F103，后续扩展其他探针和多路批量。

**当前版本只提供软件流程模拟与 USB 设备发现，不能烧录真实芯片。**

## 已实现

- Kotlin + Jetpack Compose：工作台、固件、设备、记录四个页面。
- 独立纯 Kotlin `:core`：固件模型、HEX/BIN 解析、F103 页擦除计划与后端接口。
- HEX 校验和、扩展地址、区段重叠、EOF 和地址边界检查。
- BIN 明确填写起始地址，导入后固定源文件 SHA-256。
- 固件导入到应用私有目录，重启恢复最近一次选择；单文件最大 4 MiB。
- 固定 F103 64 KB 产品预设；按设备组、容量和页几何检查；核心支持保留区冲突检查。
- 可取消的模拟任务；短任务前台服务承载，Activity 重建不负责销毁任务。
- Room 保存任务记录；进程重启将未完成记录标为中断，支持 JSON 导出。
- USB Host 设备发现、ST-LINK 候选标记、USB 权限请求和拔插刷新。
- 模拟完成单独标记，不能被当作真实校验通过或真实板卡成功数量。

## 尚未实现

- ST-LINK 命令通信、真实芯片自动探测、擦除、写入和回读校验。
- ELF/AXF 加载区段解析：目前识别到该格式会明确拒绝，不会伪装导入成功。
- 可编辑的产品/Bootloader/参数区配置；当前应用只开放默认预设。
- 多探针并行、自动换板识别、芯片真伪判断或完整料号推断。
- 自动解除读保护、Option Bytes、外部 Flash。

## 工程结构

```text
app/                  Android 应用
  src/main/java/dev/corn/flashing/
    ui/               Compose 页面、ViewModel
    storage/          Room、固件副本、任务状态与归档
    usb/              设备发现、权限、拔插
    service/          模拟任务前台服务
  src/main/cpp/       原生后端接入说明（尚无 native 实现）
  src/main/assets/    演示 HEX，仅用于解析与模拟，不是可运行固件
core/                 不依赖 Android 的模型、解析器、计划器、后端和单元测试
docs/architecture.md  完整目标架构
```

将核心单独拆成 JVM 模块是本次实现对架构提案的一个调整，便于在不启动 Android 的情况下验证文件解析和地址规则。

## 构建

- Android Studio，JDK 17 工具链。
- Android SDK Platform 37.2；最低运行版本暂定 Android 10 / API 29。
- 已固定 AGP 9.4.1、Kotlin 2.4.21、Gradle 9.6.0。
- Android Studio 打开项目根目录，配置本机 SDK；`local.properties` 不进入版本控制。
- 首次构建需要下载依赖；App 运行本身无需联网，也未声明网络权限。

```bash
./gradlew :core:test :app:assembleDebug :app:lintDebug
```

如果本机 `GRADLE_USER_HOME` 指向不可用的外部磁盘，可仅对本次构建覆盖：

```bash
GRADLE_USER_HOME="$HOME/.gradle" ./gradlew :core:test :app:assembleDebug :app:lintDebug
```

输出：`app/build/outputs/apk/debug/app-debug.apk`，应用包名 `dev.corn.flashing`。

## 手动体验

1. 安装 Debug APK，进入工作台，点击“加载演示 HEX”。
2. 点击“开始模拟流程”，观察模拟进度；可中途取消或切换页面。
3. 在“记录”查看模拟完成/取消结果，导出 JSON。
4. 在“固件”导入自己的 HEX，或明确设置 BIN 地址后导入 BIN。
5. 在“设备”刷新 USB 列表；如连接真实探针，可申请访问权限。当前不会打开探针执行命令。

模拟目标固定为 64 KB、Device ID 0x410、UID `DEMO-00000001`，不能证明连接了真实 F103，也不能验证导入程序能在电路板上运行。

## 后续接入

先完成 P0：Android 授权 FD → libusb/stlink 适配 → 真机探测 → 已知固件擦写与校验。
原生核心采用哪个上游、具体版本与许可证清单，在 P0 后锁定。当前没有打包 stlink/libusb。

架构参考：

- [Android Now in Android 的分层与状态管理](https://github.com/android/nowinandroid/blob/main/docs/ArchitectureLearningJourney.md)
- [Android USB Host](https://developer.android.com/develop/connectivity/usb/host)
- [libusb Android FD 接入](https://github.com/libusb/libusb/blob/master/android/README)
- [stlink](https://github.com/stlink-org/stlink)

以上用于方案参考，没有复制 Now in Android 应用代码。
