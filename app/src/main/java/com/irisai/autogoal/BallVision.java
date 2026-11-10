package com.irisai.autogoal;

import java.io.*;
import java.util.*;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import com.irisai.autogoal.Geometry.*;

/** Template/colour perception ported from Iris 0.0.9; never reads game memory. */
public final class BallVision implements AutoCloseable {
    public interface Assets { byte[] read(String name) throws IOException; }
    private final List<Mat> icons=new ArrayList<>(),balls=new ArrayList<>(),markers=new ArrayList<>(),banners=new ArrayList<>();
    private final Mat highlight;
    public BallVision(Assets assets) throws IOException {
        Mat icon=load(assets,"score_icon.png"),marker=load(assets,"ball_marker.png"),banner=load(assets,"match_over.png");
        highlight=load(assets,"game_highlight.png");
        for(int size:new int[]{34,40,46,50})icons.add(resized(icon,size,size));
        for(int i:new int[]{0,2,3,4}) {
            Mat raw=load(assets,"ball_"+i+".png");
            for(int size:new int[]{17,19}) {Mat base=resized(raw,size,size);for(int a=0;a<360;a+=45)balls.add(rotated(base,a));base.release();}raw.release();
        }
        Mat smallMarker=resized(marker,14,14);for(int a=0;a<360;a+=45)markers.add(rotated(smallMarker,a));
        for(double scale:new double[]{.95,1,1.05})banners.add(resized(banner,(int)Math.round(banner.cols()*scale),(int)Math.round(banner.rows()*scale)));
        icon.release();marker.release();banner.release();smallMarker.release();
    }
    private static Mat load(Assets assets,String name) throws IOException {
        MatOfByte bytes=new MatOfByte(assets.read(name));Mat m=Imgcodecs.imdecode(bytes,Imgcodecs.IMREAD_GRAYSCALE);bytes.release();
        if(m.empty())throw new IOException("Missing vision asset: "+name);return m;
    }
    private static Mat resized(Mat m,int w,int h){Mat out=new Mat();Imgproc.resize(m,out,new Size(w,h));return out;}
    private static Mat rotated(Mat m,int angle){Mat out=new Mat(),r=Imgproc.getRotationMatrix2D(new Point((m.cols()-1)/2.,(m.rows()-1)/2.),angle,1);Imgproc.warpAffine(m,out,r,m.size(),Imgproc.INTER_LINEAR,Core.BORDER_REFLECT,new Scalar(0));r.release();return out;}
    private static double score(Mat region,Mat template){
        if(region.cols()<template.cols()||region.rows()<template.rows())return -1;
        Mat result=new Mat();Imgproc.matchTemplate(region,template,result,Imgproc.TM_CCOEFF_NORMED);
        double s=Core.minMaxLoc(result).maxVal;result.release();return s;
    }
    public GoalPlanner.Observation observe(Mat rgb,Box detectedPlayer,List<Box> others,List<Box> walls,boolean owned,double gadgetX,double gadgetY){
        if(rgb.cols()!=960||rgb.rows()!=540)throw new IllegalArgumentException("Expected normalized RGB frame");
        GoalPlanner.Observation o=new GoalPlanner.Observation();Mat gray=new Mat(),hsv=new Mat();
        Imgproc.cvtColor(rgb,gray,Imgproc.COLOR_RGB2GRAY);Imgproc.cvtColor(rgb,hsv,Imgproc.COLOR_RGB2HSV);
        try {
            o.mode=hasMode(gray);if(!o.mode)return o;
            o.finished=finished(gray);if(o.finished)return o;
            Box visual=findPlayer(hsv,detectedPlayer);o.player=visual!=null?visual:detectedPlayer;
            o.others=others;o.walls=walls;o.ownRail=findRail(hsv,false);Box enemy=findRail(hsv,true);
            o.goal=enemy==null?null:new P((enemy.x1+enemy.x2)/2,(enemy.y1+enemy.y2)/2);
            o.gadget=gadgetAvailable(hsv,gadgetX*960,gadgetY*540);
            P player=o.player==null?null:o.player.feet();Mat tiny=resized(gray,480,270);
            try {
                Candidate selected=null;
                Rect local=null;if(player!=null){int l=Math.max(40,(int)(player.x/2)-36),t=Math.max(33,(int)(player.y/2)-32);int r=Math.min(440,(int)(player.x/2)+36),b=Math.min(220,(int)(player.y/2)+40);if(r>l&&b>t)local=new Rect(l,t,r-l,b-t);}
                if(owned&&local!=null)selected=select(rgb,gray,hsv,candidates(tiny,local,3),player,true,true);
                if(selected==null)selected=select(rgb,gray,hsv,candidates(tiny,new Rect(40,33,400,187),1),player,false,owned);
                if(selected==null&&!owned&&local!=null)selected=select(rgb,gray,hsv,candidates(tiny,local,1),player,true,false);
                if(selected!=null)o.ball=selected.point;
                double best=.78;
                for(Mat marker:markers){Mat scores=new Mat();Imgproc.matchTemplate(tiny,marker,scores,Imgproc.TM_CCOEFF_NORMED);Core.MinMaxLocResult mm=Core.minMaxLoc(scores);if(mm.maxVal>best){best=mm.maxVal;o.guide=new P((mm.maxLoc.x+7)*2,(mm.maxLoc.y+7)*2);}scores.release();}
            } finally {tiny.release();}
            return o;
        } finally {gray.release();hsv.release();}
    }
    private boolean hasMode(Mat gray){
        for(Rect r:new Rect[]{new Rect(0,0,110,65),new Rect(850,0,110,65)}){
            Mat crop=gray.submat(r);double best=-1;for(Mat i:icons)best=Math.max(best,score(crop,i));crop.release();if(best<.68)return false;
        }return true;
    }
    private boolean finished(Mat gray){
        Mat h=gray.submat(new Rect(0,0,900,230));boolean replay=score(h,highlight)>=.8;h.release();if(replay)return true;
        Mat crop=gray.submat(new Rect(220,80,520,250));
        try{for(Mat b:banners){if(score(crop,b)>=.8)return true;
            int w=b.cols(),left=(int)Math.round(w*.357),right=(int)Math.round(w*.643),width=crop.cols()-w+1;
            Mat a=b.colRange(0,left),z=b.colRange(right,w),sa=new Mat(),sz=new Mat();
            try{Imgproc.matchTemplate(crop,a,sa,Imgproc.TM_CCOEFF_NORMED);Imgproc.matchTemplate(crop,z,sz,Imgproc.TM_CCOEFF_NORMED);
                Mat ac=sa.colRange(0,width),zc=sz.colRange(right,right+width),minimum=new Mat();Core.min(ac,zc,minimum);double value=Core.minMaxLoc(minimum).maxVal;ac.release();zc.release();minimum.release();if(value>=.82)return true;
            }finally{a.release();z.release();sa.release();sz.release();}
        }}finally{crop.release();}return false;
    }
    private static Box findRail(Mat hsv,boolean red){
        Mat mask=new Mat(),second=new Mat();
        if(red){Core.inRange(hsv,new Scalar(0,130,100),new Scalar(9,255,255),mask);Core.inRange(hsv,new Scalar(174,130,100),new Scalar(179,255,255),second);Core.bitwise_or(mask,second,mask);}
        else Core.inRange(hsv,new Scalar(98,110,90),new Scalar(125,255,255),mask);
        zero(mask,new Rect(0,0,960,red?65:100));if(red)zero(mask,new Rect(0,350,960,190));zero(mask,new Rect(0,0,240,540));zero(mask,new Rect(720,0,240,540));
        if(red){Mat kernel=Mat.ones(1,21,CvType.CV_8U);Imgproc.morphologyEx(mask,mask,Imgproc.MORPH_CLOSE,kernel);kernel.release();}
        Mat k=Mat.ones(2,red?80:100,CvType.CV_8U);Imgproc.morphologyEx(mask,mask,Imgproc.MORPH_OPEN,k);k.release();
        Mat labels=new Mat(),stats=new Mat(),centroids=new Mat();Imgproc.connectedComponentsWithStats(mask,labels,stats,centroids);
        Box best=null;int longest=0;
        for(int n=1;n<stats.rows();n++){int[] s=new int[5];stats.get(n,0,s);int x=s[0],y=s[1],w=s[2],h=s[3];if(w>(red?240:280)&&h<(red?29:30)&&x<480&&x+w>480&&w>longest){best=new Box(x,y,x+w,y+h);longest=w;}}
        mask.release();second.release();labels.release();stats.release();centroids.release();return best;
    }
    private static void zero(Mat mask,Rect rect){Mat sub=mask.submat(rect);sub.setTo(new Scalar(0));sub.release();}
    private static Box findPlayer(Mat hsv,Box hint){
        Mat mask=new Mat();Core.inRange(hsv,new Scalar(36,150,170),new Scalar(72,255,255),mask);
        zero(mask,new Rect(0,0,960,55));zero(mask,new Rect(0,420,960,120));zero(mask,new Rect(0,0,85,540));zero(mask,new Rect(875,0,85,540));
        Mat labels=new Mat(),stats=new Mat(),centroids=new Mat();Imgproc.connectedComponentsWithStats(mask,labels,stats,centroids);Box best=null;int bestArea=0;
        for(int n=1;n<stats.rows();n++){
            int[] s=new int[5];stats.get(n,0,s);int x=s[0],y=s[1],w=s[2],h=s[3],area=s[4];
            if(w<13||w>70||h<3||h>11||w<h*2.5||area<w*h*.3)continue;
            if(hint!=null&&(Math.abs(x+w/2.-(hint.x1+hint.x2)/2)>45||y-hint.y1<=8||y-hint.y1>=65))continue;
            int top=Math.max(55,y-36),bottom=Math.max(55,y-12),left=Math.max(0,x-45),right=Math.min(960,x+w+45);
            if(bottom<=top)continue;Mat name=mask.submat(new Rect(left,top,right-left,bottom-top));int count=Core.countNonZero(name);name.release();if(count<15)continue;
            Mat text=hsv.submat(new Rect(Math.max(0,x-12),Math.max(0,y-18),Math.min(960,x+w+12)-Math.max(0,x-12),Math.max(0,y-2)-Math.max(0,y-18))),white=new Mat(),lab=new Mat(),st=new Mat(),ct=new Mat();
            Core.inRange(text,new Scalar(0,0,185),new Scalar(179,75,255),white);Imgproc.connectedComponentsWithStats(white,lab,st,ct);int digits=0;
            for(int d=1;d<st.rows();d++){int[] a=new int[5];st.get(d,0,a);if(a[3]>=5&&a[3]<=11&&a[2]<=14&&a[4]>=8)digits++;}
            text.release();white.release();lab.release();st.release();ct.release();
            if(digits>=2&&area>bestArea){bestArea=area;double cx=x+w/2.;best=new Box(cx-48,y-30,cx+48,y+100);}
        }
        mask.release();labels.release();stats.release();centroids.release();return best;
    }
    private static boolean gadgetAvailable(Mat hsv,double cx,double cy){
        int x=(int)cx,y=(int)cy;if(x<25||x+25>=960||y<25||y+25>=540)return false;
        Mat patch=hsv.submat(new Rect(x-25,y-25,51,51));byte[] pixels=new byte[51*51*3];patch.get(0,0,pixels);patch.release();int count=0;
        for(int j=0;j<51;j++)for(int i=0;i<51;i++){int idx=(j*51+i)*3,dx=i-25,dy=j-25;if(dx*dx+dy*dy<529&&(pixels[idx]&255)>=36&&(pixels[idx]&255)<=85&&(pixels[idx+1]&255)>150&&(pixels[idx+2]&255)>180)count++;}
        return count>250;
    }
    private static final class Candidate{final double score;final P point;Candidate(double s,P p){score=s;point=p;}}
    private List<Candidate> candidates(Mat tiny,Rect rect,int peaks){
        ArrayList<Candidate> found=new ArrayList<>();Mat region=tiny.submat(rect);
        try{for(Mat template:balls){if(region.cols()<template.cols()||region.rows()<template.rows())continue;Mat scores=new Mat();Imgproc.matchTemplate(region,template,scores,Imgproc.TM_CCOEFF_NORMED);
            for(int i=0;i<peaks;i++){Core.MinMaxLocResult mm=Core.minMaxLoc(scores);if(mm.maxVal<.35)break;found.add(new Candidate(mm.maxVal,new P((mm.maxLoc.x+rect.x+template.cols()/2.)*2,(mm.maxLoc.y+rect.y+template.rows()/2.)*2)));Imgproc.circle(scores,mm.maxLoc,4,new Scalar(-1),-1);}scores.release();}
        }finally{region.release();}found.sort((a,b)->Double.compare(b.score,a.score));return found;
    }
    private Candidate select(Mat rgb,Mat gray,Mat hsv,List<Candidate> candidates,P player,boolean local,boolean owned){
        Set<String> seen=new HashSet<>();for(Candidate c:candidates){boolean close=player!=null&&player.distance(c.point)<65;
            if(c.score<(local&&owned?.35:.44)||(local&&!close)||!seen.add(c.point.x+","+c.point.y))continue;
            if(!ballTexture(gray,hsv,c.point))continue;
            if(c.score>=.52||(close&&ballRing(hsv,c.point))||(close&&owned&&c.score>=.46))return c;
        }return null;
    }
    private static boolean ballTexture(Mat gray,Mat hsv,P p){
        int x=(int)p.x,y=(int)p.y;if(x<16||y<16||x+16>960||y+16>540)return false;
        Mat g=gray.submat(new Rect(x-16,y-16,32,32)),h=hsv.submat(new Rect(x-16,y-16,32,32));byte[] gs=new byte[1024],hs=new byte[3072];g.get(0,0,gs);h.get(0,0,hs);g.release();h.release();
        int count=0,pale=0,dark=0,light=0;double sum=0,squares=0;
        for(int j=0;j<32;j++)for(int i=0;i<32;i++)if(Math.pow(i-15.5,2)+Math.pow(j-15.5,2)<196){int at=j*32+i,v=gs[at]&255;count++;if((hs[at*3+1]&255)<85)pale++;if(v<90)dark++;if(v>125)light++;sum+=v;squares+=v*v;}
        return pale/(double)count>.45&&dark/(double)count>.12&&light/(double)count>.2&&Math.sqrt(squares/count-Math.pow(sum/count,2))>35;
    }
    private static boolean ballRing(Mat hsv,P p){
        int x=(int)p.x,y=(int)p.y;if(x<36||y<36||x+36>=960||y+36>=540)return false;
        Mat h=hsv.submat(new Rect(x-36,y-36,73,73));byte[] values=new byte[73*73*3];h.get(0,0,values);h.release();int left=0,right=0;
        for(int j=-23;j<=23;j++)for(int i=-36;i<=36;i++){int d=i*i+j*j,at=((j+36)*73+i+36)*3;if(d>441&&d<1225&&(values[at+1]&255)<65&&(values[at+2]&255)>210){if(i< -17)left++;if(i>17)right++;}}
        return left>=8&&right>=8;
    }
    public void close(){for(List<Mat> list:Arrays.asList(icons,balls,markers,banners))for(Mat m:list)m.release();highlight.release();}
}
