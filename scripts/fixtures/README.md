# Original no-write FLM fixture

`fixture_flm.S` and `fixture_flm.ld` build the ELF in `core/src/test/resources/fixtures/fixture.flm`. They are original test code under the repository license. Every required entry simply returns zero; **the fixture never erases or programs Flash** and must never be registered as a working hardware algorithm.

Regenerate with the installed NDK LLVM toolchain:

```sh
python3 scripts/generate-flm-fixture.py --llvm-bin /path/to/ndk/toolchains/llvm/prebuilt/darwin-x86_64/bin
```

The linked ARM ELF32 little-endian fixture contains `PrgCode`, `PrgData`, read-only `FlashInfo` and required function/object symbols. It declares a 256 KiB Flash window with 256-byte programming pages and 2 KiB erase sectors; those are metadata for parser/runtime tests, not evidence of hardware support.

The parser deliberately supports a bounded subset of FLM: zero-based position-independent linked ARM/Thumb code in `PrgCode`, optional RW/ZI sections named `PrgData`, and metadata containing `FlashDevice`. It rejects relocations, hard-float ABI, out-of-range entries, extra executable/writable sections, and unknown additional allocatable sections instead of silently omitting them. ELF structure cannot prove that arbitrary uploaded machine code is position-independent or safe; parsing is not execution approval.

Primary references used to implement structure/behavior independently:

- [Open-CMSIS-Pack Flash Programming](https://open-cmsis-pack.github.io/Open-CMSIS-Pack-Spec/main/html/flashAlgorithm.html)
- [Open-CMSIS-Pack algorithm functions](https://open-cmsis-pack.github.io/Open-CMSIS-Pack-Spec/main/html/algorithmFunc.html)
- [pyOCD FLM support](https://github.com/pyocd/pyOCD/blob/main/pyocd/target/pack/flash_algo.py)

A downloaded `Open-CMSIS-Pack/STM32F4xx_DFP` `STM32F4xx_1024.FLM` was parsed locally to check real algorithm compatibility (332-byte execution image, 1 MiB Flash, 1024-byte page, 3 sector regions). The external binary is not included in this repository or executed on hardware.
