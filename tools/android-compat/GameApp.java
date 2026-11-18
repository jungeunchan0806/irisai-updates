package com.supercell.brawlstars;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import dalvik.system.InMemoryDexClassLoader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.util.concurrent.CompletableFuture;
import org.json.JSONObject;

/** An ordinary, offline test activity with the class name the released panel accepts.
 * The signed game engine is a patch store input only. It is never executed. */
public final class GameApp extends Activity {
    private static CompletableFuture<Boot> processBoot;
    private static String processToken;
    private static boolean configured;
    private static boolean requestedRecreation;
    private static boolean reported;
    private static final class Boot {
        final Class<?> panel;
        final String name, log, terminal;
        Boot(Class<?> panel, String name, String log, String terminal) {
            this.panel=panel; this.name=name; this.log=log; this.terminal=terminal;
        }
    }
    private final Handler main = new Handler(Looper.getMainLooper());
    private String variant, action, token;
    private int base, current;
    private File directory;
    private Object store;
    private Class<?> verifier, manifest, panelHost;
    private Boot preparedBoot;
    private boolean resumed, attached;
    private int inspections, foregroundReports;
    private long started;
    private String logResult = "not-applicable";

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(new FrameLayout(this));
        variant = getIntent().getStringExtra("variant");
        action = getIntent().getStringExtra("action");
        token = getIntent().getStringExtra("token");
        if (!"solo".equals(variant) && !"combined".equals(variant)) { fail(new IllegalArgumentException("variant")); return; }
        base = variant.equals("solo") ? 22000 : 23000;
        current = base + 4;
        directory = new File(getFilesDir(), "iris-updates");
        if (processBoot==null) {
            processToken=token;
            processBoot=new CompletableFuture<>();
            new Thread(()->{
                try { processBoot.complete(prepare()); }
                catch (Throwable error) { processBoot.completeExceptionally(error); }
            }).start();
        } else if (!processToken.equals(token)) {
            Log.e("IrisReleaseQA",token+" FAIL "+variant+" "+action+" scenario started in an existing process");
            return;
        }
        Log.i("IrisReleaseQA", token+" ACTIVITY "+action+" saved="+(saved!=null));
        if (reported) {
            if ("recreate".equals(action) && saved!=null) {
                Log.i("IrisReleaseQA",token+" TERMINAL-RECREATION");
            }
            return;
        }
        processBoot.whenComplete((boot,error)->main.post(()->{
            if (reported || isDestroyed() || isFinishing()) return;
            if (error!=null) {fail(error);return;}
            preparedBoot=boot;
            presentWhenResumed();
        }));
    }

    @Override protected void onResume() {
        super.onResume();
        resumed=true;
        main.post(this::presentWhenResumed);
    }
    @Override protected void onPause() {
        resumed=false;
        super.onPause();
    }
    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
    private byte[] asset(String name) throws Exception {
        try (InputStream in = getAssets().open(variant+"/"+name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[16384]; int n;
            while ((n=in.read(bytes)) != -1) out.write(bytes, 0, n);
            return out.toByteArray();
        }
    }
    private ClassLoader loader(byte[] dex) {
        return new InMemoryDexClassLoader(ByteBuffer.wrap(dex), getClassLoader());
    }
    private static void require(boolean pass, String label) {
        if (!pass) throw new AssertionError(label);
    }
    private JSONObject select() throws Exception { return (JSONObject)verifier.getMethod("select").invoke(store); }
    private Object metadata(String which, boolean corrupt) throws Exception {
        byte[] sig=asset(which+"/manifest.sig");
        if (corrupt) sig[0]^=1;
        return verifier.getMethod("verifyManifest",byte[].class,byte[].class)
                .invoke(null,asset(which+"/manifest.json"),sig);
    }
    private void install(String which, int running, boolean corrupt) throws Exception {
        byte[] bytes=asset(which+"/patch.zip");
        if (corrupt) bytes[bytes.length/2]^=1;
        verifier.getMethod("install",manifest,byte[].class,long.class)
                .invoke(store,metadata(which,false),bytes,(long)running);
    }
    private String marker(String name) throws Exception {
        // Acknowledgement legitimately removes "pending" on a worker thread.
        try {
            return new String(Files.readAllBytes(new File(directory,name).toPath()),
                    StandardCharsets.UTF_8).trim();
        } catch (NoSuchFileException absent) {
            return "";
        }
    }
    private void rejected(ThrowingCall test) throws Exception {
        try {test.run();}
        catch (InvocationTargetException e) {
            require(e.getCause() instanceof SecurityException || e.getCause() instanceof java.io.IOException,"unexpected rejection: "+e.getCause());
            return;
        }
        throw new AssertionError("invalid input accepted");
    }
    private interface ThrowingCall {void run() throws Exception;}
    private Boot prepare() throws Exception {
        ClassLoader original=loader(asset("baseline.dex"));
        verifier=original.loadClass("com.irisai.bshack.update.PatchStore");
        manifest=original.loadClass(verifier.getName()+"$Manifest");
        store=verifier.getConstructor(File.class).newInstance(directory);
        if ("initial".equals(action)) {
            require(select().getInt("version")==base,"fresh base");
            install("previous",base,false);
            require(select().getInt("version")==current-1,"previous selected");
            require(select().getInt("version")==base && marker("failed").equals(""+(current-1)),"recover incomplete previous update");
            rejected(()->metadata("current",true));
            rejected(()->install("current",base,true));
            require(marker("active").isEmpty(),"bad download changed active slot");
            install("current",base,false);
        }
        JSONObject selected=select();
        if ("rollback".equals(action)) {
            require(selected.getInt("version")==base && marker("failed").equals(""+current),"restore embedded version");
            return new Boot(null,null,null,"restored embedded version");
        }
        require(selected.getInt("version")==current && marker("pending").equals(""+current),
                "candidate selection: version="+selected.getInt("version")+" pending="+marker("pending")
                +" active="+marker("active")+" failed="+marker("failed")+" highest="+marker("highest"));
        if ("interrupt".equals(action)) return new Boot(null,null,null,"selected before panel start");
        if ("bad-download".equals(action)) {
            rejected(()->metadata("current",true));
            rejected(()->install("current",base,true));
            require(marker("active").equals(""+current),"invalid download damaged active update");
        }
        byte[] panel=Files.readAllBytes(new File(selected.getString("ui")).toPath());
        ClassLoader currentLoader=loader(panel);
        Class<?> panelClass=currentLoader.loadClass("com.irisai.bshack.PanelHost");
        if ("initial".equals(action) && variant.equals("combined")) {
            Log.w("IrisCompatibilityFixture","UnknownHostException private-fixture-name");
            java.lang.reflect.Method read=currentLoader.loadClass("com.irisai.bshack.ProcessLogReport").getDeclaredMethod("read",boolean.class);
            read.setAccessible(true);
            String summary=(String)read.invoke(null,true);
            require(!summary.contains("private-fixture-name"),"raw log leaked");
            logResult=summary.contains("DNS lookup failed") ? "classified" : "no-matching-readable-log";
        }
        return new Boot(panelClass,selected.getString("name"),logResult,null);
    }
    private void presentWhenResumed() {
        if (reported || attached || !resumed || preparedBoot==null || isDestroyed() || isFinishing()) return;
        attached=true;
        Boot boot=preparedBoot;
        if (boot.terminal!=null) {pass(boot.terminal);return;}
        if ("recreate".equals(action) && !requestedRecreation) {
            requestedRecreation=true;
            recreate();
            return;
        }
        try {
            panelHost=boot.panel;
            logResult=boot.log;
            if (!configured) {
                panelHost.getMethod("initialize",Application.class).invoke(null,getApplication());
                panelHost.getMethod("configure",long.class,String.class,String.class).invoke(null,(long)current,boot.name,"");
                configured=true;
            }
            panelHost.getMethod("attach",Activity.class).invoke(null,this);
            started=SystemClock.uptimeMillis();
            main.post(this::inspect);
        } catch(Throwable e){fail(e);}
    }

    private View find(View root, String text) {
        if(text.contentEquals(root.getContentDescription()==null?"":root.getContentDescription())) return root;
        if(root instanceof TextView && text.contentEquals(((TextView)root).getText())) return root;
        if(root instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)root;
            for(int i=0;i<group.getChildCount();i++) {View found=find(group.getChildAt(i),text);if(found!=null)return found;}
        }
        return null;
    }
    private void inspect() {
        try {
            if (reported || isDestroyed() || isFinishing()) return;
            View root=findViewById(android.R.id.content),opener=find(root,"Iris 설정 열기");
            inspections++;
            if (SystemClock.uptimeMillis()-started > 12000) {
                throw new AssertionError("panel timeout: resumed="+resumed+" focus="+hasWindowFocus()
                        +" opener="+(opener!=null)+" laidOut="+(opener!=null && opener.isLaidOut())
                        +" shown="+(opener!=null && opener.isShown())+" inspections="+inspections
                        +" foregroundReports="+foregroundReports+" active="+marker("active")
                        +" pending="+marker("pending")+" highest="+marker("highest"));
            }
            if(!resumed||opener==null||!opener.isLaidOut()||!opener.isShown()){main.postDelayed(this::inspect,100);return;}
            View parent=(View)opener.getParent();
            int midpoint2=parent.getPaddingLeft()+parent.getWidth()-parent.getPaddingRight();
            require(Math.abs(opener.getLeft()+opener.getRight()-midpoint2)<=2,"opener not centered");
            require(opener.getTop()==parent.getPaddingTop(),"opener not at safe top edge");
            foregroundReports++;
            panelHost.getMethod("recordState",String.class).invoke(null,"{\"foreground\":true,\"game\":false,\"enabled\":false,\"phase\":\"idle\",\"auth\":{\"ready\":false}}");
            panelHost.getMethod("update",boolean.class,String.class).invoke(null,false,"QA");
            if (!marker("pending").isEmpty()){main.postDelayed(this::inspect,100);return;}
            require(marker("highest").equals(""+current),"patch success not durable");
            opener.performClick();
            require(find(root,"업데이트 확인")!=null && find(root,"업데이트 확인").isShown(),"menu did not open");
            pass("top-center, menu, patch ready; log="+logResult);
        }catch(Throwable e){fail(e);}
    }
    private void pass(String details){
        if (reported) return;
        reported=true;
        Log.i("IrisReleaseQA",token+" PASS "+variant+" "+action+" "+details);
        if ("recreate".equals(action)) recreate();
    }
    private void fail(Throwable e){
        if (reported) return;
        reported=true;
        Log.e("IrisReleaseQA",token+" FAIL "+variant+" "+action+" "+e,e);
    }
}
