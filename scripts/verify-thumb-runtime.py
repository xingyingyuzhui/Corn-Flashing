#!/usr/bin/env python3
"""Execute repository-owned Thumb code in Unicorn; never connects to hardware."""
from pathlib import Path
import re
import struct

from unicorn import (
    Uc, UC_ARCH_ARM, UC_MODE_THUMB, UC_MODE_MCLASS,
    UC_HOOK_CODE, UC_HOOK_MEM_READ, UC_HOOK_MEM_WRITE, UC_HOOK_INTR,
)
from unicorn.arm_const import (
    UC_ARM_REG_R0, UC_ARM_REG_R1, UC_ARM_REG_R2, UC_ARM_REG_R3,
    UC_ARM_REG_R9, UC_ARM_REG_SP, UC_ARM_REG_LR, UC_ARM_REG_PC, UC_ARM_REG_XPSR,
)

ROOT = Path(__file__).resolve().parent.parent
RAM = 0x20000000
FLASH = 0x08000000
SR = 0x4002200C


def check(condition, message):
    """Checks remain enabled under python -O."""
    if not condition:
        raise RuntimeError(message)


def f1_code():
    source = (ROOT / "core/src/main/kotlin/dev/corn/flashing/core/stlink/F1RamLoader.kt").read_text()
    match = re.search(r'internal val CODE: ByteArray = "([0-9a-f]+)"', source)
    offset = re.search(r'const val BREAK_OFFSET = (0x[0-9a-fA-F]+)L', source)
    check(match is not None and offset is not None, "Cannot locate embedded F1 loader/BKPT")
    code, done = bytes.fromhex(match[1]), int(offset[1], 16)
    check(code[done:done + 2] == b"\x00\xbe", "F1 completion offset must contain BKPT")
    return code, done


def check_f1(words, *, fault=False, stuck=False):
    code, done = f1_code()
    uc = Uc(UC_ARCH_ARM, UC_MODE_THUMB | UC_MODE_MCLASS)
    for address, size in [(RAM, 4096), (FLASH, 0x80000), (0x40022000, 4096)]:
        uc.mem_map(address, size)
    payload = b"".join(word.to_bytes(2, "little") for word in words)
    uc.mem_write(RAM, code)
    uc.mem_write(RAM + 256, payload or b"\x00\x00")
    for register, value in [(UC_ARM_REG_R0, FLASH), (UC_ARM_REG_R1, RAM + 256),
                            (UC_ARM_REG_R2, len(words)), (UC_ARM_REG_XPSR, 0x01000000)]:
        uc.reg_write(register, value)
    state = {"writes": 0, "busy": 0, "interrupts": 0, "reads": 0, "instructions": 0}

    def write(machine, access, address, size, value, user):
        if address == SR:
            check(size == 4 and value == 0x34, "Unexpected FLASH_SR clear operation")
            return
        index = state["writes"]
        check(FLASH <= address < FLASH + 0x80000, f"Unexpected loader write: {address:#x}")
        check(size == 2, f"Flash store must be 16-bit, got {size} bytes")
        check(index < len(words), "Loader wrote beyond requested payload")
        check(address == FLASH + index * 2 and value == words[index], "Wrong Flash address/value")
        state["writes"] += 1
        state["busy"] = 3

    def read(machine, access, address, size, value, user):
        if address == SR:
            check(size == 4, "FLASH_SR must be read as a word")
            state["reads"] += 1
            status = 1 if stuck or state["busy"] else (4 if fault else 32)
            state["busy"] = max(0, state["busy"] - 1)
            machine.mem_write(SR, status.to_bytes(4, "little"))

    def interrupt(machine, number, user):
        pc = machine.reg_read(UC_ARM_REG_PC)
        check(pc in (RAM + done, RAM + done + 2), f"Unexpected loader interrupt at {pc:#x}")
        state["interrupts"] += 1
        machine.emu_stop()

    def instruction(machine, address, size, user):
        state["instructions"] += 1

    uc.hook_add(UC_HOOK_MEM_WRITE, write)
    uc.hook_add(UC_HOOK_MEM_READ, read)
    uc.hook_add(UC_HOOK_INTR, interrupt)
    uc.hook_add(UC_HOOK_CODE, instruction)
    uc.emu_start(RAM | 1, RAM + len(code), count=100000)
    if stuck:
        check(state["instructions"] == 100000, "Stuck BSY must reach the instruction bound")
        check(state["interrupts"] == 0 and state["writes"] == 1, "Stuck BSY must not finish or write another halfword")
        check(uc.reg_read(UC_ARM_REG_R2) == len(words), "Stuck BSY must retain remaining count")
    elif fault:
        check(state["interrupts"] == 1 and state["writes"] == 1, "PGERR must stop after first halfword")
        check(uc.reg_read(UC_ARM_REG_R2) == len(words), "PGERR must retain remaining count")
        check(uc.reg_read(UC_ARM_REG_R3) & 4, "PGERR status must reach caller")
    else:
        check(state["interrupts"] == 1, "Loader did not stop at completion BKPT")
        check(state["writes"] == len(words) and uc.reg_read(UC_ARM_REG_R2) == 0, "Wrong completed halfword count")
        check(uc.reg_read(UC_ARM_REG_R0) == FLASH + len(payload), "Wrong destination progress")
        check(uc.reg_read(UC_ARM_REG_R1) == RAM + 256 + len(payload), "Wrong source progress")
        if payload:
            check(bytes(uc.mem_read(FLASH, len(payload))) == payload, "Flash payload differs")
    check(state["reads"] >= state["writes"] * 4, "BSY polling was not exercised")


def fixture_code_and_entries():
    """Read only the original test fixture's code/symbols, not arbitrary external FLM."""
    data = (ROOT / "core/src/test/resources/fixtures/fixture.flm").read_bytes()
    check(data[:7] == b"\x7fELF\x01\x01\x01", "Fixture must be ELF32 little-endian")
    check(struct.unpack_from("<H", data, 18)[0] == 40, "Fixture must target ARM")
    section_offset = struct.unpack_from("<I", data, 32)[0]
    entry_size, count, names_index = struct.unpack_from("<HHH", data, 46)
    check(entry_size == 40 and names_index < count, "Invalid fixture section table")
    check(section_offset + count * entry_size <= len(data), "Truncated fixture section table")
    sections = [struct.unpack_from("<10I", data, section_offset + i * entry_size) for i in range(count)]

    def contents(section):
        offset, length = section[4:6]
        check(offset + length <= len(data), "Truncated fixture section")
        return data[offset:offset + length]

    def string(table, offset):
        check(offset < len(table), "Invalid fixture string offset")
        end = table.find(b"\x00", offset)
        check(end >= 0, "Unterminated fixture string")
        return table[offset:end].decode("ascii")

    names = contents(sections[names_index])
    code_index = next(i for i, section in enumerate(sections) if string(names, section[0]) == "PrgCode")
    code_section = sections[code_index]
    entries = {}
    for section in sections:
        if section[1] != 2:  # SHT_SYMTAB
            continue
        check(section[6] < count and section[9] == 16, "Invalid fixture symbol table")
        strings = contents(sections[section[6]])
        symbols = contents(section)
        check(len(symbols) % 16 == 0, "Truncated fixture symbols")
        for offset in range(0, len(symbols), 16):
            name, value, size, info, other, index = struct.unpack_from("<IIIBBH", symbols, offset)
            symbol = string(strings, name)
            if symbol in ("Init", "UnInit", "EraseSector", "ProgramPage"):
                check(index == code_index and value & 1, "Fixture entry must be Thumb code")
                relative = (value & ~1) - code_section[3]
                check(0 <= relative < code_section[5], "Fixture entry outside code")
                check(symbol not in entries, "Duplicate fixture entry")
                entries[symbol] = relative
    check(len(entries) == 4, "Missing fixture entry symbols")
    return contents(code_section), entries


def check_fixture():
    code, entries = fixture_code_and_entries()
    for name, offset in entries.items():
        uc = Uc(UC_ARCH_ARM, UC_MODE_THUMB | UC_MODE_MCLASS)
        # No Flash/peripherals are mapped: accidental access fails immediately.
        uc.mem_map(RAM, 4096)
        uc.mem_write(RAM, b"\x00\xbe\xfd\xe7")
        uc.mem_write(RAM + 16, code)
        for register, value in [(UC_ARM_REG_R0, FLASH), (UC_ARM_REG_R1, 256),
                                (UC_ARM_REG_R2, RAM + 512), (UC_ARM_REG_R9, RAM + 32),
                                (UC_ARM_REG_SP, RAM + 4096), (UC_ARM_REG_LR, RAM | 1),
                                (UC_ARM_REG_XPSR, 0x01000000)]:
            uc.reg_write(register, value)
        interrupts = []

        def interrupt(machine, number, user):
            check(machine.reg_read(UC_ARM_REG_PC) in (RAM, RAM + 2), f"{name}: unexpected return breakpoint")
            interrupts.append(number)
            machine.emu_stop()

        def write(machine, access, address, size, value, user):
            raise RuntimeError(f"{name}: no-write fixture attempted a memory write at {address:#x}")

        uc.hook_add(UC_HOOK_INTR, interrupt)
        uc.hook_add(UC_HOOK_MEM_WRITE, write)
        uc.emu_start((RAM + 16 + offset) | 1, RAM + 4096, count=100)
        check(len(interrupts) == 1, f"{name}: missing LR return to BKPT")
        check(uc.reg_read(UC_ARM_REG_R0) == 0, f"{name}: nonzero result")
        check(uc.reg_read(UC_ARM_REG_R9) == RAM + 32, f"{name}: R9 changed")
        check(uc.reg_read(UC_ARM_REG_SP) == RAM + 4096, f"{name}: SP changed")


def main():
    for count in (0, 1, 2, 255, 256):
        check_f1([(i * 137) & 0xFFFF for i in range(count)])
    check_f1([0x1234, 0xABCD], fault=True)
    check_f1([0x1234], stuck=True)
    print("PASS: F1 embedded Thumb loader: 0/1/2/255/256 halfwords, BSY polling, PGERR stop, stuck BSY bound, 16-bit Flash stores")
    check_fixture()
    print("PASS: original no-write FLM fixture: four symbol-resolved entries return via LR to BKPT; R0=0; R9/SP unchanged")
    print("These are CPU/mock-register checks, not hardware Flash or external FLM compatibility evidence.")


if __name__ == "__main__":
    main()
