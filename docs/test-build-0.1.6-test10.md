# 0.1.6-test10 — 通用兼容引擎第一阶段

实现细节、目录和后续FLM路线见[兼容引擎架构](architecture/compatibility-engine.md)。

- 探针DebugProbe与现场能力ProbeInfo、只读TargetIdentifier、版本化数据驱动TargetCatalog、非等长扇区Planner、FlashAlgorithmRegistry分离。
- F1现有算法迁入独立驱动，J23 RAM/新探针半字能力继续保留。USB适配不再包含芯片Flash算法。
- 新增F072/F078与F405/F407/F415/F417识别描述和模拟读序列测试，明确仅识别，不开放擦写；双Bank F1同样仅识别。
- 工作台标题、芯片容量图动态显示；配置页列兼容性、设备页列探针固件/路径/能力。未标注整个系列均实测。
- F1对齐profile的旧ID/hash保持，新通用模板不会单独破坏已有F1批次指纹。
- 编程准备重建SWD与停核。F1 RAM每块运行前reload已启用IWDG（AAAA），不启动看门狗、不改变配置，处理已有应用启用IWDG后的运行窗口。

## 验证

- 135项core/app测试、assembleDebug、lintDebug通过。新增测试覆盖识别地址/未知ID/容量、F4非字对齐容量读取、非等长扇区、算法拒绝、版本能力和F1 profile兼容。
- 独立审查通过。
- 中间版本任务#14 RAM超时失败（未伪报成功）；补编程准备与IWDG策略后，最终任务#15完成：STM32F1大容量256KB + ST-LINK/V2 V2J23S4 + Xiaomi25060RK16C Android17/API37，原Template.hex 69668字节、35页，全量回读与软件复位通过，15.9秒。
- 配置页显示6个设备组及“算法已接入/仅识别”，手机截图确认；实际只有此F1组合完成硬件验证。F0/F4没有实板测试。
- 截图、设备日志、用户HEX/反汇编临时产物仅本地，不提交。

APK：dist/Corn-Flashing-0.1.6-test10.apk，versionCode17；已无线安装手机。
SHA-256：ee362cb35a6594f12b374bbd3e525e6a58c39062767dff4057093eff1d7239e2

本阶段未实现CMSIS-Pack导入、FLM执行、F0/F4真实烧录或V3编程。下一阶段继续在现有DebugProbe/FlashAlgorithm接口下接入，不应把metadata识别支持等同于烧录兼容。
