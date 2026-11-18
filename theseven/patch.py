"""Change only the native menu opener in the supplied TheSeven 70.648 build.

Requires keystone-engine. Refuses all unrecognized inputs; offsets are NOT
portable to other APKs. The menu and feature implementation remain original.
"""
import argparse
import hashlib
from pathlib import Path

from keystone import Ks, KS_ARCH_ARM64, KS_MODE_LITTLE_ENDIAN

ORIGINAL_SHA256 = "49f2696d19908c311722387c9c965947740a1565638590ab827928255f1acbd0"
PATCHES = {
    # Unused eight-byte local before the original formatter buffer at sp+8.
    0x3501C: "str wzr, [sp]",
    0x35060: "mov w10, #0x43a00000",  # 320 * original viewport scale
    0x35074: "mov w8, #0x42b00000",  # 88 * original viewport scale
    # Replace the opener's drawing calls, retaining native hit registration.
    0x350C4: """
        ldr s11, [x19, #0x168]
        fmov s0, #0.5
        fmul s0, s9, s0
        fsub s11, s11, s0
        fmov s10, wzr
        b #0x350f0
        mov x0, x19
        ldr w8, [sp]
        cbnz w8, #0x35904
        b #0x356d0
        nop
    """,
    0x350F0: """
        mov w21, #1
        b #0x35138
        ldrb w8, [x19, #0x174]
        cbz w8, #0x35124
        tbz w21, #0, #0x35124
        ldr w8, [sp]
        cbnz w8, #0x35124
        mov w8, #1
        str w8, [sp]
        ldr s8, [x19, #0x164]
        ldr x23, [x19, #0x120]
        adr x25, #0x7d5a8
        b #0x3502c
        ldr x8, [x24, #0x28]
        b #0x35908
        nop
        nop
        nop
    """,
    0x356CC: "b #0x350dc",
    # Append the opener AFTER the open menu, so it remains clickable on top.
    0x35904: "b #0x350f8",
}


def patch(original: bytes) -> bytes:
    if hashlib.sha256(original).hexdigest() != ORIGINAL_SHA256:
        raise ValueError("Unrecognized libbrf.so; no changes made")
    assembler = Ks(KS_ARCH_ARM64, KS_MODE_LITTLE_ENDIAN)
    result = bytearray(original)
    changed = set()
    expected_sizes = {0x350C4: 44, 0x350F0: 72}
    for address, source in PATCHES.items():
        encoded, _ = assembler.asm(source, addr=address)
        assert len(encoded) == expected_sizes.get(address, 4)
        occupied = set(range(address, address + len(encoded)))
        assert not changed.intersection(occupied)
        changed.update(occupied)
        # .text virtual addresses equal file offsets in this exact build.
        result[address:address + len(encoded)] = bytes(encoded)
    assert len(result) == len(original)
    return bytes(result)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    output = patch(args.source.read_bytes())
    args.output.write_bytes(output)
    print(hashlib.sha256(output).hexdigest())
