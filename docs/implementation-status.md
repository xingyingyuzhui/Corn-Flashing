# 基础框架交付状态

## 2026-10-26 · 发布 v0.1.5（合并 feat-stlink-flash-015）

- PR #6 已合入 main；versionCode 7 / versionName 0.1.5。
- 真实 STM32F1 页擦除 / 半字编程 / 回读 / 可选复位；批次可真机单工位。
- 详见 docs/review-0.1.5.md。

## 2026-10-26 · 发布 v0.1.4（合并 fix-stlink-usb-config）

- PR #5 已合入 main；versionCode 6 / versionName 0.1.4。
- ST-LINK USB：setConfiguration + 端点扫描 + 重试；失败页电压诚实化。
- 复查 stub：未开放控件禁用/标注；真实擦写留给 0.1.5。详见 docs/review-0.1.4.md。

## 2026-10-26 · 发布 v0.1.3（合并 fix-device-probe-statusbar）

- PR #4 已合入 main；versionCode 5 / versionName 0.1.3。
- 设备页「识别芯片」接入真实探测；卡片展示系列 / 容量 / UID。
- 主题与 SystemBarStyle 统一强制浅色状态栏图标。

## 2026-10-26 · 发布 v0.1.2（合并 config-auto-match）

- PR #3 已合入 main；versionCode 4 / versionName 0.1.2。
- 开放 F1 产品模板（0x412 / 0x410 / 0x414 / 0x430）；探测后自动匹配设备组、容量、页大小并持久化。
- Debug APK 双文件名（与 0.1.1 相同签名方式）。

## 2026-10-26 · 发布 v0.1.1（合并 stlink-probe-v1）

- PR #2 已合入 main；versionCode 3 / versionName 0.1.1。
- Debug APK 双文件名（与 0.1.0 相同签名方式）。

## 2026-10-26 · stlink-probe-v1（已合并为 v0.1.1）

- 状态栏：深色 App + 系统浅色时强制浅色状态栏 / 导航栏图标。
- 真实探测：授权 ST-LINK/V2 后可在工作台「识别芯片」或开始烧录时走 Kotlin USB bulk → SWD，读取设备组、Flash 容量、UID。
- 容量：FlashPlanner 以芯片报告为准（0x410 允许 64 或 128 KB）；固件超出容量仍拒绝。
- 擦写：真实路径在探测成功后明确报「探测已通，擦写尚未接入」；模拟路径不变。
- 未在本机插真实 ST-LINK 验证（未验证）；协议打包有 JVM 单测。

日期：2026-10-26。版本：0.1.0（由 0.1.0-framework 升版；含批量模拟与 UI）。

## 实现范围

- Android Compose 四个页面、ViewModel、应用级状态。
- JVM 核心：HEX/BIN 解析、地址与擦除页检查、模拟后端和真实后端拒绝占位。
- USB 发现与权限请求；当前不打开 USB 接口或发送 ST-LINK 命令。
- 固件本地副本、哈希验证、Room 排队与结果持久化、JSON 导出。
- 启动服务前持久化 QUEUED；进程恢复时未终结记录标为 INTERRUPTED。
- 模拟使用 shortService 前台服务；真实 USB 接入后须改为 connectedDevice。

## 已执行验证

命令：`GRADLE_USER_HOME=/Users/qin/.gradle ./gradlew :core:test :app:assembleDebug :app:lintDebug --offline`

- Debug APK 构建通过。
- 核心测试 19 项，失败 0，错误 0。
- Android lint 通过：0 错误、2 警告。警告分别为构建工具可更新、Android 12 以下备份配置建议；当前已设置 allowBackup=false，未单独验证各系统的备份行为。
- 19 项测试中包含打包演示 HEX 的解析与默认页计划验证。
- 独立只读代码检查发现的 HEX/BIN 地址耦合与排队持久化问题已在实现中修正；未做第二轮代码评审。

## 未执行验证

- 没有启动模拟器或应用，没有做界面视觉检查、Activity/Service/Room 运行时集成验证。
- 没有连接或控制任何真实探针/单片机，没有验证 USB 权限弹窗、FD 接入、SWD 或擦写。
- ELF/AXF、多路并行、真实芯片自动探测仍未实现。
- 按现有工作区偏好，没有自动运行弹窗或抢焦点的 GUI 测试。

构建成功和逻辑测试通过不等于已通过 Android 运行时或硬件兼容验证。

## 构建产物

- `app/build/outputs/apk/debug/app-debug.apk`
- 包名：`dev.corn.flashing`
- 最低 Android：10 / API 29（声明值，尚未验证设备矩阵）
- APK SHA-256：`eb4b58dfdd226fba397d7f43f4f9a810c1f7decc4b77062d239ebb9468fea20d`（v0.1.0 debug）

## 下一个实现节点

完成架构 P0：指定 Android 设备 + 实际 ST-LINK/V2 + 完整料号的 F103，验证授权 USB FD、原生会话、芯片信息及已知固件的真实擦写校验。
