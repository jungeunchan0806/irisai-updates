import copy, struct, re, zipfile, zlib
def without_padding(extra):
    result = b''
    while extra:
        if not extra.strip(b'\0'):
            break  # Some APK packers use raw zero padding, not an extra-field TLV.
        assert len(extra) >= 4
        tag, length = struct.unpack_from('<HH', extra)
        assert length + 4 <= len(extra)
        field, extra = extra[:4 + length], extra[4 + length:]
        if tag != 0xd935:
            result += field
    return result


def repack(source, output, replacements):
    """Copy original compressed bytes. Recompress only explicitly replaced entries."""
    with zipfile.ZipFile(source) as src, open(source, 'rb') as raw, zipfile.ZipFile(output, 'w') as dst:
        dst.comment = src.comment
        for original in src.infolist():
            if re.fullmatch(r'META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))', original.filename, re.I):
                continue
            info = copy.copy(original)
            info.flag_bits &= ~8  # Write known CRC/sizes directly, without data descriptor.
            info.header_offset = dst.fp.tell()
            info.extra = without_padding(info.extra)
            changed = replacements.get(info.filename)
            if changed is not None:
                info.CRC = zlib.crc32(changed)
                info.file_size = len(changed)
                if info.compress_type == zipfile.ZIP_DEFLATED:
                    compressor = zlib.compressobj(9, zlib.DEFLATED, -15)
                    compressed = compressor.compress(changed) + compressor.flush()
                else:
                    assert info.compress_type == zipfile.ZIP_STORED
                    compressed = changed
                info.compress_size = len(compressed)
            if info.compress_type == zipfile.ZIP_STORED:
                alignment = 16384 if info.filename.endswith('.so') else 4
                start = info.header_offset + len(info.FileHeader())
                if start % alignment:
                    length = (-start - 4) % alignment
                    info.extra += struct.pack('<HH', 0xd935, length) + b'\0' * length
            dst.fp.write(info.FileHeader())
            if changed is not None:
                dst.fp.write(compressed)
            else:
                raw.seek(original.header_offset)
                header = raw.read(30)
                assert header[:4] == b'PK\x03\x04'
                name_length, extra_length = struct.unpack_from('<HH', header, 26)
                raw.seek(name_length + extra_length, 1)
                remaining = original.compress_size
                while remaining:
                    block = raw.read(min(8 * 1024 * 1024, remaining))
                    assert block
                    dst.fp.write(block)
                    remaining -= len(block)
            dst.filelist.append(info)
            dst.NameToInfo[info.filename] = info
            dst.start_dir = dst.fp.tell()
            dst._didModify = True
        for name, payload in replacements.items():
            if name not in src.namelist():
                dst.writestr(name, payload, compress_type=zipfile.ZIP_DEFLATED)

