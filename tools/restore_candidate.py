#!/usr/bin/env python3
"""Reconstruct the exact signed candidate; no signing key is distributed."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import urllib.request

def checksum(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--manifest', type=Path, default=Path(__file__).resolve().parents[1] / 'distribution/candidate.json')
    p.add_argument('--backup', type=Path, required=True, help='Existing ZIP, or destination if --download is set')
    p.add_argument('--download', action='store_true')
    p.add_argument('--out', type=Path, required=True)
    p.add_argument('--xdelta', default='xdelta3')
    a = p.parse_args(); manifest = json.loads(a.manifest.read_text())
    if a.out.exists():
        raise SystemExit('Output exists; choose a new filename.')
    patch = a.manifest.parent / manifest['patch_filename']
    if checksum(patch) != manifest['patch_sha256']:
        raise SystemExit('Patch checksum mismatch.')
    if not a.backup.exists() and a.download:
        a.backup.parent.mkdir(parents=True, exist_ok=True)
        temporary = a.backup.with_suffix('.download')
        try:
            with urllib.request.urlopen(manifest['backup_url'], timeout=90) as src, temporary.open('wb') as out:
                while chunk := src.read(4 * 1024 * 1024): out.write(chunk)
            if checksum(temporary) != manifest['backup_sha256']:
                raise ValueError('Downloaded backup checksum mismatch.')
            temporary.replace(a.backup)
        finally:
            temporary.unlink(missing_ok=True)
    if checksum(a.backup) != manifest['backup_sha256']:
        raise SystemExit('The ZIP must be the unchanged BrawlStars-70.757-BlueStacks-APK.zip.')
    a.out.parent.mkdir(parents=True, exist_ok=True)
    temporary = a.out.with_suffix('.reconstructing')
    if temporary.exists():
        raise SystemExit('Temporary output already exists: ' + str(temporary))
    try:
        subprocess.run([a.xdelta, '-d', '-s', str(a.backup), str(patch), str(temporary)], check=True)
        if checksum(temporary) != manifest['apk_sha256']:
            raise ValueError('Reconstructed APK checksum mismatch.')
        temporary.replace(a.out)
    finally:
        temporary.unlink(missing_ok=True)
    print('Verified signed candidate:', a.out)
    print('This does not establish live game compatibility or friendly battle success.')

if __name__ == '__main__':
    main()
