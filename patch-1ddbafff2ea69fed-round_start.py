"""Recognize renewed control after a match/round intro or a respawn."""
import math


class RoundStartTracker:
    def __init__(self):
        self.last_seen = None
        self.last_position = None
        self.last_reset = -math.inf
        self.lineup_latched = False
        self.lineup_missing_since = None

    def leave_match(self):
        self.last_seen = None
        self.last_position = None
        self.lineup_latched = False

    def update(self, player, teammates, width, height, now, kickoff_context=True):
        def feet(box):
            return ((box[0] + box[2]) / 2, box[3] - 53 * height / 1080)
        position = feet(player)
        scale = height / 1080
        # The three players line up in front of their goal at kickoff, including
        # after a goal when the high-level state may stay 'match' throughout.
        peers = [feet(b) for b in teammates]
        lineup = kickoff_context and position[1] > height * .4 and sum(
            abs(p[1] - position[1]) < 65 * scale
            and 50 * scale < abs(p[0] - position[0]) < 460 * scale
            for p in peers
        ) >= 2
        new_lineup = lineup and not self.lineup_latched
        if lineup:
            self.lineup_latched = True
            self.lineup_missing_since = None
        else:
            if self.lineup_missing_since is None:
                self.lineup_missing_since = now
            if now - self.lineup_missing_since > .6:
                self.lineup_latched = False
        appeared = self.last_seen is None or now - self.last_seen > .5
        teleported = (self.last_position is not None and self.last_seen is not None
                      and now - self.last_seen < .5
                      and math.dist(position, self.last_position) > width * .18)
        self.last_seen, self.last_position = now, position
        reason = 'player appeared' if appeared else 'kickoff lineup' if new_lineup else 'spawn position changed' if teleported else None
        if reason and now - self.last_reset > 1.5:
            self.last_reset = now
            return reason
        return None
