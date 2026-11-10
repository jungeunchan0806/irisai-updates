"""Prepare a local Python environment and only install changed requirements."""
import hashlib
from pathlib import Path
import struct
import subprocess
import sys


def install_dependencies(root, force=False):
    requirement = root / 'requirements.txt'
    fingerprint = hashlib.sha256(requirement.read_bytes()).hexdigest()
    marker = Path(sys.prefix) / '.pyla-requirements.sha256'
    if not force and marker.is_file() and marker.read_text().strip() == fingerprint:
        return
    if sys.prefix == sys.base_prefix:
        raise RuntimeError('Run Start-Windows.bat to use the isolated .venv environment')
    subprocess.run([sys.executable, '-m', 'pip', 'install', '-r', str(requirement)], check=True)
    subprocess.run([sys.executable, '-m', 'pip', 'check'], check=True)
    marker.write_text(fingerprint, encoding='ascii')


def main():
    if sys.platform != 'win32' or sys.version_info[:2] != (3, 11) or struct.calcsize('P') != 8:
        raise RuntimeError('Windows x64 and Python 3.11 x64 are required')
    root = Path(__file__).resolve().parents[1]
    python = root / '.venv/Scripts/python.exe'
    if '--dependencies' in sys.argv:
        install_dependencies(root, force=True)
        return
    if not python.exists():
        subprocess.run([sys.executable, '-m', 'venv', str(root / '.venv')], check=True)
    subprocess.run([str(python), '-m', 'pyla_addons.bootstrap', '--dependencies'], cwd=root, check=True)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(f'Setup failed: {error}', file=sys.stderr)
        raise SystemExit(1)
