package com.irisai.bshack.check;

import android.app.Instrumentation;
import android.os.SystemClock;
import com.supercell.brawlstars.GameApp;
import java.lang.reflect.*;
import java.util.List;
import java.util.concurrent.atomic.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Actual child-loaded ONNX/OpenCV, PixelCopy and lifecycle input tests. */
final class FarmChecks {
    private static void ui(Instrumentation i,Runnable task){AtomicReference<Throwable> failure=new AtomicReference<>();i.runOnMainSync(()->{try{task.run();}catch(Throwable e){failure.set(e);}});if(failure.get()!=null)throw new AssertionError(failure.get());}
    private static Object invoke(Object target,String name,Class<?>[] args,Object...values){try{return target.getClass().getMethod(name,args).invoke(target,values);}catch(Exception e){throw new AssertionError(e);}}
    private static void call(Object target,String name){invoke(target,name,new Class<?>[]{});}
    private static void require(boolean yes,String message,List<String> checks){if(!yes)throw new AssertionError(message);checks.add(message);}
    private static int count(Instrumentation i){AtomicInteger n=new AtomicInteger();ui(i,()->n.set(GameApp.inputs.size()));return n.get();}
    private static String windowState(Instrumentation i,GameApp activity){AtomicReference<String> state=new AtomicReference<>();ui(i,()->{android.view.SurfaceView v=activity.getView();state.set("focus="+activity.hasWindowFocus()+", attached="+v.isAttachedToWindow()+", shown="+v.isShown()+", valid="+v.getHolder().getSurface().isValid()+", size="+v.getWidth()+"x"+v.getHeight());});return state.get();}
    private static void awaitInput(Instrumentation i,AtomicReference<String> phase,int previous)throws Exception{
        long deadline=SystemClock.uptimeMillis()+25000;
        while(count(i)<=previous&&SystemClock.uptimeMillis()<deadline){if(phase.get().contains("오류"))throw new AssertionError(phase.get());SystemClock.sleep(100);}
        if(count(i)<=previous)throw new AssertionError("No game input; last farm state="+phase.get());
    }
    static void run(Instrumentation i,GameApp activity,Class<?> host,List<String> checks)throws Exception {
        AtomicBoolean focused=new AtomicBoolean();long focusDeadline=SystemClock.uptimeMillis()+5000;
        while(!focused.get()&&SystemClock.uptimeMillis()<focusDeadline){i.waitForIdleSync();ui(i,()->focused.set(activity.hasWindowFocus()));if(!focused.get())SystemClock.sleep(50);}
        require(focused.get(),"game window is focused before input checks: "+windowState(i,activity),checks);
        ClassLoader loader=host.getClassLoader();Class<?> type=loader.loadClass("com.irisai.farm.FarmController"),boundary=loader.loadClass("com.irisai.farm.FarmController$Host");
        AtomicBoolean allowed=new AtomicBoolean(false),suspended=new AtomicBoolean(false),priority=new AtomicBoolean(false);
        AtomicReference<String> phase=new AtomicReference<>("");AtomicReference<Object> controller=new AtomicReference<>();
        Object callback=Proxy.newProxyInstance(loader,new Class<?>[]{boundary},(proxy,method,args)->{
            switch(method.getName()){
                case "allowed":return allowed.get();case "suspended":return suspended.get();case "autoGoalPriority":return priority.get();case "nativeAutoFire":return false;
                case "state":phase.set((String)args[1]);return null;
                case "toString":return "Isolated farm input boundary";default:return null;
            }
        });
        ui(i,()->{
            activity.getSharedPreferences("iris_farm_v1",0).edit().putString("brawler","shelly").putBoolean("repeat",false).commit();
            activity.frame("held_turn");GameApp.inputs.clear();
            try{controller.set(type.getConstructor(android.app.Activity.class,boundary).newInstance(activity,callback));}catch(Exception e){throw new AssertionError(e);}
            call(controller.get(),"toggle");require(!(Boolean)invoke(controller.get(),"enabled",new Class<?>[]{}),"farm refuses to start without authorization",checks);
        });
        // Fail at the capture boundary instead of reporting a generic input
        // timeout when an emulator renderer cannot read its surface format.
        android.graphics.Bitmap capture=android.graphics.Bitmap.createBitmap(960,540,android.graphics.Bitmap.Config.ARGB_8888);
        AtomicInteger copied=new AtomicInteger(-1);CountDownLatch captured=new CountDownLatch(1);
        ui(i,()->android.view.PixelCopy.request(activity.getView(),capture,result->{copied.set(result);captured.countDown();},new android.os.Handler(android.os.Looper.getMainLooper())));
        require(captured.await(5,TimeUnit.SECONDS)&&copied.get()==android.view.PixelCopy.SUCCESS,"fixture PixelCopy succeeds, status="+copied.get(),checks);
        int first=capture.getPixel(0,0);boolean varied=false;
        for(int y=0;y<540&&!varied;y+=20)for(int x=0;x<960;x+=20)if(capture.getPixel(x,y)!=first){varied=true;break;}
        capture.recycle();require(varied,"fixture capture contains the recorded game frame",checks);
        Object farm=controller.get();try{
            allowed.set(true);ui(i,()->call(farm,"toggle"));awaitInput(i,phase,0);
            require(count(i)>0,"child-loaded ONNX and OpenCV read game SurfaceView and generate JNI-boundary input",checks);
            ui(i,()->{int[] down=GameApp.inputs.stream().filter(v->v[0]==0&&v[3]==7).findFirst().orElseThrow(()->new AssertionError("Movement never began"));
                require(down[1]==(int)(110*activity.getView().getWidth()/960.*.5)&&down[2]==(int)(435*activity.getView().getHeight()/540.*.75),"JNI input honors game's native-resolution scale",checks);
            });
            priority.set(true);SystemClock.sleep(1600);int stopped=count(i);SystemClock.sleep(450);
            require(count(i)==stopped,"farm yields all inputs while Auto Goal handles the ball",checks);
            ui(i,()->require(GameApp.inputs.get(GameApp.inputs.size()-1)[0]==3,"goal priority releases held virtual touches",checks));
            priority.set(false);awaitInput(i,phase,stopped);
            ui(i,()->invoke(farm,"setConnectionPaused",new Class<?>[]{boolean.class},true));
            SystemClock.sleep(500);stopped=count(i);SystemClock.sleep(450);
            require(count(i)==stopped,"network loss cancels held touches and discards in-flight farm inference",checks);
            require((Boolean)invoke(farm,"enabled",new Class<?>[]{}),"connection hold preserves farm intent while authorization remains valid",checks);
            ui(i,()->invoke(farm,"setConnectionPaused",new Class<?>[]{boolean.class},false));awaitInput(i,phase,stopped);
            suspended.set(true);SystemClock.sleep(500);stopped=count(i);SystemClock.sleep(450);
            require(count(i)==stopped,"opening menu suspends new farm inputs",checks);
            suspended.set(false);awaitInput(i,phase,stopped);
            allowed.set(false);SystemClock.sleep(500);stopped=count(i);SystemClock.sleep(800);
            require(count(i)==stopped,"expired authorization cancels input and rejects late inference",checks);
            ui(i,()->require(!(Boolean)invoke(farm,"enabled",new Class<?>[]{}),"farm is OFF after authorization loss",checks));
            allowed.set(true);ui(i,()->call(farm,"toggle"));awaitInput(i,phase,stopped);
            ui(i,()->call(farm,"pause"));stopped=count(i);SystemClock.sleep(900);
            require(count(i)==stopped,"activity pause releases input and rejects queued frames",checks);
        }catch(AssertionError e){throw new AssertionError(e.getMessage()+"; "+windowState(i,activity),e);}
        finally{ui(i,()->call(farm,"close"));}
    }
}
