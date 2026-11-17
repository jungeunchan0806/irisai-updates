package com.irisai.bshack;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.ViewGroup;
import com.irisai.autogoal.ui.IrisControlPanel;
import java.util.ArrayDeque;

/** All methods run on Android's main thread. No native game calls or capture loop. */
public final class PanelHost implements Application.ActivityLifecycleCallbacks {
    private static PanelHost host;
    private final ArrayDeque<Integer> commands = new ArrayDeque<>();
    private Activity activity;
    private IrisControlPanel panel;
    private boolean resumed;
    private boolean engineEnabled;
    public static void initialize(Application app) {
        if (host != null) return;
        host = new PanelHost(); app.registerActivityLifecycleCallbacks(host);
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
                public void showLicenseDialog() { queue(4); }
            }, false);
            ((ViewGroup) next.findViewById(android.R.id.content)).addView(panel, new ViewGroup.LayoutParams(-1, -1));
        }
        resumed = true; panel.resume();
    }
    private void disconnect() {
        resumed = false; queue(2);
        if (panel != null) {
            panel.close(); if (panel.getParent() instanceof ViewGroup) ((ViewGroup) panel.getParent()).removeView(panel);
        }
        panel = null; activity = null;
    }
    public static int poll() { return host == null || host.commands.isEmpty() ? 0 : host.commands.remove(); }
    public static boolean isResumed() { return host != null && host.resumed; }
    public static void update(boolean enabled, String phase) {
        if (host != null && host.panel != null) { host.engineEnabled = enabled; host.panel.setEngineState(enabled, phase); }
    }
    @Override public void onActivityResumed(Activity value) { attach(value); }
    @Override public void onActivityPaused(Activity value) { if (value == activity) { resumed = false; queue(2); panel.pause(); } }
    @Override public void onActivityDestroyed(Activity value) { if (value == activity) disconnect(); }
    @Override public void onActivityCreated(Activity value, Bundle state) {}
    @Override public void onActivityStarted(Activity value) {}
    @Override public void onActivityStopped(Activity value) {}
    @Override public void onActivitySaveInstanceState(Activity value, Bundle state) {}
}
