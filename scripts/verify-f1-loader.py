#!/usr/bin/env python3
"""Reassemble the original Cortex-M3 loader and compare its embedded bytes/terminal BKPT."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('--llvm-bin', type=Path, help='NDK LLVM prebuilt bin directory')
args = parser.parse_args()
llvm = args.llvm_bin
if llvm is None:
    ndk = os.environ.get('ANDROID_NDK_HOME')
    if not ndk:
        parser.error('provide --llvm-bin or ANDROID_NDK_HOME')
    bins = list((Path(ndk) / 'toolchains/llvm/prebuilt').glob('*/bin'))
    if len(bins) != 1:
        parser.error('cannot uniquely locate NDK LLVM bin directory')
    llvm = bins[0]
root = Path(__file__).resolve().parent.parent
source = root / 'core/src/main/asm/f1_halfword_loader.S'
kotlin = (root / 'core/src/main/kotlin/dev/corn/flashing/core/stlink/F1RamLoader.kt').read_text()
embedded = bytes.fromhex(re.search(r'internal val CODE: ByteArray = "([0-9a-f]+)"', kotlin)[1])
break_offset = int(re.search(r'const val BREAK_OFFSET = (0x[0-9A-Fa-f]+)L', kotlin)[1], 16)
with tempfile.TemporaryDirectory(prefix='corn-f1-loader-') as temp:
    obj = Path(temp) / 'loader.o'
    binary = Path(temp) / 'loader.bin'
    subprocess.run([str(llvm / 'clang'), '--target=arm-none-eabi', '-mcpu=cortex-m3', '-mthumb', '-c', str(source), '-o', str(obj)], check=True)
    subprocess.run([str(llvm / 'llvm-objcopy'), '-O', 'binary', '-j', '.text', str(obj), str(binary)], check=True)
    rebuilt = binary.read_bytes()
    if rebuilt != embedded:
        raise SystemExit('ERROR: embedded loader differs from assembled source')
    symbols = subprocess.check_output([str(llvm / 'llvm-nm'), str(obj)], text=True)
    symbol_offset = int(re.search(r'^([0-9a-fA-F]+)\s+\w\s+corn_f1_done$', symbols, re.M)[1], 16)
    if break_offset != symbol_offset or embedded[break_offset:break_offset+2] != b'\x00\xbe':
        raise SystemExit('ERROR: completion BKPT address differs')
print(f'F1 RAM loader verified: {len(embedded)} bytes, BKPT offset 0x{break_offset:x}')
