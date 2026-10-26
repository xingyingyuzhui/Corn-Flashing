# 0.1.6-test11 — CMSIS-Pack / FLM 导入与执行基础

实现与限制见[第二阶段架构](architecture/cmsis-pack-flm.md)。

## 交付

- Pack ZIP/PDSC继承解析、严格FLM ELF/FlashDevice解析。
- RAM布局、FlashOS ABI执行、返回/超时/取消/栈保护、页合并、擦除与全量回读流程。
- 产品配置页导入.pack/.flm，选择算法、查看来源/哈希/Flash布局，显式确认绑定当前目标，恢复内置及移除。
- 绑定仅限已登记设备组且基址、容量、几何、RAM布局匹配；绑定哈希进入配置指纹，任务/批次中禁止修改。不因导入自动开放F4等目标。
- 仅支持明确的ELF32/ARM/LE/零基址位置无关FLM子集，非全部CMSIS算法。

## 验证

- 170项core/app测试、assembleDebug、lintDebug通过。
- 新增PackReader、FLM parser、runner与错误路径测试，原创952B测试FLM只有no-op返回，不会擦写真实Flash；不随App默认注册。
- Unicorn运行测试FLM真实Thumb4入口，确认LR返回BKPT、R0=0、R9/SP保持。测试不代表实际Flash编程。
- 当时脚本仅保存在本机；0.1.8-test1 补充仓库内的[可复现脚本和固定依赖](thumb-runtime-validation.md)。
- 独立审查三处修正闭合：Init错误返回清理、安全状态读前失效、原子持久化后发布绑定。
- 手机Xiaomi25060RK16C Android17/API37完成真实厂商F4 1MB FLM解析与本地验证.pack的PDSC→选择→FLM导入。当前256KB F1目标容量不符，UI阻止绑定；本地索引bindings=[]。
- App强制停止重启后算法恢复，仍未启用。
- 内置F1回归任务#16：原Template.hex 69668字节、35页，ST-LINK/V2 V2J23S4 + F1大容量256KB，擦写/全回读/软件复位成功，16.8秒。
- 本阶段未在真实芯片执行外部FLM，未宣称F4实烧通过。需要匹配实板与算法完成下一轮验证。

## APK

versionCode18 / 0.1.6-test11，已通过无线ADB安装手机。
路径dist/Corn-Flashing-0.1.6-test11.apk。
SHA-256：37d7c70d6c376739a6d78576593318156f70f0b7fd1e486b74033c2d41beff1a

用户固件、第三方FLM、验证包、设备日志和截图仅在本机.local-validation/test11/或/tmp，不提交仓库。源码仅包含原创无擦写测试夹具与生成脚本。
