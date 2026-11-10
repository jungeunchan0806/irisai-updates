"""Apply source patches before importing the Windows startup/application code."""
import json
import os
from pathlib import Path
import subprocess
import sys
from .updater import app_lock, check_and_update, recover, DEFAULT_FEED


def main():
    root = Path(__file__).resolve().parents[1]
    os.chdir(root)
    with app_lock(root):
        recover(root)
        config_file = root / 'update-settings.json'
        try:
            config = json.loads(config_file.read_text(encoding='utf-8')) if config_file.exists() else {}
            if config.get('auto_update', True):
                print('Checking Windows patches...', flush=True)
                check_and_update(root, config.get('manifest_url', DEFAULT_FEED))
        except Exception as error:
            recover(root)  # Do not start a partially patched installation.
            print(f'Update unavailable; using installed version: {error}', flush=True)
        # New process loads the newly installed startup code. The parent keeps the lock.
        env = dict(os.environ, PYLA_MANAGED_LAUNCH='1', PYTHONUNBUFFERED='1')
        return subprocess.call([sys.executable, '-m', 'pyla_addons.startup', *sys.argv[1:]], cwd=root, env=env)


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f'Startup failed: {error}', file=sys.stderr)
        raise SystemExit(1)
