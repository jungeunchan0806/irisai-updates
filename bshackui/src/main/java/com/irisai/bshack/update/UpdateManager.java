package com.irisai.bshack.update;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import java.io.*;
import java.net.*;
import java.lang.ref.WeakReference;
import java.util.concurrent.*;

/** Networking and signature verification never run on the game's main thread. */
public final class UpdateManager {
 private final Application app;
 private final PatchStore store;
 private final Handler main=new Handler(Looper.getMainLooper());
 private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"iris-patch");t.setDaemon(true);return t;});
 private WeakReference<Activity> activity=new WeakReference<>(null);
 private volatile boolean busy;
 private boolean startupChecked,english;
 private long version=UpdateConfig.VERSION;
 private String versionName=UpdateConfig.VERSION_NAME;
 private AlertDialog dialog;
 public UpdateManager(Application app){this.app=app;store=new PatchStore(app);}
 public void configure(long version,String name){this.version=version;this.versionName=name;}
 public void attach(Activity value,boolean english){activity=new WeakReference<>(value);this.english=english;}
 public void start(){if(startupChecked)return;startupChecked=true;main.postDelayed(()->check(false),5000);}
 public void healthy(){worker.execute(()->store.healthy(version));}
 private String tr(String ko,String en){return english?en:ko;}
 private Activity current(){Activity a=activity.get();return a!=null&&!a.isDestroyed()&&!a.isFinishing()?a:null;}
 private AlertDialog show(String title,String message,String button,Runnable action){
  Activity a=current();if(a==null)return null;
  if(dialog!=null&&dialog.isShowing())dialog.dismiss();
  AlertDialog d=new AlertDialog.Builder(a).setTitle(title).setMessage(message)
   .setPositiveButton(button,(v,w)->{if(action!=null)action.run();}).setCancelable(false).create();
  d.setCanceledOnTouchOutside(false);d.show();dialog=d;return d;
 }
 public void check(boolean manual){
  if(busy){if(manual)show(tr("업데이트","Update"),tr("업데이트 확인 또는 다운로드가 진행 중입니다.","An update check or download is in progress."),tr("확인","OK"),null);return;}
  busy=true;
  if(manual)show(tr("업데이트 확인","Check for updates"),tr("현재 버전 ","Current version ")+versionName+"\n"+tr("업데이트 정보를 확인하고 있습니다.","Checking for updates."),tr("확인","OK"),null);
  worker.execute(()->{
   try{
    byte[] manifest=get(UpdateConfig.CHANNEL,PatchStore.MAX_MANIFEST);
    PatchStore.Manifest m=PatchStore.verifyManifest(manifest,get(UpdateConfig.CHANNEL+".sig",2048));
    main.post(()->{
     busy=false;
     if(m.version<=version){if(manual)show(tr("업데이트 확인","Check for updates"),tr("최신 버전입니다. ","You are up to date. ")+versionName,tr("확인","OK"),null);return;}
     if(m.version==store.blockedVersion()){
      if(manual)show(tr("패치 복구됨","Patch rolled back"),tr("이 패치는 시작에 실패해 기본 버전으로 복구했습니다. 수정된 새 패치를 기다려 주세요.","This patch failed to start and was rolled back. Please wait for a corrected update."),tr("확인","OK"),null);
      return;
     }
     show(tr("업데이트 하시겠습니까?","Install the update?"),versionName+" → "+m.name+"\n\n"+m.notes+"\n\n"+tr("확인을 누르면 패치를 다운로드합니다. 적용하려면 게임을 다시 실행해야 합니다.","Confirm to download the patch. Restart the game to apply it."),tr("확인","OK"),()->download(m));
    });
   }catch(Exception e){main.post(()->{busy=false;if(manual)show(tr("업데이트 확인 실패","Update check failed"),safe(e),tr("확인","OK"),null);});}
  });
 }
 private void download(PatchStore.Manifest m){
  if(busy)return;busy=true;
  AlertDialog progress=show(tr("패치 다운로드","Downloading patch"),m.name+"\n"+tr("다운로드와 서명 검증을 진행합니다.","Downloading and verifying the patch."),tr("확인","OK"),null);
  worker.execute(()->{
   try{
    byte[] zip=get(m.url,PatchStore.MAX_PATCH);
    store.install(m,zip,version);
    main.post(()->{busy=false;
     show(tr("업데이트 준비 완료","Update ready"),tr("확인을 누르면 게임을 닫습니다. 앱을 다시 열면 새 패치가 적용됩니다. APK를 다시 설치할 필요가 없습니다.","Confirm to close the game. Reopen the app to apply the patch. No APK installation is needed."),tr("확인","OK"),()->{
      Activity a=current();if(a!=null)a.finishAffinity();main.postDelayed(()->android.os.Process.killProcess(android.os.Process.myPid()),350);
     });
    });
   }catch(Exception e){main.post(()->{busy=false;show(tr("업데이트 실패","Update failed"),safe(e)+"\n"+tr("현재 버전은 계속 사용할 수 있습니다.","Your current version is unchanged."),tr("확인","OK"),null);});}
  });
 }
 private static String safe(Exception e){String text=e.getMessage();return text==null?e.getClass().getSimpleName():text.substring(0,Math.min(350,text.length()));}
 public static byte[] get(String address,int limit)throws Exception{
  URL url=new URL(address);
  for(int redirects=0;redirects<5;redirects++){
   String host=url.getHost();
   if(!url.getProtocol().equals("https")||!(host.equals("github.com")||host.equals("release-assets.githubusercontent.com")||host.equals("objects.githubusercontent.com"))||(url.getPort()!=-1&&url.getPort()!=443))throw new SecurityException("업데이트 서버 주소가 올바르지 않습니다.");
   HttpURLConnection c=(HttpURLConnection)url.openConnection();c.setConnectTimeout(10000);c.setReadTimeout(20000);c.setInstanceFollowRedirects(false);c.setUseCaches(false);c.setRequestProperty("Accept","application/octet-stream");
   try{
    int code=c.getResponseCode();
    if(code==301||code==302||code==303||code==307||code==308){String next=c.getHeaderField("Location");if(next==null)throw new IOException("업데이트 리디렉션 오류");url=new URL(url,next);continue;}
    if(code!=200)throw new IOException("업데이트 서버 응답: HTTP "+code);
    if(c.getContentLengthLong()>limit)throw new IOException("패치 크기 제한을 초과했습니다.");
    return PatchStore.read(c.getInputStream(),limit);
   }finally{c.disconnect();}
  }throw new IOException("업데이트 주소 연결이 너무 많습니다.");
 }
}
