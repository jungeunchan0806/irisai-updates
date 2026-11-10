import atexit
import math
import random
import threading
import time

import scrcpy
from adbutils import adb, AdbDevice
from debug_view import DebugViewPublisher
from utils import config_bool, load_toml_as_dict, save_dict_as_toml, invalidate_toml_cache

brawl_stars_width, brawl_stars_height = 1920, 1080

press_coords_dict = load_toml_as_dict("cfg/buttons_config.toml")
KNOWN_BS_PACKAGES = ("com.supercell.brawlstars", "bsd.suitcase.release")


def online_devices():
    out = []
    for d in adb.device_list():
        try:
            state = d.get_state() if hasattr(d, "get_state") else d.state
        except Exception:
            state = "device"
        if state == "device":
            out.append(d)
    return out


def adb_device_port_sort_key(device: AdbDevice) -> tuple[float, int, str]:
    """Sort TCP/emulator ADB devices by their effective ADB port."""
    serial = device.serial
    if ":" in serial:
        try:
            return int(serial.rsplit(":", 1)[1]), 0, serial
        except ValueError:
            pass
    if serial.startswith("emulator-"):
        try:
            # Emulator serials contain the console port; ADB uses the next port.
            return int(serial.removeprefix("emulator-")) + 1, 1, serial
        except ValueError:
            pass
    return float("inf"), 2, serial


def discover_device(verbose: bool = False, reconnect_serial=None) -> AdbDevice:
    from pyla_addons.emulators import prepare, start_game, serial_port
    config = load_toml_as_dict("cfg/general_config.toml")
    if reconnect_serial:
        config = dict(config, emulator_port=serial_port(reconnect_serial) or config.get("emulator_port", 0))
    serial = prepare(adb, config)
    start_game(adb, serial, config)
    return adb.device(serial=serial)

class WindowController:
    match_logger = None
    JOYSTICK_REARM_INTERVAL = 3.0
    JOYSTICK_DOWN_SETTLE = .025

    def __init__(self, max_fps="auto"):
        self._move_lock = threading.RLock()
        self.are_we_moving = False
        self._last_joystick_press = 0.0
        self._last_movement_log_time = 0.0
        self.joystick_x, self.joystick_y = 220, 870
        self.scale_factor = None
        self.width = None
        self.height = None
        self.width_ratio = None
        self.height_ratio = None
        self.movement_joystick_x, self.movement_joystick_y = None, None
        self.original_movement_joystick = (None, None)
        self.BRAWL_STARS_PACKAGE = load_toml_as_dict("cfg/general_config.toml")["brawl_stars_package"]
        self.verbose_debug = config_bool(
            load_toml_as_dict("cfg/debug_settings.toml").get("verbose_debug"),
            False
        )
        print("Connecting to ADB (might take up to 2 minutes)...")
        try:
            self.device = discover_device(verbose=self.verbose_debug)
            print(f"Connected to device: {self.device.serial}")

            self.frame_lock = threading.Lock()
            self.max_fps = max_fps
            self.scrcpy_client = scrcpy.Client(device=self.device, max_width=0, bitrate=10000000) if self.max_fps == "auto" else scrcpy.Client(device=self.device, max_width=0, bitrate=10000000, max_fps=self.max_fps)
            self.last_frame = None
            self.last_frame_time = 0.0
            self.last_joystick_pos = (None, None)
            self.FRAME_STALE_TIMEOUT = 2.0
            self.re_apply_movement = config_bool(
                load_toml_as_dict("cfg/debug_settings.toml").get("re_apply_movement"),
                True
            )
            self.debug_view = DebugViewPublisher.from_config()

            def on_frame(frame):
                if frame is not None:
                    with self.frame_lock:
                        self.last_frame = frame
                        self.last_frame_time = time.time()

            self.scrcpy_client.add_listener(scrcpy.EVENT_FRAME, on_frame)
            self.scrcpy_client.start(threaded=True)
            atexit.register(self.close)
            print("Scrcpy client started successfully.")

        except Exception as error:
            raise RuntimeError(f"ADB/scrcpy initialization failed: {error}. "
                               "Check General > Emulator settings or run Diagnose-Windows.bat.") from error
        self.are_we_moving = False
        self.PID_JOYSTICK = 1
        self.PID_ATTACK = 2

    def get_latest_frame(self):
        with self.frame_lock:
            if self.last_frame is None:
                return None, 0.0
            return self.last_frame, self.last_frame_time

    def force_rediscover(self) -> bool:
        print("Reconnecting to the selected emulator.")
        try:
            self.scrcpy_client.stop()
        except Exception:
            pass
        adb.server_start()
        try:
            new_dev = discover_device(self.verbose_debug, reconnect_serial=self.device.serial)
        except (ConnectionError, RuntimeError, OSError, ValueError):
            return False
        self.device = new_dev
        print(f"Re-discovered device: {self.device.serial}")
        return True

    def reconnect_scrcpy(self, max_retries=3):
        for attempt in range(1, max_retries + 1):
            print(f"Scrcpy reconnect attempt {attempt}/{max_retries}")
            try:
                self.scrcpy_client.stop()
            except Exception:
                pass
            time.sleep(1)

            with self.frame_lock:
                self.last_frame = None
                self.last_frame_time = 0.0

            self.are_we_moving = False
            self.last_joystick_pos = (None, None)

            try:
                _ = self.device.get_state()
            except Exception:
                if not self.force_rediscover():
                    print("Device gone and re-discovery failed.")
                    time.sleep(2 * attempt)
                    continue

            def on_frame(frame):
                if frame is not None:
                    with self.frame_lock:
                        self.last_frame = frame
                        self.last_frame_time = time.time()

            try:
                self.scrcpy_client = scrcpy.Client(device=self.device, max_width=0, bitrate=10000000) if self.max_fps == "auto" else scrcpy.Client(device=self.device, max_width=0, bitrate=10000000, max_fps=self.max_fps)
                self.scrcpy_client.add_listener(scrcpy.EVENT_FRAME, on_frame)
                self.scrcpy_client.start(threaded=True)
            except Exception as e:
                print(f"Scrcpy client creation failed: {e}")
                time.sleep(2 * attempt)
                continue

            deadline = time.time() + 8
            while time.time() < deadline:
                _, ft = self.get_latest_frame()
                if ft > 0 and (time.time() - ft) < 2:
                    print(f"Scrcpy feed restored on attempt {attempt}")
                    return True
                time.sleep(0.5)

            print(f"Attempt {attempt} did not restore frame feed")
            time.sleep(2 * attempt)

        print("All scrcpy reconnect attempts exhausted")
        return False

    def restart_brawl_stars(self):
        self.device.app_stop(self.BRAWL_STARS_PACKAGE)
        time.sleep(1)
        self.device.app_start(self.BRAWL_STARS_PACKAGE)
        time.sleep(3)
        print("Brawl stars restarted successfully.")

    def is_brawl_stars_running(self):
        try:
            opened_app = self.device.app_current().package.strip()
            detected_known_package = False
            for package in KNOWN_BS_PACKAGES:
                if opened_app == package:
                    detected_known_package = True
                    break
            if detected_known_package:
                if opened_app != self.BRAWL_STARS_PACKAGE:
                    general_config = load_toml_as_dict("cfg/general_config.toml")
                    general_config["brawl_stars_package"] = opened_app
                    save_dict_as_toml(general_config, "cfg/general_config.toml")
                    self.BRAWL_STARS_PACKAGE = opened_app
                    invalidate_toml_cache("cfg/general_config.toml")
                    print(f"Detected Brawl Stars running under the '{opened_app}' package. Updating configuration to match.")
            return opened_app == self.BRAWL_STARS_PACKAGE.strip()
        except Exception as e:
            print(f"Error checking if Brawl Stars is running: {e}")
            return False

    def screenshot(self):
        frame, frame_time = self.get_latest_frame()

        deadline = time.time() + 15
        while frame is None:
            if time.time() > deadline:
                raise ConnectionError(
                    "No frame received from scrcpy within 15s. "
                    "Check USB/emulator connection."
                )
            print("Waiting for first frame...")
            time.sleep(0.1)
            frame, frame_time = self.get_latest_frame()

        age = time.time() - frame_time
        if frame_time > 0 and age > self.FRAME_STALE_TIMEOUT:
            print(f"WARNING: scrcpy frame is {age:.1f}s stale -- feed may be frozen")

        self._sync_resolution((frame.shape[1], frame.shape[0]))
        return frame

    def reset_to_default_resolution(self):
        print("Resetting window controller dimensions to 1920x1080 and updating scale ratios...")
        self.width = brawl_stars_width
        self.height = brawl_stars_height
        self.width_ratio = self.width / brawl_stars_width
        self.height_ratio = self.height / brawl_stars_height
        movement_joystick = press_coords_dict.get("movement_joystick", [180, 900])
        self.movement_joystick_x, self.movement_joystick_y = movement_joystick[0] * self.width_ratio, movement_joystick[1] * self.height_ratio
        self.original_movement_joystick = (self.movement_joystick_x, self.movement_joystick_y)
        self.scale_factor = min(self.width_ratio, self.height_ratio)

    def touch_down(self, x, y, pointer_id=0):
        return self._send_touch(x, y, scrcpy.ACTION_DOWN, pointer_id)


    def touch_move(self, x, y, pointer_id=0):
        return self._send_touch(x, y, scrcpy.ACTION_MOVE, pointer_id)


    def touch_up(self, x, y, pointer_id=0):
        return self._send_touch(x, y, scrcpy.ACTION_UP, pointer_id)


    def move(self, x, y):
        with self._move_lock:
            self._sync_resolution()
            target_x = self.joystick_x + x
            target_y = self.joystick_y + y
            now = time.monotonic()
            interval = .25 if now < getattr(self, '_round_rearm_until', 0) else self.JOYSTICK_REARM_INTERVAL
            if self.are_we_moving and now - self._last_joystick_press >= interval:
                # Game transitions/respawns can silently discard a held pointer.
                self.release_movement()
            if not self.are_we_moving:
                if not self.touch_down(self.joystick_x, self.joystick_y, pointer_id=self.PID_JOYSTICK):
                    print("Movement input failed: joystick touch-down was not sent.")
                    return False
                # Let the game establish the floating joystick's origin on a
                # separate input frame before dragging it. Back-to-back DOWN
                # and MOVE can be coalesced during the round transition.
                time.sleep(self.JOYSTICK_DOWN_SETTLE)
                if not self.touch_move(target_x, target_y, pointer_id=self.PID_JOYSTICK):
                    self.touch_up(self.joystick_x, self.joystick_y, pointer_id=self.PID_JOYSTICK)
                    print("Movement input failed: joystick move was not sent.")
                    return False
                self.are_we_moving = True
                self._last_joystick_press = time.monotonic()
                self.last_joystick_pos = (target_x, target_y)
                self._log_movement(target_x, target_y, started=True)
                return True
            if not self.re_apply_movement and self.last_joystick_pos == (target_x, target_y):
                self._log_movement(target_x, target_y)
                return True
            if not self.touch_move(target_x, target_y, pointer_id=self.PID_JOYSTICK):
                self.release_movement()
                print("Movement input failed: joystick update was not sent.")
                return False
            self.are_we_moving = True
            self.last_joystick_pos = (target_x, target_y)
            self._log_movement(target_x, target_y)
            return True


    def release_movement(self):
        with self._move_lock:
            if self.are_we_moving:
                self.touch_up(self.joystick_x, self.joystick_y, pointer_id=self.PID_JOYSTICK)
                self.are_we_moving = False
                self.last_joystick_pos = (None, None)


    def click(self, x: int, y: int, delay=0.02, already_include_ratio=True, touch_up=True, touch_down=True):
        with self._move_lock:
            self._sync_resolution()
            if not already_include_ratio:
                x = x * self.width_ratio
                y = y * self.height_ratio
            if touch_down and not self.touch_down(x, y, pointer_id=self.PID_ATTACK):
                return False
            time.sleep(delay)
            if touch_up:
                return self.touch_up(x, y, pointer_id=self.PID_ATTACK)
            return True


    def press(self, key, delay=0.02, touch_up=True, touch_down=True):
        if key not in press_coords_dict:
            return
        x, y = press_coords_dict[key]
        target_x = x * self.width_ratio
        target_y = y * self.height_ratio
        self.click(target_x, target_y, delay, touch_up=touch_up, touch_down=touch_down)

    def type_text(self, text: str) -> bool:
        """Type ASCII text into the currently focused Android input field."""
        text = str(text)
        if not text:
            return True
        if not text.isascii():
            print(f"Cannot type non-ASCII text through ADB: {text!r}")
            return False

        try:
            # Android's `input text` command uses %s to represent a space.
            self.device.shell(["input", "text", text.replace(" ", "%s")], timeout=5)
            return True
        except Exception as exc:
            print(f"Failed to type text through ADB: {exc}")
            return False

    def swipe(self, start_x, start_y, end_x, end_y, duration=0.2):
        dist_x = end_x - start_x
        dist_y = end_y - start_y
        distance = math.sqrt(dist_x ** 2 + dist_y ** 2)

        if distance == 0:
            return

        step_len = 25
        steps = max(int(distance / step_len), 1)
        step_delay = duration / steps

        with self._move_lock:
            if not self.touch_down(int(start_x), int(start_y), pointer_id=self.PID_ATTACK):
                return False
            try:
                for i in range(1, steps + 1):
                    t = i / steps
                    cx = start_x + dist_x * t
                    cy = start_y + dist_y * t
                    time.sleep(step_delay)
                    if not self.touch_move(int(cx), int(cy), pointer_id=self.PID_ATTACK):
                        return False
            finally:
                released = self.touch_up(int(end_x), int(end_y), pointer_id=self.PID_ATTACK)
            return released


    def close(self):
        if self.match_logger is not None:
            self.match_logger.shutdown()
        try:
            self.debug_view.close()
        except Exception as exc:
            print(f"Debug view close failed: {exc}")
        self.stop_scrcpy_with_timeout()

    def stop_scrcpy_with_timeout(self, timeout=2.0):
        def stop_client():
            try:
                self.scrcpy_client.stop()
            except Exception as exc:
                print(f"Scrcpy stop failed: {exc}")

        stop_thread = threading.Thread(target=stop_client, daemon=True, name="scrcpy-stop")
        stop_thread.start()
        stop_thread.join(timeout=timeout)
        if stop_thread.is_alive():
            print("Scrcpy stop is still running in the background; continuing shutdown.")

    def _send_touch(self, x, y, action, pointer_id):
        with self._move_lock:
            try:
                self.scrcpy_client.control.touch(int(x), int(y), action, pointer_id)
                return True
            except Exception as exc:
                print(f"Touch input failed ({action}, pointer {pointer_id}): {exc}")
                # Releasing a dead connection must not create a new connection.
                if action == scrcpy.ACTION_UP:
                    return False
                if not self.reconnect_scrcpy():
                    return False
                try:
                    if action == scrcpy.ACTION_MOVE:
                        # The replacement server has never seen this pointer's DOWN.
                        origin = (self.joystick_x, self.joystick_y) if pointer_id == self.PID_JOYSTICK else (x, y)
                        self.scrcpy_client.control.touch(int(origin[0]), int(origin[1]), scrcpy.ACTION_DOWN, pointer_id)
                    self.scrcpy_client.control.touch(int(x), int(y), action, pointer_id)
                    return True
                except Exception as retry_exc:
                    print(f"Touch input retry failed: {retry_exc}")
                    return False


    def _sync_resolution(self, resolution=None):
        resolution = resolution or getattr(self.scrcpy_client, "resolution", None)
        if not isinstance(resolution, tuple) or len(resolution) != 2:
            return
        width, height = resolution
        if width <= 0 or height <= 0 or (self.width, self.height) == (width, height):
            return
        with self._move_lock:
            self.release_movement()
            self.width = self.screen_width = width
            self.height = self.screen_height = height
            self.width_ratio = width / 1920.0
            self.height_ratio = height / 1080.0
            self.scale_factor = min(self.width_ratio, self.height_ratio)
            self.joystick_x = 220 * self.width_ratio
            self.joystick_y = 870 * self.height_ratio
            self.movement_joystick_x, self.movement_joystick_y = self.joystick_x, self.joystick_y
            self.original_movement_joystick = (self.joystick_x, self.joystick_y)
            print(f"Input coordinates updated to video resolution {width}x{height}")


    def _log_movement(self, target_x, target_y, started=False):
        now = time.time()
        if started or now - self._last_movement_log_time >= 10.0:
            action = "started" if started else "active"
            print(
                f"Movement input {action}: joystick "
                f"({int(self.joystick_x)}, {int(self.joystick_y)}) -> "
                f"({int(target_x)}, {int(target_y)})"
            )
            self._last_movement_log_time = now


    def rearm_movement_for_round(self):
        with self._move_lock:
            self.release_movement()
            self._round_rearm_until = time.monotonic() + 1.5


    def aim_attack(self, dx, dy):
        """Aim and release the attack joystick, preserving the movement finger."""
        length = math.hypot(dx, dy)
        if length < 1:
            return False
        with self._move_lock:
            self._sync_resolution()
            x, y = press_coords_dict['attack']
            x, y = x * self.width_ratio, y * self.height_ratio
            end_x = x + dx / length * 100 * self.width_ratio
            end_y = y + dy / length * 100 * self.height_ratio
            return self.swipe(x, y, end_x, end_y, duration=.08)
