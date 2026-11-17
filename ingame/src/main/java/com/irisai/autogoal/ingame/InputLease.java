package com.irisai.autogoal.ingame;

import com.irisai.autogoal.SessionGate;

/** A fresh decision may start one short gesture; lifecycle changes still revoke it immediately. */
final class InputLease {
    private final SessionGate gate;
    private final long epoch, frameAt;
    private final int duration;
    private long startedAt = -1;
    InputLease(SessionGate gate, long epoch, long frameAt, int duration) {
        this.gate = gate; this.epoch = epoch; this.frameAt = frameAt; this.duration = duration;
    }
    boolean start(long now) {
        if (startedAt >= 0 || duration < 50 || duration > 180 || !gate.allows(epoch, frameAt, now)) return false;
        startedAt = now; return true;
    }
    boolean valid(long now) {
        // Do not measure an already accepted gesture against inference start time:
        // that cancelled UP on slower devices even when DOWN accepted a fresh frame.
        // Allow 100ms scheduling tolerance; a stalled UI must cancel, never fire late.
        return startedAt >= 0 && gate.allows(epoch, startedAt, now) && now - startedAt <= duration + 100L;
    }
}
