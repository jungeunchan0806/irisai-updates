package com.irisai.autogoal;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import com.irisai.autogoal.Geometry.*;

public class GeometryTest {
    @Test public void wallBlocksSegmentBothWays(){List<Box> w=Arrays.asList(new Box(450,200,510,240));assertFalse(Geometry.clear(new P(480,300),new P(480,100),w,6));assertFalse(Geometry.clear(new P(480,100),new P(480,300),w,6));assertTrue(Geometry.clear(new P(400,300),new P(400,100),w,6));}
    @Test public void bodyClearanceMatters(){List<Box>w=Arrays.asList(new Box(450,200,510,240));assertTrue(Geometry.clear(new P(440,300),new P(440,100),w,6));assertFalse(Geometry.clear(new P(440,300),new P(440,100),w,17));}
    @Test public void routeDoesNotCutThroughWall(){P a=new P(480,320);List<Box>w=Arrays.asList(new Box(450,240,510,280));P p=Geometry.waypoint(a,new P(480,100),w);assertNotEquals(480,p.x,.001);assertTrue(p.y>=296);assertTrue(Geometry.clear(a,p,w,17));}
    @Test public void overlappedDetectionCanEscape(){P a=new P(480,300);P p=Geometry.waypoint(a,new P(480,60),Arrays.asList(new Box(450,270,510,320)));assertTrue(p.distance(a)>0);assertTrue(Double.isFinite(p.x)&&Double.isFinite(p.y));}
    @Test public void fullyBlockedMapStops(){P a=new P(480,300);P p=Geometry.waypoint(a,new P(480,60),Arrays.asList(new Box(0,0,960,540)));assertEquals(0,p.distance(a),.001);}
    @Test public void shootingApproachLeavesClearShot(){List<Box>w=Arrays.asList(new Box(460,180,500,210));P p=Geometry.approach(new P(480,360),new P(480,90),w);assertTrue(Geometry.clear(p,new P(480,90),w,6));assertTrue(p.y>90);}
}
