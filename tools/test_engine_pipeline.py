#!/usr/bin/env python3
"""Exercise production Iris controller/model/input routing in a disposable test app."""
import json
from pathlib import Path
import subprocess


def run(*args, check=True, timeout=180):
    return subprocess.run(args, check=check, capture_output=True, text=True, timeout=timeout)


def main():
    devices = run('adb', 'devices').stdout.splitlines()
    serials = [line.split()[0] for line in devices if line.endswith('\tdevice')]
    if len(serials) != 1 or not serials[0].startswith('emulator-'):
        raise SystemExit('Requires one disposable emulator; physical devices are refused')
    serial = serials[0]
    def adb(*args, **kw): return run('adb', '-s', serial, *args, **kw)
    if adb('shell', 'getprop', 'ro.kernel.qemu').stdout.strip() != '1':
        raise SystemExit('Not an emulator')
    package = 'com.irisai.autogoal.enginecheck'
    if adb('shell', 'pm', 'path', package, check=False).stdout.strip():
        raise SystemExit('Refusing to replace a pre-existing test installation')
    out = Path('engine-check-results'); out.mkdir(exist_ok=True)
    adb('shell', 'wm', 'size', '960x540'); adb('shell', 'wm', 'density', '160')
    adb('install', '-t', 'enginecheck/build/outputs/apk/debug/enginecheck-debug.apk')
    adb('install', '-t', 'enginecheck/build/outputs/apk/androidTest/debug/enginecheck-debug-androidTest.apk')
    result = adb('shell', 'am', 'instrument', '-w', '-r', package + '.test/com.irisai.autogoal.ingame.EngineChecks', check=False)
    (out / 'instrumentation.txt').write_text(result.stdout + result.stderr)
    adb('pull', '/sdcard/Android/data/' + package + '/files/engine-check.json', str(out / 'engine-check.json'), check=False)
    print(result.stdout)
    if result.returncode or 'INSTRUMENTATION_CODE: -1' not in result.stdout or 'PASS ' not in result.stdout:
        log = adb('logcat', '-d', '-v', 'brief', 'IrisAutoGoal:E', 'AndroidRuntime:E', '*:S', check=False)
        (out / 'test-errors.txt').write_text(log.stdout[-18000:])
        raise SystemExit('Production engine pipeline test did not pass')
    report = json.loads((out / 'engine-check.json').read_text())
    assert report['passed'] and report['live_game_tested'] is False
    print('Verified recorded-frame pipeline, not original game startup or live matches.')


if __name__ == '__main__': main()
