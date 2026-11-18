"""Execute the original/patched ARM64 UI functions with Unicorn.

Only platform boundaries (renderer, allocation, libc formatting and clock)
are mocked. No UI/hit-registration/toggle instructions are mocked.
This does not substitute for testing the complete Android game on a device.
"""
import argparse
import io
import json
import math
import struct
from pathlib import Path

from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE, UcError
from unicorn.arm64_const import *
from patch import patch

BASE, DATA, STACK, STUB, TLS = 0x1000000, 0x2000000, 0x3000000, 0x4000000, 0x5000000
RETURN = STUB + 0xF000


class NativeUI:
    def __init__(self, binary, capacity=256):
        self.uc = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
        for address, size in [(BASE, 0x100000), (DATA, 0x100000),
                              (STACK, 0x100000), (STUB, 0x10000), (TLS, 0x1000)]:
            self.uc.mem_map(address, size)
        elf = ELFFile(io.BytesIO(binary))
        for segment in elf.iter_segments():
            if segment['p_type'] == 'PT_LOAD':
                self.uc.mem_write(BASE + segment['p_vaddr'], segment.data())
        self.externals = {}
        for section in elf.iter_sections():
            if section['sh_type'] != 'SHT_RELA':
                continue
            symbols = elf.get_section(section['sh_link'])
            for relocation in section.iter_relocations():
                if relocation['r_info_type'] == 1027:  # R_AARCH64_RELATIVE
                    value = BASE + relocation['r_addend']
                else:
                    symbol = symbols.get_symbol(relocation['r_info_sym'])
                    if symbol['st_shndx'] != 'SHN_UNDEF':
                        value = BASE + symbol['st_value']
                    else:
                        value = STUB + 0x1000 + len(self.externals) * 4
                        self.externals[value] = symbol.name
                self.write64(BASE + relocation['r_offset'], value)
        self.ctx, self.renderer, self.vtable = DATA, DATA + 0x1000, DATA + 0x1100
        self.heap = DATA + 0x10000
        self.write64(self.renderer, self.vtable)
        self.callbacks = {}
        for offset, name in [(0x10, 'rect'), (0x18, 'text'), (0x20, 'line')]:
            address = STUB + offset
            self.callbacks[address] = name
            self.write64(self.vtable + offset, address)
        if capacity:
            vector = self.allocate(capacity * 32)
            for offset, value in [(0x128, vector), (0x130, vector),
                                  (0x138, vector + capacity * 32)]:
                self.write64(self.ctx + offset, value)
        self.uc.mem_write(self.ctx + 0x180, struct.pack('<i', -1))
        self.uc.reg_write(UC_ARM64_REG_TPIDR_EL0, TLS)
        self.write64(TLS + 0x28, 0x12345678ABCD)
        self.uc.hook_add(UC_HOOK_CODE, self.hook)
        self.draws = []
        self.clock = 1_000_000_000
        self.called_externals = set()

    def write64(self, address, value):
        self.uc.mem_write(address, struct.pack('<Q', value))

    def read64(self, address):
        return struct.unpack('<Q', self.uc.mem_read(address, 8))[0]

    def x(self, n):
        return self.uc.reg_read(UC_ARM64_REG_X0 + n)

    def s(self, n):
        return struct.unpack('<f', struct.pack('<I', self.uc.reg_read(UC_ARM64_REG_S0 + n)))[0]

    def string(self, address):
        out = bytearray()
        while len(out) < 4096:
            byte = self.uc.mem_read(address + len(out), 1)
            if byte == b'\0':
                return bytes(out)
            out += byte
        raise AssertionError('Unterminated string')

    def allocate(self, size):
        start = self.heap
        self.heap += (size + 15) & ~15
        assert self.heap < DATA + 0x100000
        return start

    def returned(self, result=None):
        if result is not None:
            self.uc.reg_write(UC_ARM64_REG_X0, result)
        self.uc.reg_write(UC_ARM64_REG_PC, self.uc.reg_read(UC_ARM64_REG_LR))

    def hook(self, uc, address, size, _):
        if address in self.callbacks:
            name = self.callbacks[address]
            item = [name, [round(self.s(i), 4) for i in range(5)]]
            if name == 'text':
                item.append(self.string(self.x(1)).decode('utf-8', 'replace'))
            self.draws.append(item)
            self.returned()
        elif address == BASE + 0x72884:  # operator new
            self.returned(self.allocate(self.x(0)))
        elif address == BASE + 0x728F8:  # operator delete
            self.returned()
        elif address == BASE + 0x58378:  # steady clock, nanoseconds
            self.clock += 1_000_000_000
            self.returned(self.clock)
        elif address == BASE + 0x359E0:  # bounded formatting helper
            # Real Android libc formats FPS/progress here. Formatting itself
            # is outside the UI change; preserve stable text for comparison.
            value = self.string(self.x(3)).replace(b'%d', str(self.x(4)).encode())
            self.uc.mem_write(self.x(0), value[:max(0, self.x(1)-1)] + b'\0')
            self.returned(len(value))
        elif address in self.externals:
            name = self.externals[address]
            self.called_externals.add(name)
            if name == 'strlen':
                self.returned(len(self.string(self.x(0))))
            elif name in ('memcpy', 'memmove', '__memcpy_chk', '__memmove_chk'):
                self.uc.mem_write(self.x(0), bytes(self.uc.mem_read(self.x(1), self.x(2))))
                self.returned()
            elif name in ('memset', '__memset_chk'):
                self.uc.mem_write(self.x(0), bytes([self.x(1) & 255]) * self.x(2))
                self.returned()
            else:
                raise AssertionError(f'Unmocked platform call {name} from {self.uc.reg_read(UC_ARM64_REG_LR)-BASE:#x}')

    def call(self, address):
        self.uc.reg_write(UC_ARM64_REG_SP, STACK + 0x80000)
        self.uc.reg_write(UC_ARM64_REG_LR, RETURN)
        try:
            self.uc.emu_start(BASE + address, RETURN, count=500_000)
        except UcError as exc:
            raise AssertionError(f'{exc}: PC={self.uc.reg_read(UC_ARM64_REG_PC):#x}') from exc
        assert self.uc.reg_read(UC_ARM64_REG_PC) == RETURN, 'Native function failed to return'

    def render(self, width=1280, height=720, interactive=1):
        self.draws = []
        for n, value in enumerate([self.ctx, self.renderer, 100, 60, interactive]):
            self.uc.reg_write(UC_ARM64_REG_X0 + n, value)
        for n, value in enumerate([width, height]):
            self.uc.reg_write(UC_ARM64_REG_S0 + n, struct.unpack('<I', struct.pack('<f', value))[0])
        self.call(0x34E48)
        start, end = self.read64(self.ctx + 0x128), self.read64(self.ctx + 0x130)
        hits = [struct.unpack('<I4fIQ', self.uc.mem_read(address, 32)) for address in range(start, end, 32)]
        return self.draws.copy(), hits

    def toggle(self, hit_id=1):
        self.uc.reg_write(UC_ARM64_REG_X0, self.ctx)
        self.uc.reg_write(UC_ARM64_REG_X1, hit_id)
        self.call(0x32DD8)
        return bool(self.uc.mem_read(self.ctx + 0x174, 1)[0])


def run(original):
    modified = patch(original)
    cases = []
    for capacity in [0, 1, 256]:
        for width, height in [(1280, 720), (2400, 1080), (800, 360), (720, 1280)]:
            for tab in [0, 1, 2]:
                source, result = NativeUI(original, capacity), NativeUI(modified, capacity)
                for ui in [source, result]:
                    ui.uc.mem_write(ui.ctx + 0x17C, struct.pack('<i', tab))
                source_draws, source_hits = source.render(width, height)
                draws, hits = result.render(width, height)
                assert any('더세븐' in str(draw) for draw in source_draws)
                assert draws == [], 'Closed opener must have no pixels'
                assert len(hits) == 1 and hits[0][0] == 1
                scale = max(.8, min(1.15, height / 576))
                expected = [width / 2 - 160 * scale, 0, 320 * scale, 88 * scale]
                assert all(math.isclose(a, b, abs_tol=.001) for a, b in zip(hits[0][1:5], expected))
                assert source.toggle() and result.toggle()
                source_draws, source_hits = source.render(width, height)
                draws, hits = result.render(width, height)
                assert draws == source_draws, 'Original expanded menu drawing changed'
                assert hits[:-1] == source_hits, 'Original expanded menu controls changed'
                assert hits[-1][0] == 1, 'Invisible toggle must be topmost (last)'
                assert not result.toggle(), 'Same invisible button must close menu'
                assert result.render(width, height)[0] == []
                assert result.toggle() and not result.toggle(3), 'Existing close X must work'
                # Repeated redraw/rotate/open/close must retain the trigger.
                for _ in range(4):
                    result.render(width, height)
                    assert result.toggle()
                    result.render(height, width)
                    assert not result.toggle()
                cases.append({'capacity': capacity, 'viewport': [width, height], 'tab': tab, 'pass': True})
    # Noninteractive loading-screen rendering is unchanged, open or closed.
    for opened in [False, True]:
        source, result = NativeUI(original), NativeUI(modified)
        if opened:
            source.toggle()
            result.toggle()
        assert source.render(interactive=0) == result.render(interactive=0)
    try:
        patch(modified)
        raise AssertionError('Already patched input was not rejected')
    except ValueError:
        pass
    return {'native_cases': cases, 'noninteractive_cases': 2,
            'wrong_input_rejected': True,
            'scope': 'Actual ARM64 menu renderer and click handler; mocked platform boundaries',
            'physical_device_game_tested': False}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('source', type=Path)
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    report = run(args.source.read_bytes())
    if args.report:
        args.report.write_text(json.dumps(report, indent=2) + '\n')
    print(f"PASS: {len(report['native_cases'])} render/toggle scenarios + 2 loading-screen cases")
