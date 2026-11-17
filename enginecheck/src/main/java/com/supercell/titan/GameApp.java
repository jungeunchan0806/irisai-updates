package com.supercell.titan;

import android.app.Activity;
import android.os.Looper;
import android.view.SurfaceView;
import java.util.ArrayList;
import java.util.List;

/** Original test fixture, not Supercell code. Records bridge calls without running a game. */
public class GameApp extends Activity {
    public static final List<int[]> inputs = new ArrayList<>();
    protected SurfaceView surface;
    public SurfaceView getView() { return surface; }
    public static boolean isNativeLibraryLoaded() { return true; }
    public static void nOnTouchEvent(int type, int x, int y, int pointer) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new AssertionError("Wrong input thread");
        inputs.add(new int[]{type, x, y, pointer});
    }
}
