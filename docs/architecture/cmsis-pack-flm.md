# CMSIS-Pack / FLM 执行：第二阶段

## 可用流程

产品配置 → 算法包 → 导入 `.pack` 或 `.flm` → 选择算法 → 查看 FlashDevice、页大小、来源和 SHA-256。导入操作只保存本机文件，不连接 USB、不授权擦写。

0.1.8-test1 起，外部 FLM 执行属于实验功能，每次应用进程启动默认关闭，不持久化开启权限。关闭时保留已导入文件和历史绑定，但目标目录与执行器只开放内置支持；F0/F4 保持仅识别。单独确认开启实验执行后，才允许绑定或使用保存的绑定；结束烧录、识别、批次及导入后才能切换。关闭后即使仍持有旧算法对象，校验与执行入口也会拒绝外部代码。

绑定前必须先识别实际芯片。只有已登记识别规则的设备组、实际容量、Flash 基址、完整扇区布局、算法 RAM/栈/缓冲布局均匹配，才允许用户确认绑定。绑定后任务通过 `FlashAlgorithmRegistry` 分派到 `FlmFlashAlgorithm`。外部算法未经本项目实板验证，UI明确展示此状态；用户仍需根据具体料号与厂家说明选择算法。

绑定描述的 key 包含算法完整哈希，进入产品配置指纹；修改绑定受运行任务/识别/批次状态限制。可恢复内置算法或移除文件。索引持久化成功后才发布新运行状态；恢复时验证哈希及全部绑定，损坏资源不启用。

## 解析范围与限制

- Pack输入最大64MiB、条目4096、总解压128MiB；不向文件系统解压ZIP路径。
- PDSC：禁DTD/外部实体，限制XML节点与深度。支持family/subFamily/device/variant处理器、内存与算法属性继承；多算法要求用户选择。
- 算法提取不根据PDSC猜测硬件device ID，也不把器件名列表自动登记为可烧录型号。
- FLM支持已链接的ELF32 ARM小端、零基址PrgCode、可选PrgData RW/ZI、FlashDevice元数据和标准Thumb函数入口。拒绝需重定位、硬浮点调用约定、遗漏加载段、未知布局和损坏边界。
- 不承诺任意FLM通用：解析结构不能证明机器码安全或位置无关，也不能仅凭Flash几何证明具体料号适配。
- 外部 FLM 是在 MCU 上运行的机器码，没有指令级访问沙箱。主机检查传入的擦除/写入地址与页补齐范围，不能限制 FLM 内部访问，因此不能对外部 FLM 保证保留区、Option Bytes 或 RDP 不被改动。内置 F1 路径的保证不自动扩展至外部算法。
- 当前仅片内Flash且FLM声明容量必须和实际芯片完全一致；外部QSPI、TrustZone、多核心、厂家调试脚本尚未支持。
- PDSC的RAM属性用于展示的解析数据；实际运行仅使用内置芯片描述中已登记的最小RAM范围，不直接信任包声称的RAM扩展。
- Init时钟参数当前为0（由算法自行处理）；需要特定目标时钟或初始化脚本的算法不应使用本版本。

## 执行行为

`FlmRamLayout` 在最小RAM范围内分配独立返回BKPT、代码/RW/ZI、编程页缓冲、32字节栈保护和默认1024字节栈。上传/读回、入口、对齐、容量与不重叠检查必须在第一次Init前通过。

`FlmRunner` 设置R0–R3参数、R9静态基址、CONTROL/特殊屏蔽寄存器、SP、LR、Thumb xPSR及PC。返回必须停在约定BKPT、R0=0、栈保护完整。超时、取消或USB读写错误不重发算法命令，不在不确定状态执行UnInit。

生命周期：

1. prepareProgramming + 上传回读和返回断点零写入自检。
2. Init(function=1) → EraseSector逐扇区 → UnInit(1)。
3. Init(function=2) → ProgramPage逐编程页 → UnInit(2)。
4. 主机全量回读比较原始固件字节（用户选不校验时明确显示未校验）。
5. 按设置软件复位并确认运行。

稀疏/奇数记录按编程页合并，空白补FlashDevice.erasedByte；补齐不能越容量、保留区或擦除计划。实际Flash写入由FLM在芯片上完成，主机不使用32位写模拟Flash编程宽度。FLM Init负责该芯片的时钟/Flash/看门狗初始化，通用执行器不写F1特定寄存器。

Init返回错误但完整返回BKPT时尝试UnInit清理；状态不确定时停止执行，后续清理不覆盖原始错误。

## 验证与证据边界

- JVM测试覆盖ZIP/XML继承与恶意边界、FLM ELF/符号/FlashDevice边界、RAM布局、ABI、返回非0、错误PC、超时、取消、初始化失败清理、读状态失败禁止后续命令、页补齐/保留区和回读失败。
- 原创测试FLM仅在core测试资源中，4个函数返回0且不擦写，附汇编/链接脚本/生成脚本；不随App内置或默认注册。
- Unicorn执行该夹具的真实Thumb机器码，验证4个入口经LR返回BKPT、R0=0、R9/SP保持；这不是实板Flash验证。可复现脚本与固定依赖见[Thumb运行验证](../thumb-runtime-validation.md)。
- 真实F4厂商FLM完成桌面与手机只读解析；使用本地验证ZIP测试包完成Android PDSC/FLM导入。当前F1目标容量不匹配时绑定被阻止，索引bindings保持空。
- 外部FLM尚未完成对应实板擦写验证，不能称F4实烧兼容。下一步需要具体目标板、匹配厂商算法和同型号固件，完成实际擦写/回读/复位以及断连取消测试。

## 来源

- [Open-CMSIS-Pack算法说明](https://open-cmsis-pack.github.io/Open-CMSIS-Pack-Spec/main/html/flashAlgorithm.html)
- [FlashOS调用接口](https://open-cmsis-pack.github.io/Open-CMSIS-Pack-Spec/main/html/algorithmFunc.html)
- [pyOCD FLM实现，行为参考](https://github.com/pyocd/pyOCD/blob/main/pyocd/target/pack/flash_algo.py)
- [用于只读解析验证的F4算法](https://github.com/Open-CMSIS-Pack/STM32F4xx_DFP/blob/main/CMSIS/Flash/STM32F4xx_1024.FLM)

第三方FLM仅在本机验证，不加入仓库；再分发任何厂商包前须遵循其自身许可证。
