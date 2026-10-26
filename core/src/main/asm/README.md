# STM32F1 RAM half-word loader

`f1_halfword_loader.S` is original project code, under the repository's PolyForm Noncommercial license. No third-party loader bytes are embedded.

The host uses existing ST-LINK API v2 32-bit memory access to upload code at `0x20000000` and a maximum 512-byte data block at `0x20000100`. It never uses a 32-bit Flash store. The Cortex-M3 executes `STRH`, waits for FLASH_SR.BSY to clear, and checks PGERR/WRPRTERR for each halfword. The code uses no stack and no interrupts. The Android bridge must mask interrupts while resuming, retain debug enable, and clear the interrupt mask when halting again.

Registers: R0 Flash destination, R1 SRAM source, R2 positive halfword count; R3 returns the last FLASH_SR. Completion requires the known BKPT address, R2=0, correct progressed R0/R1, and no BSY/error flags. A zero count branches directly to BKPT without touching Flash or FLASH_SR; this is executed before any erase to check the complete register/resume/halt path. Loader and data uploads are read back before execution. Host timeout/cancellation requests halt; the outer algorithm then waits for Flash BSY and locks Flash before ending the job.

The routine deliberately takes over the stopped core and overwrites the low 768 bytes of SRAM. It does not attempt to preserve an application's volatile RAM state. The normal successful job resets the application; with reset disabled the core remains halted, matching the existing programming path. It does not write option bytes or disable hardware watchdogs.

The outer `F1FlashProgrammer` restricts support to known single-bank F1 IDs and validated Flash/page/reserved ranges, merges sparse or odd records into aligned halfwords, and retains full byte readback verification. The loader's defensive range check is limited to the maximum 512 KB F1 Flash window; the actual target capacity remains the outer planner's responsibility.

Prior-art research (behavior only):

- [ST PM0075](https://www.st.com/resource/en/programming_manual/pm0075-stm32f10xxx-flash-memory-microcontrollers-stmicroelectronics.pdf), main Flash programming and FLASH_SR/CR register definitions.
- [stlink-org flash_loader.c](https://github.com/stlink-org/stlink/blob/develop/src/stlink-lib/flash_loader.c), RAM loader upload/register execution/completion approach.
- [Arm Cortex-M3 TRM](https://documentation-service.arm.com/static/5e8e107f88295d1e18d34714), debug register access and Thumb execution state.

Verify embedded code against the assembly source:

```sh
python3 scripts/verify-f1-loader.py --llvm-bin "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin"
```

The script compiles with `--target=arm-none-eabi -mcpu=cortex-m3 -mthumb`, extracts `.text` with LLVM objcopy, compares all 48 embedded bytes, and checks the terminal BKPT symbol offset (`0x26`). Generated object/binary files stay in a temporary directory.
