# Corn Flashing 全量复查（相对 0.1.3 → 0.1.4）

## 严重（已修）

1. **ST-LINK bulkTransfer 返回 -1**  
   根因：`openDevice` 后未 `setConfiguration`，直接 `claimInterface(0)`；端点选择过粗。  
   修复：选配置 0 → `setConfiguration` → 扫描全部接口的 bulk OUT+IN（PID 0x3748 偏好 0x02/0x81；0x374B 偏好 0x01/0x81）→ `claimInterface(force)` → 打开后先 smoke `GET_VERSION`；写失败短睡重试 + `UsbRequest` 回退；异常带上 iface/端点并提示拔插、排除电脑占用。

2. **失败页谎称「目标电压 3.30 V · 模拟」**  
   真实路径失败也写死模拟电压。  
   修复：`SessionState.voltageMv`；成功/失败/无目标页用 `voltageLabel`（模拟才标模拟，否则真实 mV 或 —）。USB/连接类失败标题改为「连接 / 识别失败」，不再伪装成校验失败。

## 重要（已诚实化 / 部分打开）

| 项 | 原状 | 0.1.4 |
|----|------|-------|
| 设备页识别芯片 | 0.1.3 已接 probe | 保持；USB 打开路径加固 |
| 闪灯定位 | stub toast | 禁用「未开放」 |
| 保留区＋添加 | stub toast | 「未开放」无点击 |
| 多工位并行 | stub toast | 「未开放」Chip |
| 自动换板 / 完成后复位 | 可切换但未接线 | 可保存偏好，文案标明尚未接线 / 擦写后生效 |
| 擦除/校验偏好 | 可切换未作用于真机 | 标签标明「真实擦写接入后生效」 |
| 成功页「通过 · 模拟」 | 一律模拟 | 按 `job.mode` 区分 |
| 备份配置与固件 | 「即将支持」 | 「未开放」 |

## 建议 / 下一版（0.1.5）

- **真实页擦除 + 编程 + 回读**（F103 / ST-LINK/V2）：本版仍拒绝在探测成功后擦写，避免半套协议写坏板。
- 自动换板：接上 UID/插拔检测后真正自动 `startSingle`。
- 闪灯定位：ST-LINK LED 命令。
- 可编辑保留区、多工位并行、配置备份。
- 批次在非模拟模式下走真实 backend（今日批次 UI 仍以模拟为主路径演练）。

## 验证

`./gradlew --offline :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 通过（含 `StLinkUsbLayoutTest`）。
