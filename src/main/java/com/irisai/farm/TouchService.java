package com.irisai.farm;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.accessibility.*;
import java.util.List;

/** All access and dispatch take place on the main thread. No global actions or node clicks. */
public final class TouchService extends AccessibilityService {
    static final String GAME="com.supercell.brawlstars";
    static TouchService active;
    private final Handler main=new Handler(Looper.getMainLooper());
    private boolean busy;
    private long generation, nextAbility, nextAttack;
    private static final int STEP_MS=110;

    @Override protected void onServiceConnected(){ active=this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if(CaptureService.live!=null)CaptureService.live.windowChanged();
    }
    @Override public void onInterrupt(){invalidate();}
    @Override public void onDestroy(){if(active==this)active=null;invalidate();super.onDestroy();}

    Rect gameWindow() {
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null)return null;
        int id;
        try{if(!GAME.contentEquals(root.getPackageName()==null?"":root.getPackageName()))return null;id=root.getWindowId();}
        finally{root.recycle();}
        List<AccessibilityWindowInfo> windows=getWindows(); Rect result=null;
        try{for(AccessibilityWindowInfo w:windows) {
            if(w.getId()==id&&w.getType()==AccessibilityWindowInfo.TYPE_APPLICATION&&w.getDisplayId()==Display.DEFAULT_DISPLAY) {
                Rect r=new Rect();w.getBoundsInScreen(r);if(r.width()>r.height()&&!r.isEmpty())result=r;
            }
        }}finally{for(AccessibilityWindowInfo w:windows)w.recycle();}
        return result;
    }
    void invalidate(){generation++;nextAttack=nextAbility=0;}

    private static Path path(Rect r,double x,double y,double dx,double dy){
        float sx=r.left+(float)(r.width()*x/960),sy=r.top+(float)(r.height()*y/540);
        Path path=new Path();path.moveTo(sx,sy);
        if(dx!=0||dy!=0)path.lineTo(sx+(float)(r.width()*dx/960),sy+(float)(r.height()*dy/540));
        return path;
    }
    private static GestureDescription.StrokeDescription stroke(Rect r,double x,double y,double dx,double dy,long ms){
        return new GestureDescription.StrokeDescription(path(r,x,y,dx,dy),0,ms);
    }

    // Android retains this pointer between segments. Re-check the focused game window before every extension.
    private final class Charge extends GestureResultCallback {
        final Rect window;
        final long ticket;
        final Path stationary;
        GestureDescription.StrokeDescription held;
        long remaining;
        Charge(Rect window,Path stationary,long duration){
            this.window=new Rect(window);this.stationary=stationary;ticket=generation;
            long first=Math.min(STEP_MS,duration);remaining=duration-first;
            held=new GestureDescription.StrokeDescription(stationary,0,first,remaining>0);
        }
        @Override public void onCompleted(GestureDescription gesture){
            if(remaining<=0){busy=false;return;}
            boolean keep=active==TouchService.this&&generation==ticket&&window.equals(gameWindow());
            long duration=keep?Math.min(STEP_MS,remaining):1;
            remaining=keep?remaining-duration:0;
            held=held.continueStroke(stationary,0,duration,remaining>0);
            GestureDescription next=new GestureDescription.Builder().addStroke(held).build();
            busy=dispatchGesture(next,this,main);
        }
        @Override public void onCancelled(GestureDescription gesture){busy=false;if(ticket==generation)nextAttack=0;}
    }
    boolean play(Rect expected,FarmVision.Observation o,FarmPolicy.Brawler b){
        if(busy||active!=this||expected==null)return false;
        Rect real=gameWindow();if(!expected.equals(real))return false;
        long now=SystemClock.uptimeMillis();
        GestureDescription.Builder gesture=new GestureDescription.Builder();int count=0;
        long abilityDue=nextAbility,attackDue=nextAttack;
        Charge charge=null;
        if(o.tap){gesture.addStroke(stroke(real,o.tapX,o.tapY,0,0,55));count++;}
        else if(o.decision!=null){
            FarmPolicy.Decision d=o.decision;
            long duration=STEP_MS;double ax=0,ay=0;long attackMs=0;
            if(now>=abilityDue&&(d.hyper||d.superAttack||d.gadget)){
                ax=d.hyper?700:d.superAttack?755:820;ay=d.hyper?450:d.superAttack?440:495;
                attackMs=55;abilityDue=now+750;
            }else if(d.attack&&now>=attackDue){
                ax=862.5;ay=400;attackMs=b.holdSeconds>0?Math.min(3000,Math.max(55,Math.round(1000*b.holdSeconds))):55;
                attackDue=now+attackMs+160;
            }
            double dist=Math.hypot(d.dx,d.dy);
            if(dist>1){gesture.addStroke(stroke(real,110,435,d.dx/dist*48,d.dy/dist*48,duration));count++;}
            if(attackMs>0){
                charge=new Charge(real,path(real,ax,ay,0,0),attackMs);
                gesture.addStroke(charge.held);count++;
            }
        }
        if(count==0)return false;
        long ticket=generation;
        busy=dispatchGesture(gesture.build(),charge!=null?charge:new GestureResultCallback(){
            @Override public void onCompleted(GestureDescription g){busy=false;}
            @Override public void onCancelled(GestureDescription g){busy=false;if(ticket==generation)nextAttack=0;}
        },main);
        if(busy){nextAbility=abilityDue;nextAttack=attackDue;}
        return busy;
    }
}
