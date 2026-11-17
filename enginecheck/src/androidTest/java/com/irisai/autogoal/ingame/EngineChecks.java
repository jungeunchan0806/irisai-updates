package com.irisai.autogoal.ingame;

import android.app.*;
import android.content.Intent;
import android.os.*;
import android.view.*;
import android.widget.TextView;
import com.supercell.titan.GameApp;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;

public final class EngineChecks extends Instrumentation {
    private EngineCheckActivity activity;
    private final List<String> checks = new ArrayList<>();
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); JSONObject report = new JSONObject();
        try {
            getTargetContext().getSharedPreferences("iris_control_panel_v1", 0).edit().clear().commit();
            activity = (EngineCheckActivity) startActivitySync(new Intent(getTargetContext(), EngineCheckActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync(); SystemClock.sleep(500);
            onUi(() -> { require(activity.getView().getHolder().getSurface().isValid(), "recorded-frame surface is valid"); toggle().performClick(); });
            awaitGesture(0);
            onUi(() -> {
                require(toggle().getText().toString().contains("ON"), "actual controller enabled from shared UI");
                require(GameApp.inputs.stream().anyMatch(v -> v[0] == 0 && v[3] == 9), "models and planner reached internal DOWN call");
                require(GameApp.inputs.stream().anyMatch(v -> v[0] == 2 && v[3] == 9), "aim/movement coordinates reached internal MOVE call");
                require(GameApp.inputs.stream().anyMatch(v -> v[0] == 1 && v[3] == 9), "finite gesture reached internal UP call");
                toggle().performClick();
                require(toggle().getText().toString().contains("OFF"), "OFF disables actual controller");
            });
            int stopped = count(); SystemClock.sleep(1200);
            require(count() == stopped, "no stale frame or delayed gesture after OFF");
            onUi(() -> toggle().performClick()); awaitGesture(stopped);
            onUi(() -> activity.controller.pause());
            stopped = count(); SystemClock.sleep(1200);
            require(count() == stopped, "background pause stops internal input");
            onUi(() -> { activity.controller.resume(); toggle().performClick(); activity.controller.close(); });
            stopped = count(); SystemClock.sleep(1200);
            require(count() == stopped, "close rejects pending inference input");
            report.put("passed", true); report.put("checks", new JSONArray(checks));
            report.put("capture", "PixelCopy from recorded-frame SurfaceView");
            report.put("inference", "real packaged OpenCV and ONNX models on Android x86_64");
            report.put("input_sink", "test Java method matching game entrypoint; NOT the original native game");
            report.put("live_game_tested", false); report.put("friendly_matches", 0);
            File out = new File(getTargetContext().getExternalFilesDir(null), "engine-check.json");
            try (FileWriter writer = new FileWriter(out)) { writer.write(report.toString(2)); }
            result.putString("stream", "PASS " + checks.size() + " engine pipeline checks\n"); finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL " + error + "\n"); finish(Activity.RESULT_CANCELED, result);
        }
    }
    private void awaitGesture(int start) {
        long end = SystemClock.uptimeMillis() + 45000;
        while (SystemClock.uptimeMillis() < end) {
            AtomicReference<Boolean> complete = new AtomicReference<>(false);
            onUi(() -> { for (int i = start; i < GameApp.inputs.size(); i++) if (GameApp.inputs.get(i)[0] == 1) complete.set(true); });
            if (complete.get()) return; SystemClock.sleep(100);
        }
        AtomicReference<String> status = new AtomicReference<>(); onUi(() -> status.set(toggle().getText().toString()));
        throw new AssertionError("No completed internal input gesture: " + status.get());
    }
    private int count() { AtomicReference<Integer> n = new AtomicReference<>(); onUi(() -> n.set(GameApp.inputs.size())); return n.get(); }
    private TextView toggle() { return (TextView) find(activity.getWindow().getDecorView()); }
    private View find(View v) {
        if ("오토골 켜기 또는 끄기".contentEquals(v.getContentDescription() == null ? "" : v.getContentDescription())) return v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
            View match = find(((ViewGroup) v).getChildAt(i)); if (match != null) return match;
        }
        return null;
    }
    private void onUi(Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> { try { action.run(); } catch (Throwable t) { failure.set(t); } });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
    private void require(boolean ok, String label) { if (!ok) throw new AssertionError(label); checks.add(label); }
}
