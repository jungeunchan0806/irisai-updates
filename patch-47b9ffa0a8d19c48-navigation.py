"""Local obstacle geometry and measured, bounded recovery from blocked movement."""
from collections import deque
import math

import cv2
import numpy as np


def wall_footprints(walls):
    """Project raised tile artwork onto the floor, in 960x540 coordinates."""
    return [(x1, max(y1, y2 - (x2 - x1) * .72), x2, y2)
            for x1, y1, x2, y2 in walls if x2 > x1 and y2 > y1]


def segment_clear(start, end, walls, radius=17):
    for x1, y1, x2, y2 in walls:
        if cv2.clipLine((int(x1-radius), int(y1-radius),
                         max(1, int(x2-x1+2*radius)), max(1, int(y2-y1+2*radius))),
                        tuple(map(int, start)), tuple(map(int, end)))[0]:
            return False
    return True


class CameraMotion:
    """Track static background translation; moving brawlers and HUD are masked."""
    def __init__(self):
        self.gray = None
        self.points = None
        self.at = None

    def update(self, frame, entities, now):
        gray = cv2.cvtColor(cv2.resize(frame, (480, 270)), cv2.COLOR_RGB2GRAY)
        shift = (0., 0.)
        if self.gray is not None and self.points is not None and self.at is not None and now-self.at < .8:
            nxt, status, errors = cv2.calcOpticalFlowPyrLK(
                self.gray, gray, self.points, None, winSize=(15, 15), maxLevel=2,
                criteria=(cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 15, .05))
            if nxt is not None:
                good = (status[:, 0] == 1) & (errors[:, 0] < 20)
                old, new = self.points[good], nxt[good]
                if len(old) >= 10:
                    affine, inliers = cv2.estimateAffinePartial2D(
                        old, new, method=cv2.RANSAC, ransacReprojThreshold=1.5)
                    if affine is not None and inliers.mean() > .6:
                        scale = math.hypot(affine[0, 0], affine[0, 1])
                        if .97 < scale < 1.03 and abs(affine[0, 1]) < .03:
                            shift = (float(affine[0, 2])*2, float(affine[1, 2])*2)
        mask = np.zeros_like(gray)
        mask[35:225, 75:405] = 255
        sx, sy = 480/frame.shape[1], 270/frame.shape[0]
        for x1, y1, x2, y2 in entities:
            cv2.rectangle(mask, (int(x1*sx)-10, int(y1*sy)-10),
                          (int(x2*sx)+10, int(y2*sy)+10), 0, -1)
        self.points = cv2.goodFeaturesToTrack(gray, maxCorners=100, qualityLevel=.03,
                                             minDistance=8, mask=mask)
        self.gray, self.at = gray, now
        return shift


class MovementRecovery:
    """Hold a clear escape direction until actual motion or a bounded timeout.

    The anchor comes from the name/HP area, not the animated feet. Background
    flow accounts for a camera following a player at a fixed screen position.
    """
    def __init__(self):
        self.reset()

    def reset(self):
        self.camera = CameraMotion()
        self.camera_offset = np.zeros(2)
        self.samples = deque()
        self.sample_at = -math.inf
        self.escape = None
        self.escape_at = 0.
        self.escape_origin = None
        self.failed = deque()
        self.recoveries = 0
        self.status = 'moving'
        self._world = None

    def observe(self, frame, anchor, entities, now):
        if now-self.sample_at < .16:
            return
        self.camera_offset += self.camera.update(frame, entities, now)
        self._world = np.asarray(anchor, dtype=float)-self.camera_offset
        self.samples.append((now, self._world.copy()))
        while self.samples and now-self.samples[0][0] > 1.8:
            self.samples.popleft()
        self.sample_at = now

    def displacement(self):
        if len(self.samples) < 4:
            return math.inf
        points = [p for _, p in self.samples]
        return float(np.linalg.norm(np.median(points[-3:], axis=0)-np.median(points[:3], axis=0)))

    @staticmethod
    def clearance(player, direction, walls):
        # Permit a player overlapping an inflated detector rectangle to leave
        # it; never permit entering another occupied region along the ray.
        initial = {i for i, (x1,y1,x2,y2) in enumerate(walls)
                   if x1-16 <= player[0] <= x2+16 and y1-16 <= player[1] <= y2+16}
        remaining = set(initial)
        distance = 0
        for length in range(6, 127, 6):
            x, y = player[0]+direction[0]*length, player[1]+direction[1]*length
            if not (125 < x < 835 and 60 < y < 470):
                break
            hits = {i for i, (x1,y1,x2,y2) in enumerate(walls)
                    if x1-16 <= x <= x2+16 and y1-16 <= y <= y2+16}
            if hits-remaining:
                break
            remaining &= hits
            if not hits:
                distance = length
        return distance

    def choose_escape(self, player, movement, walls, now):
        magnitude = math.hypot(*movement)
        forward = (movement[0]/magnitude, movement[1]/magnitude) if magnitude > .1 else (0., -1.)
        while self.failed and now-self.failed[0][0] > 15:
            self.failed.popleft()
        candidates = []
        for angle in (math.pi/2, -math.pi/2, math.pi, 3*math.pi/4, -3*math.pi/4,
                      math.pi/4, -math.pi/4):
            c,s = math.cos(angle), math.sin(angle)
            direction = (forward[0]*c-forward[1]*s, forward[0]*s+forward[1]*c)
            room = self.clearance(player, direction, walls)
            penalty = sum(140 for _,old in self.failed if np.dot(direction,old) > .8)
            candidates.append((room-penalty-15*max(0,math.cos(angle)), room, direction))
        _,room,direction = max(candidates, key=lambda item:item[0])
        if room < 18:
            # A stale/overlapping detector must not cause a permanent zero.
            # Backing away from the attempted direction is bounded to .9s.
            direction = (-forward[0], -forward[1])
        self.escape, self.escape_at = direction, now
        self.escape_origin = self._world.copy() if self._world is not None else np.asarray(player)
        self.samples.clear()
        self.recoveries += 1
        self.status = 'escaping'
        print(f'Navigation: escape {self.recoveries}, direction=({direction[0]:.2f},{direction[1]:.2f}), clearance={room:.0f}')

    def adjust(self, frame, player, anchor, entities, walls, movement, now):
        self.observe(frame, anchor, entities, now)
        if self.escape is not None:
            moved = float(np.linalg.norm(self._world-self.escape_origin)) if self._world is not None else 0.
            elapsed = now-self.escape_at
            if (moved >= 50 and elapsed >= .3) or elapsed >= 1.5:
                if moved < 18:
                    self.failed.append((now, self.escape))
                    self.choose_escape(player, movement, walls, now)
                else:
                    print(f'Navigation: escape released after {moved:.0f}px of ground progress')
                    self.escape = None
                    self.samples.clear()
                    self.status = 'moving'
            elif elapsed > .3 and self.clearance(player,self.escape,walls) < 12:
                self.failed.append((now,self.escape))
                self.choose_escape(player,movement,walls,now)
        elif (self.samples and now-self.samples[0][0] >= 1.25 and self.displacement() < 8):
            self.choose_escape(player, movement, walls, now)
        if self.escape is not None:
            return (self.escape[0]*75, self.escape[1]*75)
        return movement
