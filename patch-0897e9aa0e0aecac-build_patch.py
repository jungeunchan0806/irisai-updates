"""Build the standalone installer and GitHub Release update manifest."""
import argparse
import base64
import json
from pathlib import Path
from urllib.parse import quote, urlsplit
import zipfile
import zlib
from .updater import digest

ROOT = Path(__file__).resolve().parents[1]
FILES = ["main.py", "play.py", "window_controller.py", "state_finder.py", "brawl_ball.py",
         "navigation.py", "round_start.py", "webui/services.py", "static/js/app.js",
         "README.md", "WINDOWS_GUIDE_KO.md", "IRIS_LICENSE.txt", "Start-Windows.bat", "Setup-Windows.bat",
         "Update-Windows.bat", "Diagnose-Windows.bat", "requirements.txt"]
FILES += [p.relative_to(ROOT).as_posix() for p in sorted((ROOT / "images/brawl_ball").glob("*.png"))]
FILES += ["images/states/" + name for name in ("game_highlight.png", "match_intro_vs.png", "spectating_following.png")]
FILES += [p.relative_to(ROOT).as_posix() for p in sorted((ROOT / "playstyles").glob("iris_*.pyla"))]


INSTALLER = '''#!/usr/bin/env python3
"""PylaAI source patch. Embedded payload is SHA-256 checked before installation."""
import argparse
import base64
import json
from pathlib import Path
import sys
import zlib

PAYLOAD = __PAYLOAD__

def main():
    if sys.version_info < (3, 11):
        raise RuntimeError("Python 3.11 or later is required")
    parser = argparse.ArgumentParser(description="Install the PylaAI source patch")
    parser.add_argument("--target", type=Path)
    args = parser.parse_args()
    target = args.target
    if target is None:
        if (Path(__file__).resolve().parent / "main.py").exists():
            target = Path(__file__).resolve().parent
        else:
            import tkinter
            from tkinter import filedialog
            window = tkinter.Tk()
            window.withdraw()
            folder = filedialog.askdirectory(title="Select PylaAI source folder containing main.py")
            window.destroy()
            if not folder:
                print("Cancelled; no files changed.")
                return
            target = Path(folder)
    target = target.expanduser().resolve()
    if not (target / "main.py").is_file() or not (target / "cfg").is_dir():
        raise RuntimeError("Choose an existing source installation (main.py and cfg). Compiled EXE installs are unsupported.")
    bundle = json.loads(zlib.decompress(base64.b64decode(PAYLOAD)))
    data = {k: base64.b64decode(v) for k, v in bundle["payloads"].items()}
    namespace = {"__name__": "embedded_pyla_updater"}
    exec(compile(data["pyla_addons/updater.py"], "embedded_pyla_updater", "exec"), namespace)
    with namespace["app_lock"](target):
        changed = namespace["install"](target, bundle["manifest"], data)
    print("Patch installed. Run Start-Windows.bat." if changed else "Patch already installed.")
    print("Iris gameplay and Windows connection patch installed. Settings and queues were preserved.")
    print("Read WINDOWS_GUIDE_KO.md in your PylaAI folder.")

if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print("Patch failed:", error, file=sys.stderr)
        raise SystemExit(1)
'''


def build(output, version, release_url=None):
    if release_url and urlsplit(release_url).scheme != "https":
        raise ValueError("Release URL must be HTTPS")
    files = FILES + [str(p.relative_to(ROOT)).replace("\\", "/") for p in sorted((ROOT / "pyla_addons").glob("*.py"))]
    files += [p.relative_to(ROOT).as_posix() for p in sorted((ROOT / "pyla_addons").glob("*.ps1"))]
    files += ["pyla_addons/base_hashes.json", "pyla_addons/iris_source_provenance.json"]
    bases = json.loads((ROOT / "pyla_addons" / "base_hashes.json").read_text())
    payloads = {name: (ROOT / name).read_bytes() for name in files}
    manifest = {"schema": 1, "platform": "windows", "product": "pyla-windows", "version": version, "files": []}
    output.mkdir(parents=True, exist_ok=True)
    for name, data in payloads.items():
        item = {"path": name, "size": len(data), "sha256": digest(data), "base_sha256": bases.get(name)}
        asset = "patch-" + digest(data)[:16] + "-" + Path(name).name
        if release_url:
            item["url"] = release_url.rstrip("/") + "/" + quote(asset)
            (output / asset).write_bytes(data)
        manifest["files"].append(item)
    bundle = {"manifest": manifest, "payloads": {k: base64.b64encode(v).decode() for k, v in payloads.items()}}
    encoded = base64.b64encode(zlib.compress(json.dumps(bundle).encode(), 9)).decode()
    installer = output / "Apply-Windows-Patch.py"
    installer.write_text(INSTALLER.replace("__PAYLOAD__", repr(encoded)), encoding="utf-8")
    (output / "windows-manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    batch = '@echo off\nsetlocal\ncd /d "%~dp0"\npy -3.11 Apply-Windows-Patch.py %*\nif errorlevel 1 echo Install Python 3.11 x64 if the Python launcher was not found.\npause\n'
    (output / "Apply-Windows-Patch.bat").write_bytes(batch.replace("\n", "\r\n").encode())
    with zipfile.ZipFile(output / "PylaAI-Windows-AutoPatch.zip", "w", zipfile.ZIP_DEFLATED) as archive:
        for path in (installer, output / "Apply-Windows-Patch.bat", ROOT / "WINDOWS_GUIDE_KO.md", ROOT / "LICENSE"):
            archive.write(path, path.name)
    print(f"Built {len(payloads)} verified files: {output / 'PylaAI-Windows-AutoPatch.zip'}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "patch-release")
    parser.add_argument("--version", default="0.1.0")
    parser.add_argument("--release-url")
    args = parser.parse_args()
    build(args.output, args.version, args.release_url)


if __name__ == "__main__":
    main()
