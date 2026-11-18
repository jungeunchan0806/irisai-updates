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
import java.nio.file.Files;
import org.json.JSONObject;

/** An ordinary, offline test activity with the class name the released panel accepts.
 * The signed game engine is a patch store input only. It is never executed. */
public final class GameApp extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private String variant, action, token;
    private int base, current;
    private File directory;
    private Object store;
    private Class<?> verifier, manifest, panelHost;
    private long started;
    private JSONObject selected;
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
        new Thread(() -> {
            try { prepare(); } catch (Throwable e) {fail(e);}
        }).start();
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
        File f=new File(directory,name);
        return f.exists() ? new String(Files.readAllBytes(f.toPath()),"UTF-8").trim() : "";
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
    private void prepare() throws Exception {
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
        selected=select();
        if ("rollback".equals(action)) {
            require(selected.getInt("version")==base && marker("failed").equals(""+current),"restore embedded version");
            pass("restored embedded version"); return;
        }
        require(selected.getInt("version")==current && marker("pending").equals(""+current),"candidate selected");
        if ("interrupt".equals(action)) {pass("selected before panel start");return;}
        if ("bad-download".equals(action)) {
            rejected(()->metadata("current",true));
            rejected(()->install("current",base,true));
            require(marker("active").equals(""+current),"invalid download damaged active update");
        }
        byte[] panel=Files.readAllBytes(new File(selected.getString("ui")).toPath());
        ClassLoader currentLoader=loader(panel);
        panelHost=currentLoader.loadClass("com.irisai.bshack.PanelHost");
        if ("initial".equals(action) && variant.equals("combined")) {
            Log.w("IrisCompatibilityFixture","UnknownHostException private-fixture-name");
            java.lang.reflect.Method read=currentLoader.loadClass("com.irisai.bshack.ProcessLogReport").getDeclaredMethod("read",boolean.class);
            read.setAccessible(true);
            String summary=(String)read.invoke(null,true);
            require(!summary.contains("private-fixture-name"),"raw log leaked");
            logResult=summary.contains("DNS lookup failed") ? "classified" : "no-matching-readable-log";
        }
        main.post(()->{
            try {
                panelHost.getMethod("initialize",Application.class).invoke(null,getApplication());
                panelHost.getMethod("configure",long.class,String.class,String.class).invoke(null,(long)current,selected.getString("name"),"");
                panelHost.getMethod("attach",Activity.class).invoke(null,this);
                started=SystemClock.uptimeMillis();
                main.post(this::inspect);
            } catch(Throwable e){fail(e);}
        });
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
            if (SystemClock.uptimeMillis()-started > 12000) throw new AssertionError("panel layout or health timeout");
            View root=findViewById(android.R.id.content),opener=find(root,"Iris 설정 열기");
            if(opener==null||!opener.isLaidOut()||!opener.isShown()){main.postDelayed(this::inspect,100);return;}
            View parent=(View)opener.getParent();
            int midpoint2=parent.getPaddingLeft()+parent.getWidth()-parent.getPaddingRight();
            require(Math.abs(opener.getLeft()+opener.getRight()-midpoint2)<=2,"opener not centered");
            require(opener.getTop()==parent.getPaddingTop(),"opener not at safe top edge");
            panelHost.getMethod("recordState",String.class).invoke(null,"{\"foreground\":true,\"game\":false,\"enabled\":false,\"phase\":\"idle\",\"auth\":{\"ready\":false}}");
            panelHost.getMethod("update",boolean.class,String.class).invoke(null,false,"QA");
            if (!marker("pending").isEmpty()){main.postDelayed(this::inspect,100);return;}
            require(marker("highest").equals(""+current),"patch success not durable");
            opener.performClick();
            require(find(root,"업데이트 확인")!=null && find(root,"업데이트 확인").isShown(),"menu did not open");
            pass("top-center, menu, patch ready; log="+logResult);
        }catch(Throwable e){fail(e);}
    }
    private void pass(String details){Log.i("IrisReleaseQA",token+" PASS "+variant+" "+action+" "+details);}
    private void fail(Throwable e){Log.e("IrisReleaseQA",token+" FAIL "+variant+" "+action+" "+e,e);}
}
