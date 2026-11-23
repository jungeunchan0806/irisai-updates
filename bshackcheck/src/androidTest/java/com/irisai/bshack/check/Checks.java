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
 private void tap(int x, int y) {
  long down = SystemClock.uptimeMillis();
  for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
   MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
   event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
   try { sendPointerSync(event); } finally { event.recycle(); }
  }
  waitForIdleSync();
 }
 private void checkOpenerPosition() {
  int[] rootOrigin = new int[2];
  int[] point = new int[3];
  ui(() -> {
   View root = root();
   View opener = find(root, "Iris 설정 열기");
   View panel = (View) opener.getParent();
   int[] location = new int[2];
   panel.getLocationOnScreen(rootOrigin);
   opener.getLocationOnScreen(location);
   point[0] = rootOrigin[0] + panel.getWidth() / 2;
   point[1] = rootOrigin[1] + panel.getPaddingTop() + opener.getHeight() / 2;
   point[2] = rootOrigin[0] + panel.getWidth() - panel.getPaddingRight() - 8;
   require(Math.abs(location[0] + opener.getWidth() / 2 - point[0]) <= 1
       && location[1] == rootOrigin[1] + panel.getPaddingTop(), "transparent opener centered at original height");
  });
  tap(point[2], point[1]);
  ui(() -> require(!find(root(), "설정 닫기").isShown(), "old top-right tap leaves menu closed"));
  tap(point[0], point[1]);
  ui(() -> {
   View close = find(root(), "설정 닫기");
   require(close.isShown(), "top-center screen tap opens menu");
   close.performClick();
  });
 }
 @Override public void onStart() {
  Bundle result=new Bundle();try{
   getTargetContext().getSharedPreferences("iris_control_panel_v1",0).edit().clear().commit();
   byte[] bytes;try(InputStream in=getTargetContext().getAssets().open("iris-panel.dex");ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);bytes=out.toByteArray();}
   host=new InMemoryDexClassLoader(ByteBuffer.wrap(bytes),getTargetContext().getClassLoader()).loadClass("com.irisai.bshack.PanelHost");
   activity=(GameApp)startActivitySync(new Intent(getTargetContext(),GameApp.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
   ui(()->{call("initialize",new Class<?>[]{Application.class},activity.getApplication());call("attach",new Class<?>[]{Activity.class},activity);while(poll()!=0){};});waitForIdleSync();SystemClock.sleep(250);
   checkOpenerPosition();
   ui(()->{
    View toggle=find(root(),"오토골 켜기 또는 끄기"),opener=find(root(),"Iris 설정 열기");
    require(toggle!=null&&toggle.isShown(),"in-memory DEX attaches UI to game-shaped Activity");
    require(Math.abs(opener.getWidth()-toggle.getWidth()*2)<=1&&Math.abs(opener.getHeight()-toggle.getHeight()*2)<=1,"opener 2x toggle dimensions within pixel rounding");
    toggle.performClick();require(poll()==1&&poll()==0,"tap queues one engine toggle");
    require(!toggle.isLongClickable() && find(root(),"discord.gg/bshack")==null,"HUD contains only the toggle");
    call("update",new Class<?>[]{boolean.class,String.class},true,"골대로 운반");require(((TextView)toggle).getText().toString().contains("ON"),"native state updates HUD");
    opener.performClick();require(find(root(),"키 등록")==null,"buyer key registration absent from menu");
    require(find(root(),"HWID 복사")==null && find(root(),"언어")==null && find(root(),"Discord")==null && find(root(),"● 전체")==null,"menu has no extra commands or navigation tabs");
    require(find(root(),"조작 위치")==null,"no coordinate calibration in native engine mode");
    ((View)find(root(),"UI 전부 숨기기").getParent()).performClick();require(!toggle.isShown(),"hide all hides toggle");require(poll()==0,"hiding HUD does not toggle engine");
    opener.performClick();require(find(root(),"UI 다시 표시").isShown(),"invisible opener recovers hidden UI");
    ((View)find(root(),"UI 다시 표시").getParent()).performClick();
    find(root(),"설정 닫기").performClick(); require(toggle.isShown() && poll()==0,"restoring and closing the menu shows the HUD");
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
