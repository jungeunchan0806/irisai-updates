#!/usr/bin/env python3
"""Static APK validation; never reports a live match or native runtime as tested."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import zipfile

STALE_JAR_SIGNATURE = re.compile(
    r'META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC)|SIG-[^/]+)', re.IGNORECASE)


def validate_original_metadata(final, originals):
    """Check runtime metadata independently of asset/native/DEX checks.

    The original candidate dropped this directory entirely. Only obsolete JAR
    signatures may be omitted when producing an APK signed with another key.
    """
    verified = set()
    for apk in sorted(originals.glob('*.apk')):
        with zipfile.ZipFile(apk) as original:
            for info in original.infolist():
                name = info.filename
                if (info.is_dir() or not name.startswith('META-INF/')
                        or STALE_JAR_SIGNATURE.fullmatch(name)):
                    continue
                try:
                    with original.open(name) as source, final.open(name) as output:
                        same = digest(source) == digest(output)
                except KeyError as error:
                    raise ValueError('Original runtime metadata missing: ' + name) from error
                if not same:
                    raise ValueError('Original runtime metadata changed: ' + name)
                verified.add(name)
    return sorted(verified)


def dex_classes(data):
    if data[:4] != b'dex\n':
        raise ValueError('Not a DEX file')
    strings = []
    count, offset = struct.unpack_from('<II', data, 56)
    for i in range(count):
        p = struct.unpack_from('<I', data, offset + i * 4)[0]
        while data[p] & 128:
            p += 1
        p += 1
        strings.append(data[p:data.index(0, p)].decode('utf-8', 'replace'))
    count, offset = struct.unpack_from('<II', data, 64)
    types = [strings[struct.unpack_from('<I', data, offset + i * 4)[0]] for i in range(count)]
    count, offset = struct.unpack_from('<II', data, 96)
    return {types[struct.unpack_from('<I', data, offset + i * 32)[0]] for i in range(count)}


def digest(stream):
    return hashlib.file_digest(stream, 'sha256').hexdigest()


def validate(candidate, originals, payload, sdk, java):
    report = {'apk': candidate.name, 'bytes': candidate.stat().st_size,
              'sha256': digest(candidate.open('rb')), 'static_checks': {},
              'live_brawlstars_tested': False, 'friendly_matches': 0}
    checks = report['static_checks']
    build = sdk / 'build-tools' / '35.0.0'
    signature = subprocess.check_output([str(java), '-jar', str(build / 'lib/apksigner.jar'),
                                         'verify', '--min-sdk-version', '24', '--verbose', '--print-certs', str(candidate)], text=True)
    checks['signature_v2_v3'] = ('Verified using v2 scheme (APK Signature Scheme v2): true' in signature
                                and 'Verified using v3 scheme (APK Signature Scheme v3): true' in signature)
    match = re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-f]+)', signature)
    report['signer_certificate_sha256'] = match.group(1) if match else None
    subprocess.run([str(build / 'zipalign'), '-c', '-P', '16', '4', str(candidate)], check=True)
    checks['zip_alignment'] = True
    manifest = subprocess.check_output([str(build / 'aapt2'), 'dump', 'xmltree', '--file', 'AndroidManifest.xml', str(candidate)], text=True)
    checks['provider'] = all(s in manifest for s in ['com.irisai.autogoal.ingame.InitProvider', 'com.supercell.brawlstars.irisautogoal.init'])
    checks['no_external_control_permissions'] = all(s not in manifest for s in [
        'SYSTEM_ALERT_WINDOW', 'BIND_ACCESSIBILITY_SERVICE', 'FOREGROUND_SERVICE_MEDIA_PROJECTION'])
    checks['standalone'] = 'isSplitRequired' not in manifest and 'requiredSplitTypes' not in manifest
    checks['min_sdk_32'] = bool(re.search(r'minSdkVersion[^\n]*=32', manifest))
    checks['version_70757'] = bool(re.search(r'versionCode[^\n]*=70757', manifest))
    with zipfile.ZipFile(candidate) as final, zipfile.ZipFile(payload) as module:
        names = final.namelist()
        checks['unique_zip_entries'] = len(names) == len(set(names))
        all_classes = set()
        for name in names:
            if re.fullmatch(r'classes\d*\.dex', name):
                current = dex_classes(final.read(name))
                if all_classes & current:
                    raise ValueError('Duplicate classes: ' + repr(sorted(all_classes & current)[:15]))
                all_classes.update(current)
        checks['unique_dex_classes'] = True
        checks['entrypoint_class_present'] = 'Lcom/irisai/autogoal/ingame/InitProvider;' in all_classes
        verified = 0
        for apk in sorted(originals.glob('*.apk')):
            with zipfile.ZipFile(apk) as original:
                for info in original.infolist():
                    name = info.filename
                    if name.endswith('/') or not (name.startswith(('assets/', 'lib/')) or re.fullmatch(r'classes\d*\.dex', name)):
                        continue
                    if digest(original.open(name)) != digest(final.open(name)):
                        raise ValueError('Original game content changed: ' + name)
                    verified += 1
        checks['original_assets_dex_native_unchanged'] = True
        report['original_entries_verified'] = verified
        metadata = validate_original_metadata(final, originals)
        checks['original_runtime_metadata_unchanged'] = True
        report['original_metadata_entries_verified'] = len(metadata)
        report['original_service_descriptors_verified'] = [
            n for n in metadata if n.startswith('META-INF/services/')]
        for info in module.infolist():
            name = info.filename
            if name.startswith(('assets/iris_autogoal/', 'lib/arm64-v8a/')) and not name.endswith('/libc++_shared.so'):
                if digest(module.open(name)) != digest(final.open(name)):
                    raise ValueError('Module content mismatch: ' + name)
        checks['payload_content'] = True
    if not all(checks.values()):
        raise ValueError('Static validation failure: ' + repr(checks))
    return report


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--apk', type=Path, required=True)
    p.add_argument('--originals', type=Path, required=True)
    p.add_argument('--payload', type=Path, required=True)
    p.add_argument('--sdk', type=Path, required=True)
    p.add_argument('--java', type=Path, required=True)
    p.add_argument('--report', type=Path, required=True)
    a = p.parse_args()
    report = validate(a.apk, a.originals, a.payload, a.sdk, a.java)
    a.report.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))
