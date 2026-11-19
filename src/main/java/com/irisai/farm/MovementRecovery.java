package com.irisai.farm;

import com.irisai.autogoal.Geometry.P;
import com.irisai.autogoal.Geometry.Box;
import java.util.*;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import org.opencv.video.Video;

/** Port of navigation.py: background motion, bounded escapes, failed-path memory. */
final class MovementRecovery implements AutoCloseable {
    private Mat previous;
    private MatOfPoint2f points;
    private double cameraX,cameraY,lastSample=-1e9,escapeAt;
    private P world,escape,origin;
    private final ArrayDeque<double[]> samples=new ArrayDeque<>(),failed=new ArrayDeque<>();
    int recoveries;
    void reset(){close();cameraX=cameraY=0;lastSample=-1e9;samples.clear();failed.clear();escape=null;world=null;recoveries=0;}
    private void observe(Mat rgb,P anchor,List<Box> entities,double now) {
        if(now-lastSample<.16)return;
        Mat small=new Mat(),gray=new Mat(),mask=Mat.zeros(270,480,CvType.CV_8U);
        Imgproc.resize(rgb,small,new Size(480,270));Imgproc.cvtColor(small,gray,Imgproc.COLOR_RGB2GRAY);small.release();
        try {
            if(previous!=null&&points!=null&&points.rows()>=10&&now-lastSample<.8) {
                MatOfPoint2f next=new MatOfPoint2f();MatOfByte status=new MatOfByte();MatOfFloat error=new MatOfFloat();
                try {
                    Video.calcOpticalFlowPyrLK(previous,gray,points,next,status,error,new Size(15,15),2,
                        new TermCriteria(TermCriteria.COUNT|TermCriteria.EPS,15,.05));
                    Point[] a=points.toArray(),b=next.toArray();byte[] ok=status.toArray();float[] errors=error.toArray();
                    ArrayList<Double> dx=new ArrayList<>(),dy=new ArrayList<>();
                    for(int i=0;i<Math.min(a.length,b.length);i++)if(ok[i]!=0&&errors[i]<20){dx.add(b[i].x-a[i].x);dy.add(b[i].y-a[i].y);}
                    if(dx.size()>=10){Collections.sort(dx);Collections.sort(dy);double mx=dx.get(dx.size()/2),my=dy.get(dy.size()/2);int inliers=0;
                        for(int i=0;i<a.length&&i<b.length;i++)if(ok[i]!=0&&errors[i]<20&&Math.hypot((b[i].x-a[i].x)-mx,(b[i].y-a[i].y)-my)<1.5)inliers++;
                        if(inliers>dx.size()*.6&&Math.abs(mx)<35&&Math.abs(my)<35){cameraX+=mx*2;cameraY+=my*2;}}
                }finally{next.release();status.release();error.release();}
            }
            Imgproc.rectangle(mask,new Point(75,35),new Point(405,225),new Scalar(255),-1);
            for(Box b:entities)Imgproc.rectangle(mask,new Point(b.x1/2-10,b.y1/2-10),new Point(b.x2/2+10,b.y2/2+10),new Scalar(0),-1);
            MatOfPoint found=new MatOfPoint();
            try{Imgproc.goodFeaturesToTrack(gray,found,100,.03,8,mask);if(points!=null)points.release();points=new MatOfPoint2f(found.toArray());}finally{found.release();}
            if(previous!=null)previous.release();previous=gray;gray=null;
            world=new P(anchor.x-cameraX,anchor.y-cameraY);samples.add(new double[]{now,world.x,world.y});
            while(!samples.isEmpty()&&now-samples.peek()[0]>1.8)samples.remove();lastSample=now;
        } finally{mask.release();if(gray!=null)gray.release();}
    }
    private static double clearance(P player,P direction,List<Box> walls) {
        Set<Integer> remaining=new HashSet<>();for(int i=0;i<walls.size();i++)if(walls.get(i).contains(player,16))remaining.add(i);
        double distance=0;
        for(int length=6;length<127;length+=6){P p=new P(player.x+direction.x*length,player.y+direction.y*length);
            if(p.x<=125||p.x>=835||p.y<=60||p.y>=470)break;
            Set<Integer> hits=new HashSet<>();for(int i=0;i<walls.size();i++)if(walls.get(i).contains(p,16))hits.add(i);
            if(!remaining.containsAll(hits))break;remaining.retainAll(hits);if(hits.isEmpty())distance=length;}
        return distance;
    }
    private void choose(P player,P movement,List<Box> walls,double now) {
        double len=Math.hypot(movement.x,movement.y);P forward=len>.1?new P(movement.x/len,movement.y/len):new P(0,-1);
        while(!failed.isEmpty()&&now-failed.peek()[0]>15)failed.remove();
        double best=-1e9,room=0;P selected=new P(-forward.x,-forward.y);
        for(double angle:new double[]{Math.PI/2,-Math.PI/2,Math.PI,Math.PI*.75,-Math.PI*.75,Math.PI/4,-Math.PI/4}){
            P d=new P(forward.x*Math.cos(angle)-forward.y*Math.sin(angle),forward.x*Math.sin(angle)+forward.y*Math.cos(angle));
            double space=clearance(player,d,walls),score=space-15*Math.max(0,Math.cos(angle));
            for(double[] f:failed)if(d.x*f[1]+d.y*f[2]>.8)score-=140;
            if(score>best){best=score;selected=d;room=space;}}
        if(room<18)selected=new P(-forward.x,-forward.y);
        escape=selected;escapeAt=now;origin=world==null?player:world;samples.clear();recoveries++;
    }
    P adjust(Mat rgb,P player,P anchor,List<Box> entities,List<Box> walls,P movement,double now) {
        if(Math.hypot(movement.x,movement.y)<1){reset();return movement;}
        observe(rgb,anchor,entities,now);
        if(escape!=null){double moved=world==null?0:world.distance(origin),elapsed=now-escapeAt;
            if((moved>=50&&elapsed>=.3)||elapsed>=1.5){if(moved<18){failed.add(new double[]{now,escape.x,escape.y});choose(player,movement,walls,now);}else{escape=null;samples.clear();}}
            else if(elapsed>.3&&clearance(player,escape,walls)<12){failed.add(new double[]{now,escape.x,escape.y});choose(player,movement,walls,now);}}
        else if(samples.size()>=4&&now-samples.peek()[0]>=1.25){double[] a=samples.peek(),b=samples.peekLast();if(Math.hypot(a[1]-b[1],a[2]-b[2])<8)choose(player,movement,walls,now);}
        return escape==null?movement:new P(escape.x*75,escape.y*75);
    }
    public void close(){if(previous!=null){previous.release();previous=null;}if(points!=null){points.release();points=null;}}
}
