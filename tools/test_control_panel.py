#!/usr/bin/env python3
"""Run UI-only instrumentation on a disposable emulator, never a real phone/game."""
import json
from pathlib import Path
import subprocess


def adb(*args, **kwargs):
    return subprocess.run(['adb', *args], check=True, text=True, capture_output=True, **kwargs).stdout


serial = adb('get-serialno').strip()
if not serial.startswith('emulator-') or adb('shell', 'getprop', 'ro.kernel.qemu').strip() != '1':
    raise SystemExit('A disposable Android emulator is required; real devices are refused.')
root = Path('control-panel-results'); root.mkdir(exist_ok=True)
adb('install', '-r', 'uipreview/build/outputs/apk/debug/uipreview-debug.apk')
adb('install', '-r', 'uipreview/build/outputs/apk/androidTest/debug/uipreview-debug-androidTest.apk')
for label, size, density in [('wide', '1280x720', '160'), ('compact', '960x540', '240')]:
    adb('shell', 'wm', 'size', size); adb('shell', 'wm', 'density', density)
    adb('shell', 'am', 'force-stop', 'com.irisai.autogoal.preview')
    target = root / label; target.mkdir(exist_ok=True)
    output = adb('shell', 'am', 'instrument', '-w', '-r', 'com.irisai.autogoal.preview.test/com.irisai.autogoal.preview.PreviewChecks', timeout=150)
    (target / 'instrumentation.txt').write_text(output)
    # Keep screenshots even if a later assertion or result-format check fails.
    try:
        adb('pull', '/sdcard/Android/data/com.irisai.autogoal.preview/files/ui-checks', str(target))
    except subprocess.CalledProcessError:
        pass
    if 'PASS ' not in output or 'INSTRUMENTATION_CODE: -1' not in output:
        (target / 'crash.txt').write_text(adb('logcat', '-d', '-b', 'crash', '-t', '100'))
        raise SystemExit('UI checks failed: ' + output)
    report = json.loads((target / 'ui-checks/result.json').read_text())
    if report.get('passed') is not True or report.get('game_startup_tested') is not False:
        raise SystemExit('Invalid test report')
    print(label, 'passed', len(report['checks']), 'UI checks')
adb('shell', 'wm', 'size', 'reset'); adb('shell', 'wm', 'density', 'reset')
