# 0.1.6-test7 — ST-LINK/V2 J13–J25 RAM 编程路径

旧版探针不再要求原生 WRITE_MEM_16BIT。J13–J25 使用 API v2 的 32 位 SRAM/调试寄存器访问，上传原创 48 字节 Thumb 程序，由 Cortex-M3 执行 STRH 写入 Flash。J26+ 保留原半字写路径；更旧版本/V3 未在此变更中开放烧录。

## 实现

- 单 Bank F1 已知 ID 0x412/0x410/0x414，容量最大512 KB；容量、页、保留区检查全部保留。
- SRAM 代码位于0x20000000，数据区0x20000100，最多512字节；擦前完成上传回读、寄存器读写和零写入 BKPT 自检。会覆盖低端768字节SRAM，不保留应用RAM现场。
- 擦除35页后分块编程，每半字等待BSY并检查PGERR/WRPRTERR。结束必须满足指定BKPT、剩余数量为0、源和目的地址进度正确。
- 主机超时/取消请求停核，清中断屏蔽，再等待Flash空闲、清编程状态并锁定Flash；不会重发不确定状态的Flash写命令。
- 编程期间设置并校验DBGMCU_CR看门狗调试冻结位，结束恢复原值；CPU运行期间没有关闭硬件看门狗。
- 使用AIRCR SYSRESETREQ软件复位，不依赖NRST接线；清reset vector catch，确认复位结束及S_HALT清除。DEMCR在finally恢复，清理异常不掩盖主异常。
- 完整字节回读校验保留；不会修改Option Bytes/RDP。

## 验证

- 106项core/app单元测试、assembleDebug、lintDebug通过。
- scripts/verify-f1-loader.py：NDK LLVM重新编译汇编，与全部48字节内嵌机器码一致。
- Unicorn运行真实Thumb机器码：0/1/2/255/256半字、BSY轮询、PGERR停止、BSY卡死指令上限；Flash写访问均为16位。
- 当时脚本仅保存在本机；0.1.8-test1 将验证整理进仓库，固定依赖与复现入口见 [Thumb运行验证](thumb-runtime-validation.md)。
- 独立审查通过。首次手机写入因中途脱离halt失败；添加DBGMCU看门狗调试冻结后，完整69668字节写入与回读通过，随后暴露NRST依赖的复位问题，已改为AIRCR软件复位。

## 最终手机实烧

Xiaomi 25060RK16C / Android17 API37，通过无线ADB安装；OTG接ST-LINK/V2 V2J23S4、STM32F1高密度256KB目标。任务#11（2026-10-26 20:40:23）耗时17.9秒，Template.hex 69668字节、35个2KB页：擦除、写入、全量回读校验、AIRCR复位并解除停核全部通过。该结论只覆盖这块板与该探针/固件组合，未测试应用固件的外设功能或真实多工位并行。截图、实时日志和用户固件留在本地，不提交仓库。

APK dist/Corn-Flashing-0.1.6-test7.apk，versionCode14。
SHA-256：1c1e899b00b40eeb069c9ec3b641e4d4647b5191cf0822930fa6ad1ced67e6dc
