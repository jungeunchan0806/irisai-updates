#!/usr/bin/env python3
"""Rebuild the 70.757 candidate from the exact user-provided backup and our payload."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile
from validate_apk import validate

BACKUP_SHA256 = '8e1f184645198723018aa2d89cca5842925cf9a618fa1182d92aeb2ddebd34ea'
APKEDITOR_SHA256 = 'a9cd40df818845456be6d696de6110c89edf4b0a0580cb83438ed6b25a366e67'
ORIGINALS = {
    'base.apk': '0d6db21949e9fe7fca344648b1a709a1b603730ae42d28e831e25ac28d626e0e',
    'split_config.arm64_v8a.apk': '3e3038f9b795827dbccb73f79574c429179cb57be089bca66e832b901f669e8b',
    'split_config.en.apk': 'e9a4e2afd5caa5a96261730232438cece37380899d8b1d30b7bb70b55d0d8931',
    'split_config.ko.apk': '3f961d909fd19abc188195e61fa8037e41ae3b1a37d3f64446acb7725c30e879',
    'split_config.xhdpi.apk': 'b545c325676466fd8ae31c565c19b6b0f5082b1a4806d9e80e04f117cac14369',
    'split_install_time_asset_pack.apk': 'dc78a2a3383592129d0482a2ebbda77b4302cf193ca8a479eab4a154af8febee',
}

def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def run(*args):
    subprocess.run([str(v) for v in args], check=True)

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--backup', type=Path, required=True)
    p.add_argument('--payload', type=Path, required=True)
    p.add_argument('--apkeditor', type=Path, required=True)
    p.add_argument('--java-home', type=Path, required=True)
    p.add_argument('--sdk', type=Path, required=True)
    p.add_argument('--keystore', type=Path, required=True)
    p.add_argument('--password-file', type=Path, required=True)
    p.add_argument('--alias', default='iris-ingame')
    p.add_argument('--work', type=Path, required=True, help='New empty temporary build directory')
    p.add_argument('--out', type=Path, required=True)
    a = p.parse_args()
    if a.out.exists():
        raise SystemExit('Output already exists; choose a new filename.')
    if sha(a.backup) != BACKUP_SHA256 or sha(a.apkeditor) != APKEDITOR_SHA256:
        raise SystemExit('Backup/APKEditor checksum mismatch.')
    a.work.mkdir(parents=True, exist_ok=False)
    a.out.parent.mkdir(parents=True, exist_ok=True)
    originals = a.work / 'originals'; originals.mkdir()
    with zipfile.ZipFile(a.backup) as archive:
        for name in ORIGINALS:
            matches = [n for n in archive.namelist() if Path(n).name == name]
            if len(matches) != 1:
                raise SystemExit('Missing/ambiguous original: ' + name)
            dest = originals / name
            with archive.open(matches[0]) as src, dest.open('wb') as out:
                shutil.copyfileobj(src, out, 1024 * 1024)
            if sha(dest) != ORIGINALS[name]:
                raise SystemExit('Original APK checksum mismatch: ' + name)
    suffix = '.exe' if os.name == 'nt' else ''
    java = a.java_home / 'bin' / ('java' + suffix)
    javac = a.java_home / 'bin' / ('javac' + suffix)
    build = a.sdk / 'build-tools' / '35.0.0'
    classes = a.work / 'patcher-classes'; classes.mkdir()
    patcher = Path(__file__).parent / 'patcher/InjectPayload.java'
    run(javac, '-cp', a.apkeditor, '-d', classes, patcher)
    merged = a.work / 'merged.apk'
    unsigned = a.work / 'unsigned.apk'
    aligned = a.work / 'aligned.apk'
    run(java, '-Xmx4g', '-jar', a.apkeditor, 'm', '-i', originals, '-o', merged, '-clean-meta', '-validate-modules')
    run(java, '-Xmx4g', '-cp', str(a.apkeditor) + os.pathsep + str(classes),
        'InjectPayload', merged, a.payload, unsigned)
    merged.unlink()
    run(build / ('zipalign' + suffix), '-P', '16', '-f', '4', unsigned, aligned)
    unsigned.unlink()
    run(java, '-Xmx2g', '-jar', build / 'lib/apksigner.jar', 'sign', '--ks', a.keystore,
        '--ks-key-alias', a.alias, '--ks-pass', 'file:' + str(a.password_file),
        '--min-sdk-version', '32', '--v1-signing-enabled', 'false', '--v2-signing-enabled', 'true',
        '--v3-signing-enabled', 'true', '--v4-signing-enabled', 'false', '--out', a.out, aligned)
    aligned.unlink()
    result = validate(a.out, originals, a.payload, a.sdk, java)
    a.out.with_suffix('.validation.json').write_text(json.dumps(result, indent=2) + '\n')
    a.out.with_suffix('.apk.sha256').write_text(result['sha256'] + '  ' + a.out.name + '\n')
    print('Built and statically verified:', a.out)
    print('Live Brawl Stars and friendly battles have NOT been verified by this script.')

if __name__ == '__main__':
    main()
