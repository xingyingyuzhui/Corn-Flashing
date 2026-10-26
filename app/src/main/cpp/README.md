# Native backend integration boundary

No native library is bundled or loaded by this framework. `UnavailableHardwareBackend`
always rejects execution. `DemoFlashBackend` never opens USB.

The first native implementation should satisfy `core/FlashBackend` and validate:

1. Android `UsbManager` permission and open connection → authorized USB FD.
2. FD → `libusb_wrap_sys_device` → adapted stlink session; no independent device scan/open.
3. Explicit ownership of `UsbDeviceConnection`, FD, libusb handle and JNI session handle.
4. Bounded calls, cooperative cancellation, disconnect invalidation and deterministic close.
5. Real device-group/capacity probing → `FlashPlanner` → page erase/write/read-back verification.
6. No global mutable session state shared by concurrently running probes.

Only after these pass on the actual hardware should a CMake/NDK target be introduced and
hardware actions enabled in the UI. Change the foreground-service type from the current
simulation-only `shortService` to `connectedDevice` with the appropriate permission and
USB grant preconditions. Do not reuse the simulation's fixed target or UID.
