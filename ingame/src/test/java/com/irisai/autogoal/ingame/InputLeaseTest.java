package com.irisai.autogoal.ingame;

import com.irisai.autogoal.SessionGate;
import org.junit.Test;
import static org.junit.Assert.*;

public class InputLeaseTest {
    private SessionGate ready() {
        SessionGate gate = new SessionGate();
        gate.setGame(true); gate.setCapture(true); gate.setAccess(true); gate.setEnabled(true); return gate;
    }
    @Test public void slowButFreshInferenceCanCompleteItsGesture() {
        SessionGate gate = ready(); InputLease lease = new InputLease(gate, gate.generation(), 0, 180);
        assertTrue(lease.start(650)); assertTrue(lease.valid(674)); assertTrue(lease.valid(830));
    }
    @Test public void staleFrameCannotStart() {
        SessionGate gate = ready(); InputLease lease = new InputLease(gate, gate.generation(), 0, 180);
        assertFalse(lease.start(701)); assertFalse(lease.valid(720));
    }
    @Test public void offAndReenableCannotReviveGesture() {
        SessionGate gate = ready(); InputLease lease = new InputLease(gate, gate.generation(), 0, 120);
        assertTrue(lease.start(200)); gate.setEnabled(false); assertFalse(lease.valid(220));
        gate.setEnabled(true); assertFalse(lease.valid(230)); assertFalse(lease.start(230));
    }
    @Test public void delayedUiCannotFireLateRelease() {
        SessionGate gate = ready(); InputLease lease = new InputLease(gate, gate.generation(), 0, 180);
        assertTrue(lease.start(650)); assertTrue(lease.valid(930)); assertFalse(lease.valid(931));
        assertFalse(lease.valid(649));
    }
}
