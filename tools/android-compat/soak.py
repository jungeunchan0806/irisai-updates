"""Own the disposable emulator and recover a lost device during a bounded CI soak."""
from pathlib import Path
import json
import os
import shutil
import subprocess
import sys
import time


def capture(args):
    try:
        result = subprocess.run(args, capture_output=True, text=True, timeout=8)
        return (result.stdout + result.stderr).strip()
    except (OSError, subprocess.TimeoutExpired) as error:
        return str(error)


def main():
    deadline = int(os.environ["DEADLINE"])
    api = os.environ["EXPECTED_API"]
    emulator = str(Path(os.environ["ANDROID_HOME"]) / "emulator/emulator")
    os.environ["ANDROID_SERIAL"] = "emulator-5554"
    avd = "irisqa" + api
    user_home = Path(os.environ.get("RUNNER_TEMP", "/tmp")) / ("iris-soak-" + api)
    avd_home = user_home / "avd"
    avd_home.mkdir(parents=True, exist_ok=True)
    os.environ.update(ANDROID_USER_HOME=str(user_home), ANDROID_EMULATOR_HOME=str(user_home),
                      ANDROID_AVD_HOME=str(avd_home))
    avd_path = avd_home / (avd + ".avd")
    image = "system-images;android-" + api + ";google_apis;x86_64"
    out = Path("compat-evidence")
    out.mkdir(exist_ok=True)
    subprocess.run(["avdmanager", "create", "avd", "--force", "--name", avd,
                    "--path", str(avd_path), "--package", image, "--device", "pixel_2"], input="no\n",
                   text=True, check=True)
    if not (avd_path / "config.ini").is_file():
        raise RuntimeError("avdmanager did not create the requested device at " + str(avd_path))
    # Older command-line tools may put the registry entry in a different user directory.
    # The emulator and the device data always use this job's private registry.
    entry = avd_home / (avd + ".ini")
    if not entry.exists():
        entry.write_text(f"avd.ini.encoding=UTF-8\npath={avd_path}\ntarget=android-{api}\n")
    if avd not in capture([emulator, "-list-avds"]).splitlines():
        raise RuntimeError("Emulator cannot resolve the generated AVD registry entry")
    subprocess.run(["adb", "start-server"], check=True)
    attempts = 0
    empty_attempts = 0
    completed = 0
    while time.time() < deadline:
        attempts += 1
        session = out / ("session-%03d" % attempts)
        session.mkdir()
        with (session / "emulator.log").open("w") as log:
            device = subprocess.Popen([emulator, "-avd", avd, "-port", "5554",
                "-no-window", "-no-snapshot", "-wipe-data", "-no-boot-anim",
                "-no-audio", "-gpu", "swiftshader_indirect", "-memory", "2560",
                "-cores", "2", "-partition-size", "3072"], stdout=log, stderr=log)
            try:
                booted = False
                boot_deadline = min(deadline, time.time() + 240)
                while device.poll() is None and time.time() < boot_deadline:
                    if capture(["adb", "shell", "getprop", "sys.boot_completed"]) == "1":
                        booted = True
                        break
                    time.sleep(3)
                status = None
                if booted and time.time() < deadline:
                    env = dict(os.environ, COMPAT_OUT=str(session))
                    status = subprocess.run([sys.executable, str(Path(__file__).with_name("check.py")),
                        "--skip-apks", "--soak-until", str(deadline)], env=env).returncode
                state = capture(["adb", "get-state"])
                package = capture(["adb", "shell", "service", "check", "package"])
                transport_lost = device.poll() is not None or state != "device" or "package: found" not in package
                results = session / "results.jsonl"
                count = 0
                if results.exists():
                    count = sum(json.loads(line).get("type") == "panel-update-cycle"
                                for line in results.read_text().splitlines())
                completed += count
                report = dict(session=attempts, api=api, booted=booted, suiteExit=status,
                    emulatorExit=device.poll(), adbState=state[:150], package=package[:160],
                    cycleVariants=count, hostFreeMiB=shutil.disk_usage(".").free // (1024*1024))
                with (out / "sessions.jsonl").open("a") as summaries:
                    print(json.dumps(report), file=summaries)
                print(json.dumps(report), flush=True)
                if status not in (None, 0) and not transport_lost:
                    raise RuntimeError("Test failed on a connected emulator; see " + str(session))
                if status == 0:
                    break
                empty_attempts = 0 if count else empty_attempts + 1
                if empty_attempts >= 3:
                    raise RuntimeError("Three consecutive emulator sessions produced no complete scenario")
            finally:
                capture(["adb", "emu", "kill"])
                try:
                    device.wait(timeout=18)
                except subprocess.TimeoutExpired:
                    device.kill()
                    device.wait()
    if not completed:
        raise RuntimeError("No complete UI/update scenario")


if __name__ == "__main__":
    main()
