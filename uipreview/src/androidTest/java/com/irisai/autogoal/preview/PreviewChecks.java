package com.irisai.autogoal.preview;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.*;
import android.widget.TextView;
import com.irisai.autogoal.ui.IrisControlPanel;
import java.io.*;
import java.util.*;
import org.json.JSONObject;

/** Exercises real Android layout and touch routing on a disposable CI emulator. */
public final class PreviewChecks extends Instrumentation {
    private PanelPreviewActivity activity;
    private File directory;
    private final List<String> checks = new ArrayList<>();
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            getTargetContext().getSharedPreferences("iris_control_panel_v1", 0).edit().clear().commit();
            activity = (PanelPreviewActivity) startActivitySync(new Intent(getTargetContext(), PanelPreviewActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync(); SystemClock.sleep(200);
            directory = new File(getTargetContext().getExternalFilesDir(null), "ui-checks"); directory.mkdirs();
            onUi(() -> {
                View toggle = byDescription(activity.root, "오토골 켜기 또는 끄기");
                View opener = byDescription(activity.root, "Iris 설정 열기");
                require(toggle != null && toggle.isShown() && toggle.getWidth() > 0, "default toggle visible");
                require(opener != null && Math.abs(opener.getWidth() - toggle.getWidth() * 2) <= 1
                    && Math.abs(opener.getHeight() - toggle.getHeight() * 2) <= 1, "invisible opener is 2x width and height");
                int before = activity.board.taps;
                tap(activity.root.getWidth() / 2f, activity.root.getHeight() * .85f);
                require(activity.board.taps == before + 1, "outside UI touches reach game area");
                tap(opener.getX() + opener.getWidth() / 2f, opener.getY() + opener.getHeight() / 2f);
            });
            waitForIdleSync(); screenshot("panel-ko.png");
            onUi(() -> {
                require(byText(activity.root, "IRIS  /  컨트롤 센터").isShown(), "menu opens");
                ((View) byText(activity.root, "언어").getParent()).performClick();
                require(byText(activity.root, "IRIS  /  CONTROL CENTER").isShown(), "language changes immediately");
                require("https://discord.gg/bshack".equals(IrisControlPanel.DISCORD_URL), "exact Discord destination");
                require(!allText(activity.root).toLowerCase(Locale.ROOT).contains("license"), "no license controls");
            });
            waitForIdleSync(); screenshot("panel-en.png");
            onUi(() -> {
                View hide = (View) byText(activity.root, "Hide all UI").getParent();
                hide.requestRectangleOnScreen(new android.graphics.Rect(0, 0, hide.getWidth(), hide.getHeight()), true);
                hide.performClick();
                require(!byDescription(activity.root, "Toggle Auto Goal").isShown(), "hide all removes HUD");
                byDescription(activity.root, "Open Iris settings").performClick();
                require(byText(activity.root, "Show UI").isShown(), "hidden UI can be recovered");
                ((View) byText(activity.root, "Show UI").getParent()).performClick();
                byDescription(activity.root, "Close settings").performClick();
                require(byDescription(activity.root, "Toggle Auto Goal").isShown(), "HUD restored");
            });
            waitForIdleSync(); screenshot("hud.png");
            JSONObject report = new JSONObject(); report.put("passed", true); report.put("checks", new org.json.JSONArray(checks));
            report.put("game_startup_tested", false); report.put("streamer_actual_capture_tested", false);
            try (FileWriter out = new FileWriter(new File(directory, "result.json"))) { out.write(report.toString(2)); }
            result.putString("stream", "PASS " + checks.size() + " Android UI checks\n"); finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL " + error + "\n"); finish(Activity.RESULT_CANCELED, result);
        }
    }
    private void require(boolean ok, String label) { if (!ok) throw new AssertionError(label); checks.add(label); }
    private void onUi(Runnable action) {
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
    private void screenshot(String name) throws IOException {
        waitForIdleSync(); SystemClock.sleep(150);
        Bitmap b = getUiAutomation().takeScreenshot();
        if (b == null) throw new IOException("Screenshot unavailable");
        try (FileOutputStream out = new FileOutputStream(new File(directory, name))) { b.compress(Bitmap.CompressFormat.PNG, 100, out); }
        finally { b.recycle(); }
    }
    private void tap(float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0), up = MotionEvent.obtain(now, now + 30, MotionEvent.ACTION_UP, x, y, 0);
        try { activity.root.dispatchTouchEvent(down); activity.root.dispatchTouchEvent(up); } finally { down.recycle(); up.recycle(); }
    }
    private static View byDescription(View root, String value) {
        if (value.contentEquals(root.getContentDescription() == null ? "" : root.getContentDescription())) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            View v = byDescription(((ViewGroup) root).getChildAt(i), value); if (v != null) return v;
        }
        return null;
    }
    private static TextView byText(View root, String value) {
        if (root instanceof TextView && value.contentEquals(((TextView) root).getText())) return (TextView) root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            TextView v = byText(((ViewGroup) root).getChildAt(i), value); if (v != null) return v;
        }
        return null;
    }
    private static String allText(View root) {
        StringBuilder s = new StringBuilder(root instanceof TextView ? ((TextView) root).getText() : "");
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) s.append(' ').append(allText(((ViewGroup) root).getChildAt(i)));
        return s.toString();
    }
}
