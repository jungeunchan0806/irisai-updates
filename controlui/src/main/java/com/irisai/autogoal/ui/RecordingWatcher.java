package com.irisai.autogoal.ui;

import android.app.Activity;
import android.os.Build;
import android.view.WindowManager;
import java.util.function.Consumer;

/** Public Android recording visibility callback. It never secures the game window. */
final class RecordingWatcher {
    private final Activity activity;
    private final Consumer<Boolean> callback;
    private Consumer<Integer> listener;
    RecordingWatcher(Activity activity, Consumer<Boolean> callback) {
        this.activity = activity; this.callback = callback;
    }
    boolean supported() { return Build.VERSION.SDK_INT >= 35; }
    boolean start() {
        if (Build.VERSION.SDK_INT < 35) return false;
        if (listener != null) return true;
        Consumer<Integer> next = state -> callback.accept(state == WindowManager.SCREEN_RECORDING_STATE_VISIBLE);
        try {
            int state = activity.getWindowManager().addScreenRecordingCallback(activity.getMainExecutor(), next);
            listener = next;
            next.accept(state);
            return true;
        } catch (RuntimeException e) { listener = null; return false; }
    }
    void stop() {
        if (listener != null && Build.VERSION.SDK_INT >= 35) {
            try { activity.getWindowManager().removeScreenRecordingCallback(listener); }
            catch (RuntimeException ignored) { /* Activity/window may already be gone. */ }
        }
        listener = null;
    }
}
