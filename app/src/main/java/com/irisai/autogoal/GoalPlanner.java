package com.irisai.autogoal;

import java.util.*;
import com.irisai.autogoal.Geometry.*;

/** Iris possession/carry/aim decisions. All coordinates are normalized to 960x540. */
public final class GoalPlanner {
    public enum Kind { NONE, MOVE, SHOOT }
    public static final class Decision {
        public final Kind kind;public final double dx,dy;public final String phase;
        Decision(Kind k,double x,double y,String p){kind=k;dx=x;dy=y;phase=p;}
        static Decision stop(String p){return new Decision(Kind.NONE,0,0,p);}
    }
    public static final class Observation {
        public boolean mode,finished,gadget;
        public Box player,ownRail;
        public P ball,goal,guide;
        public List<Box> others=new ArrayList<>(),walls=new ArrayList<>();
    }
    private double nearSince=Double.NaN,lastPossession=-1e9,lastShot=-1e9,lastNear=-1e9,lastBallAt=-1e9,lastGoalAt=-1e9;
    private P lastBall,lastGoal;
    public void reset(){nearSince=Double.NaN;lastPossession=lastShot=lastNear=lastBallAt=lastGoalAt=-1e9;lastBall=lastGoal=null;}
    public boolean ownedRecently(double now){return now-lastPossession<2;}
    public void shotAccepted(double now){lastShot=now;lastPossession=-1e9;nearSince=Double.NaN;}
    public Decision decide(Observation o,double now){
        if(!o.mode||o.finished){reset();return Decision.stop(o.finished?"경기 종료":"브롤볼 대기");}
        if(o.player==null){lastPossession=-1e9;nearSince=Double.NaN;return Decision.stop("캐릭터 찾는 중");}
        P player=o.player.feet();
        if(o.ball!=null){lastBall=o.ball;lastBallAt=now;}
        if(o.goal!=null){lastGoal=o.goal;lastGoalAt=now;}
        boolean near=o.ball!=null&&player.distance(o.ball)<60&&!o.gadget;
        if(near)for(Box other:o.others)if(player.distance(o.ball)+10>=other.feet().distance(o.ball)){near=false;break;}
        if(near){lastNear=now;if(Double.isNaN(nearSince))nearSince=now;if(now-nearSince>=.12)lastPossession=now;}
        else {if(o.ball!=null||now-lastNear>.35)nearSince=Double.NaN;
            if(o.gadget||(o.ball!=null&&player.distance(o.ball)>90))lastPossession=-1e9;}
        boolean carrying=now-lastPossession<2&&now-lastShot>.9;
        ArrayList<Box> walls=new ArrayList<>(o.walls);
        if(o.ownRail!=null){Box r=o.ownRail;walls.add(new Box(0,r.y1+5,r.x1+16,540));walls.add(new Box(r.x2-16,r.y1+5,960,540));}
        P target;String phase;
        if(carrying){
            phase="골대로 운반";P visible=o.goal!=null?o.goal:now-lastGoalAt<.8?lastGoal:null;
            target=visible!=null?Geometry.approach(player,visible,walls):new P(480,65);
            if(o.goal!=null&&o.goal.y+30<player.y&&player.distance(o.goal)<230&&now-lastShot>1.2&&near&&Geometry.clear(player,o.goal,walls,6))
                return new Decision(Kind.SHOOT,o.goal.x-player.x,o.goal.y-player.y,"조준 슛");
        }else if(o.ball!=null){target=o.ball;phase="공 획득";}
        else {phase="공 찾는 중";target=o.guide!=null?o.guide:now-lastBallAt<.6?lastBall:new P(480,o.goal!=null?450:65);}
        if(o.ownRail!=null){Box r=o.ownRail;double center=(r.x1+r.x2)/2;
            if(player.x>r.x1&&player.x<r.x2&&player.y>r.y1+35){
                target=player.y>r.y1+85&&Math.abs(player.x-center)>35?new P(center,player.y):new P(center,Math.max(65,r.y1-100));phase="골대 밖으로 이동";}}
        P next=Geometry.waypoint(player,target,walls);double dx=next.x-player.x,dy=next.y-player.y,len=Math.hypot(dx,dy);
        if(len<1)return Decision.stop("경로 대기");
        return new Decision(Kind.MOVE,dx/len,dy/len,phase);
    }
}
