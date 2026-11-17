#!/usr/bin/env python3
"""Replace only Iris code in the exact published packaging2 APK and sign a new candidate."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile
from build_ingame import APKEDITOR_SHA256, run, sha
from validate_apk import dex_classes, digest, STALE_JAR_SIGNATURE

PREVIOUS_SHA256 = 'f644891128d3774010beff017e9e682c484bf444d8e04f66de097e47fbab4fa5'
PREVIOUS_CERT = '487be061bdbadf1269cb5b81c051d897624c5373f5734f46274e5da8465ae591'
IRIS_LIBS = {'lib/arm64-v8a/' + n for n in (
    'libopencv_java4.so', 'libonnxruntime.so', 'libonnxruntime4j_jni.so')}
MODULE_NAMESPACES = ('Lcom/irisai/autogoal/', 'Lai/onnxruntime/', 'Lorg/opencv/')


def previous_dex(apk):
    result = []
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if not re.fullmatch(r'classes\d*\.dex', name):
                continue
            classes = dex_classes(archive.read(name))
            if not any(c.startswith('Lcom/irisai/autogoal/') for c in classes):
                continue
            unexpected = [c for c in classes if not c.startswith(MODULE_NAMESPACES)]
            if unexpected:
                raise ValueError('Refusing to remove mixed game/module DEX: ' + repr(unexpected[:10]))
            result.append(name)
    if not result:
        raise ValueError('Previous Iris DEX not found')
    return result


def signature(apk, java, sdk):
    output = subprocess.check_output([str(java), '-jar', str(sdk / 'build-tools/35.0.0/lib/apksigner.jar'),
        'verify', '--min-sdk-version', '24', '--verbose', '--print-certs', str(apk)], text=True)
    for version in ('v2', 'v3'):
        if f'Verified using {version} scheme (APK Signature Scheme {version}): true' not in output:
            raise ValueError('Missing APK ' + version + ' signature')
    return re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-f]+)', output).group(1)


def validate_update(previous, candidate, payload, old_dex, sdk, java):
    cert = signature(candidate, java, sdk)
    run(sdk / 'build-tools/35.0.0/zipalign', '-c', '-P', '16', '4', candidate)
    manifest = subprocess.check_output([str(sdk / 'build-tools/35.0.0/aapt2'), 'dump', 'xmltree',
        '--file', 'AndroidManifest.xml', str(candidate)], text=True)
    if manifest.count('com.irisai.autogoal.ingame.InitProvider') != 1:
        raise ValueError('Expected exactly one Iris provider')
    if not re.search(r'versionCode[^\n]*=70757', manifest) or 'com.supercell.brawlstars' not in manifest:
        raise ValueError('Game identity changed')
    if any(p in manifest for p in ['SYSTEM_ALERT_WINDOW', 'BIND_ACCESSIBILITY_SERVICE', 'FOREGROUND_SERVICE_MEDIA_PROJECTION']):
        raise ValueError('Unexpected external control permission')
    retained = metadata = 0
    with zipfile.ZipFile(previous) as before, zipfile.ZipFile(candidate) as after, zipfile.ZipFile(payload) as module:
        if len(after.namelist()) != len(set(after.namelist())):
            raise ValueError('Duplicate ZIP entries')
        for entry in before.infolist():
            name = entry.filename
            if entry.is_dir() or name == 'AndroidManifest.xml' or name in old_dex or name in IRIS_LIBS or name.startswith('assets/iris_autogoal/') or STALE_JAR_SIGNATURE.fullmatch(name):
                continue
            with before.open(name) as src, after.open(name) as dst:
                if digest(src) != digest(dst):
                    raise ValueError('Retained game content changed: ' + name)
            retained += 1
            metadata += name.startswith('META-INF/')
        all_classes = set()
        final_dex_hashes = set()
        for name in after.namelist():
            if re.fullmatch(r'classes\d*\.dex', name):
                current = dex_classes(after.read(name))
                if all_classes & current:
                    raise ValueError('Duplicate DEX classes')
                all_classes.update(current)
                with after.open(name) as stream: final_dex_hashes.add(digest(stream))
        for name in module.namelist():
            if re.fullmatch(r'classes\d*\.dex', name):
                with module.open(name) as stream:
                    if digest(stream) not in final_dex_hashes:
                        raise ValueError('Current module DEX missing')
            elif name.startswith('assets/iris_autogoal/') or name in IRIS_LIBS:
                with module.open(name) as src, after.open(name) as dst:
                    if digest(src) != digest(dst): raise ValueError('Payload content mismatch: ' + name)
        required = {'Lcom/irisai/autogoal/ingame/Engine;', 'Lcom/irisai/autogoal/ingame/NativeBridge;',
            'Lcom/irisai/autogoal/ingame/GameController;', 'Lcom/irisai/autogoal/ui/IrisControlPanel;'}
        if not required <= all_classes:
            raise ValueError('Real auto-goal/UI integration missing')
        if any(c.startswith('Lcom/irisai/autogoal/preview/') or c.startswith('Lcom/irisai/autogoal/harness/') for c in all_classes):
            raise ValueError('Preview/test code must not be shipped in the game')
    return {'apk': candidate.name, 'bytes': candidate.stat().st_size, 'sha256': sha(candidate),
        'previous_apk_sha256': PREVIOUS_SHA256, 'signer_certificate_sha256': cert,
        'same_signer_as_previous': cert == PREVIOUS_CERT, 'replaced_iris_dex': old_dex,
        'unchanged_game_entries': retained, 'unchanged_metadata_entries': metadata,
        'static_checks': {'signature_v2_v3': True, 'alignment_16kb': True, 'one_provider': True,
            'unique_dex_classes': True, 'old_game_content_preserved': True, 'new_payload_exact': True,
            'real_engine_and_bridge_present': True, 'no_preview_classes': True},
        'live_game_tested': False, 'friendly_matches': 0, 'game_crash_fix_verified': False,
        'streamer_mode_enabled': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('previous', 'payload', 'apkeditor', 'java-home', 'sdk', 'keystore', 'password-file', 'work', 'out'):
        parser.add_argument('--' + name, type=Path, required=True)
    a = parser.parse_args()
    if a.out.exists() or a.work.exists(): raise SystemExit('Choose new output and work paths')
    if sha(a.previous) != PREVIOUS_SHA256 or sha(a.apkeditor) != APKEDITOR_SHA256:
        raise SystemExit('Previous APK or APKEditor checksum mismatch')
    java, javac = a.java_home / 'bin/java', a.java_home / 'bin/javac'
    if signature(a.previous, java, a.sdk) != PREVIOUS_CERT:
        raise SystemExit('Previous APK signer mismatch')
    old_dex = previous_dex(a.previous)
    a.work.mkdir(parents=True); a.out.parent.mkdir(parents=True, exist_ok=True)
    classes = a.work / 'classes'; classes.mkdir()
    run(javac, '-cp', a.apkeditor, '-d', classes, Path(__file__).parent / 'patcher/InjectPayload.java')
    unsigned, aligned = a.work / 'unsigned.apk', a.work / 'aligned.apk'
    run(java, '-Xmx4g', '-cp', str(a.apkeditor) + os.pathsep + str(classes), 'InjectPayload',
        a.previous, a.payload, unsigned, ','.join(old_dex))
    run(a.sdk / 'build-tools/35.0.0/zipalign', '-P', '16', '-f', '4', unsigned, aligned)
    unsigned.unlink()
    run(java, '-Xmx2g', '-jar', a.sdk / 'build-tools/35.0.0/lib/apksigner.jar', 'sign',
        '--ks', a.keystore, '--ks-key-alias', 'iris-ingame', '--ks-pass', 'file:' + str(a.password_file),
        '--min-sdk-version', '32', '--v1-signing-enabled', 'false', '--v2-signing-enabled', 'true',
        '--v3-signing-enabled', 'true', '--v4-signing-enabled', 'false', '--out', a.out, aligned)
    aligned.unlink()
    report = validate_update(a.previous, a.out, a.payload, old_dex, a.sdk, java)
    a.out.with_suffix('.validation.json').write_text(json.dumps(report, indent=2) + '\n')
    a.out.with_suffix('.apk.sha256').write_text(report['sha256'] + '  ' + a.out.name + '\n')
    print(json.dumps(report, indent=2))
    print('Actual game APK rebuilt. Live game compatibility is not established by static checks.')


if __name__ == '__main__': main()
