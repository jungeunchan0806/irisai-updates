#!/usr/bin/env python3
"""Validate this packaging-only retry; never treats static checks as a launch test."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile


def digest(stream):
    return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--original', type=Path, required=True)
    parser.add_argument('--retry', type=Path, required=True)
    parser.add_argument('--signature-report', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    manifest = json.loads((root / 'distribution/packaging2/manifest.json').read_text())
    for path, expected in [(args.original, manifest['backup_sha256']),
                           (args.retry, manifest['apk_sha256'])]:
        with path.open('rb') as stream:
            if digest(stream) != expected:
                raise SystemExit('Unexpected APK contents: ' + path.name)
    signature = args.signature_report.read_text()
    for required in [
        'Verified using v2 scheme (APK Signature Scheme v2): true',
        'Verified using v3 scheme (APK Signature Scheme v3): true',
        'Signer #1 certificate SHA-256 digest: 487be061bdbadf1269cb5b81c051d897624c5373f5734f46274e5da8465ae591',
    ]:
        if required not in signature:
            raise SystemExit('Missing expected signature verification: ' + required)
    with zipfile.ZipFile(args.original) as old, zipfile.ZipFile(args.retry) as new:
        old_names, new_names = set(old.namelist()), set(new.namelist())
        if len(new_names) != len(new.namelist()):
            raise SystemExit('Duplicate ZIP entries')
        added = new_names - old_names
        if (old_names - new_names or len(added) != 128
                or any(not n.startswith('META-INF/') for n in added)):
            raise SystemExit('Changes exceed the 128 restored metadata entries')
        for name in sorted(old_names):
            with old.open(name) as source, new.open(name) as output:
                if digest(source) != digest(output):
                    raise SystemExit('Existing content changed: ' + name)
        for descriptor in ['META-INF/services/fi', 'META-INF/services/zd1']:
            if not new.read(descriptor).strip():
                raise SystemExit('Missing or empty service descriptor: ' + descriptor)
    args.retry.with_suffix('.apk.sha256').write_text(
        manifest['apk_sha256'] + '  ' + args.retry.name + '\n')
    print('Signature and hashes verified; 128 metadata entries restored; all prior file bytes unchanged.')
    print('Physical phone startup and nv.s: 01 resolution remain UNVERIFIED.')


if __name__ == '__main__':
    main()
