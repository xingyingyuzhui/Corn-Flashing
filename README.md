# Corn Flashing

正式版：**0.1.8**（versionCode 23）。本版收紧外部 FLM 实验执行入口并修正校验/擦除文案，详见 [0.1.8 验证记录](docs/test-build-0.1.8-test1.md)。保留此前的沉浸式界面、参数保留区和可扩展兼容性引擎。

外部 FLM 实验执行每次进程启动默认关闭，旧绑定停用；开启会恢复已保存的绑定，新绑定需明确确认，仍无具体料号实板兼容保证。移除未实现的 CRC32 校验选项（旧偏好迁移为全量回读），擦除选项改为“全部非保留区”。[Thumb 模拟验证现可从仓库复现](docs/thumb-runtime-validation.md)。

Android 离线单片机烧录工具的基础框架。目标为 ST-LINK/V2 + STM32F103，后续扩展其他探针和多路批量。

**公开源码 · 非商业授权：[PolyForm Noncommercial 1.0.0](LICENSE)。**

**真实 ST-LINK/V2 可识别并擦写 STM32F1（页擦除 / 半字编程 / 回读）；F0/F4 指定设备组默认仅识别；可导入 CMSIS-Pack/FLM 算法；参数保留区默认关闭。内置路径不改 Option Bytes，外部 FLM 不受此保证约束。**

## 安装包

安装包在 GitHub Release [v0.1.8](https://github.com/xingyingyuzhui/Corn-Flashing/releases/tag/v0.1.8)，APK 不入库。

- [`Corn-Flashing-0.1.8.apk`](https://github.com/xingyingyuzhui/Corn-Flashing/releases/download/v0.1.8/Corn-Flashing-0.1.8.apk)：日常安装（模拟烧录 + 真实 ST-LINK F1 烧录；本机 Debug 密钥签名）。
- [`Corn-Flashing-0.1.8.apk.sha256`](https://github.com/xingyingyuzhui/Corn-Flashing/releases/download/v0.1.8/Corn-Flashing-0.1.8.apk.sha256)：安装包校验值。

> **升级提示**：0.1.8 沿用 0.1.6/0.1.7 的签名，可覆盖升级（包括 0.1.8-test1）。v0.1.5 及更早版本使用不同签名密钥，需先卸载旧包（会清空任务记录与保留区设置）。

## 已实现

- Kotlin + Jetpack Compose：工作台、固件、配置、记录、设置五个页面（深色玉米主题）。
- 单工位连续批量烧录（模拟）：批次规则、暂停/恢复、报告与 CSV 导出；多工位并行仅展示为待做。
- 独立纯 Kotlin `:core`：固件模型、HEX/BIN 解析、F103 页擦除计划与后端接口。
- HEX 校验和、扩展地址、区段重叠、EOF 和地址边界检查。
- BIN 明确填写起始地址，导入后固定源文件 SHA-256。
- 固件导入到应用私有目录，重启恢复最近一次选择；单文件最大 4 MiB。
- 开放 F1 产品模板（0x412 / 0x410 / 0x414 / 0x430）；探测成功后自动匹配设备组、容量与页大小，并持久化；非法容量仍拒绝；核心支持保留区冲突检查。
- 可取消的模拟任务；短任务前台服务承载，Activity 重建不负责销毁任务。
- Room 保存任务记录；进程重启将未完成记录标为中断，支持 JSON 导出。
- USB Host 设备发现、ST-LINK 候选标记、USB 权限请求和拔插刷新。
- Kotlin USB bulk 探测 ST-LINK/V2：进入 SWD，读 DBGMCU_IDCODE、Flash 容量（0x1FFFF7E0）、96-bit UID；规划按探测结果对齐产品配置（小/中/大/超大容量），不再锁死单一设备组。
- 深色 App：主题与 SystemBarStyle 统一强制浅色状态栏 / 导航栏图标，避免系统浅色主题下看不清时间。
- 设备页「识别芯片」走真实探测（与工作台相同），卡片展示自动匹配的芯片系列 / 容量 / UID。
- ST-LINK USB：打开设备后先 setConfiguration 再 claimInterface；按 PID 选择 bulk 端点；写失败重试 / UsbRequest 回退；失败页不再误显示「3.30 V · 模拟」。
- 真实 STM32F1 擦写：规划页擦除、半字编程、回读校验、可选复位；批次可关模拟走单工位真机。
- 参数保留区（默认关闭）：按页声明不擦写的地址范围，整片擦除也会跳过；按设备组＋容量分别保存；固件覆盖保留区时拒绝烧录；批次换板核对配置指纹。只保护烧录过程，不管理外置 EEPROM。
- 模拟完成单独标记，不能被当作真实校验通过或真实板卡成功数量。

## 尚未实现

- NDK + libusb + stlink 原生路线（架构长期方案；当前为 Kotlin USB 探测）。
- ELF/AXF 加载区段解析：目前识别到该格式会明确拒绝，不会伪装导入成功。
- 可手动编辑的产品/Bootloader/参数区配置；当前由探测自动匹配容量与设备组，产品配置页仅开放参数保留区。
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

## 许可证与商业使用

本项目原创代码、文档与资源采用 [PolyForm Noncommercial License 1.0.0](LICENSE)，版权声明见 [NOTICE](NOTICE)。

- 可在许可证允许的非商业用途下使用、研究、修改和分发，分发时须保留许可证与 Required Notice。
- 商业用途需另行取得作者书面授权，不因源码公开或可免费下载而自动获得商业使用权。
- 许可证还明确列出了个人用途与非商业组织等许可用途，具体范围、例外和条件以 [许可证原文](LICENSE) 为准。
- 如需商业授权，请通过 [GitHub Issues](https://github.com/xingyingyuzhui/Corn-Flashing/issues) 联系维护者。提出申请不代表已获授权。
- 第三方代码、Gradle Wrapper 和构建依赖继续遵循各自许可证，不适用本项目对原创内容的重新授权。

由于包含非商业用途限制，本项目属于“源码可用”（source-available），不属于 OSI 定义的开源软件。参见 [OSI 开源定义第 6 条](https://opensource.org/osd) 与 [PolyForm 官方条款](https://polyformproject.org/licenses/noncommercial/1.0.0)。

## 兼容性引擎

通用探针接口、芯片描述库和算法注册已接入；当前真实烧录算法为单Bank STM32F1，F0/F4指定设备组仅识别。具体支持范围与实测组合见[兼容引擎架构](docs/architecture/compatibility-engine.md)，最新验证见[test10记录](docs/test-build-0.1.6-test10.md)。CMSIS-Pack/FLM解析、显式绑定与执行基础已接入，见[第二阶段说明](docs/architecture/cmsis-pack-flm.md)；外部算法仍需对应实板验证。

最新算法包阶段验证：[test11记录](docs/test-build-0.1.6-test11.md)。产品配置中可导入.pack/.flm；仅显式绑定且布局匹配后参与任务，外部FLM尚无实板验证记录。

参数保留区验证：[0.1.6 记录](docs/test-build-0.1.6-test12.md)。默认关闭；开启后按页跳过，固件冲突拒绝，真机 35 页擦写/回读/复位通过，178 项测试。
