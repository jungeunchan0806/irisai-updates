package com.irisai.bshack.check;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.SystemClock;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Regressions for UI-only startup, blocked update networking and failed marker I/O. */
final class HealthChecks {
 private static final String READY="{\"game\":false,\"foreground\":true,\"auth\":{\"ready\":true}}";
 private static Field field(Class<?> c,String name)throws Exception{Field f=c.getDeclaredField(name);f.setAccessible(true);return f;}
 private static void require(boolean value,String label,List<String> checks){if(!value)throw new AssertionError(label);checks.add(label);}
 private static void write(File file,String text)throws Exception{try(FileOutputStream out=new FileOutputStream(file)){out.write(text.getBytes(StandardCharsets.UTF_8));}}
 private static void remove(File f){if(f.isDirectory())for(File child:f.listFiles())remove(child);f.delete();}
 private static void call(Class<?> c,String name,Class<?>[] args,Object...values){try{c.getMethod(name,args).invoke(null,values);}catch(Exception e){throw new AssertionError(e);}}
 private static void tick(Class<?> c){call(c,"recordState",new Class<?>[]{String.class},READY);call(c,"update",new Class<?>[]{boolean.class,String.class},false,"게임 로딩 중");}
 private static boolean value(Instrumentation i,Object host,Field field){AtomicBoolean b=new AtomicBoolean();i.runOnMainSync(()->{try{b.set(field.getBoolean(host));}catch(Exception e){throw new AssertionError(e);}});return b.get();}
 private static void reset(Instrumentation i,Class<?> type,Object host,long version)throws Exception{
  Field healthy=field(type,"markedHealthy"),pending=field(type,"healthPending"),responses=field(type,"healthyResponses"),next=field(type,"nextHealthAttempt");
  i.runOnMainSync(()->{try{healthy.setBoolean(host,false);pending.setBoolean(host,false);responses.setInt(host,0);next.setLong(host,0);call(type,"configure",new Class<?>[]{long.class,String.class,String.class},version,"fixture","");}catch(Exception e){throw new AssertionError(e);}});
 }
 static void run(Instrumentation i,Activity activity,Class<?> type,List<String> checks)throws Exception{
  Object host=field(type,"host").get(null),manager=field(type,"updates").get(host);
  ExecutorService network=(ExecutorService)field(manager.getClass(),"worker").get(manager);
  CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
  network.execute(()->{entered.countDown();try{release.await(30,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
  require(entered.await(5,TimeUnit.SECONDS),"fixture blocks updater network executor",checks);
  File root=new File(activity.getFilesDir(),"iris-updates"),pending=new File(root,"pending");
  Field healthy=field(type,"markedHealthy"),inFlight=field(type,"healthPending");
  try{
   remove(root);root.mkdirs();write(pending,"22002");reset(i,type,host,22002);
   i.runOnMainSync(()->{
    call(type,"recordState",new Class<?>[]{String.class},"{\"foreground\":false,\"auth\":{\"ready\":true}}");
    call(type,"update",new Class<?>[]{boolean.class,String.class},false,"게임 로딩 중");
    call(type,"recordError",new Class<?>[]{String.class,String.class},"fixture","bridge is not ready");
    call(type,"update",new Class<?>[]{boolean.class,String.class},false,"UI 연결 오류");
   });
   SystemClock.sleep(100);require(pending.exists(),"errors and background UI do not acknowledge startup",checks);
   i.runOnMainSync(()->tick(type));SystemClock.sleep(100);
   require(pending.exists(),"one response alone does not acknowledge startup",checks);
   i.runOnMainSync(()->tick(type));
   long deadline=SystemClock.uptimeMillis()+2000;
   while(!value(i,host,healthy)&&SystemClock.uptimeMillis()<deadline)SystemClock.sleep(20);
   require(value(i,host,healthy)&&!pending.exists(),"responsive UI commits startup without game readiness or network completion",checks);
   require(release.getCount()==1,"health acknowledgement uses an independent executor",checks);

   reset(i,type,host,22003);write(pending,"22003");
   File highest=new File(root,"highest");remove(highest);highest.mkdirs();write(new File(highest,"block"),"fixture");
   i.runOnMainSync(()->{tick(type);tick(type);});
   deadline=SystemClock.uptimeMillis()+2000;
   while(value(i,host,inFlight)&&SystemClock.uptimeMillis()<deadline)SystemClock.sleep(20);
   require(pending.exists()&&!value(i,host,healthy),"failed marker write is not reported as healthy",checks);
   remove(highest);deadline=SystemClock.uptimeMillis()+6000;
   while(!value(i,host,healthy)&&SystemClock.uptimeMillis()<deadline){i.runOnMainSync(()->tick(type));SystemClock.sleep(100);}
   require(value(i,host,healthy)&&!pending.exists(),"temporary marker I/O failure retries and commits",checks);
  }finally{release.countDown();remove(root);reset(i,type,host,23000);}
 }
}
