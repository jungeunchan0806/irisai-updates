package com.irisai.farm;

import com.irisai.autogoal.Geometry.P;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import java.util.*;

/** Small grayscale patches are re-localized on each new frame between detector results. */
final class FrameTracker implements AutoCloseable {
    static final Size SIZE = new Size(320,180);
    private static final int RADIUS=5, ABOVE_FEET=5, SEARCH=15;
    private static final long MAX_AGE_MS=700;
    private final List<Anchor> enemies=new ArrayList<>();
    private Anchor player;
    private FarmPolicy.Frame reference;
    private long capturedAt;
    private final Mat scores=new Mat();

    static final class Anchor {
        final Mat patch;
        int x,y;
        Anchor(Mat patch,int x,int y){this.patch=patch;this.x=x;this.y=y;}
    }
    private Anchor anchor(Mat gray,P feet) {
        int x=(int)Math.round(feet.x/3), y=(int)Math.round(feet.y/3)-ABOVE_FEET;
        if(x<RADIUS||y<RADIUS||x+RADIUS>=gray.cols()||y+RADIUS>=gray.rows())return null;
        Mat roi=gray.submat(new Rect(x-RADIUS,y-RADIUS,2*RADIUS+1,2*RADIUS+1));
        try {
            Core.MinMaxLocResult contrast=Core.minMaxLoc(roi);
            return contrast.maxVal-contrast.minVal<22?null:new Anchor(roi.clone(),x,y);
        } finally {roi.release();}
    }
    void seed(Mat gray,FarmPolicy.Frame scene,long when) {
        clear(); if(scene==null||scene.player==null)return;
        player=anchor(gray,scene.player); if(player==null)return;
        reference=scene;capturedAt=when;
        for(P feet:scene.enemies){Anchor a=anchor(gray,feet);if(a!=null)enemies.add(a);}
    }
    private P locate(Mat gray,Anchor a) {
        int left=Math.max(0,a.x-SEARCH-RADIUS),top=Math.max(0,a.y-SEARCH-RADIUS);
        int right=Math.min(gray.cols(),a.x+SEARCH+RADIUS+1),bottom=Math.min(gray.rows(),a.y+SEARCH+RADIUS+1);
        Mat roi=gray.submat(new Rect(left,top,right-left,bottom-top));
        try {
            Imgproc.matchTemplate(roi,a.patch,scores,Imgproc.TM_CCOEFF_NORMED);
            Core.MinMaxLocResult peak=Core.minMaxLoc(scores);
            if(peak.maxVal<.78)return null;
            a.x=left+RADIUS+(int)peak.maxLoc.x; a.y=top+RADIUS+(int)peak.maxLoc.y;
            return new P(a.x*3,(a.y+ABOVE_FEET)*3);
        } finally { roi.release(); }
    }
    FarmPolicy.Frame track(Mat gray,long now) {
        if(player==null)return null;
        if(now<capturedAt||now-capturedAt>MAX_AGE_MS){clear();return null;}
        P pos=locate(gray,player);
        if(pos==null){clear();return null;}
        FarmPolicy.Frame f=new FarmPolicy.Frame();f.player=pos;f.walls=reference.walls;
        ArrayList<P> seen=new ArrayList<>();
        for(Iterator<Anchor> it=enemies.iterator();it.hasNext();){Anchor a=it.next();P p=locate(gray,a);
            if(p==null){a.patch.release();it.remove();}else seen.add(p);}
        f.enemies=seen;
        // Buttons are read only in a full analysis. Never replay an old ability indication.
        return f;
    }
    void clear(){if(player!=null)player.patch.release();player=null;for(Anchor a:enemies)a.patch.release();enemies.clear();reference=null;}
    @Override public void close(){clear();scores.release();}
}
