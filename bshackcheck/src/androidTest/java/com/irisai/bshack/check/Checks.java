package com.irisai.bshack.check;
import android.app.*;
import android.content.Intent;
import android.os.*;
import android.view.*;
import android.widget.TextView;
import dalvik.system.InMemoryDexClassLoader;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import com.supercell.brawlstars.GameApp;
public final class Checks extends Instrumentation {
 Class<?> host; GameApp activity; List<String> checks = new ArrayList<>();
 @Override public void onCreate(Bundle args) { super.onCreate(args);start(); }
 private Object call(String name, Class<?>[] types, Object... args) { try { return host.getMethod(name,types).invoke(null,args); } catch(Exception e) { throw new AssertionError(e); } }
 private int poll() { return (Integer) call("poll",new Class<?>[]{}); }
 private void require(boolean value,String label) { if(!value)throw new AssertionError(label);checks.add(label); }
 private void ui(Runnable r) { java.util.concurrent.atomic.AtomicReference<Throwable> e=new java.util.concurrent.atomic.AtomicReference<>();runOnMainSync(()->{try{r.run();}catch(Throwable t){e.set(t);}});if(e.get()!=null)throw new AssertionError(e.get()); }
 private View find(View v,String value) { if(value.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()))return v;if(v instanceof TextView&&value.contentEquals(((TextView)v).getText()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View r=find(((ViewGroup)v).getChildAt(i),value);if(r!=null)return r;}return null; }
 private View root(){return activity.findViewById(android.R.id.content);}
 @Override public void onStart() {
  Bundle result=new Bundle();try{
   getTargetContext().getSharedPreferences("iris_control_panel_v1",0).edit().clear().commit();
   byte[] bytes;try(InputStream in=getTargetContext().getAssets().open("iris-panel.dex");ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);bytes=out.toByteArray();}
   host=new InMemoryDexClassLoader(ByteBuffer.wrap(bytes),getTargetContext().getClassLoader()).loadClass("com.irisai.bshack.PanelHost");
   activity=(GameApp)startActivitySync(new Intent(getTargetContext(),GameApp.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
   ui(()->{call("initialize",new Class<?>[]{Application.class},activity.getApplication());call("attach",new Class<?>[]{Activity.class},activity);while(poll()!=0){};});waitForIdleSync();SystemClock.sleep(250);
   ui(()->{
    View toggle=find(root(),"오토골 켜기 또는 끄기"),opener=find(root(),"Iris 설정 열기");
    require(toggle!=null&&toggle.isShown(),"in-memory DEX attaches UI to game-shaped Activity");
    require(Math.abs(opener.getWidth()-toggle.getWidth()*2)<=1&&Math.abs(opener.getHeight()-toggle.getHeight()*2)<=1,"opener 2x toggle dimensions within pixel rounding");
    toggle.performClick();require(poll()==1&&poll()==0,"tap queues one engine toggle");
    toggle.performLongClick();require(poll()==3,"long press requests original HWID copy");
    call("update",new Class<?>[]{boolean.class,String.class},true,"골대로 운반");require(((TextView)toggle).getText().toString().contains("ON"),"native state updates HUD");
    opener.performClick();require(find(root(),"키 등록")==null,"buyer key registration absent from menu");
    require(find(root(),"스트리머 모드")==null,"streamer mode absent from menu");
    require(find(root(),"조작 위치")==null,"no coordinate calibration in native engine mode");
    ((View)find(root(),"UI 전부 숨기기").getParent()).performClick();require(!toggle.isShown(),"hide all hides toggle");require(poll()==0,"hiding HUD does not toggle engine");
    opener.performClick();require(find(root(),"UI 다시 표시").isShown(),"invisible opener recovers hidden UI");
    ((View)find(root(),"언어").getParent()).performClick();require(find(root(),"IRIS  /  CONTROL CENTER")!=null,"language switching works");
   });
   ui(()->activity.finish());
   java.util.concurrent.atomic.AtomicBoolean active=new java.util.concurrent.atomic.AtomicBoolean(true);
   long deadline=SystemClock.uptimeMillis()+5000;
   while(active.get()&&SystemClock.uptimeMillis()<deadline){waitForIdleSync();ui(()->active.set((Boolean)call("isResumed",new Class<?>[]{})));if(active.get())SystemClock.sleep(50);}
   ui(()->{require(!(Boolean)call("isResumed",new Class<?>[]{}),"activity destruction clears foreground state");require(poll()==2,"leaving game queues engine stop");});
   result.putString("stream","PASS "+checks.size()+" in-memory payload checks\n"+String.join("\n",checks)+"\n");finish(Activity.RESULT_OK,result);
  }catch(Throwable e){result.putString("stream","FAIL "+e+"\n");finish(Activity.RESULT_CANCELED,result);}
 }
}
