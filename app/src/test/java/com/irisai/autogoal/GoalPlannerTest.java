package com.irisai.autogoal;
import org.junit.Test;
import static org.junit.Assert.*;
import com.irisai.autogoal.Geometry.*;
import com.irisai.autogoal.GoalPlanner.*;

public class GoalPlannerTest {
    private Observation observation(){Observation o=new Observation();o.mode=true;o.player=new Box(432,200,528,326.5);o.ball=new P(480,285);o.goal=new P(480,130);return o;}
    @Test public void possessionNeedsTwoSeparatedObservations(){GoalPlanner p=new GoalPlanner();Observation o=observation();assertNotEquals(Kind.SHOOT,p.decide(o,1).kind);assertNotEquals(Kind.SHOOT,p.decide(o,1.05).kind);assertEquals(Kind.SHOOT,p.decide(o,1.25).kind);}
    @Test public void neverShootsTowardOwnGoal(){GoalPlanner p=new GoalPlanner();Observation o=observation();o.goal=new P(480,460);p.decide(o,1);assertNotEquals(Kind.SHOOT,p.decide(o,1.25).kind);}
    @Test public void noBlindShotFromCachedGoal(){GoalPlanner p=new GoalPlanner();Observation o=observation();p.decide(o,1);p.decide(o,1.25);o.goal=null;assertNotEquals(Kind.SHOOT,p.decide(o,1.3).kind);}
    @Test public void closerTeammateCannotGrantPossession(){GoalPlanner p=new GoalPlanner();Observation o=observation();o.others.add(new Box(440,190,520,311.5));p.decide(o,1);assertNotEquals(Kind.SHOOT,p.decide(o,1.25).kind);assertFalse(p.ownedRecently(1.25));}
    @Test public void availableGadgetPreventsFalsePossession(){GoalPlanner p=new GoalPlanner();Observation o=observation();o.gadget=true;p.decide(o,1);assertNotEquals(Kind.SHOOT,p.decide(o,1.25).kind);}
    @Test public void wallPreventsShotEvenIfBallIsOwned(){GoalPlanner p=new GoalPlanner();Observation o=observation();o.walls.add(new Box(450,200,510,240));p.decide(o,1);assertNotEquals(Kind.SHOOT,p.decide(o,1.25).kind);}
    @Test public void rejectedDispatchDoesNotConsumeCooldown(){GoalPlanner p=new GoalPlanner();Observation o=observation();p.decide(o,1);assertEquals(Kind.SHOOT,p.decide(o,1.25).kind);assertEquals(Kind.SHOOT,p.decide(o,1.3).kind);p.shotAccepted(1.3);assertNotEquals(Kind.SHOOT,p.decide(o,1.5).kind);assertEquals(Kind.SHOOT,p.decide(o,2.6).kind);}
    @Test public void losingPlayerCannotShoot(){GoalPlanner p=new GoalPlanner();Observation o=observation();p.decide(o,1);p.decide(o,1.25);o.player=null;assertEquals(Kind.NONE,p.decide(o,1.3).kind);}
    @Test public void resultOrUnknownModeResetsOwnership(){for(boolean finished:new boolean[]{false,true}){GoalPlanner p=new GoalPlanner();Observation o=observation();p.decide(o,1);p.decide(o,1.25);o.mode=finished;o.finished=finished;assertEquals(Kind.NONE,p.decide(o,1.3).kind);assertFalse(p.ownedRecently(1.3));}}
    @Test public void absentBallDoesNotAllowShot(){GoalPlanner p=new GoalPlanner();Observation o=observation();p.decide(o,1);p.decide(o,1.25);o.ball=null;assertNotEquals(Kind.SHOOT,p.decide(o,1.3).kind);}
    @Test public void distantBallRevokesPossession(){GoalPlanner p=new GoalPlanner();Observation o=observation();p.decide(o,1);p.decide(o,1.25);o.ball=new P(200,100);p.decide(o,1.3);assertFalse(p.ownedRecently(1.3));}
}
