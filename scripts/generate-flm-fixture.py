#!/usr/bin/env python3
"""Generate the original no-write ARM FLM used only by parser/runtime tests."""
import argparse
from pathlib import Path
import subprocess
import tempfile
parser = argparse.ArgumentParser()
parser.add_argument('--llvm-bin', required=True, type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
with tempfile.TemporaryDirectory(prefix='corn-flm-fixture-') as directory:
    obj = Path(directory) / 'fixture.o'
    subprocess.run([str(args.llvm_bin / 'clang'), '--target=arm-none-eabi', '-mcpu=cortex-m3', '-mthumb', '-c', str(root / 'scripts/fixtures/fixture_flm.S'), '-o', str(obj)], check=True)
    output = root / 'core/src/test/resources/fixtures/fixture.flm'
    subprocess.run([str(args.llvm_bin / 'ld.lld'), '--nmagic', '-T', str(root / 'scripts/fixtures/fixture_flm.ld'), str(obj), '-o', str(output)], check=True)
    print(f'Generated test-only FLM: {output} ({output.stat().st_size} bytes)')
