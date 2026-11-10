"""Windows emulator discovery and local ADB startup. No shell or global ADB kill."""
from dataclasses import dataclass
import csv
import io
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import tempfile
import time

WINDOWS = os.name == 'nt'
DEFAULT_PORTS = (5555, 5557, 62001, 16384, 16416, 7555)
EXECUTABLES = ('HD-Player.exe', 'ldconsole.exe', 'dnconsole.exe', 'Nox.exe',
               'MuMuPlayer.exe', 'NemuPlayer.exe')


@dataclass(frozen=True)
class Emulator:
    name: str
    executable: Path
    args: tuple = ()
    ports: tuple = ()
    config: Path | None = None
    instance: str = ''

    @property
    def label(self):
        return f'{self.name} / {self.instance or "default"}'


def run(args, timeout=10):
    return subprocess.run([str(x) for x in args], capture_output=True, text=True,
                          errors='replace', timeout=timeout, check=False,
                          creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))


def registry_roots():
    """Read installed product locations, including custom installation drives."""
    if not WINDOWS:
        return []
    import winreg
    roots = []
    for hive in (winreg.HKEY_LOCAL_MACHINE, winreg.HKEY_CURRENT_USER):
        for view in (winreg.KEY_WOW64_64KEY, winreg.KEY_WOW64_32KEY):
            for key in (r'SOFTWARE\BlueStacks_nxt', r'SOFTWARE\BlueStacks',
                        r'SOFTWARE\leidian', r'SOFTWARE\Nox', r'SOFTWARE\Netease\MuMuPlayer'):
                try:
                    with winreg.OpenKey(hive, key, 0, winreg.KEY_READ | view) as handle:
                        for name in ('InstallDir', 'InstallPath', 'UserDefinedDir', 'DataDir'):
                            try:
                                value = winreg.QueryValueEx(handle, name)[0]
                                if isinstance(value, str) and value:
                                    roots.append(Path(os.path.expandvars(value)))
                            except OSError:
                                pass
                except OSError:
                    pass
            try:
                with winreg.OpenKey(hive, r'SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall',
                                    0, winreg.KEY_READ | view) as handle:
                    for i in range(winreg.QueryInfoKey(handle)[0]):
                        try:
                            with winreg.OpenKey(handle, winreg.EnumKey(handle, i)) as item:
                                label = winreg.QueryValueEx(item, 'DisplayName')[0]
                                if not any(n in str(label).lower() for n in ('bluestacks', 'ldplayer', 'nox', 'mumu')):
                                    continue
                                value = winreg.QueryValueEx(item, 'InstallLocation')[0]
                                if isinstance(value, str) and value:
                                    roots.append(Path(os.path.expandvars(value)))
                        except OSError:
                            pass
            except OSError:
                pass
    return list(dict.fromkeys(roots))


def parse_bluestacks(text):
    instances = {}
    for instance, port in re.findall(r'^bst\.instance\.([\w-]+)\.(?:adb_port|status\.adb_port)\s*=\s*"(\d+)"', text, re.M):
        number = int(port)
        if 0 < number < 65536 and number != 5037:
            instances.setdefault(instance, set()).add(number)
    return instances


def discover(extra_path=''):
    roots = registry_roots()
    for base in (os.environ.get('ProgramFiles', r'C:\Program Files'),
                 os.environ.get('ProgramFiles(x86)', r'C:\Program Files (x86)'),
                 os.environ.get('LOCALAPPDATA', ''), 'C:/', 'D:/'):
        if base:
            roots.extend(Path(base) / suffix for suffix in (
                'BlueStacks_nxt', 'BlueStacks', 'LDPlayer/LDPlayer9', 'LDPlayer/LDPlayer4.0',
                'LDPlayer9', 'Nox', 'Netease/MuMuPlayer-12.0', 'MuMuPlayerGlobal-12.0',
                'MuMuPlayer', 'Nemu'))
    requested = Path(os.path.expandvars(extra_path)).expanduser() if extra_path else None
    if requested:
        if not requested.exists():
            raise FileNotFoundError(f'Emulator Path does not exist: {requested}')
        roots = [requested.parent if requested.is_file() else requested]
    configs = [root / 'bluestacks.conf' for root in registry_roots()]
    programdata = Path(os.environ.get('ProgramData', r'C:\ProgramData'))
    found, seen = [], set()
    for base in dict.fromkeys(roots):
        for root in (base, base / 'bin', base / 'shell', base / 'vmonitor' / 'bin'):
            for filename in EXECUTABLES:
                exe = root / filename
                if not exe.is_file() or (requested and requested.is_file() and exe.resolve() != requested.resolve()):
                    continue
                kind = ('BlueStacks' if filename == 'HD-Player.exe' else
                        'LDPlayer' if filename in ('ldconsole.exe', 'dnconsole.exe') else
                        'Nox' if filename == 'Nox.exe' else 'MuMu')
                identity = (str(root.resolve()).casefold(), kind)
                if identity in seen:
                    continue
                seen.add(identity)
                if kind == 'BlueStacks':
                    product = 'BlueStacks_nxt' if 'nxt' in str(root).lower() else 'BlueStacks'
                    options = [root / 'bluestacks.conf', programdata / product / 'bluestacks.conf']
                    options += [p for p in configs if product.casefold() == p.parent.name.casefold()]
                    conf = next((p for p in options if p.is_file()), None)
                    if conf is None and len([p for p in configs if p.is_file()]) == 1:
                        conf = next(p for p in configs if p.is_file())
                    instances = parse_bluestacks(conf.read_text(encoding='utf-8-sig')) if conf else {}
                    for instance, ports in sorted(instances.items()):
                        found.append(Emulator(kind, exe, ('--instance', instance), tuple(sorted(ports)), conf, instance))
                    if not instances:
                        found.append(Emulator(kind, exe, (), (5555,), conf))
                elif kind == 'LDPlayer':
                    files = sorted(root.glob('vms/config/leidian[0-9]*.config'))
                    for conf in files:
                        match = re.fullmatch(r'leidian(\d+)\.config', conf.name)
                        if not match:
                            continue
                        index = int(match[1])
                        port = 5555 + 2 * index
                        if port < 65536:
                            found.append(Emulator(kind, exe, ('launch', '--index', str(index)),
                                                  (port,), conf, str(index)))
                    if not files:
                        found.append(Emulator(kind, exe, ('launch', '--index', '0'), (5555,), None, '0'))
                elif kind == 'Nox':
                    found.append(Emulator(kind, exe, (), (62001,)))
                else:
                    found.append(Emulator(kind, exe, (), (16384, 16416, 7555)))
    priority = {'BlueStacks': 0, 'LDPlayer': 1, 'MuMu': 2, 'Nox': 3}
    return sorted(found, key=lambda e: (priority[e.name], str(e.executable).casefold(), e.instance))


def replace_config(path, original, updated):
    """Refuse concurrent edits; keep a byte-exact backup next to the configuration."""
    if original == updated:
        return False
    if path.is_symlink():
        raise RuntimeError('Emulator configuration must not be a symlink')
    if path.read_bytes() != original:
        raise RuntimeError('Emulator configuration changed; close the emulator and retry')
    backup = path.with_name(path.name + f'.pyla-{time.time_ns()}.bak')
    shutil.copy2(path, backup)
    fd, temp = tempfile.mkstemp(prefix='.pyla-', dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as handle:
            handle.write(updated)
            handle.flush()
            os.fsync(handle.fileno())
        shutil.copymode(path, temp)
        if path.read_bytes() != original:
            raise RuntimeError('Emulator configuration changed during setup')
        os.replace(temp, path)
    finally:
        Path(temp).unlink(missing_ok=True)
    return True


def enable_bluestacks_adb(config, instance):
    raw = config.read_bytes()
    text = raw.decode('utf-8-sig')
    matched = 0
    for key in ('bst.enable_adb_access', f'bst.instance.{instance}.enable_adb_access'):
        pattern = rf'(?m)^({re.escape(key)}\s*=\s*)"[01]"'
        text, count = re.subn(pattern, lambda m: m[1] + '"1"', text)
        matched += count
    if not matched:
        raise RuntimeError('Unknown BlueStacks config format. Enable Settings > Advanced > Android Debug Bridge.')
    updated = (b'\xef\xbb\xbf' if raw.startswith(b'\xef\xbb\xbf') else b'') + text.encode('utf-8')
    return replace_config(config, raw, updated)


def enable_ldplayer_adb(config):
    raw = config.read_bytes()
    text = raw.decode('utf-8-sig')
    document = json.loads(text)
    value = document.get('basicSettings.adbDebug')
    if type(value) is not int or value not in (0, 1, 2):
        raise RuntimeError('Unknown LDPlayer config format. Enable Settings > Other > ADB debugging > Local connection.')
    # 1 = local connection. Do not enable root or remote debugging.
    text, count = re.subn(r'("basicSettings\.adbDebug"\s*:\s*)[012](?=\s*[,}])',
                         lambda m: m[1] + '1', text)
    if count != 1:
        raise RuntimeError('Ambiguous LDPlayer ADB setting')
    updated = (b'\xef\xbb\xbf' if raw.startswith(b'\xef\xbb\xbf') else b'') + text.encode('utf-8')
    return replace_config(config, raw, updated)


def is_running(emulator):
    """Conservatively protect config whenever any same-brand instance is active."""
    if not WINDOWS:
        return False
    names = {'BlueStacks': ('HD-Player.exe',), 'LDPlayer': ('dnplayer.exe', 'Ld9BoxHeadless.exe'),
             'MuMu': ('MuMuPlayer.exe', 'NemuPlayer.exe'), 'Nox': ('Nox.exe',)}[emulator.name]
    result = run(['tasklist', '/FO', 'CSV', '/NH'])
    if result.returncode:
        raise RuntimeError('Cannot check emulator process state; refusing to edit a potentially live config')
    active = {row[0].casefold() for row in csv.reader(io.StringIO(result.stdout)) if row}
    return any(n.casefold() in active for n in names)


def port_open(port):
    try:
        with socket.create_connection(('127.0.0.1', port), timeout=.08):
            return True
    except OSError:
        return False


def serial_port(serial):
    match = re.fullmatch(r'(?:127\.0\.0\.1|localhost):(\d+)', serial)
    if match:
        port = int(match[1])
    elif re.fullmatch(r'emulator-\d+', serial):
        port = int(serial.removeprefix('emulator-')) + 1
    else:
        return None
    return port if 0 < port <= 65535 and port != 5037 else None


def boolean(value, default=True):
    if value is None:
        return default
    return str(value).lower().strip() in ('true', '1', 'yes', 'on')


def validate_settings(config):
    raw = config.get('emulator_port', 0) or 0
    preferred = int(raw)
    if isinstance(raw, bool) or float(raw) != preferred or not 0 <= preferred <= 65535:
        raise ValueError('Emulator Port must be 0 (auto) or 1..65535')
    timeout = int(config.get('emulator_boot_timeout', 120))
    if not 5 <= timeout <= 300:
        raise ValueError('Emulator boot timeout must be 5..300 seconds')
    instance = str(config.get('emulator_instance', '')).strip()
    if instance and not re.fullmatch(r'[\w-]{1,64}', instance):
        raise ValueError('Invalid emulator instance name')
    # Legacy upstream default 5037 is the host server, never a guest endpoint.
    return (0 if preferred == 5037 else preferred), timeout, instance


def connect_devices(adb, ports):
    for port in dict.fromkeys(ports):
        if 0 < port <= 65535 and port != 5037 and port_open(port):
            try:
                adb.connect(f'127.0.0.1:{port}', timeout=1)
            except Exception:
                pass
    # Deduplicate emulator-5554 and 127.0.0.1:5555 aliases of the same guest.
    devices = {}
    for device in sorted(adb.device_list(), key=lambda d: d.serial):
        port = serial_port(device.serial)
        try:
            if port is not None and device.get_state() == 'device':
                devices.setdefault(port, device)
        except Exception:
            pass
    return devices


def booted(device):
    try:
        return device.shell('getprop sys.boot_completed', timeout=2).strip() == '1'
    except Exception:
        return False


def prepare(adb, config, log=print):
    """Detect, optionally configure/launch, then wait for the chosen Android guest."""
    preferred, timeout, instance = validate_settings(config)
    explicit_path = str(config.get('emulator_path', '')).strip()
    candidates = discover(explicit_path) if WINDOWS else []
    if explicit_path and not candidates:
        raise RuntimeError('No supported emulator executable found at Emulator Path')
    if instance:
        candidates = [e for e in candidates if e.instance == instance]
        if not candidates:
            raise RuntimeError(f'Emulator instance {instance!r} was not found')
    if preferred:
        matching = [e for e in candidates if preferred in e.ports]
        # An explicit executable may use a custom port not present in config.
        candidates = matching or (candidates if explicit_path and len(candidates) == 1 else [])
    ports = ([preferred] if preferred else [p for e in candidates for p in e.ports])
    if not explicit_path and not instance and not preferred:
        ports += list(DEFAULT_PORTS)
    adb.server_start()
    devices = connect_devices(adb, ports)
    allowed = set(ports)
    constrained = bool(preferred or explicit_path or instance)
    available = {p: d for p, d in devices.items() if not constrained or p in allowed}
    chosen = None
    device = None
    if len(available) == 1:
        device = next(iter(available.values()))
    elif len(available) > 1:
        raise ConnectionError('Multiple emulators are online. Select Emulator Port in General settings: '
                              + ', '.join(f'{p} ({d.serial})' for p, d in available.items()))
    if device and booted(device):
        log(f'ADB ready: {device.serial}')
        return device.serial
    if not device and candidates:
        chosen = candidates[0]
        # Config-derived ports are stricter than broad fallback probes after launch.
        allowed = {preferred} if preferred else set(chosen.ports)
        ports = sorted(allowed)
        log(f'Selected {chosen.label}: {chosen.executable}')
        if boolean(config.get('auto_emulator', True)):
            running = is_running(chosen)
            if chosen.config and chosen.name in ('BlueStacks', 'LDPlayer'):
                if running:
                    log('Emulator is running: leaving its config intact. If ADB is off, close it and start Pyla again.')
                else:
                    try:
                        changed = (enable_bluestacks_adb(chosen.config, chosen.instance)
                                   if chosen.name == 'BlueStacks' else enable_ldplayer_adb(chosen.config))
                        log('ADB enabled (configuration backed up).' if changed else 'ADB is already enabled.')
                    except (OSError, ValueError, RuntimeError) as error:
                        log(f'Automatic ADB setup unavailable: {error}')
            elif chosen.name in ('Nox', 'MuMu'):
                log(f'{chosen.name}: trying built-in ADB. If disabled, enable ADB once in emulator settings.')
            subprocess.Popen([str(chosen.executable), *chosen.args], cwd=chosen.executable.parent,
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            log('Waiting for Android to finish booting...')
    if not device and not candidates and not preferred:
        raise ConnectionError('No emulator detected. Install BlueStacks 5 or LDPlayer 9, or set Emulator Path in General settings.')
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        devices = connect_devices(adb, ports)
        if device:
            devices = {p: d for p, d in devices.items() if p == serial_port(device.serial)}
        else:
            devices = {p: d for p, d in devices.items() if p in allowed}
        if len(devices) > 1:
            raise ConnectionError('Multiple matching devices appeared; set an explicit Emulator Port')
        for current in devices.values():
            if booted(current):
                log(f'ADB ready: {current.serial}')
                return current.serial
        time.sleep(1)
    raise ConnectionError(f'Android/ADB was not ready after {timeout}s. Check ADB authorization, the selected port, '
                          'and emulator settings. For automatic BlueStacks/LDPlayer ADB setup, close the emulator and retry.')


def start_game(adb, serial, config, log=print):
    if not boolean(config.get('auto_start_game', True)):
        return
    package = str(config.get('brawl_stars_package', 'com.supercell.brawlstars'))
    if not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+', package):
        raise ValueError('Invalid Android package name')
    device = adb.device(serial=serial)
    if package not in device.list_packages():
        raise RuntimeError(f'Install {package} in the selected emulator first')
    device.app_start(package)
    log(f'Started game: {package}')
