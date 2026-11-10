"""List emulator installations and ADB state without altering emulator settings."""
import json
import os
from pathlib import Path
import sys
import tomllib
from .emulators import discover, validate_settings


def main():
    root = Path(__file__).resolve().parents[1]
    report = {'platform': sys.platform, 'python': sys.version.split()[0], 'emulators': [], 'devices': [], 'errors': []}
    try:
        with (root / 'cfg/general_config.toml').open('rb') as handle:
            config = tomllib.load(handle)
        preferred, timeout, instance = validate_settings(config)
        report['selection'] = {'port': preferred, 'instance': instance, 'timeout': timeout}
        if os.name == 'nt':
            report['emulators'] = [{'name': e.name, 'instance': e.instance, 'executable': str(e.executable),
                                    'ports': e.ports, 'config': str(e.config) if e.config else None}
                                   for e in discover(config.get('emulator_path', ''))]
        from adbutils import adb
        adb.server_start()
        # adb devices includes offline/unauthorized state; don't silently discard it.
        report['devices'] = [{'serial': e.serial, 'state': e.state} for e in adb.list()]
    except Exception as error:
        report['errors'].append(str(error))
    output = root / 'logs/windows-diagnostics.json'
    output.parent.mkdir(exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(output.read_text(encoding='utf-8'))
    print(f'Diagnostics saved: {output}')


if __name__ == '__main__':
    main()
