package com.irisai.bshack;

import android.app.*;
import android.content.*;
import android.os.*;
import android.provider.Settings;
import android.widget.Toast;
import com.irisai.bshack.update.PatchStore;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Typed Android authorization client; no dynamic Java proxies or game touch hooks. */
final class LicenseClient {
 private static final String URL_CHECK="https://bshack-license-panel.jungeunchan0806.chatgpt.site/api/v1/license/check";
 private final Application app;
 private final SharedPreferences prefs;
 private final Handler main=new Handler(Looper.getMainLooper());
 private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"iris-license");t.setDaemon(true);return t;});
 private String hwid="",error="",status="unregistered";
 private boolean ready,checked,busy,consentDialog;
 private long until,request,completed;
 LicenseClient(Application app){
  this.app=app;prefs=app.getSharedPreferences("bshack_license_v1",0);
  try{
   String id=Settings.Secure.getString(app.getContentResolver(),Settings.Secure.ANDROID_ID);
   if(id==null||id.isEmpty()){id=prefs.getString("fallback_id",null);if(id==null){id=UUID.randomUUID().toString();prefs.edit().putString("fallback_id",id).commit();}}
   byte[] bytes=MessageDigest.getInstance("SHA-256").digest(("bshack-license-v1|"+app.getPackageName()+"|"+id).getBytes(StandardCharsets.UTF_8));
   StringBuilder s=new StringBuilder("BS-");for(byte b:bytes)s.append(String.format(Locale.US,"%02X",b&255));hwid=s.toString();ready=true;
  }catch(Exception e){error="HWID 초기화: "+e.getClass().getSimpleName();}
  main.post(this::refreshTimer);
 }
 private void refreshTimer(){if(ready&&prefs.getBoolean("license_consent_v1",false))refresh();main.postDelayed(this::refreshTimer,60000);}
 synchronized String state(){
  try{return new JSONObject().put("ready",ready).put("checked",checked).put("pending",busy||consentDialog)
   .put("remainingMs",Math.max(0,until-SystemClock.elapsedRealtime())).put("status",status).put("error",error)
   .put("completed",completed).toString();}catch(Exception e){return "{}";}
 }
 long request(Activity activity){
  request++;error="";
  if(!ready){completed=request;return request;}
  if(until>SystemClock.elapsedRealtime()){completed=request;return request;}
  if(prefs.getBoolean("license_consent_v1",false)){refresh();return request;}
  if(activity==null||activity.isFinishing()){error="게임 화면 준비 중";completed=request;return request;}
  if(!consentDialog){
   consentDialog=true;
   new AlertDialog.Builder(activity).setTitle("HWID 구독 확인")
    .setMessage("등록된 구독을 확인하려면 이 앱 전용 HWID를 인증 서버로 전송합니다.\n\n"+hwid)
    .setPositiveButton("확인",(d,w)->{prefs.edit().putBoolean("license_consent_v1",true).apply();consentDialog=false;refresh();})
    .setNegativeButton("닫기",(d,w)->{consentDialog=false;completed=request;})
    .setOnCancelListener(d->{consentDialog=false;completed=request;}).show();
  }
  return request;
 }
 void copy(){((ClipboardManager)app.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("BSHACK HWID",hwid));Toast.makeText(app,"HWID를 복사했습니다.",Toast.LENGTH_SHORT).show();}
 private void refresh(){
  if(busy||!ready)return;busy=true;
  worker.execute(()->{
   JSONObject response=null;String failure="";HttpURLConnection c=null;
   try{
    c=(HttpURLConnection)new URL(URL_CHECK).openConnection();c.setConnectTimeout(7000);c.setReadTimeout(7000);c.setInstanceFollowRedirects(false);
    c.setRequestMethod("POST");c.setDoOutput(true);c.setUseCaches(false);c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Accept","application/json");
    byte[] bytes=new JSONObject().put("hwid",hwid).toString().getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(bytes.length);
    try(OutputStream out=c.getOutputStream()){out.write(bytes);}
    int code=c.getResponseCode();if(code!=200)throw new IOException("HTTP "+code);
    response=new JSONObject(new String(PatchStore.read(c.getInputStream(),4096),StandardCharsets.UTF_8));
   }catch(Exception e){failure=e.getClass().getSimpleName()+": "+(e.getMessage()==null?"":e.getMessage());}
   finally{if(c!=null)c.disconnect();}
   final JSONObject data=response;final String networkError=failure;
   main.post(()->{
    try{
     error=networkError;
     if(data!=null){
      long leaseMillis=AuthorizationLease.millis(data.toString());
      if(leaseMillis>0){
       until=SystemClock.elapsedRealtime()+leaseMillis;status="active";
      }else{until=0;status=data.optString("status","unregistered");}
      checked=true;
     }
    }catch(Exception e){error="인증 응답 오류: "+e.getMessage();until=0;}
    finally{busy=false;completed=request;}
   });
  });
 }
}
