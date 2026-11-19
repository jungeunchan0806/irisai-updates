package com.irisai.farm;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.*;
import android.media.ImageReader;
import android.media.projection.*;
import android.os.*;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.*;
import android.widget.Toast;
import java.util.Locale;

public final class CaptureService extends Service implements VisionLoop.Output,Overlay.Actions {
    static CaptureService live;
    private static final String END="com.irisai.externalplayer.END";
    private final Handler main=new Handler(Looper.getMainLooper());
    private MediaProjection projection;
    private VirtualDisplay display;
    private volatile ImageReader reader;
    private VisionLoop loop;
    private Overlay overlay;
    private Profiles profiles;
    private FarmPolicy.Brawler profile;
    private boolean armed,ending,visible=true;
    private int realW,realH,captureW,captureH,density,epoch;
    private Rect game,activeCrop;
    private long nextTap;
    private String stage="",phase="브롤러를 선택한 후 시작하세요",stats="캡처 · 추적 · 전체 분석 속도 측정 대기";
    private int confirmations;
    private final Runnable poll=new Runnable(){public void run(){if(ending)return;refreshWindow();main.postDelayed(this,240);}};
    private final Runnable checkWindow=this::refreshWindow;
    private final MediaProjection.Callback callback=new MediaProjection.Callback(){
        @Override public void onStop(){end();}
        @Override public void onCapturedContentResize(int w,int h){if(!ending&&w>0&&h>0)resize(w,h);}
        @Override public void onCapturedContentVisibilityChanged(boolean on){visible=on;refreshWindow();}
    };
    private final DisplayManager.DisplayListener listener=new DisplayManager.DisplayListener(){
        public void onDisplayAdded(int id){} public void onDisplayRemoved(int id){if(id==Display.DEFAULT_DISPLAY)end();}
        public void onDisplayChanged(int id){if(id==Display.DEFAULT_DISPLAY&&Build.VERSION.SDK_INT<34&&!ending){DisplayMetrics m=metrics();resize(m.widthPixels,m.heightPixels);}}
    };
    private DisplayMetrics metrics(){DisplayMetrics m=new DisplayMetrics();getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(m);return m;}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null||END.equals(intent.getAction())){end();return START_NOT_STICKY;}
        if(projection!=null)return START_NOT_STICKY;
        try{
            NotificationManager nm=getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("capture","화면 공유",NotificationManager.IMPORTANCE_LOW));
            PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,CaptureService.class).setAction(END),PendingIntent.FLAG_IMMUTABLE);
            Notification note=new Notification.Builder(this,"capture").setSmallIcon(R.drawable.iris).setContentTitle("IrisAI 화면 공유 중")
                    .setContentText("원형 버튼에서 플레이를 설정할 수 있습니다.").setOngoing(true)
                    .addAction(new Notification.Action.Builder(null,"종료",stop).build()).build();
            startForeground(41,note,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            if(!Settings.canDrawOverlays(this))throw new IllegalStateException("다른 앱 위에 표시 권한이 필요합니다.");
            Intent grant=intent.getParcelableExtra("grant");
            if(grant==null)throw new IllegalStateException("화면 공유 승인이 필요합니다.");
            projection=getSystemService(MediaProjectionManager.class).getMediaProjection(intent.getIntExtra("code",0),grant);
            if(projection==null)throw new IllegalStateException("화면 공유가 취소됐습니다.");
            projection.registerCallback(callback,main);
            System.loadLibrary("opencv_java4");
            profiles=new Profiles(this);loop=new VisionLoop(this,this);
            Display device=getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
            Context window=createDisplayContext(device).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null);
            overlay=new Overlay(new ContextThemeWrapper(window,android.R.style.Theme_Material_Light_NoActionBar),profiles,this);
            DisplayMetrics m=metrics();density=m.densityDpi;
            resize(m.widthPixels,m.heightPixels);
            if(ending)return START_NOT_STICKY;
            getSystemService(DisplayManager.class).registerDisplayListener(listener,main);
            live=this;main.post(poll);
        }catch(Exception|LinkageError ex){Toast.makeText(this,"시작 실패 · "+ex.getMessage(),Toast.LENGTH_LONG).show();end();}
        return START_NOT_STICKY;
    }
    private void resize(int w,int h){
        if(ending||loop==null||projection==null||w<1||h<1||(w==realW&&h==realH))return;
        realW=w;realH=h;float scale=Math.min(1f,960f/Math.max(w,h));
        captureW=Math.max(2,Math.round(w*scale));captureH=Math.max(2,Math.round(h*scale));
        ImageReader old=reader;
        try{
            reader=ImageReader.newInstance(captureW,captureH,PixelFormat.RGBA_8888,3);
            reader.setOnImageAvailableListener(src->{if(src==reader)loop.image(src);},loop.frames);
            Surface s=reader.getSurface();
            try{s.setFrameRate(120f,Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);}catch(RuntimeException ignored){}
            if(display==null){
                display=projection.createVirtualDisplay("IrisAI",captureW,captureH,density,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,s,null,main);
                if(display==null)throw new IllegalStateException("공유 화면을 만들 수 없습니다.");
            }else{display.setSurface(null);display.resize(captureW,captureH,density);display.setSurface(s);}
            resetPipeline();
        }catch(RuntimeException ex){Toast.makeText(this,"화면 크기 변경 실패 · "+ex.getMessage(),Toast.LENGTH_LONG).show();end();}
        finally{if(old!=null){old.setOnImageAvailableListener(null,null);loop.frames.post(old::close);}}
    }
    private Rect region(Rect win){
        if(win==null||captureW==0||realW<=realH)return null;
        DisplayMetrics m=metrics();
        if(Math.abs(realW-m.widthPixels)<=3&&Math.abs(realH-m.heightPixels)<=3){
            Rect device=new Rect(0,0,realW,realH);if(!device.contains(win))return null;
            return new Rect(Math.round(win.left*captureW/(float)realW),Math.round(win.top*captureH/(float)realH),
                    Math.round(win.right*captureW/(float)realW),Math.round(win.bottom*captureH/(float)realH));
        }
        if(Math.abs(realW-win.width())<=3&&Math.abs(realH-win.height())<=3)return new Rect(0,0,captureW,captureH);
        return null;
    }
    void windowChanged(){if(!ending&&!main.hasCallbacks(checkWindow))main.post(checkWindow);}
    private void refreshWindow(){
        if(ending||overlay==null)return;
        if(!Settings.canDrawOverlays(this)){end();return;}
        Rect current=armed&&visible&&!overlay.opened()&&TouchService.active!=null?TouchService.active.gameWindow():null;
        Rect crop=region(current);
        if(!java.util.Objects.equals(current,game)||!java.util.Objects.equals(crop,activeCrop)){
            game=current;activeCrop=crop;configure();
        }
    }
    private void resetPipeline(){game=null;activeCrop=null;configure();refreshWindow();}
    private void configure(){
        epoch++;confirmations=0;stage="";nextTap=0;
        if(TouchService.active!=null)TouchService.active.invalidate();
        if(loop!=null)loop.configure(epoch,activeCrop,armed?profile:null,profiles!=null&&profiles.repeat());
        if(armed)phase=activeCrop==null?"게임 전면 화면 대기":"화면 인식 중";
        updateMenu();
    }
    @Override public void result(int ticket,FarmVision.Observation o,boolean full,long captured){
        if(ending||!armed||ticket!=epoch||game==null||overlay.opened()||SystemClock.elapsedRealtime()-captured>1200)return;
        if(full){confirmations=o.state.equals(stage)?confirmations+1:1;stage=o.state;}
        TouchService touch=TouchService.active;if(touch==null)return;
        if(o.decision!=null){phase=o.decision.phase;touch.play(game,o,profile);}
        else{
            phase=o.state.equals("lobby")?"로비":o.state.equals("player_missing")?"캐릭터 찾는 중":o.state.startsWith("result_")?"경기 결과":"화면 전환 대기";
            long now=SystemClock.elapsedRealtime();
            if(full&&o.tap&&profiles.repeat()&&confirmations>=2&&now>=nextTap&&touch.play(game,o,profile))nextTap=now+3000;
        }
    }
    @Override public void rates(int ticket,float c,float t,float a,long ms){
        if(ticket!=epoch)return;
        if(BuildConfig.DEBUG)android.util.Log.i("IrisExternal",String.format(Locale.ROOT,"capture=%.2f track=%.2f full=%.2f latency_ms=%d",c,t,a,ms));
        stats=String.format(Locale.KOREA,"최근 실측 FPS   캡처 %.1f  ·  추적 %.1f  ·  전체 분석 %.1f\n전체 분석 지연 %d ms",c,t,a,ms);
    }
    @Override public void failed(int ticket,String error){if(ticket!=epoch)return;stop();phase="인식 오류 · "+error;openMenu();}
    @Override public void open(){openMenu();}
    void openMenu(){
        if(ending||overlay==null||overlay.opened())return;
        String previous=phase;overlay.open(armed,previous,stats);resetPipeline();
        overlay.status(armed,armed?"설정 중 · "+previous:previous,stats);
    }
    @Override public void close(){if(overlay!=null){overlay.close();resetPipeline();}}
    @Override public void start(String name,boolean repeat){
        if(TouchService.active==null){phase="IrisAI 게임 터치 접근성을 먼저 켜세요.";updateMenu();return;}
        try{profiles.save(name,repeat);profile=profiles.profile();armed=true;stats="인식 속도 측정 중";close();}
        catch(Exception ex){phase="브롤러 설정 오류";updateMenu();}
    }
    @Override public void stop(){armed=false;phase="플레이 정지";resetPipeline();}
    @Override public void exit(){end();}
    private void updateMenu(){if(overlay!=null)overlay.status(armed,phase,stats);}
    private void end(){ending=true;stopSelf();}
    @Override public void onDestroy(){
        ending=true;live=null;armed=false;epoch++;main.removeCallbacksAndMessages(null);
        if(TouchService.active!=null)TouchService.active.invalidate();
        getSystemService(DisplayManager.class).unregisterDisplayListener(listener);
        if(projection!=null)projection.unregisterCallback(callback);
        if(display!=null)display.release();
        ImageReader last=reader;reader=null;
        if(last!=null){last.setOnImageAvailableListener(null,null);loop.frames.post(last::close);}
        if(loop!=null)loop.close();
        if(projection!=null)projection.stop();
        if(overlay!=null)overlay.destroy();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
    @Override public IBinder onBind(Intent i){return null;}
}
