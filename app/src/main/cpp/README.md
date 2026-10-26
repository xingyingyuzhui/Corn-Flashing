# Authorized USBFS diagnostic transport

`usbfs_diag.c` implements bulk transfer, endpoint CLEAR_HALT and errno decoding via JNI.
It borrows only the FD returned by Android's authorized UsbDeviceConnection. It never opens,
claims, duplicates or closes a device FD. The Kotlin session owns connection and interface lifetime.

CMake 3.22.1 + NDK 27.2.12479018 build four Android ABIs. Linker maximum page size is 16384.
JNI availability self-tests use fd=-1, returning EBADF before any ioctl; no hardware I/O occurs.

The native strategy is considered only during read-only GET_VERSION handshake selection.
After selection, all protocol operations use that fixed strategy. Failed flash commands are
never automatically replayed. CLEAR_HALT is only permitted by the handshake coordinator when
endpoint GET_STATUS reports halt or native bulk returns EPIPE, at most once per endpoint.

No libusb/stlink native dependency is included; the existing Kotlin ST-LINK protocol is retained.
Root is not requested. The native path exposes errno; it does not bypass Android/OEM permissions.
