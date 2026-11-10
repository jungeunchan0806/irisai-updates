package com.irisai.autogoal;
import org.junit.Test;
import static org.junit.Assert.*;

public class SessionGateTest {
    private SessionGate ready(){SessionGate g=new SessionGate();g.setGame(true);g.setCapture(true);g.setAccess(true);g.setEnabled(true);return g;}
    @Test public void startsOff(){assertFalse(new SessionGate().ready());}
    @Test public void offOnNeverReplaysOldDecision(){SessionGate g=ready();long epoch=g.generation();g.setEnabled(false);g.setEnabled(true);assertFalse(g.allows(epoch,100,101));assertTrue(g.allows(g.generation(),100,101));}
    @Test public void switchingAwayAndBackInvalidates(){SessionGate g=ready();long epoch=g.generation();g.setGame(false);assertFalse(g.ready());g.setGame(true);assertFalse(g.allows(epoch,100,101));}
    @Test public void lostCaptureOrInputPermissionStops(){SessionGate g=ready();g.setAccess(false);assertFalse(g.ready());g.setAccess(true);g.setCapture(false);assertFalse(g.ready());}
    @Test public void staleOrFutureFrameNeverActs(){SessionGate g=ready();assertFalse(g.allows(g.generation(),1000,999));assertFalse(g.allows(g.generation(),1000,1701));assertTrue(g.allows(g.generation(),1000,1700));}
    @Test public void duplicateForegroundSignalPreservesCurrentDecision(){SessionGate g=ready();long e=g.generation();g.setGame(true);assertTrue(g.allows(e,100,101));}
    @Test public void resizeInvalidatesPendingActions(){SessionGate g=ready();long e=g.generation();g.invalidate();assertFalse(g.allows(e,100,101));}
}
