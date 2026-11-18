import math
import random
import time
import cv2
import numpy as np
import os
import threading
from concurrent.futures import ThreadPoolExecutor, TimeoutError

from config_loader import get_config
from detect import Detect
try:
    from early_access.early_access import add_advanced_visuals
    early_access = True
except ImportError:
    early_access = False
    def add_advanced_visuals(a, b):
        return None
from state_finder import get_state
from utils import load_toml_as_dict, count_hsv_pixels, load_brawlers_info, interpret_iris_code, \
    count_mask_pixels, JOYSTICK_RADIUS, clamp, config_bool
from runtime_paths import prune_runtime_files, runtime_path
from runtime_profiler import get_runtime_profiler
from brawl_ball import AutoGoal, find_player_bar, path_waypoint, own_goal_rail, own_goal_exit, small_frame
from navigation import MovementRecovery, wall_footprints
from round_start import RoundStartTracker


brawl_stars_width, brawl_stars_height = 1920, 1080
POISON_LOW_HSV = np.array((30, 90, 221), dtype=np.uint8)
POISON_HIGH_HSV = np.array((57, 114, 235), dtype=np.uint8)
PLAYER_HIT_CIRCLE_RADIUS = 53

class Play:

    def __init__(self, main_info_model, tile_detector_model, close_tile_detector_model, window_controller, iris_code):
        bot_config = load_toml_as_dict("cfg/bot_config.toml")
        time_config = load_toml_as_dict("cfg/time_tresholds.toml")
        self.fix_movement_keys = {
            "delay_to_trigger": get_config("cfg/bot_config.toml", "unstuck_movement_delay", 2.4),
            "duration": get_config("cfg/bot_config.toml", "unstuck_movement_hold_time", 1.4),
            "toggled": False,
            "started_at": time.time(),
            "fixed": (0, 0),
            "last_direction_key": None,
            "rotation_sign": 1,
            "rotation_angle_step": 1,
            "max_rotation_angle_step": 4,
        }
        self.super_treshold = get_config("cfg/time_tresholds.toml", "super", 5)
        self.gadget_treshold = get_config("cfg/time_tresholds.toml", "gadget", 5)
        self.hypercharge_treshold = get_config("cfg/time_tresholds.toml", "hypercharge", 5)
        self.walls_treshold = get_config("cfg/time_tresholds.toml", "wall_detection", 2)
        self.last_walls_data = []
        self.last_bushes_data = []
        self.keys_hold = []
        self.time_since_different_movement = time.time()
        self.time_since_gadget_checked = time.time()
        self.is_gadget_ready = False
        self.time_since_hypercharge_checked = time.time()
        self.is_hypercharge_ready = False
        self.time_since_super_checked = time.time()
        self.is_super_ready = False
        self.window_controller = window_controller
        self.profiler = get_runtime_profiler()
        self.TILE_SIZE = bot_config.get("perceived_tile_size", 54)
        lobby_config = load_toml_as_dict("./cfg/lobby_config.toml")
        self.super_crop_area = lobby_config['pixel_counter_crop_area']['super']
        self.gadget_crop_area = lobby_config['pixel_counter_crop_area']['gadget']
        self.hypercharge_crop_area = lobby_config['pixel_counter_crop_area']['hypercharge']
        self.centered_wall_detection = config_bool(bot_config.get("centered_wall_detection"), False)
        self.centered_wall_crop_size = 640

        debug_config = load_toml_as_dict("cfg/debug_settings.toml")
        self.verbose_debug = config_bool(debug_config.get('verbose_debug'), False)
        self.debug_capture_max_files = max(int(debug_config.get("debug_capture_max_files", 100)), 1)
        self.debug_capture_max_bytes = max(int(float(debug_config.get("debug_capture_max_mb", 500)) * 1024 * 1024), 1)
        self.debug_frames_dir = runtime_path("debug_frames", create_parent=False)
        self.debug_frames_dir.mkdir(parents=True, exist_ok=True)
        prune_runtime_files(
            self.debug_frames_dir,
            patterns=("*.png", "*.jpg", "*.jpeg", "*.mp4"),
            max_files=self.debug_capture_max_files,
            max_bytes=self.debug_capture_max_bytes,
        )
        self.Detect_main_info = Detect(main_info_model, classes=['enemy', 'teammate', 'player'])
        self.tile_detector_model_classes = get_config("cfg/bot_config.toml", "wall_model_classes", ["wall", "bush", "close_bush"])
        self.Detect_tile_detector = None if self.centered_wall_detection else Detect(
            tile_detector_model,
            classes=self.tile_detector_model_classes
        )
        self.Detect_centered_tile_detector = Detect(
            close_tile_detector_model,
            classes=self.tile_detector_model_classes
        ) if self.centered_wall_detection else None

        self.time_since_walls_checked = 0
        self.time_since_player_last_found = time.time()
        self.current_brawler = None
        self.brawlers_info = load_brawlers_info()
        self.brawler_ranges = None
        self.time_since_detections = {
            "player": time.time(),
            "enemy": time.time(),
        }
        self.time_since_last_proceeding = time.time()

        self.last_movement = ''
        self.last_movement_change_time = time.time()
        self.minimum_movement_delay = get_config("cfg/bot_config.toml", "minimum_movement_delay", 0.05)
        self.stuck_position_timeout = get_config("cfg/bot_config.toml", "stuck_position_timeout", 1.5)
        self.stuck_position_tolerance = get_config("cfg/bot_config.toml", "stuck_position_tolerance", 24)
        self._movement_progress_center = None
        self._movement_progress_at = time.time()
        self.no_detection_proceed_delay = get_config("cfg/time_tresholds.toml", "no_detection_proceed", 60)
        self.gadget_pixels_minimum = get_config("cfg/bot_config.toml", "gadget_pixels_minimum", 100)
        self.hypercharge_pixels_minimum = get_config("cfg/bot_config.toml", "hypercharge_pixels_minimum", 100)
        self.super_pixels_minimum = get_config("cfg/bot_config.toml", "super_pixels_minimum", 100)
        self.wall_detection_confidence = get_config("cfg/bot_config.toml", "wall_detection_confidence", 0.55)
        self.entity_detection_confidence = get_config("cfg/bot_config.toml", "entity_detection_confidence", 0.5)
        self.seconds_to_hold_attack_after_reaching_max = get_config("cfg/bot_config.toml", "seconds_to_hold_attack_after_reaching_max", 0.3)
        self.persistent_data = {"time_since_holding_attack": None}
        self.iris_code = iris_code
        self.context = None
        self.frame = None
        self._executor = ThreadPoolExecutor(max_workers=2)
        self._closed = False
        self._last_frame_time = 0.0
        self._last_data_cache = None
        self._last_debug_write_time = 0.0
        self._last_no_player_debug_time = 0.0
        self._cache_lock = threading.RLock()
        self.auto_goal = AutoGoal()
        self.normal_recovery = MovementRecovery()
        self.normal_navigation = {}
        self.round_start = RoundStartTracker()
        self._was_playing = False
        # Compile the CoreML graph before queueing a match, not on the first
        # playable frame. Otherwise cold inference consumes the opening seconds.
        warmup = np.zeros((640, 640, 3), dtype=np.uint8)
        self.get_main_data(warmup)
        self.get_tile_data(warmup)

    def close(self):
        if self._closed:
            return
        self._closed = True
        self.window_controller.release_movement()
        self._executor.shutdown(wait=True, cancel_futures=True)

    def save_debug_frame(self, filename, image):
        path = self.debug_frames_dir / filename
        if not cv2.imwrite(str(path), image):
            print(f"Could not save diagnostic frame: {path}")
            return None
        prune_runtime_files(
            self.debug_frames_dir,
            patterns=("*.png", "*.jpg", "*.jpeg", "*.mp4"),
            max_files=self.debug_capture_max_files,
            max_bytes=self.debug_capture_max_bytes,
        )
        return path

    @staticmethod
    def get_entity_pos(entity):
        return (entity[0] + entity[2]) / 2, (entity[1] + entity[3]) / 2

    @staticmethod
    def get_distance(enemy_coords, player_coords):
        return math.hypot(enemy_coords[0] - player_coords[0], enemy_coords[1] - player_coords[1])

    @staticmethod
    def is_there_enemy(enemy_data):
        if not enemy_data:
            return False
        return True

    @staticmethod
    def is_there_teammate(teammate_data):
        if not teammate_data:
            return False
        return True

    @staticmethod
    def count_enemies_in_area(enemy_data, pos, radius):
        if not enemy_data:
            return 0
        count = 0
        for enemy in enemy_data:
            ex, ey = (enemy[0] + enemy[2]) / 2, (enemy[1] + enemy[3]) / 2
            dist = math.hypot(ex - pos[0], ey - pos[1])
            if dist <= radius:
                count += 1
        return count

    @staticmethod
    def count_teammates_in_area(teammate_data, pos, radius):
        if not teammate_data:
            return 0
        count = 0
        for teammate in teammate_data:
            tx, ty = (teammate[0] + teammate[2]) / 2, (teammate[1] + teammate[3]) / 2
            dist = math.hypot(tx - pos[0], ty - pos[1])
            if dist <= radius:
                count += 1
        return count

    def attack(self, touch_up=True, touch_down=True):
        self.window_controller.press("attack", touch_up=touch_up, touch_down=touch_down)

    def use_hypercharge(self):
        print("Using hypercharge")
        self.window_controller.press("hypercharge")
        self.time_since_hypercharge_checked = time.time()
        self.is_hypercharge_ready = False

    def use_gadget(self):
        print("Using gadget")
        self.window_controller.press("gadget")
        self.time_since_gadget_checked = time.time()
        self.is_gadget_ready = False

    def use_super(self):
        print("Using super")
        self.window_controller.press("super")
        self.time_since_super_checked = time.time()
        self.is_super_ready = False

    @staticmethod
    def get_random_movement():
        random_movement = random.randint(-75, 75), random.randint(-75, 75)
        return random_movement

    @staticmethod
    def movement_to_vector(movement):
        if not isinstance(movement, (tuple, list)) or len(movement) != 2:
            return None

        x, y = movement
        if x is None or y is None:
            return None

        try:
            return float(x), float(y)
        except (TypeError, ValueError):
            return None

    @staticmethod
    def rotate_movement(movement, angle_radians):
        x, y = movement
        cos_angle = math.cos(angle_radians)
        sin_angle = math.sin(angle_radians)
        return (
            x * cos_angle - y * sin_angle,
            x * sin_angle + y * cos_angle,
        )

    @staticmethod
    def movement_direction_key(movement):
        x, y = movement
        magnitude = math.hypot(x, y)
        if magnitude < 1:
            return None

        angle = math.atan2(y, x)
        return round(angle / (math.pi / 8)) % 16

    def choose_safe_movement(self, player_box, movement, walls):
        movement_vector = self.movement_to_vector(movement)
        if movement_vector is None:
            return movement
        movement_vector = self.clamp_movement(movement_vector)

        magnitude = math.hypot(*movement_vector)
        if magnitude < 1 or not walls:
            return movement_vector

        for angle in (0, -math.pi / 4, math.pi / 4, -math.pi / 2, math.pi / 2,
                      -3 * math.pi / 4, 3 * math.pi / 4, math.pi):
            candidate = self.rotate_movement(movement_vector, angle)
            candidate = self.clamp_movement(candidate)
            if not self.is_path_blocked(player_box, candidate, walls):
                return candidate

        return (0.0, 0.0)

    def reset_movement_progress(self, current_time):
        self._movement_progress_center = None
        self._movement_progress_at = current_time
        if hasattr(self, 'normal_recovery'):
            self.normal_recovery.reset()

    def unstuck_movement_if_needed(self, movement, player_box=None, walls=None, current_time=None):
        if current_time is None:
            current_time = time.time()
        walls = walls or []

        movement_vector = self.movement_to_vector(movement)
        if movement_vector is None:
            self.fix_movement_keys["toggled"] = False
            self.fix_movement_keys["last_direction_key"] = None
            self.fix_movement_keys["rotation_sign"] = 1
            self.fix_movement_keys["rotation_angle_step"] = 1
            self.time_since_different_movement = current_time
            self.reset_movement_progress(current_time)
            return movement

        direction_key = self.movement_direction_key(movement_vector)
        if direction_key is None:
            self.fix_movement_keys["toggled"] = False
            self.fix_movement_keys["last_direction_key"] = None
            self.fix_movement_keys["rotation_sign"] = 1
            self.fix_movement_keys["rotation_angle_step"] = 1
            self.time_since_different_movement = current_time
            self.reset_movement_progress(current_time)
            return movement_vector

        if self.fix_movement_keys['toggled']:
            if current_time - self.fix_movement_keys['started_at'] > self.fix_movement_keys['duration']:
                self.fix_movement_keys['toggled'] = False
                self.fix_movement_keys["last_direction_key"] = direction_key
                self.time_since_different_movement = current_time
                return movement_vector

            return self.fix_movement_keys['fixed']

        player_center, _radius = self.get_player_hit_circle(player_box)
        if player_center is not None:
            tolerance = self.stuck_position_tolerance * (self.window_controller.scale_factor or 1)
            if self._movement_progress_center is None:
                self._movement_progress_center = player_center
                self._movement_progress_at = current_time
            elif self.get_distance(player_center, self._movement_progress_center) >= tolerance:
                self._movement_progress_center = player_center
                self._movement_progress_at = current_time
            elif current_time - self._movement_progress_at >= self.stuck_position_timeout:
                self.fix_movement_keys["rotation_sign"] *= -1
                escape_direction = self.rotate_movement(
                    movement_vector,
                    self.fix_movement_keys["rotation_sign"] * math.pi / 2,
                )
                escape_direction = self.choose_safe_movement(player_box, escape_direction, walls)
                self.fix_movement_keys['fixed'] = escape_direction
                self.fix_movement_keys['toggled'] = True
                self.fix_movement_keys['started_at'] = current_time
                self._movement_progress_at = current_time
                return escape_direction

        if self.fix_movement_keys["last_direction_key"] != direction_key:
            self.fix_movement_keys["last_direction_key"] = direction_key
            self.fix_movement_keys["rotation_sign"] = 1
            self.fix_movement_keys["rotation_angle_step"] = 1
            self.time_since_different_movement = current_time

        if current_time - self.time_since_different_movement > self.fix_movement_keys["delay_to_trigger"]:
            self.fix_movement_keys["rotation_sign"] *= -1
            angle_step = self.fix_movement_keys["rotation_angle_step"]
            rotated_movement = self.rotate_movement(
                movement_vector,
                self.fix_movement_keys["rotation_sign"] * angle_step * math.pi / 4
            )
            rotated_movement = self.choose_safe_movement(player_box, rotated_movement, walls)
            if self.fix_movement_keys["rotation_sign"] > 0:
                self.fix_movement_keys["rotation_angle_step"] += 1
                if self.fix_movement_keys["rotation_angle_step"] > self.fix_movement_keys["max_rotation_angle_step"]:
                    self.fix_movement_keys["rotation_angle_step"] = 1

            self.fix_movement_keys['fixed'] = rotated_movement
            self.fix_movement_keys['toggled'] = True
            self.fix_movement_keys['started_at'] = current_time
            return rotated_movement

        return movement_vector

    def load_brawler_ranges(self, brawlers_info=None):
        if not brawlers_info:
            brawlers_info = load_brawlers_info()
        screen_size_ratio = self.window_controller.scale_factor
        ranges = {}
        for brawler, info in brawlers_info.items():
            attack_range = info['attack_range']
            safe_range = info['safe_range']
            super_range = info['super_range']
            v = [safe_range, attack_range, super_range]
            ranges[brawler] = [int(v[0] * screen_size_ratio), int(v[1] * screen_size_ratio), int(v[2] * screen_size_ratio)]
        return ranges

    @staticmethod
    def can_attack_through_walls(brawler, skill_type, brawlers_info=None):
        if not brawlers_info:
            brawlers_info = load_brawlers_info()
        brawler_data = brawlers_info.get(brawler, {})
        if skill_type == "attack":
            return brawler_data.get('ignore_walls_for_attacks', False)
        elif skill_type == "super":
            return brawler_data.get('ignore_walls_for_supers', False)
        raise ValueError("skill_type must be either 'attack' or 'super'")

    @staticmethod
    def must_brawler_hold_attack(brawler, brawlers_info=None):
        if not brawlers_info:
            brawlers_info = load_brawlers_info()
        brawler_data = brawlers_info.get(brawler, {})
        return brawler_data.get('hold_attack', 0) > 0

    @staticmethod
    def walls_block_line_of_sight(p1, p2, walls):
        if not walls:
            return False

        p1_t = (int(p1[0]), int(p1[1]))
        p2_t = (int(p2[0]), int(p2[1]))
        min_x, max_x = min(p1_t[0], p2_t[0]), max(p1_t[0], p2_t[0])
        min_y, max_y = min(p1_t[1], p2_t[1]), max(p1_t[1], p2_t[1])
        for wall in walls:
            x1, y1, x2, y2 = wall

            if max_x < x1 or min_x > x2 or max_y < y1 or min_y > y2:
                continue

            rect = (int(x1), int(y1), int(x2 - x1), int(y2 - y1))
            if cv2.clipLine(rect, p1_t, p2_t)[0]:
                return True
        return False

    def get_player_hit_circle(self, player_box):
        radius = PLAYER_HIT_CIRCLE_RADIUS * (self.window_controller.scale_factor or 1)
        if player_box and len(player_box) >= 4:
            x1, y1, x2, y2 = player_box[:4]
            return ((x1 + x2) / 2, y2 - radius), radius

        return None, radius

    def get_actual_player_box(self, player_box):
        center, radius = self.get_player_hit_circle(player_box)
        if center is None:
            return None
        return [
            center[0] - radius,
            center[1] - radius,
            center[0] + radius,
            center[1] + radius,
        ]

    @staticmethod
    def point_rect_distance_sq(point, rect):
        x, y = point
        x1, y1, x2, y2 = rect
        dx = max(x1 - x, 0, x - x2)
        dy = max(y1 - y, 0, y - y2)
        return dx * dx + dy * dy

    @staticmethod
    def walls_block_swept_circle(p1, p2, radius, walls):
        if not walls:
            return False

        p1_t = (int(p1[0]), int(p1[1]))
        p2_t = (int(p2[0]), int(p2[1]))
        min_x, max_x = min(p1_t[0], p2_t[0]), max(p1_t[0], p2_t[0])
        min_y, max_y = min(p1_t[1], p2_t[1]), max(p1_t[1], p2_t[1])
        radius = int(math.ceil(radius))

        for wall in walls:
            x1, y1, x2, y2 = wall[:4]
            wall_rect = (x1, y1, x2, y2)
            expanded_x1 = int(x1 - radius)
            expanded_y1 = int(y1 - radius)
            expanded_x2 = int(x2 + radius)
            expanded_y2 = int(y2 + radius)

            if max_x < expanded_x1 or min_x > expanded_x2 or max_y < expanded_y1 or min_y > expanded_y2:
                continue

            rect = (
                expanded_x1,
                expanded_y1,
                max(1, expanded_x2 - expanded_x1),
                max(1, expanded_y2 - expanded_y1),
            )
            if cv2.clipLine(rect, p1_t, p2_t)[0]:
                radius_sq = radius * radius
                start_distance_sq = Play.point_rect_distance_sq(p1, wall_rect)
                end_distance_sq = Play.point_rect_distance_sq(p2, wall_rect)
                if start_distance_sq <= radius_sq and end_distance_sq > start_distance_sq:
                    continue
                return True

        return False

    def is_enemy_hittable(self, player_pos, enemy_pos, walls, skill_type):
        if self.can_attack_through_walls(self.current_brawler, skill_type, self.brawlers_info):
            return True
        if self.walls_block_line_of_sight(player_pos, enemy_pos, walls):
            return False
        return True

    def find_closest_enemy(self, enemy_data, player_coords, walls, skill_type):
        player_pos_x, player_pos_y = player_coords
        closest_hittable_distance = float('inf')
        closest_unhittable_distance = float('inf')
        closest_hittable = None
        closest_unhittable = None
        for enemy in enemy_data:
            enemy_pos = self.get_entity_pos(enemy)
            distance = self.get_distance(enemy_pos, player_coords)
            if self.is_enemy_hittable((player_pos_x, player_pos_y), enemy_pos, walls, skill_type):
                if distance < closest_hittable_distance:
                    closest_hittable_distance = distance
                    closest_hittable = [enemy_pos, distance]
            else:
                if distance < closest_unhittable_distance:
                    closest_unhittable_distance = distance
                    closest_unhittable = [enemy_pos, distance]
        if closest_hittable:
            return closest_hittable
        elif closest_unhittable:
            return closest_unhittable

        return None, None

    def find_closest_teammate(self, teammate_data, player_coords, walls):
        closest_distance = float('inf')
        closest_teammate = None
        for teammate in teammate_data:
            teammate_pos = self.get_entity_pos(teammate)
            distance = self.get_distance(teammate_pos, player_coords)
            if distance < closest_distance:
                closest_distance = distance
                closest_teammate = teammate_pos
        return closest_teammate, closest_distance

    def is_there_poison_gas(self, player_data, threshold=7000, area_from_player_checked=1.5):
        actual_player_box = self.get_actual_player_box(player_data) or player_data
        px1, py1, px2, py2 = actual_player_box
        player_width = max(px2 - px1, 1)
        player_height = max(py2 - py1, 1)
        min_x = int(max(px1 - player_width*area_from_player_checked, 0))
        max_x = int(min(px2 + player_width*area_from_player_checked, self.window_controller.width))
        min_y = int(max(py1 - player_height*area_from_player_checked, 0))
        max_y = int(min(py2 + player_height*area_from_player_checked, self.window_controller.height))

        if min_x >= max_x or min_y >= max_y:
            return {
                "up": 0,
                "down": 0,
                "left": 0,
                "right": 0,
            }

        roi = self.frame[min_y:max_y, min_x:max_x]
        hsv_roi = cv2.cvtColor(roi, cv2.COLOR_RGB2HSV)

        mask = cv2.inRange(hsv_roi, POISON_LOW_HSV, POISON_HIGH_HSV)
        x, y = self.get_entity_pos(actual_player_box)
        roi_w = int(max_x - min_x)
        roi_h = int(max_y - min_y)
        local_px = int(clamp(x - min_x, 0, roi_w))
        local_py = int(clamp(y - min_y, 0, roi_h))

        counts = {
            "up": count_mask_pixels(mask, 0, 0, roi_w, local_py),
            "down": count_mask_pixels(mask, 0, local_py, roi_w, roi_h),
            "left": count_mask_pixels(mask, 0, 0, local_px, roi_h),
            "right": count_mask_pixels(mask, local_px, 0, roi_w, roi_h),
        }

        result = {
            direction: count if count > threshold else 0
            for direction, count in counts.items()
        }

        if self.verbose_debug:
            print("Poison gas pixels:", counts)

            if time.time() - self._last_debug_write_time >= 2.0:
                self._last_debug_write_time = time.time()

                ts = int(time.time())

                debug_regions = {
                    "up": roi[0:local_py, 0:roi_w],
                    "down": roi[local_py:roi_h, 0:roi_w],
                    "left": roi[0:roi_h, 0:local_px],
                    "right": roi[0:roi_h, local_px:roi_w],
                }

                for direction, img in debug_regions.items():
                    if img.size > 0:
                        self.save_debug_frame(
                            f"poison_gas_{direction}_debug_{ts}.png",
                            cv2.cvtColor(img, cv2.COLOR_RGB2BGR),
                        )

        return result

    def get_main_data(self, frame):
        with self.profiler.measure("entity_inference"):
            return self.Detect_main_info.detect_objects(
                frame,
                conf_tresh=self.entity_detection_confidence,
            )

    def is_path_blocked(self, player_box, move_direction, walls, distance=None):
        if distance is None:
            distance = self.TILE_SIZE*self.window_controller.scale_factor
        movement = self.movement_to_vector(move_direction)
        if movement is None:
            return False

        magnitude = math.hypot(movement[0], movement[1])
        if magnitude < 1:
            return False

        dx = movement[0] / magnitude * distance
        dy = movement[1] / magnitude * distance
        hit_circle_center, hit_circle_radius = self.get_player_hit_circle(player_box)
        if hit_circle_center is None:
            return False

        new_pos = (hit_circle_center[0] + dx, hit_circle_center[1] + dy)
        return self.walls_block_swept_circle(hit_circle_center, new_pos, hit_circle_radius, walls)

    @staticmethod
    def validate_game_data(data):
        incomplete = False
        if "player" not in data.keys() or not data["player"]:
            incomplete = True

        if "enemy" not in data.keys():
            data['enemy'] = []

        if "teammate" not in data.keys():
            data['teammate'] = []

        if 'wall' not in data.keys() or not data['wall']:
            data['wall'] = []

        if 'bush' not in data.keys() or not data['bush']:
            data['bush'] = []

        return None if incomplete else data

    def track_no_detections(self, data):
        if not data:
            data = {
                "enemy": None,
                "player": None
            }
        for key in self.time_since_detections:
            if key in data and data[key]:
                self.time_since_detections[key] = time.time()

    def do_movement(self, movement):
        movement_vector = self.movement_to_vector(movement)
        if movement_vector is None or math.hypot(*movement_vector) < 1:
            self.window_controller.release_movement()
            return
        self.window_controller.move(*movement_vector)

    def get_brawler_range(self, brawler):
        if self.brawler_ranges is None:
            self.brawler_ranges = self.load_brawler_ranges(self.brawlers_info)
        return self.brawler_ranges.get(brawler, [0, 0, 0])

    def clamp_movement(self, movement):
        x, y = movement
        target_x = clamp(x, -JOYSTICK_RADIUS*self.window_controller.width_ratio, JOYSTICK_RADIUS*self.window_controller.width_ratio)
        target_y = clamp(y, -JOYSTICK_RADIUS*self.window_controller.height_ratio, JOYSTICK_RADIUS*self.window_controller.height_ratio)
        return target_x, target_y

    def loop(self, brawler, data, current_time):
        self.context = {
                'player_data': data['player'][0],
                'enemy_data': data['enemy'],
                'teammate_data': data['teammate'],
                'brawler': brawler,
                'walls': data['wall'],
                'bushes': data['bush'],
                'brawlers_info': self.brawlers_info,
                'must_brawler_hold_attack': self.must_brawler_hold_attack,
                'is_gadget_ready': self.is_gadget_ready,
                'is_hypercharge_ready': self.is_hypercharge_ready,
                'is_super_ready': self.is_super_ready,
                'TILE_SIZE': self.TILE_SIZE*self.window_controller.scale_factor,
                'get_entity_pos': self.get_entity_pos,
                'get_distance': self.get_distance,
                'get_actual_player_box': self.get_actual_player_box,
                'get_brawler_range': self.get_brawler_range,
                'is_there_enemy': self.is_there_enemy,
                'attack': self.attack,
                'use_hypercharge': self.use_hypercharge,
                'use_super': self.use_super,
                'use_gadget': self.use_gadget,
                'get_random_movement': self.get_random_movement,
                'current_brawler': self.current_brawler,
                'last_movement': self.last_movement,
                'last_movement_change_time': self.last_movement_change_time,
                'seconds_to_hold_attack_after_reaching_max': self.seconds_to_hold_attack_after_reaching_max,
                "width": brawl_stars_width,
                "height": brawl_stars_height,
                'find_closest_enemy': self.find_closest_enemy,
                'find_closest_teammate': self.find_closest_teammate,
                'is_there_poison_gas': self.is_there_poison_gas,
                'is_path_blocked': self.is_path_blocked,
                'is_enemy_hittable': self.is_enemy_hittable,
                'time': time,
                'random': random,
                "persistent_data": self.persistent_data,
                'debug': self.verbose_debug,
                'JOYSTICK_RADIUS': JOYSTICK_RADIUS,
                'teammates_data': data['teammate'],
                'is_there_teammate': self.is_there_teammate,
                'count_enemies_in_area': self.count_enemies_in_area,
                'count_teammates_in_area': self.count_teammates_in_area,
                'center': (960, 540),
                'rotate_movement': self.rotate_movement
            }
        movement = self.get_movement()
        if self.movement_to_vector(movement) is None:
            self.window_controller.release_movement()
            self.last_movement = ''
            return None
        movement = self.clamp_movement(movement)
        current_time = time.time()
        if movement != self.last_movement:
            if current_time - self.last_movement_change_time >= self.minimum_movement_delay:
                self.last_movement = movement
                self.last_movement_change_time = current_time
            else:
                movement = self.last_movement
        else:
            self.last_movement_change_time = current_time
        movement = self.navigate_playstyle_movement(data, movement, current_time)
        return self.clamp_movement(movement)

    def navigate_playstyle_movement(self, data, movement, now):
        if math.hypot(*movement) < 1:
            self.normal_recovery.reset()
            return movement
        width, height = self.frame.shape[1], self.frame.shape[0]
        sx,sy = 960/width,540/height
        box = data['player'][0]
        visual = find_player_bar(self.frame, hint=box)
        anchor_box = visual if visual is not None else box
        player = ((anchor_box[0]+anchor_box[2])/2*sx,anchor_box[3]*sy-26.5)
        anchor = ((anchor_box[0]+anchor_box[2])/2*sx,anchor_box[1]*sy)
        walls = wall_footprints([(b[0]*sx,b[1]*sy,b[2]*sx,b[3]*sy) for b in data['wall']])
        dx,dy = movement[0]/self.window_controller.width_ratio,movement[1]/self.window_controller.height_ratio
        length = math.hypot(dx,dy)
        target = (max(130,min(830,player[0]+dx/length*120)),max(65,min(465,player[1]+dy/length*120)))
        # Respawning behind the goal wall must route through its opening even
        # when ordinary combat is chasing an enemy on the other side.
        rail = own_goal_rail(self.frame)
        if rail is not None and self.auto_goal.vision.is_mode(
                cv2.cvtColor(small_frame(self.frame),cv2.COLOR_RGB2GRAY)):
            w,x,y,h = rail
            walls += [(0,y+5,x+16,540),(x+w-16,y+5,960,540)]
            target = own_goal_exit(self.frame,player) or target
        waypoint = path_waypoint(player,target,walls)
        dx,dy = waypoint[0]-player[0],waypoint[1]-player[1]
        length = math.hypot(dx,dy)
        command = (dx/length*75,dy/length*75) if length > 1 else (0.,0.)
        entities = data['player']+data['teammate']+data['enemy']
        command = self.normal_recovery.adjust(self.frame,player,anchor,entities,walls,command,now)
        self.normal_navigation = {'player':player,'target':target,'waypoint':waypoint,
                                  'recovery':self.normal_recovery.status,
                                  'recoveries':self.normal_recovery.recoveries,
                                  'progress':self.normal_recovery.displacement()}
        return (command[0]*self.window_controller.width_ratio,command[1]*self.window_controller.height_ratio)

    def check_if_hypercharge_ready(self, frame):
        wr, hr = self.window_controller.width_ratio, self.window_controller.height_ratio
        x1, y1 = int(self.hypercharge_crop_area[0] * wr), int(self.hypercharge_crop_area[1] * hr)
        x2, y2 = int(self.hypercharge_crop_area[2] * wr), int(self.hypercharge_crop_area[3] * hr)
        screenshot = frame[y1:y2, x1:x2]
        purple_pixels = count_hsv_pixels(screenshot, (137, 158, 159), (179, 255, 255))
        if self.verbose_debug:
            print("hypercharge purple pixels:", purple_pixels, "(if > ", self.hypercharge_pixels_minimum, " then hypercharge is ready)")
            if time.time() - self._last_debug_write_time >= 2.0:
                self._last_debug_write_time = time.time()
                self.save_debug_frame(
                    f"hypercharge_debug_{purple_pixels}_{int(time.time())}.png",
                    cv2.cvtColor(screenshot, cv2.COLOR_RGB2BGR),
                )

        if purple_pixels > self.hypercharge_pixels_minimum:
            return True
        return False

    def check_if_gadget_ready(self, frame):
        wr, hr = self.window_controller.width_ratio, self.window_controller.height_ratio
        x1, y1 = int(self.gadget_crop_area[0] * wr), int(self.gadget_crop_area[1] * hr)
        x2, y2 = int(self.gadget_crop_area[2] * wr), int(self.gadget_crop_area[3] * hr)
        screenshot = frame[y1:y2, x1:x2]
        green_pixels = count_hsv_pixels(screenshot, (57, 219, 165), (62, 255, 255))
        if self.verbose_debug:
            print("gadget green pixels:", green_pixels, "(if > ", self.gadget_pixels_minimum, " then gadget is ready)")
            if time.time() - self._last_debug_write_time >= 2.0:
                self._last_debug_write_time = time.time()
                self.save_debug_frame(
                    f"gadget_debug_{green_pixels}_{int(time.time())}.png",
                    cv2.cvtColor(screenshot, cv2.COLOR_RGB2BGR),
                )

        if green_pixels > self.gadget_pixels_minimum:
            return True
        return False

    def check_if_super_ready(self, frame):
        wr, hr = self.window_controller.width_ratio, self.window_controller.height_ratio
        x1, y1 = int(self.super_crop_area[0] * wr), int(self.super_crop_area[1] * hr)
        x2, y2 = int(self.super_crop_area[2] * wr), int(self.super_crop_area[3] * hr)
        screenshot = frame[y1:y2, x1:x2]
        yellow_pixels = count_hsv_pixels(screenshot, (17, 170, 200), (27, 255, 255))
        if self.verbose_debug:
            print("super yellow pixels:", yellow_pixels, "(if > ", self.super_pixels_minimum, " then super is ready)")
            if time.time() - self._last_debug_write_time >= 2.0:
                self._last_debug_write_time = time.time()
                self.save_debug_frame(
                    f"super_debug_{yellow_pixels}_{int(time.time())}.png",
                    cv2.cvtColor(screenshot, cv2.COLOR_RGB2BGR),
                )

        if yellow_pixels > self.super_pixels_minimum:
            return True
        return False

    def get_centered_wall_crop(self, frame, player_data=None):
        frame_height, frame_width = frame.shape[:2]
        crop_size = self.centered_wall_crop_size

        if not player_data:
            return frame, 0, 0
        if player_data:
            center_x, center_y = self.get_entity_pos(player_data[0])
        else:
            center_x, center_y = frame_width / 2, frame_height / 2

        crop_x1 = int(clamp(round(center_x - crop_size / 2), 0, frame_width - crop_size))
        crop_y1 = int(clamp(round(center_y - crop_size / 2), 0, frame_height - crop_size))
        crop_x2 = crop_x1 + crop_size
        crop_y2 = crop_y1 + crop_size

        return frame[crop_y1:crop_y2, crop_x1:crop_x2], crop_x1, crop_y1

    @staticmethod
    def offset_tile_data(tile_data, offset_x, offset_y):
        if not offset_x and not offset_y:
            return tile_data

        offset_data = {}
        for class_name, boxes in tile_data.items():
            offset_data[class_name] = [
                [box[0] + offset_x, box[1] + offset_y, box[2] + offset_x, box[3] + offset_y]
                for box in boxes
            ]
        return offset_data

    def get_tile_data(self, frame, player_data=None):
        with self.profiler.measure("tile_inference"):
            if self.centered_wall_detection and self.Detect_centered_tile_detector is not None:
                crop, offset_x, offset_y = self.get_centered_wall_crop(frame, player_data)
                tile_data = self.Detect_centered_tile_detector.detect_objects(
                    crop,
                    conf_tresh=self.wall_detection_confidence
                )
                return self.offset_tile_data(tile_data, offset_x, offset_y)

            return self.Detect_tile_detector.detect_objects(
                frame,
                conf_tresh=self.wall_detection_confidence,
            )

    def process_tile_data(self, tile_data):
        walls = []
        bushes = []
        for class_name, boxes in tile_data.items():
            if 'bush' not in class_name:
                walls.extend(boxes)
            else:
                bushes.extend(boxes)
        return walls, bushes

    def get_movement(self):
        with self.profiler.measure("playstyle_decision"):
            movement, _updated_globals = interpret_iris_code(self.iris_code, self.context)
            return movement

    def publish_debug_view(self, frame, data, state, movement=None):
        if not hasattr(self.window_controller, "debug_view"):
            return

        self.frame = frame
        advanced_visuals = bool(getattr(self.window_controller.debug_view, "advanced_visuals", False))
        debug_data = {
            "state": state,
            "player": [],
            "enemy": [],
            "teammate": [],
            "wall": [],
            "attack_range": 0,
            "super_range": 0,
            "poison_gas": {},
            "movement": None,
            "joystick": [self.window_controller.joystick_x, self.window_controller.joystick_y],
            "advanced_visuals": advanced_visuals,
            "joystick_radius": int(JOYSTICK_RADIUS * (self.window_controller.scale_factor or 1)),
            "joystick_directions": [],
            "enemy_los_lines": [],
            "teammate_los_lines": [],
            "player_hit_circle": None,
        }

        if data:
            for key in ["player", "enemy", "teammate", "wall"]:
                debug_data[key] = [[int(v) for v in box[:4]] for box in (data.get(key) or []) if len(box) >= 4]
            try:
                _, attack_range, super_range = self.get_brawler_range(self.current_brawler)
                debug_data["attack_range"] = int(attack_range)
                debug_data["super_range"] = int(super_range)
            except Exception:
                pass
            if debug_data["player"]:
                try:
                    debug_data["poison_gas"] = self.is_there_poison_gas(debug_data["player"][0])
                except Exception:
                    pass
                if advanced_visuals and early_access:
                    add_advanced_visuals(self, debug_data)

        if movement is not None:
            debug_data["movement"] = [float(movement[0]), float(movement[1])]

        self.window_controller.debug_view.publish(frame, debug_data)

    def main(self, frame, brawler, main, frame_time=0.0):
        if self._closed:
            self.window_controller.release_movement()
            return
        current_time = time.time()
        state = main.get_latest_state()
        # A state-checker result can belong to the previous intro frame. The
        # player's health bar + name provide a positive live-game signal.
        visual_player = None
        if state in {"match", "match_intro", "loading", "match_making"}:
            visual_player = find_player_bar(frame)
        if state in {"match_intro", "loading", "match_making"} and visual_player:
            state = "match"
            main.set_latest_state(state)
        if state != "match":
            self.window_controller.release_movement()
            self._was_playing = False
            self.auto_goal.reset()
            self.round_start.leave_match()
            main.telemetry.update_navigation({'phase':state,'at':current_time,'player_detected':False})
            self.publish_debug_view(frame, None, state)
            return

        first_playable_frame = not self._was_playing and visual_player is not None
        if first_playable_frame:
            self.last_movement = ''
            self.last_movement_change_time = 0
            self.fix_movement_keys['toggled'] = False
            self.time_since_different_movement = current_time
            self.reset_movement_progress(current_time)
            self.window_controller.release_movement()

        wall_due = current_time - self.time_since_walls_checked > self.walls_treshold

        if wall_due and not self.centered_wall_detection:
            main_future = self._executor.submit(self.get_main_data, frame)
            tile_future = self._executor.submit(self.get_tile_data, frame, None)
            try:
                data = main_future.result(timeout=5.0)
            except TimeoutError:
                print("Main model inference timed out")
                data = {}
            try:
                tile_data = tile_future.result(timeout=5.0)
            except TimeoutError:
                print("Tile model inference timed out")
                tile_data = None
        else:
            data = self.get_main_data(frame)
            if wall_due:
                tile_data = self.get_tile_data(frame, data.get("player"))
            else:
                tile_data = None

        if wall_due and tile_data is not None:
            walls, bushes = self.process_tile_data(tile_data)
            self.time_since_walls_checked = current_time
            self.last_walls_data = walls
            data['wall'] = walls
            self.last_bushes_data = bushes
            data['bush'] = bushes
        else:
            data['wall'] = self.last_walls_data
            data['bush'] = self.last_bushes_data

        if not data.get('player') and visual_player:
            data['player'] = [visual_player]
        data = self.validate_game_data(data)
        self.track_no_detections(data)
        if data:
            self.time_since_player_last_found = time.time()

        if not data:
            main.telemetry.update_navigation({'phase':'waiting_for_player','at':current_time,'player_detected':False})
            missing_player_for = current_time - self.time_since_player_last_found
            # A single obscured frame is not a death/respawn. Preserve the
            # short possession and wall-progress history through particles.
            if missing_player_for > .5:
                self.auto_goal.reset(keep_mode=True)
            capture_due = missing_player_for > 1.0 and current_time - self._last_no_player_debug_time >= 30.0
            proceed_due = current_time - self.time_since_last_proceeding > self.no_detection_proceed_delay
            detected_state = get_state(frame) if capture_due or proceed_due else None

            if detected_state and detected_state != "match":
                self.window_controller.release_movement()
                main.handle_detected_state(detected_state)
                self.time_since_last_proceeding = current_time
                self.publish_debug_view(frame, data, detected_state)
                return

            if missing_player_for > 1.0:
                self.window_controller.release_movement()
                self.reset_movement_progress(current_time)
                if capture_due:
                    frame_path = self.save_debug_frame(
                        f"no-player-{int(current_time)}.png",
                        cv2.cvtColor(frame, cv2.COLOR_RGB2BGR),
                    )
                    self._last_no_player_debug_time = current_time
                    if frame_path:
                        print(f"No player detected; diagnostic frame saved: {frame_path}")
            if proceed_due:
                print("haven't detected the player in a while proceeding")
                self.window_controller.press("proceed")
                self.time_since_last_proceeding = time.time()
            self.publish_debug_view(frame, data, state)
            return
        rail = own_goal_rail(frame)
        foot_y = data['player'][0][3]*540/frame.shape[0]-26.5
        kickoff_context = rail is not None and 0 < rail[2]-foot_y < 120
        reason = self.round_start.update(data['player'][0], data['teammate'], frame.shape[1], frame.shape[0], current_time,
                                         kickoff_context=kickoff_context)
        if reason:
            self.window_controller.rearm_movement_for_round()
            self.auto_goal.reset(keep_mode=True)
            self.last_movement = ''
            self.last_movement_change_time = 0
            self.fix_movement_keys['toggled'] = False
            self.fix_movement_keys['last_direction_key'] = None
            self.time_since_different_movement = current_time
            self.reset_movement_progress(current_time)
            print(f'Round movement reset: {reason}; fresh joystick input without waiting for damage')
        self.time_since_last_proceeding = time.time()
        with self._cache_lock:
            self._last_data_cache = data
        if current_time - self.time_since_hypercharge_checked > self.hypercharge_treshold:
            self.is_hypercharge_ready = self.check_if_hypercharge_ready(frame)
            self.time_since_hypercharge_checked = current_time
        if current_time - self.time_since_gadget_checked > self.gadget_treshold:
            self.is_gadget_ready = self.check_if_gadget_ready(frame)
            self.time_since_gadget_checked = current_time
        if current_time - self.time_since_super_checked > self.super_treshold:
            self.is_super_ready = self.check_if_super_ready(frame)
            self.time_since_super_checked = current_time
        self.frame = frame
        auto_goal_enabled = config_bool(get_config('cfg/bot_config.toml', 'auto_goal', False), False)
        if auto_goal_enabled:
            _, attack_range, _ = self.get_brawler_range(brawler)
            movement = self.auto_goal.decide(frame, data, self.window_controller, current_time,
                                             attack_range=attack_range)
            phase = self.auto_goal.phase
            if phase != getattr(self, '_auto_goal_phase', None):
                if phase in {'carry', 'shoot'}:
                    main.telemetry.emit('auto_goal', f'Auto goal: {phase}.')
                    if current_time - getattr(self, '_auto_goal_capture_at', 0) > 3:
                        self.save_debug_frame(f'auto-goal-{phase}-{int(current_time)}.png', cv2.cvtColor(frame, cv2.COLOR_RGB2BGR))
                        self._auto_goal_capture_at = current_time
                self._auto_goal_phase = phase
        else:
            self.auto_goal.reset()
            movement = None
        if movement is None:
            movement = self.loop(brawler, data, current_time)
        navigation = self.auto_goal.navigation if auto_goal_enabled and self.auto_goal.phase != 'waiting_for_brawl_ball' else getattr(self,'normal_navigation',{})
        navigation = {key:([float(x) for x in value] if isinstance(value,(list,tuple)) else value)
                      for key,value in navigation.items()}
        if not math.isfinite(navigation.get('progress',0)):
            navigation['progress'] = None
        main.telemetry.update_navigation({**navigation,'phase':self.auto_goal.phase if auto_goal_enabled else 'playstyle',
                                          'at':current_time,'player_detected':True,
                                          'command':[float(x) for x in movement] if movement is not None else None})
        self.publish_debug_view(frame, data, state, movement)
        if movement is not None:
            if frame_time:
                self.profiler.record_seconds("frame_to_action", max(0.0, time.time() - frame_time))
            self.do_movement(movement)
            if first_playable_frame:
                print(f"First playable frame to movement: {time.time() - current_time:.3f}s")
            self._was_playing = True
