package com.irisai.autogoal.ingame;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class StrokeTest {
    static final class Clock implements Stroke.Scheduler {
        final List<Runnable> pending = new ArrayList<>();
        public void later(Runnable action, long delay) { pending.add(action); }
        void run(int index) { pending.get(index).run(); }
    }
    @Test public void downMoveUpUsesGameContract() {
        Clock clock = new Clock(); List<String> events = new ArrayList<>(); int[] released = {0};
        Stroke s = new Stroke((t,x,y,p) -> events.add(t+":"+x+":"+y+":"+p), clock);
        assertTrue(s.start(10,20,60,40,120,()->true,()->released[0]++));
        assertTrue(s.busy()); clock.run(0); clock.run(1);
        assertEquals(Arrays.asList("0:10:20:9","2:60:40:9","1:60:40:9"),events);
        assertEquals(1,released[0]); assertFalse(s.busy());
    }
    @Test public void offCancelsWithoutFiringAimedShot() {
        Clock clock=new Clock(); List<Integer> events=new ArrayList<>();
        Stroke s=new Stroke((t,x,y,p)->events.add(t),clock);
        s.start(10,20,60,40,120,()->true,()->fail("Must not release"));
        clock.run(0); s.cancel(); clock.run(1);
        assertEquals(Arrays.asList(0,2,3),events); assertFalse(s.busy());
    }
    @Test public void oldCallbacksDoNotCancelNewGesture() {
        Clock clock=new Clock(); List<Integer> events=new ArrayList<>();
        Stroke s=new Stroke((t,x,y,p)->events.add(t),clock);
        s.start(10,20,60,40,120,()->false,()->fail()); assertTrue(events.isEmpty());
        s.start(10,20,60,40,120,()->true,()->fail()); s.cancel();
        s.start(10,20,60,40,120,()->true,()->{});
        clock.run(0); clock.run(1); assertTrue(s.busy()); clock.run(2); clock.run(3);
        assertEquals(Arrays.asList(0,3,0,2,1),events);
    }
    @Test public void lostPermissionCancelsAtNextStep() {
        Clock clock=new Clock(); List<Integer> events=new ArrayList<>(); boolean[] permit={true};
        Stroke s=new Stroke((t,x,y,p)->events.add(t),clock);
        s.start(10,20,60,40,120,()->permit[0],()->fail()); permit[0]=false;
        clock.run(0); clock.run(1); assertEquals(Arrays.asList(0,3),events);
    }
    @Test public void upFailureCancelsNativePointer() {
        Clock clock=new Clock(); List<Integer> events=new ArrayList<>();
        Stroke s=new Stroke((t,x,y,p)->{events.add(t); if(t==1)throw new Exception("native failure");},clock);
        s.start(10,20,60,40,120,()->true,()->fail()); clock.run(0); clock.run(1);
        assertEquals(Arrays.asList(0,2,1,3),events); assertFalse(s.busy());
    }
    @Test public void refusesOverlapInvalidCoordinatesAndExcessiveHolds() {
        Clock clock=new Clock(); List<Integer> events=new ArrayList<>();
        Stroke s=new Stroke((t,x,y,p)->events.add(t),clock);
        assertFalse(s.start(-1,0,2,3,120,()->true,()->{}));
        assertFalse(s.start(1,0,2,3,1000,()->true,()->{}));
        assertTrue(s.start(1,0,2,3,120,()->true,()->{}));
        assertFalse(s.start(1,0,2,3,120,()->true,()->{}));
        assertEquals(Collections.singletonList(0),events);
    }
    @Test public void failureDuringDownStillAttemptsCancellation() {
        Clock clock=new Clock(); List<Integer> events=new ArrayList<>();
        Stroke s=new Stroke((t,x,y,p)->{events.add(t);if(t==0)throw new Exception();},clock);
        assertFalse(s.start(1,0,2,3,120,()->true,()->fail()));
        assertEquals(Arrays.asList(0,3),events); assertFalse(s.busy());
    }
}
