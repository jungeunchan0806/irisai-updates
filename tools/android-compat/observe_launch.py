"""Observe the launcher of an unchanged APK on a fresh, disconnected CI emulator."""
from pathlib import Path
import argparse
import json
import os
import re
import subprocess
import time

from check import GAME, PACKAGES, adb, digest, install, shell, wait_package_service


def has_no_route(*args):
    code, message = adb("shell", "ip", *args)
    return code != 0 and bool(re.search(r"unreachable|no route", message, re.I))


def disconnected():
    connectivity = shell("dumpsys", "connectivity")
    return (
        shell("settings", "get", "global", "airplane_mode_on") == "1"
        and re.search(r"^\s*Active default network:\s*(?:none|null|-1)\s*$",
                      connectivity, re.I | re.M) is not None
        and has_no_route("route", "get", "203.0.113.1")
        and has_no_route("-6", "route", "get", "2001:db8::1")
    )


def isolate():
    virtual = shell("getprop", "ro.boot.qemu") == "1" or shell("getprop", "ro.kernel.qemu") == "1"
    if os.environ.get("GITHUB_ACTIONS") != "true" or not virtual:
        raise RuntimeError("Expected a disposable GitHub Actions emulator")
    shell("cmd", "connectivity", "airplane-mode", "enable")
    shell("svc", "wifi", "disable")
    shell("svc", "data", "disable")
    for _ in range(20):
        if disconnected():
            return
        time.sleep(1)
    raise RuntimeError("Could not verify that both IPv4 and IPv6 routes are unavailable")


def screenshot(path):
    with path.open("wb") as target:
        subprocess.run(["adb", "exec-out", "screencap", "-p"], stdout=target,
                       timeout=20, check=True)
    with path.open("rb") as capture:
        if capture.read(8) != b"\x89PNG\r\n\x1a\n":
            raise RuntimeError("Invalid screenshot")


def exit_reasons(text, seen):
    records = []
    for item in re.split(r"\bApplicationExitInfo\s*#\d+\s*:", text):
        pid = re.search(r"\bpid=(\d+)\b", item)
        reason = re.search(r"\breason=(\d+)(?:\s+\(([A-Z_ /-]+(?:\([A-Z_ /-]+\))?)\))?", item)
        status = re.search(r"\bstatus=(-?\d+)", item)
        if pid and pid[1] in seen and reason:
            records.append(dict(reason=int(reason[1]), label=reason[2],
                                status=int(status[1]) if status else None))
    return records


def standard_crash_signals(seen):
    categories = set()
    for pid in seen:
        code, logs = adb("logcat", "-d", "-b", "main", "-b", "crash", "--pid=" + pid)
        if code:
            continue
        signatures = {
            "Java main-thread exception": r"FATAL EXCEPTION: main",
            "native library load error": r"UnsatisfiedLinkError|dlopen failed",
            "missing application resources": r"Resources\$NotFoundException|Unable to open asset",
            "out of memory": r"OutOfMemoryError",
        }
        for label, pattern in signatures.items():
            if re.search(pattern, logs):
                categories.add(label)
        for signal in re.findall(r"Fatal signal \d+ \((SIG[A-Z0-9]+)\)", logs):
            categories.add(signal)
    return sorted(categories)


def observe(user, component, attempt, out):
    shell("am", "force-stop", "--user", user, GAME)
    if not disconnected():
        raise RuntimeError("Guest acquired a route before launch")
    started = time.monotonic()
    response = shell("am", "start", "--user", user, "-a", "android.intent.action.MAIN",
                     "-c", "android.intent.category.LAUNCHER", "-n", component)
    if "Error" in response:
        raise RuntimeError(response)
    samples = []
    seen = set()
    for seconds in (2, 10, 30, 75):
        time.sleep(max(0, started + seconds - time.monotonic()))
        if not disconnected():
            shell("am", "force-stop", "--user", user, GAME)
            raise RuntimeError("Guest acquired a route during observation")
        code, pid = adb("shell", "pidof", GAME)
        if code == 0:
            seen.update(p for p in pid.split() if p.isdecimal())
        windows = shell("dumpsys", "activity", "activities")
        resumed = [line.strip() for line in windows.splitlines() if
                   re.search(r"\b(?:topResumedActivity|mResumedActivity)\s*[:=]", line, re.I)]
        foreground = any(GAME + "/" in line for line in resumed) if resumed else None
        image = f"launch-{attempt}-{seconds}s.png"
        screenshot(out / image)
        samples.append(dict(elapsed=round(time.monotonic()-started, 1),
                            processRunning=(code == 0 and bool(pid.strip())),
                            gameActivityResumed=foreground, screenshot=image))
    exits = shell("dumpsys", "activity", "exit-info", GAME)
    # Include only standard OS classifications, never app data or an entire crash dump.
    observed = dict(launch=attempt, samples=samples, exitReasons=exit_reasons(exits, seen),
                    androidCrashSignals=standard_crash_signals(seen))
    (out / f"launch-{attempt}.json").write_text(json.dumps(observed, indent=2))
    print(json.dumps(observed), flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("variant", choices=PACKAGES)
    args = parser.parse_args()
    user = wait_package_service()
    isolate()
    out = Path("startup-evidence")
    out.mkdir(exist_ok=True)
    spec = PACKAGES[args.variant]
    apk = Path(os.environ["RUNNER_TEMP"]) / (args.variant + ".apk")
    try:
        subprocess.run(["curl", "-fsSL", "--retry", "3", "--max-time", "400",
                        "-o", str(apk), spec["apkUrl"]], check=True)
        if digest(apk) != spec["apkSha256"]:
            raise RuntimeError("The APK does not match the published checksum")
        result, detail = install(apk, GAME, user)
        if result != "installed":
            raise RuntimeError(f"Install failed: {result} {detail}")
        component = shell("cmd", "package", "resolve-activity", "--brief", "--user", user,
                          "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER",
                          "-p", GAME).splitlines()[-1].strip()
        if not re.fullmatch(re.escape(GAME) + r"/[\w.$]+", component):
            raise RuntimeError("The package launcher could not be resolved")
        environment = dict(variant=args.variant, apkSha256=spec["apkSha256"], component=component,
                           sdk=shell("getprop", "ro.build.version.sdk"),
                           abis=shell("getprop", "ro.product.cpu.abilist"),
                           pageBytes=shell("getconf", "PAGE_SIZE"), offline=disconnected())
        (out / "environment.json").write_text(json.dumps(environment, indent=2))
        print(json.dumps(environment), flush=True)
        try:
            for attempt in (1, 2):
                observe(user, component, attempt, out)
        finally:
            shell("am", "force-stop", "--user", user, GAME)
    finally:
        apk.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
