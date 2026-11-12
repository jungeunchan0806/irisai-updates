package com.irisai.autogoal.ingame;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import com.irisai.autogoal.*;
import com.irisai.autogoal.Geometry.Box;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;
import java.io.*;
import java.util.ArrayList;
import java.util.List;

/** Confined to one worker. Reads pixels from the game's own surface, not game memory. */
final class Engine implements AutoCloseable {
    final GoalPlanner planner = new GoalPlanner();
    private BallVision vision;
    private YoloDetector entities, tiles;
    Engine(Context context) throws Exception {
        System.loadLibrary("opencv_java4"); Core.setNumThreads(2);
        try {
            vision = new BallVision(name -> read(context, "vision/" + name));
            entities = new YoloDetector(model(context, "mainInGameModel.onnx"));
            tiles = new YoloDetector(model(context, "tileDetector.onnx"));
        } catch (Exception | LinkageError e) { close(); throw e; }
    }
    GoalPlanner.Decision observe(Bitmap bitmap, double gadgetX, double gadgetY) throws Exception {
        Mat rgba = new Mat(), rgb = new Mat();
        try {
            Utils.bitmapToMat(bitmap, rgba);
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB);
            Box player = null; float playerScore = 0;
            List<Box> others = new ArrayList<>(), walls = new ArrayList<>();
            for (YoloDetector.Detection d : entities.detect(rgb, .4f, 12)) {
                if (d.kind == 2) { if (d.score > playerScore) { player = d.box; playerScore = d.score; } }
                else if (d.kind == 0 || d.kind == 1) others.add(d.box);
            }
            // The supplied tile model's class 0 is wall. Bushes are passable.
            for (YoloDetector.Detection d : tiles.detect(rgb, .55f, 100)) {
                if (d.kind == 0) walls.add(d.box.footprint());
            }
            double now = SystemClock.uptimeMillis() / 1000.;
            return planner.decide(vision.observe(rgb, player, others, walls,
                    planner.ownedRecently(now), gadgetX, gadgetY), now);
        } finally { rgba.release(); rgb.release(); }
    }
    private static byte[] read(Context c, String path) throws IOException {
        try (InputStream in = c.getAssets().open("iris_autogoal/" + path); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384]; int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toByteArray();
        }
    }
    private static String model(Context c, String name) throws IOException {
        File dir = new File(c.getNoBackupFilesDir(), "iris-autogoal-models");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Model directory unavailable");
        File target = new File(dir, name);
        // Refresh from the installed payload so upgrading cannot retain stale weights.
        try (InputStream in = c.getAssets().open("iris_autogoal/models/" + name); OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        }
        return target.getAbsolutePath();
    }
    @Override public void close() {
        if (vision != null) { vision.close(); vision = null; }
        if (entities != null) { try { entities.close(); } catch (Exception ignored) {} entities = null; }
        if (tiles != null) { try { tiles.close(); } catch (Exception ignored) {} tiles = null; }
    }
}
