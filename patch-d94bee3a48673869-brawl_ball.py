"""Screen-only Brawl Ball perception and goal-directed movement.

Coordinates inside this module use a 960 x 540 image. No game memory or
network protocol is used. A pair of football score icons gates every action.
"""
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
import heapq
import math
import time

import cv2
import numpy as np
from navigation import MovementRecovery, segment_clear, wall_footprints


def small_frame(frame):
    return cv2.resize(frame, (960, 540), interpolation=cv2.INTER_AREA)


def find_player_bar(frame, hint=None):
    """Recover a missed player from its green health bar AND green name.

    The two independent marks exclude grass, healing particles and teammates.
    Returns the same name-to-feet box convention as the entity model.
    """
    small = small_frame(frame)
    hsv = cv2.cvtColor(small, cv2.COLOR_RGB2HSV)
    mask = cv2.inRange(hsv, (36, 150, 170), (72, 255, 255))
    mask[:55] = 0
    mask[420:] = 0
    mask[:, :85] = 0
    mask[:, 875:] = 0
    count, labels, stats, _ = cv2.connectedComponentsWithStats(mask)
    candidates = []
    for x, y, w, h, area in stats[1:]:
        if not (13 <= w <= 70 and 3 <= h <= 11 and w >= h * 2.5 and area >= w * h * .3):
            continue
        if hint is not None:
            hx = (hint[0]+hint[2])/2 * 960/frame.shape[1]
            hy = hint[1] * 540/frame.shape[0]
            if abs(x+w/2-hx) > 45 or not 8 < y-hy < 65:
                continue
        # The player's own name is bright green just above the white HP text.
        name = mask[max(55, y - 36):max(55, y - 12), max(0, x - 45):min(960, x + w + 45)]
        if np.count_nonzero(name) < 15:
            continue
        # Spawn shields make the HP fill almost white; the green outline is
        # sparse. Require actual white number-shaped glyphs above it instead
        # of mistaking green costume decorations for a solid health bar.
        text_hsv = hsv[max(0,y-18):max(0,y-2),max(0,x-12):min(960,x+w+12)]
        white = cv2.inRange(text_hsv,(0,0,185),(179,75,255))
        _,_,digits,_ = cv2.connectedComponentsWithStats(white)
        glyphs = [d for d in digits[1:] if 5 <= d[3] <= 11 and d[2] <= 14 and d[4] >= 8]
        if len(glyphs) < 2:
            continue
        candidates.append((area, x + w / 2, y))
    if not candidates:
        return None
    _, x, y = max(candidates)
    wr, hr = frame.shape[1] / 960, frame.shape[0] / 540
    return [(x - 48) * wr, (y - 30) * hr, (x + 48) * wr, (y + 100) * hr]


@dataclass
class BallObservation:
    active: bool = False
    ball: tuple | None = None
    goal: tuple | None = None
    score: float = 0.0
    guide: tuple | None = None
    ring_visible: bool = False


class BallVision:
    def __init__(self, assets="images/brawl_ball"):
        assets = Path(assets)
        icon = cv2.imread(str(assets / "score_icon.png"), cv2.IMREAD_GRAYSCALE)
        if icon is None:
            raise FileNotFoundError(f"Brawl Ball score icon missing: {assets}")
        self.icons = [cv2.resize(icon, (size, size)) for size in (34, 40, 46, 50)]
        self.templates = []
        marker = cv2.imread(str(assets / "ball_marker.png"), cv2.IMREAD_GRAYSCALE)
        marker = cv2.resize(marker, (14, 14))
        self.markers = [cv2.warpAffine(marker, cv2.getRotationMatrix2D((6.5, 6.5), angle, 1),
                                      (14, 14), borderMode=cv2.BORDER_REFLECT)
                        for angle in range(0, 360, 45)]
        for path in sorted(assets.glob("ball_[0-9]*.png")):
            raw = cv2.imread(str(path), cv2.IMREAD_GRAYSCALE)
            for size in (17, 19):
                base = cv2.resize(raw, (size, size))
                for angle in range(0, 360, 45):
                    self.templates.append(cv2.warpAffine(
                        base, cv2.getRotationMatrix2D(((size - 1) / 2, (size - 1) / 2), angle, 1),
                        (size, size), borderMode=cv2.BORDER_REFLECT,
                    ))
        self._last_at = -math.inf
        self._last = BallObservation()
        self._mode_seen = -math.inf

    def reset(self, keep_mode=False):
        self._last_at = -math.inf
        self._last = BallObservation()
        if not keep_mode:
            self._mode_seen = -math.inf

    def is_mode(self, gray):
        # Both teams' HUD icons must be present, not just a football in artwork.
        scores = []
        for crop in (gray[0:65, 0:110], gray[0:65, 850:960]):
            scores.append(max(cv2.minMaxLoc(cv2.matchTemplate(crop, icon, cv2.TM_CCOEFF_NORMED))[1]
                              for icon in self.icons))
        return min(scores) >= .68

    @staticmethod
    def find_goal(small):
        hsv = cv2.cvtColor(small, cv2.COLOR_RGB2HSV)
        red = cv2.inRange(hsv, (0, 130, 100), (9, 255, 255)) | cv2.inRange(hsv, (174, 130, 100), (179, 255, 255))
        red[:65] = 0
        red[350:] = 0
        red[:, :240] = 0
        red[:, 720:] = 0
        # Reflections and combat particles can split the thin goal rail.
        red = cv2.morphologyEx(red,cv2.MORPH_CLOSE,np.ones((1,21),np.uint8))
        horizontal = cv2.morphologyEx(red, cv2.MORPH_OPEN, np.ones((2, 80), np.uint8))
        count, labels, stats, _ = cv2.connectedComponentsWithStats(horizontal)
        bars = [(w, x, y, h) for x, y, w, h, area in stats[1:]
                if w > 240 and h <= 28 and x < 480 < x + w]
        if not bars:
            return None
        w, x, y, h = max(bars)
        return (x + w / 2, y + h / 2)

    @staticmethod
    def is_ball_texture(small, center):
        x, y = map(int, center)
        patch = small[y-16:y+16, x-16:x+16]
        if patch.shape[:2] != (32,32):
            return False
        gray = cv2.cvtColor(patch, cv2.COLOR_RGB2GRAY)
        hsv = cv2.cvtColor(patch, cv2.COLOR_RGB2HSV)
        yy, xx = np.ogrid[:32,:32]
        mask = (xx-15.5)**2 + (yy-15.5)**2 < 14**2
        # A round hat, barrel or goal decoration can resemble the grayscale
        # template. Require the football's alternating pale and dark patches.
        return (np.mean(hsv[:,:,1][mask] < 85) > .45
                and np.mean(gray[mask] < 90) > .12
                and np.mean(gray[mask] > 125) > .2
                and np.std(gray[mask]) > 35)

    @staticmethod
    def has_ball_ring(small, center):
        """White arcs locate a partly lit ball; they do not prove ownership."""
        x,y = map(int,center)
        patch = small[max(0,y-36):y+37,max(0,x-36):x+37]
        if patch.shape[:2] != (73,73):
            return False
        hsv = cv2.cvtColor(patch,cv2.COLOR_RGB2HSV)
        yy,xx = np.ogrid[-36:37,-36:37]
        ring = (xx*xx+yy*yy > 21**2) & (xx*xx+yy*yy < 35**2) & (abs(yy)<24)
        white = (hsv[:,:,1]<65) & (hsv[:,:,2]>210) & ring
        return np.count_nonzero(white & (xx < -17)) >= 8 and np.count_nonzero(white & (xx > 17)) >= 8

    def observe(self, frame, now, player=None, owned_recently=False):
        if now - self._last_at < .15:
            return self._last
        self._last_at = now
        small = small_frame(frame)
        gray = cv2.cvtColor(small, cv2.COLOR_RGB2GRAY)
        mode_visible = self.is_mode(gray)
        if mode_visible:
            self._mode_seen = now
        # The mode cannot change during a match. Combat overlays can cover
        # the HUD for longer than a timeout; only a full between-match reset
        # should revoke a mode established by both football score icons.
        if not mode_visible and self._mode_seen == -math.inf:
            self._last = BallObservation()
            return self._last
        # Exclude the HUD and virtual buttons. The middle of the top edge stays
        # visible so the kickoff ball can be found as soon as the camera settles.
        tiny = cv2.resize(gray, (480, 270))
        field = tiny[33:220, 40:440]
        best_score = 0.
        def candidates(region,left,top,peaks=1):
            nonlocal best_score
            result=[]
            for template in self.templates:
                if min(region.shape,default=0) < max(template.shape):
                    continue
                scores=cv2.matchTemplate(region,template,cv2.TM_CCOEFF_NORMED)
                for _ in range(peaks):
                    _,score,_,xy = cv2.minMaxLoc(scores)
                    best_score=max(best_score,score)
                    if score < .35:
                        break
                    result.append((score,((xy[0]+left+template.shape[1]/2)*2,
                                           (xy[1]+top+template.shape[0]/2)*2)))
                    # A brighter costume can outrank a partially covered
                    # football in the same template's local correlation map.
                    cv2.circle(scores,xy,4,-1,thickness=-1)
            return result

        def select(candidates,near_only=False):
            # Reject artwork before ranking it above the real football.
            checked=set()
            for score,point in sorted(candidates,reverse=True):
                close=player is not None and math.dist(player,point)<65
                if score < (.35 if near_only and owned_recently else .44) or (near_only and not close) or point in checked:
                    continue
                # Nearby peaks can have very different arc alignment. Only
                # deduplicate the same center, not a whole eight-pixel disk.
                checked.add(point)
                if not self.is_ball_texture(small,point):
                    continue
                ring=self.has_ball_ring(small,point)
                if score >= .52 or (close and ring) or (close and owned_recently and score >= .46):
                    return point,ring,score
            return None

        local=None
        if player is not None:
            left,top=max(40,int(player[0]/2)-36),max(33,int(player[1]/2)-32)
            right,bottom=min(440,int(player[0]/2)+36),min(220,int(player[1]/2)+40)
            local=tiny[top:bottom,left:right]
        # A carried ball moves with the player. Start with a small crop so
        # continuous carrying does not require 64 whole-field correlations.
        selected=select(candidates(local,left,top,peaks=3),near_only=True) if owned_recently and local is not None else None
        if selected is None:
            selected=select(candidates(field,40,33))
        if selected is None and local is not None and not owned_recently:
            selected=select(candidates(local,left,top),near_only=True)
        marker_score, guide = 0., None
        for marker in self.markers:
            _, score, _, xy = cv2.minMaxLoc(cv2.matchTemplate(tiny, marker, cv2.TM_CCOEFF_NORMED))
            if score > max(.78, marker_score):
                marker_score, guide = score, ((xy[0] + 7) * 2, (xy[1] + 7) * 2)
        ball,held,accepted_score = selected or (None,False,best_score)
        self._last = BallObservation(True, ball,
                                     self.find_goal(small), accepted_score, guide, held)
        return self._last


@lru_cache(maxsize=1)
def _result_assets():
    root=Path('images/brawl_ball')
    banner=cv2.imread(str(root/'match_over.png'),cv2.IMREAD_GRAYSCALE)
    digits=[cv2.imread(str(root/f'score_{i}.png'),cv2.IMREAD_GRAYSCALE) for i in range(3)]
    if banner is None or any(d is None for d in digits):
        raise FileNotFoundError('Brawl Ball result templates are missing')
    icon=cv2.imread(str(root/'score_icon.png'))
    white=cv2.inRange(cv2.cvtColor(icon,cv2.COLOR_BGR2HSV),(0,0,190),(179,85,255))
    icons=[cv2.resize(white,(s,s)) for s in (28,30,32,34,36,40,44,46,50)]
    return icons,[cv2.resize(banner,None,fx=s,fy=s) for s in (.95,1.,1.05)],digits


def find_brawl_ball_result(frame):
    """Read a completed match even when Android skips the later results page."""
    small=small_frame(frame)
    gray=cv2.cvtColor(small,cv2.COLOR_RGB2GRAY)
    icons,banners,digits=_result_assets()
    region=gray[80:330,220:740]
    finished=False
    for b in banners:
        if cv2.minMaxLoc(cv2.matchTemplate(region,b,cv2.TM_CCOEFF_NORMED))[1]>=.8:
            finished=True
            break
        # The game's network warning covers the middle of "Match over".
        # Require both remaining text fragments at their original spacing.
        w=b.shape[1];left=round(w*.357);right=round(w*.643)
        width=region.shape[1]-w+1
        a=cv2.matchTemplate(region,b[:,:left],cv2.TM_CCOEFF_NORMED)[:,:width]
        z=cv2.matchTemplate(region,b[:,right:],cv2.TM_CCOEFF_NORMED)[:,right:right+width]
        if np.max(np.minimum(a,z))>=.82:
            finished=True
            break
    if not finished:
        return None
    white=cv2.inRange(cv2.cvtColor(small,cv2.COLOR_RGB2HSV),(0,0,190),(179,85,255))
    scores=[max(cv2.minMaxLoc(cv2.matchTemplate(c,i,cv2.TM_CCOEFF_NORMED))[1]
                for i in icons) for c in (white[:65,:110],white[:65,850:960])]
    # Result animations shrink/fade one icon. The finished banner and two
    # readable digits are also mandatory, with at least one clear icon.
    if min(scores)<.6 or max(scores)<.75:
        return None

    def read_score(left,right):
        hsv=cv2.cvtColor(small[8:55,left:right],cv2.COLOR_RGB2HSV)
        mask=cv2.inRange(hsv,(0,0,190),(179,75,255))
        _,labels,stats,_=cv2.connectedComponentsWithStats(mask)
        minimum,maximum=(85,125) if left<200 else (835,880)
        found=[(x,y,w,h,i) for i,(x,y,w,h,a) in enumerate(stats)
               if i and 6<=w<=35 and 15<=h<=40 and a>=45
               and minimum<=left+x+w/2<=maximum]
        if len(found)!=1:
            return None
        x,y,w,h,i=found[0]
        glyph=cv2.resize(((labels[y:y+h,x:x+w]==i)*255).astype(np.uint8),
                         (24,32),interpolation=cv2.INTER_NEAREST)>0
        scores=sorted((np.count_nonzero(glyph & (d>0))/max(1,np.count_nonzero(glyph | (d>0))),n)
                      for n,d in enumerate(digits))
        if scores[-1][0]<.72 or scores[-1][0]-scores[-2][0]<.1:
            return None
        return scores[-1][1]

    own,opponent=read_score(75,135),read_score(815,890)
    if own is None or opponent is None:
        return None
    return 'victory' if own>opponent else 'defeat' if own<opponent else 'draw'


def path_waypoint(player, target, walls, radius=17):
    """A* around inflated walls, with no diagonal corner cutting."""
    cell = 16
    width, height = 60, 34
    blocked = np.zeros((height, width), dtype=bool)
    blocked[:, :7] = True
    blocked[:, 53:] = True
    for x1, y1, x2, y2 in walls:
        left, right = max(7, int((x1 - radius) // cell)), min(53, int((x2 + radius) // cell) + 1)
        top, bottom = max(0, int((y1 - radius) // cell)), min(height, int((y2 + radius) // cell) + 1)
        blocked[top:bottom, left:right] = True
    def grid(point):
        return (max(7, min(52, int(point[0] // cell))), max(0, min(height - 1, int(point[1] // cell))))
    start, end = grid(player), grid(target)
    if blocked[start[1], start[0]]:
        # Project an overlapping detection to the nearest free space instead
        # of returning zero forever inside an inflated wall rectangle.
        free_y, free_x = np.where(~blocked)
        if len(free_x):
            best = min(zip(free_x, free_y), key=lambda p: math.dist(p, start) + .02 * math.dist(p, end))
            return ((best[0] + .5) * cell, (best[1] + .5) * cell)
    # A detection can overlap a player standing at a wall. Permit only the
    # starting cell; do not clear the obstacle or allow crossing its corners.
    blocked[start[1], start[0]] = False
    queue = [(0., start)]
    costs, parent = {start: 0.}, {}
    closest, closest_distance = start, math.dist(start, end)
    while queue:
        _, node = heapq.heappop(queue)
        distance = math.dist(node, end)
        if distance < closest_distance:
            closest, closest_distance = node, distance
        if node == end:
            closest = node
            break
        x, y = node
        for dx, dy in ((0,-1),(-1,0),(1,0),(0,1),(-1,-1),(1,-1),(-1,1),(1,1)):
            nx, ny = x + dx, y + dy
            if not (7 <= nx < 53 and 0 <= ny < height) or blocked[ny, nx]:
                continue
            if dx and dy and (blocked[y, nx] or blocked[ny, x]):
                continue
            next_node = (nx, ny)
            cost = costs[node] + math.hypot(dx, dy)
            if cost < costs.get(next_node, math.inf):
                costs[next_node], parent[next_node] = cost, node
                heapq.heappush(queue, (cost + math.dist(next_node, end), next_node))
    if closest == start:
        return player
    route = [closest]
    while route[-1] != start:
        route.append(parent[route[-1]])
    route.reverse()
    # Skipping an L-shaped pair of cells cuts across its blocked corner.
    # Look ahead only when the entire continuous segment has clearance.
    waypoint = ((route[1][0]+.5)*cell, (route[1][1]+.5)*cell)
    for node in route[2:4]:
        candidate = ((node[0]+.5)*cell, (node[1]+.5)*cell)
        if not segment_clear(player, candidate, walls, radius):
            break
        waypoint = candidate
    return waypoint


def own_goal_rail(frame):
    """Return the blue goal's upper rail in normalized image coordinates."""
    hsv = cv2.cvtColor(small_frame(frame), cv2.COLOR_RGB2HSV)
    blue = cv2.inRange(hsv, (98, 110, 90), (125, 255, 255))
    blue[:100] = 0
    blue[:, :240] = 0
    blue[:, 720:] = 0
    bar = cv2.morphologyEx(blue, cv2.MORPH_OPEN, np.ones((2, 100), np.uint8))
    _, _, stats, _ = cv2.connectedComponentsWithStats(bar)
    rails = [(w,x,y,h) for x,y,w,h,area in stats[1:]
             if w > 280 and h < 30 and x < 480 < x+w]
    if not rails:
        return None
    return max(rails)


def own_goal_exit(frame, player):
    """Aim beyond the front wall so A* can route around it, not stop at it."""
    rail = own_goal_rail(frame)
    if rail is None:
        return None
    w,x,y,h = rail
    if not (x < player[0] < x+w and player[1] > y+35):
        return None
    center = x+w/2
    if player[1] > y+85 and abs(player[0]-center)>35:
        return (center, player[1])
    return (center,max(65,y-100))


def gadget_available(frame):
    """A clearly enabled gadget is negative evidence of ball possession."""
    small = small_frame(frame)
    # Match the app's configured game control position, including user changes.
    from pyla_addons.game_config import get_config
    x,y = get_config('cfg/buttons_config.toml','gadget',[1640,990])
    x,y = int(x/2),int(y/2)
    patch=small[max(0,y-25):y+26,max(0,x-25):x+26]
    if patch.shape[:2] != (51,51):
        return False
    hsv=cv2.cvtColor(patch,cv2.COLOR_RGB2HSV)
    yy,xx=np.ogrid[-25:26,-25:26]
    mask=(xx*xx+yy*yy < 23**2)&(hsv[:,:,0]>=36)&(hsv[:,:,0]<=85)&(hsv[:,:,1]>150)&(hsv[:,:,2]>180)
    return np.count_nonzero(mask)>250


def shooting_approach(player, goal, walls):
    """Reach a clear shooting position in front of the goal, not behind a post."""
    candidates=[]
    for forward in (120,160,200):
        for sideways in (0,-48,48,-96,96):
            point=(float(goal[0]+sideways),float(goal[1]+forward))
            if (135 < point[0] < 825 and 70 < point[1] < 450
                    and math.dist(point,goal)<230
                    and segment_clear(point,point,walls,radius=17)
                    and segment_clear(point,goal,walls,radius=6)):
                candidates.append((math.dist(player,point)+abs(sideways)*.1,point))
    if candidates:
        return min(candidates)[1]
    return (float(goal[0]),min(440.,float(goal[1])+200))


class AutoGoal:
    def __init__(self):
        self.vision = BallVision()
        self.reset()

    def reset(self, keep_mode=False):
        self.vision.reset(keep_mode=keep_mode)
        self.near_since = None
        self.last_possession = -math.inf
        self.last_near = -math.inf
        self.last_shot = -math.inf
        self.last_combat = -math.inf
        self.last_ball = None
        self.last_ball_at = -math.inf
        self.last_goal = None
        self.last_goal_at = -math.inf
        self.phase = "waiting"
        self._last_log = ""
        self.recovery = MovementRecovery()
        self.navigation = {}

    def decide(self, frame, data, controller, now, attack_range=None):
        width, height = frame.shape[1], frame.shape[0]
        sx, sy = 960 / width, 540 / height
        def feet(box):
            return ((box[0] + box[2]) / 2 * sx, box[3] * sy - 26.5)
        box = data['player'][0]
        visual = find_player_bar(frame, hint=box)
        player = feet(visual if visual is not None else box)
        anchor = ((box[0]+box[2])/2*sx, box[1]*sy)
        if visual is not None:
            anchor = ((visual[0]+visual[2])/2*sx, visual[1]*sy)
        observation = self.vision.observe(frame, now, player=player,
                                          owned_recently=now-self.last_possession < 2.)
        if not observation.active:
            self.near_since = None
            self.last_possession = -math.inf
            self.phase = "waiting_for_brawl_ball"
            return None
        walls = wall_footprints([(b[0]*sx, b[1]*sy, b[2]*sx, b[3]*sy) for b in data.get('wall', [])])
        rail = own_goal_rail(frame)
        if rail is not None:
            w,x,y,h = rail
            walls += [(0,y+5,x+16,540),(x+w-16,y+5,960,540)]
        ball = observation.ball
        if ball:
            self.last_ball, self.last_ball_at = ball, now
        if observation.goal:
            self.last_goal,self.last_goal_at = observation.goal,now
        control_available = gadget_available(frame)
        near = ball is not None and math.dist(player, ball) < 60 and not control_available
        if near:
            other_distance = min((math.dist(feet(b), ball) for b in data.get('teammate', []) + data.get('enemy', [])), default=math.inf)
            near = math.dist(player, ball) + 10 < other_distance
        if near:
            self.last_near = now
            if self.near_since is None:
                self.near_since = now
            if now - self.near_since >= .12:
                self.last_possession = now
        else:
            if ball is not None or now-self.last_near > .35:
                self.near_since = None
            # A nearby, partly occluded ball can shift relative to the player
            # during a turn or a 150ms perception interval. Only clearly
            # separate evidence should immediately revoke recent possession.
            if control_available or (ball is not None and math.dist(player,ball)>90):
                self.last_possession = -math.inf
        carrying = now - self.last_possession < 2. and now - self.last_shot > .9
        # While collecting, fight a nearby blocker only when the enabled
        # gadget positively shows that the ball is not in our hands. This
        # prevents ordinary auto-attacks from accidentally kicking it away.
        if (attack_range and control_available and not carrying
                and now-self.last_shot > .9 and now-self.last_combat >= .3):
            enemies = [feet(b) for b in data.get('enemy', [])]
            reachable = [p for p in enemies if math.dist(player,p) <= attack_range*sx
                         and segment_clear(player,p,walls,radius=0)]
            if reachable:
                controller.press('attack')
                self.last_combat = now
        if carrying:
            self.phase = "carry"
            visible_goal = observation.goal or (self.last_goal if now-self.last_goal_at < .8 else None)
            target = shooting_approach(player,visible_goal,walls) if visible_goal else (480,45)
            goal = observation.goal
            if goal and goal[1] + 30 < player[1] and math.dist(player, goal) < 230 and now - self.last_shot > 1.2 and near:
                clear = True
                for x1,y1,x2,y2 in walls:
                    if cv2.clipLine((int(x1-6), int(y1-6), max(1,int(x2-x1+12)), max(1,int(y2-y1+12))),
                                    tuple(map(int, player)), tuple(map(int, goal)))[0]:
                        clear = False
                        break
                if clear:
                    self.phase = "shoot"
                    controller.release_movement()
                    if controller.aim_attack(goal[0] - player[0], goal[1] - player[1]):
                        self.last_shot = now
                        self.last_possession = -math.inf
                        self.near_since = None
                    self._log()
                    return (0., 0.)
        elif ball:
            self.phase = "collect"
            target = ball
        else:
            self.phase = "search"
            # Maintain forward progress to reveal an off-screen ball. Never
            # shoot without a fresh ball and a visible opponent goal.
            target = observation.guide
            if target is None and now - self.last_ball_at < .6:
                target = self.last_ball
            if target is None:
                target = (480, 450) if observation.goal else (480, 65)
        exit_target = own_goal_exit(frame, player)
        if exit_target:
            self.phase = 'leave_spawn'
            target = exit_target
        waypoint = path_waypoint(player, target, walls)
        dx, dy = waypoint[0] - player[0], waypoint[1] - player[1]
        length = math.hypot(dx, dy)
        self._log()
        movement = (dx/length*75,dy/length*75) if length >= 1 else (0.,0.)
        entities = data.get('player',[])+data.get('teammate',[])+data.get('enemy',[])
        movement = self.recovery.adjust(frame,player,anchor,entities,walls,movement,now)
        self.navigation = {'player':player,'target':target,'waypoint':waypoint,
                           'recovery':self.recovery.status,'recoveries':self.recovery.recoveries,
                           'progress':self.recovery.displacement(),'ball':ball,
                           'ball_score':float(observation.score),'near':bool(near),
                           'goal':observation.goal,
                           'carrying':bool(carrying),'ring_visible':bool(observation.ring_visible),
                           'gadget_available':bool(control_available)}
        return (movement[0]*controller.width_ratio, movement[1]*controller.height_ratio)

    def _log(self):
        if self.phase != self._last_log:
            print(f"Auto goal: {self.phase}")
            self._last_log = self.phase
