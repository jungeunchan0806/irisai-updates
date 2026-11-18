"""Verify every APK payload; only libbrf.so may differ from the input."""
import argparse
import hashlib
import json
import re
import struct
import zipfile
from pathlib import Path

from patch import patch

LIB = 'lib/arm64-v8a/libbrf.so'
BASELINE_SHA256 = '309be672f46987a109f7eb8690c6b32b51e4fe6f24867c9566bb1ba145345a7b'


def sha(path):
    with open(path, 'rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def is_signature(name):
    return re.fullmatch(r'META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))', name, re.I)


def verify(source, output):
    assert sha(source) == BASELINE_SHA256, 'Unrecognized original APK'
    with zipfile.ZipFile(source) as original, zipfile.ZipFile(output) as result, open(output, 'rb') as raw:
        before = {i.filename for i in original.infolist() if not is_signature(i.filename)}
        after = {i.filename for i in result.infolist() if not is_signature(i.filename)}
        assert before == after, 'Unexpected added or removed APK content'
        assert len(result.namelist()) == len(set(result.namelist())), 'Duplicate ZIP names'
        modified = []
        for name in sorted(before):
            with original.open(name) as a, result.open(name) as b:
                first = hashlib.file_digest(a, 'sha256').digest()
                second = hashlib.file_digest(b, 'sha256').digest()
            if first != second:
                modified.append(name)
            info = result.getinfo(name)
            if info.compress_type == zipfile.ZIP_STORED:
                raw.seek(info.header_offset)
                header = raw.read(30)
                assert header[:4] == b'PK\x03\x04'
                name_size, extra_size = struct.unpack_from('<HH', header, 26)
                start = info.header_offset + 30 + name_size + extra_size
                alignment = 16384 if name.endswith('.so') else 4
                assert start % alignment == 0, f'Unaligned {name}'
        assert modified == [LIB], modified
        assert result.read(LIB) == patch(original.read(LIB)), 'Incorrect native patch'
        return {'sha256': sha(output), 'bytes': Path(output).stat().st_size,
                'baseline_sha256': BASELINE_SHA256, 'modified_entries': modified,
                'unchanged_entries': len(before) - 1, 'all_entry_crc_and_hashes_checked': True,
                'stored_entry_alignment_checked': True,
                'physical_device_game_tested': False}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('original', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    report = verify(args.original, args.output)
    if args.report:
        args.report.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))
