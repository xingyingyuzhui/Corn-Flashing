# 基础框架交付状态

日期：2026-10-26。版本：0.1.0-framework。

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
- APK SHA-256：`2b687623f03c961415a7b16e06fa7031bbf4a94e435101496c1cd5e7d3561d63`

## 下一个实现节点

完成架构 P0：指定 Android 设备 + 实际 ST-LINK/V2 + 完整料号的 F103，验证授权 USB FD、原生会话、芯片信息及已知固件的真实擦写校验。
