"""Transactional source-install patches with hashes, backups and local-edit protection."""
import argparse
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import tempfile
import time
from urllib.parse import urlsplit
from urllib.request import Request, urlopen

MAX_FILE = 8 * 1024 * 1024
MAX_TOTAL = 32 * 1024 * 1024
STATE = ".pyla-update-state.json"
DEFAULT_FEED = "https://raw.githubusercontent.com/jungeunchan0806/irisai-updates/windows-updates/windows-manifest.json"
ALLOWED_ROOT_FILES = {"main.py", "play.py", "window_controller.py", "README.md",
                      "Start-Pyla.bat", "Update-Pyla.bat", "Learn-Videos.bat",
                      "requirements-video.txt", "requirements-training.txt", "PATCH_GUIDE_KO.md",
                      "Start-Windows.bat", "Setup-Windows.bat", "Update-Windows.bat",
                      "Diagnose-Windows.bat", "WINDOWS_GUIDE_KO.md", "requirements.txt",
                      "brawl_ball.py", "navigation.py", "round_start.py", "state_finder.py", "IRIS_LICENSE.txt"}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def safe_target(root, name):
    p = PurePosixPath(name)
    if (not name or "\\" in name or ":" in name or p.is_absolute() or
            any(part in ("..", ".") for part in p.parts) or str(p) != name):
        raise ValueError(f"Unsafe patch path: {name}")
    gameplay_asset = name.startswith("images/brawl_ball/") or name in {
        "images/states/game_highlight.png", "images/states/match_intro_vs.png", "images/states/spectating_following.png"}
    iris_style = len(p.parts) == 2 and p.parts[0] == "playstyles" and p.name.startswith("iris_") and p.suffix == ".pyla"
    if name not in ALLOWED_ROOT_FILES and p.parts[0] not in ("pyla_addons", "static", "webui") and not gameplay_asset and not iris_style:
        raise ValueError(f"Patches cannot modify user data or this path: {name}")
    if p.suffix not in (".py", ".js", ".css", ".html", ".md", ".bat", ".txt", ".json", ".ps1", ".png", ".pyla"):
        raise ValueError(f"Unsupported patch file: {name}")
    root = Path(root).resolve()
    target = root.joinpath(*p.parts)
    if not target.resolve().is_relative_to(root) or any(x.is_symlink() for x in (target, *target.parents)):
        raise ValueError(f"Patch path follows a symlink: {name}")
    return target


@contextmanager
def app_lock(root):
    """OS lock is released even after a crash; no stale PID lock to delete."""
    path = Path(root) / ".pyla-runtime.lock"
    with path.open("a+b") as handle:
        handle.seek(0)
        if not handle.read(1):
            handle.write(b"0")
            handle.flush()
        handle.seek(0)
        try:
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as exc:
            raise RuntimeError("PylaAI or another updater is running. Close it before updating.") from exc
        try:
            yield
        finally:
            handle.seek(0)
            if os.name == "nt":
                msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(handle, fcntl.LOCK_UN)


def atomic_write(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=".pyla-", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as handle:
            handle.write(content)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)


def recover(root):
    """Restore an interrupted transaction before launching any application code."""
    root = Path(root)
    journal = root / ".pyla-update-journal.json"
    if not journal.exists():
        return False
    entry = json.loads(journal.read_text(encoding="utf-8"))
    backup = (root / entry["backup"]).resolve()
    if not backup.is_relative_to((root / ".pyla-backups").resolve()):
        raise ValueError("Invalid recovery backup path")
    for item in entry["files"]:
        target = safe_target(root, item["path"])
        if item["existed"]:
            atomic_write(target, (backup / item["path"]).read_bytes())
        else:
            target.unlink(missing_ok=True)
    old_state = backup / "state.json"
    if old_state.exists():
        atomic_write(root / STATE, old_state.read_bytes())
    else:
        (root / STATE).unlink(missing_ok=True)
    journal.unlink()
    return True


def version_key(value):
    if not isinstance(value, str) or not re.fullmatch(r"\d+\.\d+\.\d+", value):
        raise ValueError("Patch version must be major.minor.patch")
    return tuple(int(x) for x in value.split("."))


def validate_manifest(manifest):
    if manifest.get("schema") != 1 or not isinstance(manifest.get("version"), str) or not manifest["version"]:
        raise ValueError("Unsupported update manifest")
    version_key(manifest["version"])
    if manifest.get("platform") != "windows" or manifest.get("product") != "pyla-windows":
        raise ValueError("This feed is not a Pyla Windows source patch")
    files = manifest.get("files")
    if not isinstance(files, list) or not 0 < len(files) <= 150:
        raise ValueError("Invalid update file list")
    names = set()
    total = 0
    for item in files:
        name = item["path"]
        if name.casefold() in names:
            raise ValueError("Duplicate update path")
        names.add(name.casefold())
        if not re.fullmatch(r"[0-9a-f]{64}", item["sha256"]):
            raise ValueError("Invalid SHA-256")
        if not isinstance(item["size"], int) or not 0 <= item["size"] <= MAX_FILE:
            raise ValueError("Patch file exceeds size limit")
        total += item["size"]
    if total > MAX_TOTAL:
        raise ValueError("Patch exceeds total size limit")


def install(root, manifest, payloads):
    """Caller must hold app_lock. payloads maps paths to already downloaded bytes."""
    root = Path(root).resolve()
    validate_manifest(manifest)
    recover(root)
    state_path = root / STATE
    state = json.loads(state_path.read_text()) if state_path.exists() else {"files": {}}
    if state.get("version") and version_key(manifest["version"]) < version_key(state["version"]):
        raise RuntimeError("The update feed is older than the installed Windows patch")
    changes = []
    for item in manifest["files"]:
        name = item["path"]
        target = safe_target(root, name)
        content = payloads[name]
        if len(content) != item["size"] or digest(content) != item["sha256"]:
            raise ValueError(f"Downloaded patch failed hash verification: {name}")
        existing = target.read_bytes() if target.exists() else None
        actual = digest(existing) if existing is not None else None
        if actual == item["sha256"]:
            continue
        expected = state.get("files", {}).get(name, item.get("base_sha256"))
        # Git for Windows often checks out LF source as CRLF; that is not a user edit.
        normalized = digest(existing.replace(b"\r\n", b"\n")) if existing is not None else None
        if actual != expected and normalized != expected:
            raise RuntimeError(f"Local changes or incompatible PylaAI version: {name}. Nothing was overwritten.")
        changes.append((item, target, content))
    if not changes:
        hashes = dict(state.get("files", {}))
        hashes.update({i["path"]: i["sha256"] for i in manifest["files"]})
        atomic_write(state_path, json.dumps({"version": manifest["version"], "files": hashes}, indent=2).encode())
        return False
    backup = root / ".pyla-backups" / str(time.time_ns())
    backup.mkdir(parents=True)
    if state_path.exists():
        shutil.copy2(state_path, backup / "state.json")
    journal_files = []
    for item, target, _ in changes:
        exists = target.exists()
        if exists:
            dest = backup / item["path"]
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(target, dest)
        journal_files.append({"path": item["path"], "existed": exists})
    journal = root / ".pyla-update-journal.json"
    atomic_write(journal, json.dumps({"backup": str(backup.relative_to(root)), "files": journal_files}).encode())
    try:
        for _, target, content in changes:
            atomic_write(target, content)
        hashes = dict(state.get("files", {}))
        hashes.update({i["path"]: i["sha256"] for i in manifest["files"]})
        atomic_write(state_path, json.dumps({"version": manifest["version"], "files": hashes}, indent=2).encode())
        journal.unlink()
    except BaseException:
        recover(root)
        raise
    return True


def fetch(url, limit):
    parsed = urlsplit(url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError("Update URLs must use HTTPS without embedded credentials")
    with urlopen(Request(url, headers={"User-Agent": "PylaAI-Updater/1"}), timeout=15) as response:
        if urlsplit(response.url).scheme != "https":
            raise ValueError("Update redirect downgraded HTTPS")
        data = response.read(limit + 1)
    if len(data) > limit:
        raise ValueError("Update download exceeds size limit")
    return data


def check_and_update(root, manifest_url):
    """Manifest URL is explicitly configured by the installation owner."""
    manifest = json.loads(fetch(manifest_url, 256 * 1024))
    validate_manifest(manifest)
    payloads = {}
    origin = urlsplit(manifest_url).hostname
    for item in manifest["files"]:
        target = safe_target(root, item["path"])
        if urlsplit(item["url"]).hostname != origin:
            raise ValueError("Patch files must come from the configured update host")
        existing = target.read_bytes() if target.is_file() else None
        payloads[item["path"]] = (existing if existing is not None and digest(existing) == item["sha256"]
                                  else fetch(item["url"], item["size"]))
    return install(root, manifest, payloads)


def main():
    parser = argparse.ArgumentParser(description="Apply verified Pyla source updates")
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--manifest", help="Trusted HTTPS manifest URL; saved after a successful check")
    args = parser.parse_args()
    settings = args.root / "update-settings.json"
    config = json.loads(settings.read_text()) if settings.exists() else {}
    url = args.manifest or config.get("manifest_url", DEFAULT_FEED)
    if not url:
        parser.error("No published update feed is configured. See WINDOWS_GUIDE_KO.md.")
    with app_lock(args.root):
        recover(args.root)
        changed = check_and_update(args.root, url)
        if args.manifest:
            atomic_write(settings, json.dumps({"manifest_url": url, "auto_update": True}, indent=2).encode())
    print("Update installed. Restart PylaAI." if changed else "Already up to date.")


if __name__ == "__main__":
    main()
