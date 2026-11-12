package com.irisai.autogoal.ingame;

import android.app.Activity;
import android.os.Looper;
import android.view.SurfaceView;
import java.lang.reflect.Method;

/** Version-specific Java JNI entry point verified against the user's 70.757 APK. */
final class NativeBridge implements Stroke.Sink {
    private final Method input, loaded, view;
    private final Activity activity;
    NativeBridge(Activity activity) throws ReflectiveOperationException {
        this.activity = activity;
        Class<?> game = Class.forName("com.supercell.titan.GameApp", false, activity.getClassLoader());
        input = game.getMethod("nOnTouchEvent", int.class, int.class, int.class, int.class);
        loaded = game.getMethod("isNativeLibraryLoaded");
        view = game.getMethod("getView");
    }
    SurfaceView surface() throws ReflectiveOperationException { return (SurfaceView) view.invoke(activity); }
    boolean loaded() throws ReflectiveOperationException { return Boolean.TRUE.equals(loaded.invoke(null)); }
    @Override public void send(int type, int x, int y, int pointer) throws Exception {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Input requires game UI thread");
        input.invoke(null, type, x, y, pointer);
    }
}
