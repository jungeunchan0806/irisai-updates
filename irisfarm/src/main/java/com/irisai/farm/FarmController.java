package com.irisai.farm;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Own-process capture and GameApp JNI input. No ADB, system overlay or accessibility. */
public final class FarmController implements AutoCloseable {
    public interface Host {
        boolean allowed();
        boolean suspended();
        boolean autoGoalPriority();
        boolean nativeAutoFire();
        void state(boolean enabled,String phase,String brawler);
    }
    private final Activity activity;
    private final Host host;
    private final SharedPreferences prefs;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->new Thread(r,"Iris-Farm"));
    private final JSONObject brawlers;
    private Method input,view,loaded;
    private SurfaceView surface;
    private FarmVision vision; // Worker-owned.
    private boolean enabled,closed,busy,ready,moving,attacking,tapping;
    private long epoch,lastMove,lastAttack,holdAt,lastAbility,nextTap,tapSequence;
    private int moveX,moveY;
    private String selected,phase="브롤러를 선택하세요",lastStage="";
    private int stageFrames;
    private boolean repeat;
    private boolean connectionPaused;
    private long inputEpoch;
    private FarmPolicy.Brawler profile;
    private final Runnable tick=this::tick;

    public FarmController(Activity activity,Host host)throws Exception {
        this.activity=activity;this.host=host;prefs=activity.getSharedPreferences("iris_farm_v1",0);
        selected=prefs.getString("brawler","");repeat=prefs.getBoolean("repeat",true);
        brawlers=new JSONObject(new String(FarmVision.read(activity,"brawlers.json"),StandardCharsets.UTF_8));
        Class<?> game=Class.forName("com.supercell.titan.GameApp",false,activity.getClassLoader());
        input=game.getMethod("nOnTouchEvent",int.class,int.class,int.class,int.class);
        view=game.getMethod("getView");loaded=game.getMethod("isNativeLibraryLoaded");
        publish();
    }
    public boolean enabled(){return enabled;}
    public void setConnectionPaused(boolean paused){
        if(connectionPaused==paused)return;connectionPaused=paused;inputEpoch++;
        if(paused)cancelInputs();
        if(enabled){phase=paused?"연결 복구 대기":"화면 다시 확인 중";publish();}
    }
    public void toggle(){if(enabled)stop("중지됨");else start();}
    private void start(){
        if(closed||!host.allowed())return;
        if(!brawlers.has(selected)){phase="설정에서 현재 브롤러를 선택하세요";publish();return;}
        try {
            if(!Boolean.TRUE.equals(loaded.invoke(null))){phase="게임 로딩 후 다시 켜세요";publish();return;}
            surface=(SurfaceView)view.invoke(activity);
            if(!surfaceReady()){phase="게임 화면 준비 중";publish();return;}
            profile=profile(selected);enabled=true;ready=false;long ticket=++epoch;phase="Iris 모델 준비 중";publish();
            worker.execute(()->{
                try {if(vision!=null)vision.close();vision=new FarmVision(activity.getApplicationContext(),profile);
                    ui.post(()->{if(current(ticket)){ready=true;phase="화면 확인 중";publish();ui.post(tick);}});
                }catch(Exception|LinkageError e){ui.post(()->{if(current(ticket))stop("모델 오류: "+e.getClass().getSimpleName());});}
            });
        }catch(Exception|LinkageError e){stop("게임 입력 연결 오류: "+e.getClass().getSimpleName());}
    }
    private FarmPolicy.Brawler profile(String name)throws JSONException {
        JSONObject v=brawlers.getJSONObject(name);FarmPolicy.Brawler b=new FarmPolicy.Brawler();b.name=name;
        b.safeRange=Math.floor(v.getDouble("safe_range")*.5);b.attackRange=Math.floor(v.getDouble("attack_range")*.5);b.superRange=Math.floor(v.getDouble("super_range")*.5);
        b.superType=v.optString("super_type","damage");b.holdSeconds=v.optDouble("hold_attack",0);
        b.throughWalls=v.optBoolean("ignore_walls_for_attacks");b.superThroughWalls=v.optBoolean("ignore_walls_for_supers");return b;
    }
    private boolean surfaceReady(){return surface!=null&&surface.isAttachedToWindow()&&surface.isShown()&&surface.getHolder().getSurface().isValid()&&surface.getWidth()>surface.getHeight()&&surface.getHeight()>0;}
    private boolean current(long ticket){return !closed&&enabled&&epoch==ticket;}
    private boolean permitted(long ticket){return current(ticket)&&!connectionPaused&&host.allowed()&&!host.suspended()&&activity.hasWindowFocus()&&surfaceReady();}
    private void tick(){
        if(closed||!enabled)return;
        if(!host.allowed()){stop("인증 또는 게임 연결을 확인하세요");return;}
        long now=SystemClock.uptimeMillis();
        if(!permitted(epoch)){cancelInputs();phase=connectionPaused?"연결 복구 대기":"메뉴를 닫으면 계속합니다";publish();}
        else if(ready&&!busy)capture();
        if(moving&&now-lastMove>1000)cancelInputs();
        if(attacking&&now-holdAt>Math.min(5000,(long)(profile.holdSeconds*1000+1500)))cancelInputs();
        if(enabled)ui.postDelayed(tick,150);
    }
    private void capture(){
        final long ticket=epoch,inputs=inputEpoch,at=SystemClock.uptimeMillis();final int width=surface.getWidth(),height=surface.getHeight();final float scaleX=surface.getScaleX(),scaleY=surface.getScaleY();final boolean repeatNow=repeat;
        Bitmap bitmap=Bitmap.createBitmap(960,540,Bitmap.Config.ARGB_8888);busy=true;
        try {PixelCopy.request(surface,bitmap,result->{
            if(result!=PixelCopy.SUCCESS||!permitted(ticket)||inputs!=inputEpoch){bitmap.recycle();busy=false;
                if(current(ticket)&&result!=PixelCopy.SUCCESS){phase="화면 읽기 재시도 · "+result;publish();}return;}
            worker.execute(()->{
                FarmVision.Observation observation=null;Throwable error=null;
                try{observation=vision.observe(bitmap,at/1000.,repeatNow);}catch(Exception|LinkageError e){error=e;}finally{bitmap.recycle();}
                final FarmVision.Observation outcome=observation;final Throwable problem=error;
                ui.post(()->{busy=false;if(!current(ticket))return;
                    if(problem!=null){stop("화면 분석 오류: "+problem.getClass().getSimpleName());return;}
                    if(!permitted(ticket)||inputs!=inputEpoch){cancelInputs();return;}
                    if(surface.getWidth()!=width||surface.getHeight()!=height||surface.getScaleX()!=scaleX||surface.getScaleY()!=scaleY){cancelInputs();phase="화면 크기 변경 · 다시 분석 중";publish();return;}
                    long age=SystemClock.uptimeMillis()-at;
                    if(age>1200){cancelInputs();phase="화면 분석 지연 · "+age+"ms";publish();return;}
                    if(outcome!=null)apply(outcome,ticket);
                });
            });
        },ui);}catch(RuntimeException e){bitmap.recycle();busy=false;stop("게임 화면 읽기 오류");}
    }
    private void apply(FarmVision.Observation o,long ticket){
        long now=SystemClock.uptimeMillis();stageFrames=o.state.equals(lastStage)?stageFrames+1:1;lastStage=o.state;
        try {
            if(!o.state.equals("battle")){
                cancelInputs();
                phase=o.state.equals("lobby")?"로비 · 다음 경기 대기":o.state.startsWith("result_")?"경기 종료":o.state.equals("selection")?"게임에서 브롤러를 선택하세요":o.state.equals("player_missing")?"캐릭터 찾는 중":"로딩 또는 재개 대기";
                if(o.tap&&stageFrames>=2&&now>=nextTap){tap(o.tapX,o.tapY,ticket);nextTap=now+3000;phase="다음 경기 준비";}
                publish();return;
            }
            if(host.autoGoalPriority()){cancelInputs();phase="오토골 동작 우선";publish();return;}
            FarmPolicy.Decision d=o.decision;phase=d.phase;move(d.dx,d.dy);
            // Abilities precede normal taps, as in aggressive_universal. Otherwise
            // a new attack tap on every frame can starve super/gadget forever.
            if(!attacking&&!tapping&&now-lastAbility>700){
                if(d.hyper){tap(700,450,ticket);lastAbility=now;}
                else if(d.superAttack){tap(755,440,ticket);lastAbility=now;}
                else if(d.gadget){tap(820,495,ticket);lastAbility=now;}
            }
            if(host.nativeAutoFire()){if(attacking)cancelInputs();}
            else if(d.attack&&now-lastAttack>=200&&!tapping){
                if(profile.holdSeconds<=0){tap(862.5,400,ticket);lastAttack=now;}
                else if(!attacking){attacking=true;holdAt=now;send(0,862.5,400,8);}
                else if(now-holdAt>=profile.holdSeconds*1000){send(1,862.5,400,8);attacking=false;lastAttack=now;}
            } else if(!d.attack&&attacking)cancelInputs();
            publish();
        }catch(Exception|LinkageError e){stop("입력 오류: "+e.getClass().getSimpleName());}
    }
    private void move(double dx,double dy)throws Exception {
        double length=Math.hypot(dx,dy);
        if(length<1){if(moving){send(1,moveX,moveY,7);moving=false;}return;}
        if(!moving){moving=true;send(0,110,435,7);}
        moveX=(int)(110+dx/length*48);moveY=(int)(435+dy/length*48);send(2,moveX,moveY,7);lastMove=SystemClock.uptimeMillis();
    }
    private void tap(double x,double y,long ticket)throws Exception {
        if(tapping||!permitted(ticket))return;tapping=true;long gesture=++tapSequence;send(0,x,y,9);
        ui.postDelayed(()->{if(!tapping||gesture!=tapSequence)return;if(!permitted(ticket)){cancelInputs();return;}
            try{send(1,x,y,9);tapping=false;}catch(Exception|LinkageError e){stop("입력 해제 오류");}},80);
    }
    private void send(int type,double x,double y,int pointer)throws Exception {
        if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("Main-thread game input required");
        // GL2JNISurfaceView overrides these getters with its native-resolution
        // touch scale; match its original onTouchEvent conversion exactly.
        float scaleX=surface.getScaleX(),scaleY=surface.getScaleY();
        if(!Float.isFinite(scaleX)||!Float.isFinite(scaleY)||scaleX<=0||scaleY<=0)throw new IllegalStateException("Invalid game input scale");
        input.invoke(null,type,(int)(x*surface.getWidth()/960.*scaleX),(int)(y*surface.getHeight()/540.*scaleY),pointer);
    }
    private void cancelInputs(){
        if(!moving&&!attacking&&!tapping)return;moving=attacking=tapping=false;tapSequence++;
        try{input.invoke(null,3,0,0,0);}catch(Exception|LinkageError ignored){}
    }
    public void stop(String reason){enabled=false;ready=false;epoch++;ui.removeCallbacks(tick);cancelInputs();phase=reason;publish();}
    public void pause(){if(enabled)stop("게임으로 돌아오면 다시 켜세요");}
    private void publish(){host.state(enabled,phase,selected);}
    public void settings(boolean english){
        ArrayList<String> names=new ArrayList<>();Iterator<String> it=brawlers.keys();while(it.hasNext())names.add(it.next());Collections.sort(names);
        LinearLayout body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(28,16,28,16);
        TextView help=new TextView(activity);help.setText(english?"Choose the brawler currently selected in the game. Iris uses its original range-aware combat policy.":"게임에서 현재 선택한 브롤러를 맞춰 주세요. Iris 원본의 사거리 기반 전투 판단을 사용합니다.");body.addView(help);
        Spinner spinner=new Spinner(activity);ArrayList<String> choices=new ArrayList<>();choices.add(english?"Select brawler":"브롤러 선택");choices.addAll(names);
        spinner.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,choices));spinner.setSelection(Math.max(0,choices.indexOf(selected)));body.addView(spinner);
        CheckBox again=new CheckBox(activity);again.setText(english?"Automatically start the next match":"로비·결과 화면에서 다음 경기 시작");again.setChecked(repeat);body.addView(again);
        new AlertDialog.Builder(activity).setTitle(english?"Iris Farm settings":"Iris 오토팜 설정").setView(body)
            .setPositiveButton(english?"Save":"저장",(d,w)->{stop("설정 저장됨 · 다시 켜세요");selected=spinner.getSelectedItemPosition()>0?choices.get(spinner.getSelectedItemPosition()):"";repeat=again.isChecked();prefs.edit().putString("brawler",selected).putBoolean("repeat",repeat).apply();publish();})
            .setNegativeButton(english?"Close":"닫기",null).show();
    }
    public void close(){if(closed)return;stop("종료");closed=true;worker.execute(()->{if(vision!=null){vision.close();vision=null;}});worker.shutdown();}
}
