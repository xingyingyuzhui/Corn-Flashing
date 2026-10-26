# 可复现的 Thumb 机器码验证

脚本仅执行仓库原创的 F1 RAM loader 和不擦写的 FLM 测试夹具，不连接 USB、探针或芯片，不读取用户固件。依赖固定为 `unicorn==2.1.4`。它与 JVM 单元测试独立，Gradle 不会自动运行。

在项目根目录执行（需要 Python 3 和能安装对应平台 Unicorn wheel 的环境）：

```sh
python3 -m venv /tmp/corn-thumb-venv
/tmp/corn-thumb-venv/bin/python -m pip install -r scripts/requirements-thumb.txt
/tmp/corn-thumb-venv/bin/python scripts/verify-thumb-runtime.py
```

脚本使用自身文件位置定位仓库，也可从其他工作目录执行。检查使用显式异常，`python -O` 不会禁用检查；任何失败都以非零状态退出。正常结束会打印两项 `PASS`。

## 覆盖范围

- **F1 loader**：直接读取 `F1RamLoader.kt` 中的内嵌机器码和完成断点偏移，不依赖私有 `/tmp` 二进制。模拟 0、1、2、255、256 半字写入；检查每次 Flash 存储为 16 位、地址和值正确、源/目标寄存器进度、剩余计数、完成 BKPT。模拟 `FLASH_SR` 的多次 BSY 轮询、PGERR 停止，以及 BSY 永久不清时到达 100000 条指令上限且不继续写入。
- **FLM 夹具**：读取仓库中的 `core/src/test/resources/fixtures/fixture.flm`，从 ELF 符号表定位 Init / UnInit / EraseSector / ProgramPage，不假设入口固定偏移。每个入口均返回 R0=0，经 LR 到达主机返回 BKPT，保留 R9/SP。只映射模拟 RAM，任何内存写入均使检查失败；夹具本身不擦除、不烧录 Flash。

F1 脚本模拟的是寄存器状态，不模拟真实 Flash 控制器的电气时序、擦除行为、保护状态、看门狗或 USB。FLM 验证只证明原创 no-write 夹具在这些调用条件下的返回行为，不能证明外部厂商 FLM 的芯片兼容性、安全性或实际擦写能力。BSY 指令上限只是模拟器测试边界，不替代主机运行器的实际超时/取消测试。

## 与其他检查的关系

`verify-thumb-runtime.py` 验证内嵌机器码的模拟执行行为。汇编源码是否与内嵌字节一致，仍由另一条 NDK LLVM 检查负责：

```sh
python3 scripts/verify-f1-loader.py --llvm-bin /path/to/ndk/toolchains/llvm/prebuilt/darwin-x86_64/bin
```

FLM 夹具的原创汇编源码、链接脚本和重建命令见 [夹具说明](../scripts/fixtures/README.md)。上述检查不包含或执行任何第三方 FLM。
