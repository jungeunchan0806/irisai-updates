package com.irisai.autogoal.ingame;

import android.graphics.*;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import java.io.*;

/** Runs the production controller against a recorded-frame SurfaceView and test input sink. */
public final class EngineCheckActivity extends com.supercell.titan.GameApp {
    GameController controller;
    private Bitmap frame;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try (InputStream in = getAssets().open("fixtures/rolling.jpg")) { frame = BitmapFactory.decodeStream(in); }
        catch (IOException e) { throw new IllegalStateException(e); }
        surface = new SurfaceView(this);
        surface.getHolder().addCallback(new SurfaceHolder.Callback() {
            public void surfaceCreated(SurfaceHolder h) { drawFrame(); }
            public void surfaceChanged(SurfaceHolder h, int format, int w, int height) { drawFrame(); }
            public void surfaceDestroyed(SurfaceHolder h) {}
        });
        setContentView(surface);
        controller = new GameController(this);
    }
    private void drawFrame() {
        Canvas canvas = surface.getHolder().lockCanvas();
        if (canvas == null) return;
        try { canvas.drawBitmap(frame, null, new Rect(0, 0, canvas.getWidth(), canvas.getHeight()), null); }
        finally { surface.getHolder().unlockCanvasAndPost(canvas); }
    }
    @Override public void onResume() { super.onResume(); controller.resume(); }
    @Override public void onPause() { controller.pause(); super.onPause(); }
    @Override public void onDestroy() { controller.close(); frame.recycle(); super.onDestroy(); }
}
