package com.irisai.farm;

import com.irisai.autogoal.Geometry.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class FarmPolicyTest {
    private FarmPolicy.Brawler brawler(){FarmPolicy.Brawler b=new FarmPolicy.Brawler();b.attackRange=250;b.safeRange=160;b.superRange=120;return b;}
    private FarmPolicy.Frame frame(double distance){FarmPolicy.Frame f=new FarmPolicy.Frame();f.player=new P(400,270);f.enemies=List.of(new P(400+distance,270));f.superReady=f.gadgetReady=f.hyperReady=true;return f;}
    @Test public void rangedRetreatHasHysteresisAndReset(){
        FarmPolicy p=new FarmPolicy();FarmPolicy.Brawler b=brawler();
        assertTrue(p.decide(frame(210),b).dx>0);
        assertTrue(p.decide(frame(100),b).dx<0);
        assertTrue(p.decide(frame(165),b).dx<0);
        assertTrue(p.decide(frame(190),b).dx>0);
        p.reset();assertTrue(p.decide(frame(165),b).dx>0);
    }
    @Test public void abilitiesRespectRangeAndWalls(){
        FarmPolicy p=new FarmPolicy();FarmPolicy.Brawler b=brawler();
        FarmPolicy.Decision close=p.decide(frame(100),b);assertTrue(close.attack&&close.superAttack&&close.gadget&&close.hyper);
        FarmPolicy.Decision far=p.decide(frame(260),b);assertFalse(far.attack||far.superAttack||far.gadget||far.hyper);
        FarmPolicy.Frame blocked=frame(100);blocked.walls=List.of(new Box(440,220,460,320));
        FarmPolicy.Decision hidden=p.decide(blocked,b);assertFalse(hidden.attack||hidden.superAttack||hidden.gadget||hidden.hyper);
        b.throughWalls=true;hidden=p.decide(blocked,b);assertTrue(hidden.attack);assertFalse(hidden.superAttack);
    }
    @Test public void spawningAndChargingSupersUseOriginalRangeRule(){
        FarmPolicy.Brawler b=brawler();FarmPolicy p=new FarmPolicy();
        b.superType="spawnable";assertTrue(p.decide(frame(180),b).superAttack);
        b.superType="charge";b.name="stu";assertTrue(p.decide(frame(330),b).superAttack);
        b.name="shelly";assertFalse(p.decide(frame(330),b).superAttack);
    }
    @Test public void missingPlayerNeverGeneratesInput(){
        FarmPolicy.Decision d=new FarmPolicy().decide(new FarmPolicy.Frame(),brawler());
        assertEquals(0,d.dx,0);assertEquals(0,d.dy,0);assertFalse(d.attack||d.superAttack||d.gadget||d.hyper);
    }
    @Test public void searchAvoidsWallAndDoesNotUseAbilities(){
        FarmPolicy.Frame f=frame(150);f.enemies=List.of();f.walls=List.of(new Box(360,200,440,235));
        FarmPolicy.Decision d=new FarmPolicy().decide(f,brawler());assertTrue(d.dx<0);assertFalse(d.attack||d.superAttack||d.gadget||d.hyper);
    }
    @Test public void overlappingDetectionCanEscapeButFullyBlockedMapCannot(){
        FarmPolicy.Frame f=frame(150);f.enemies=List.of();f.walls=List.of(new Box(390,260,410,280));
        FarmPolicy.Decision d=new FarmPolicy().decide(f,brawler());assertTrue(Math.hypot(d.dx,d.dy)>1);
        f.walls=List.of(new Box(0,0,960,540));d=new FarmPolicy().decide(f,brawler());assertEquals(0,Math.hypot(d.dx,d.dy),0);
    }
}
