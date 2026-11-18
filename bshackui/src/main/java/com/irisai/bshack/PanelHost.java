package com.irisai.bshack;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.app.AlertDialog;
import android.view.ViewGroup;
import com.irisai.autogoal.ui.IrisControlPanel;
import com.irisai.bshack.update.UpdateManager;
import java.util.ArrayDeque;

/** All methods run on Android's main thread. No native game calls or capture loop. */
public final class PanelHost implements Application.ActivityLifecycleCallbacks {
    private static PanelHost host;
    private final ArrayDeque<Integer> commands = new ArrayDeque<>();
    private Activity activity;
    private IrisControlPanel panel;
    private boolean resumed;
    private boolean engineEnabled;
    private UpdateManager updates;
    private LicenseClient license;
    private ConnectionMonitor connection;
    private String connectionReport="";
    private long lastHeartbeat, nextHealthAttempt;
    private boolean markedHealthy, healthPending, freshBridgeState;
    private String latestError = "", diagnosticState = "", bootName = "2.2.0", recovery = "";
    private final Handler handler = new Handler(Looper.getMainLooper());
    public static void initialize(Application app) {
        if (host != null) return;
        host = new PanelHost(); host.updates = new UpdateManager(app);host.license=new LicenseClient(app);host.connection=new ConnectionMonitor(app,()->host.license.networkRestored());app.registerActivityLifecycleCallbacks(host);
        host.handler.postDelayed(host::watchdog, 1500);
    }
    public static void configure(long version,String name,String recovery) {
        if(host==null)return;host.bootName=name;host.recovery=recovery;host.updates.configure(version,name);
    }
    public static String licenseStatus(){return host==null?"{}":host.license.state();}
    public static long authorize(){return host==null?0:host.license.request(host.activity);}
    public static void copyHwid(){if(host!=null)host.license.copy();}
    public static void attachContext(Context context) {
        for(int i=0;i<8&&context!=null;i++) {
            if(context instanceof Activity){attach((Activity)context);return;}
            if(!(context instanceof ContextWrapper))return;
            Context base=((ContextWrapper)context).getBaseContext();if(base==context)return;context=base;
        }
    }
    public static void attach(Activity activity) {
        if (host == null || activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        String name = activity.getClass().getName();
        if (!name.equals("com.supercell.titan.GameApp") && !name.equals("com.supercell.brawlstars.GameApp")) return;
        host.connect(activity);
    }
    private void queue(int action) {
        if (action == 2) { commands.clear(); commands.add(2); return; }
        if (commands.size() < 8) commands.add(action);
        if(action==1&&panel!=null)panel.setEngineState(engineEnabled,"요청 처리 중");
    }
    private void connect(Activity next) {
        if (activity == next && resumed) return;
        if (activity != next) {
            disconnect(); activity = next;
            panel = new IrisControlPanel(next, new IrisControlPanel.Actions() {
                public void toggleAutoGoal() { queue(1); }
                public void calibrate() {}
                public void resetControls() {}
                public void visibilitySuppressed() {}
                public boolean nativeEngine() { return true; }
                public void copyHwid() { queue(3); }
                public void checkUpdates() { updates.attach(activity,panel.english());updates.check(true); }
                public void showDiagnostics() { diagnostics(); }
            }, false);
            ((ViewGroup) next.findViewById(android.R.id.content)).addView(panel, new ViewGroup.LayoutParams(-1, -1));
        }
        resumed = true; panel.resume();
        lastHeartbeat=SystemClock.uptimeMillis();freshBridgeState=false;
        updates.attach(next,panel.english());updates.start();
    }
    private void disconnect() {
        resumed = false; freshBridgeState=false;queue(2);
        if(activity!=null){updates.pause(activity);license.detach(activity);}
        if (panel != null) {
            panel.close(); if (panel.getParent() instanceof ViewGroup) ((ViewGroup) panel.getParent()).removeView(panel);
        }
        panel = null; activity = null;
    }
    public static int poll() { return host == null || host.commands.isEmpty() ? 0 : host.commands.remove(); }
    public static boolean isResumed() { return host != null && host.resumed; }
    public static String connectionStatus(){
        return host==null?"{\"network\":\"unknown\",\"focused\":false,\"generation\":0}":
            host.connection.snapshot(host.resumed&&host.activity!=null&&host.activity.hasWindowFocus());
    }
    public static void updateConnection(String report){
        if(host==null)return;
        try{
            org.json.JSONObject data=new org.json.JSONObject(report);host.connectionReport=report;

            if(host.panel!=null)host.panel.setConnectionState(data.optString("reason","waiting"));
        }catch(Exception e){}
    }
    public static void update(boolean enabled, String phase) {
        if (host != null && host.panel != null) {
            host.lastHeartbeat=SystemClock.uptimeMillis();host.engineEnabled = enabled; host.panel.setEngineState(enabled, phase);
            // A foreground bridge response and an attached, laid-out panel attest
            // to patch startup even while game or account initialization is pending.
            boolean responsive = host.resumed && host.freshBridgeState
                    && host.panel.isAttachedToWindow() && host.panel.isLaidOut()
                    && host.panel.getWidth() > 0 && host.panel.getHeight() > 0;
            host.freshBridgeState = false;
            if (responsive && !host.markedHealthy && !host.healthPending && host.lastHeartbeat >= host.nextHealthAttempt) {
                final PanelHost current=host;current.healthPending=true;
                current.updates.healthy(success->{
                    current.healthPending=false;current.markedHealthy=success;
                    if(!success)current.nextHealthAttempt=SystemClock.uptimeMillis()+2000;
                });
            }
        }
    }
    public static void recordError(String stage,String error) { if(host!=null){host.latestError=stage+": "+error;host.freshBridgeState=false;} }
    public static void recordState(String state) {
        if(host==null)return;host.diagnosticState=state;host.freshBridgeState=false;
        try{
            org.json.JSONObject data = new org.json.JSONObject(state);
            host.freshBridgeState = Boolean.TRUE.equals(data.opt("foreground"))
                    && data.opt("game") instanceof Boolean
                    && data.opt("enabled") instanceof Boolean
                    && data.opt("phase") instanceof String
                    && data.optJSONObject("auth") != null;
        }catch(Exception ignored){}
    }
    private void watchdog() {
        if(resumed&&panel!=null&&SystemClock.uptimeMillis()-lastHeartbeat>5000)panel.setEngineState(false,"UI 응답 없음 · 오류 확인");
        handler.postDelayed(this::watchdog,1500);
    }
    private void diagnostics() {
        if(activity==null)return;
        String report="IRIS "+bootName+"\nAndroid "+android.os.Build.VERSION.RELEASE+" / API "+android.os.Build.VERSION.SDK_INT+"\n"+android.os.Build.MANUFACTURER+" "+android.os.Build.MODEL+"\n"+diagnosticState+"\nConnection: "+connectionReport+"\n"+latestError+"\n"+recovery;
        new AlertDialog.Builder(activity).setTitle(panel.english()?"Diagnostics":"오류 확인").setMessage(report)
            .setPositiveButton(panel.english()?"Copy":"내용 복사",(d,w)->{
                ((ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("IRIS diagnostic",report));
            }).setNegativeButton(panel.english()?"Close":"닫기",null).show();
    }
    @Override public void onActivityResumed(Activity value) { attach(value); }
    @Override public void onActivityPaused(Activity value) { if (value == activity) { resumed = false; freshBridgeState=false;queue(2); updates.pause(value);license.detach(value);panel.pause(); } }
    @Override public void onActivityDestroyed(Activity value) { if (value == activity) disconnect(); }
    @Override public void onActivityCreated(Activity value, Bundle state) {}
    @Override public void onActivityStarted(Activity value) {}
    @Override public void onActivityStopped(Activity value) {}
    @Override public void onActivitySaveInstanceState(Activity value, Bundle state) {}
}
