"""Prepare Android before opening Pyla's Windows UI; diagnostics remain accessible."""
import os
from pathlib import Path
import subprocess
import sys
import tomllib
from .emulators import boolean, prepare, start_game


def main():
    root = Path(__file__).resolve().parents[1]
    os.chdir(root)
    if os.name != 'nt':
        raise RuntimeError('This launcher is for Windows 10/11 x64')
    # A successful dependency install writes this hash; failed installs are retried next run.
    from .bootstrap import install_dependencies
    install_dependencies(root)
    with (root / 'cfg/general_config.toml').open('rb') as handle:
        config = tomllib.load(handle)
    log_path = root / 'logs/windows-startup.log'
    log_path.parent.mkdir(exist_ok=True)
    with log_path.open('w', encoding='utf-8') as handle:
        def log(message):
            print(message, flush=True)
            handle.write(str(message) + '\n')
            handle.flush()
        try:
            from adbutils import adb
            if boolean(config.get('auto_emulator', True)):
                serial = prepare(adb, config, log)
                start_game(adb, serial, config, log)
        except Exception as error:
            # Still show General settings so a custom path/port can be corrected.
            log(f'Emulator setup: {error}')
            log('Opening Pyla settings. Correct General settings and press Start to retry.')
    return subprocess.call([sys.executable, str(root / 'main.py'), *sys.argv[1:]], cwd=root)


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f'Startup failed: {error}', file=sys.stderr)
        raise SystemExit(1)
