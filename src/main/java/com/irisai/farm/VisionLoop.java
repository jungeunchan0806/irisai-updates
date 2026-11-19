package com.irisai.farm;

import android.content.Context;
import android.graphics.Rect;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

/** Copies only the newest frame needed by an idle model worker. Tracking owns a separate thread. */
final class VisionLoop implements AutoCloseable {
  interface Output {
    void result(int token, FarmVision.Observation observation, boolean full, long captured);

    void rates(int token, float capture, float tracking, float analysis, long latency);

    void failed(int token, String reason);
  }

  final Handler frames;
  private final HandlerThread thread = new HandlerThread("Iris frames");
  private final ExecutorService inference =
      Executors.newSingleThreadExecutor(r -> new Thread(r, "Iris models"));
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Context app;
  private final Output output;
  private final FrameTracker tracker = new FrameTracker();
  private final FarmPolicy fastPolicy = new FarmPolicy();
  private final Mat scaled = new Mat(), thumb = new Mat(), modelRgba = new Mat();
  private int token;
  private boolean running, repeat, busy;
  private volatile boolean closed;
  private Rect crop;
  private FarmPolicy.Brawler profile;
  private long lastTimestamp, nextFrame, rateAt, lastDecision, latency;
  private int captured, tracked, analyzed;
  // Used exclusively by inference.
  private FarmVision vision;
  private String loadedProfile = "";
  private int modelEpoch = -1;

  VisionLoop(Context context, Output output) {
    app = context.getApplicationContext();
    this.output = output;
    thread.start();
    frames = new Handler(thread.getLooper());
  }

  void configure(int epoch, Rect area, FarmPolicy.Brawler b, boolean again) {
    final Rect region = area == null ? null : new Rect(area);
    frames.post(
        () -> {
          token = epoch;
          crop = region;
          profile = b;
          repeat = again;
          running = region != null && b != null;
          tracker.clear();
          fastPolicy.reset();
          rateAt = lastTimestamp = nextFrame = latency = lastDecision = 0;
          captured = tracked = analyzed = 0;
        });
  }

  void image(ImageReader source) {
    if (closed) return;
    try (Image image = source.acquireLatestImage()) {
      if (image == null || !running) return;
      long timestamp = image.getTimestamp();
      if (timestamp > 0 && timestamp == lastTimestamp) return;
      lastTimestamp = timestamp;
      long arrival = SystemClock.elapsedRealtimeNanos();
      long now = arrival / 1_000_000L;
      if (rateAt == 0) rateAt = now;
      captured++;
      long frameTime = timestamp > 0 ? timestamp : arrival;
      long period = 1_000_000_000L / 120;
      if (nextFrame != 0 && frameTime + 500_000L < nextFrame) return;
      nextFrame =
          (nextFrame == 0 || frameTime - nextFrame > period)
              ? frameTime + period
              : nextFrame + period;
      Image.Plane p = image.getPlanes()[0];
      ByteBuffer bytes = p.getBuffer();
      if (p.getPixelStride() != 4 || p.getRowStride() < 4 * image.getWidth())
        throw new IllegalStateException("지원되지 않는 화면 형식");
      Rect r = new Rect(crop);
      if (!r.intersect(0, 0, image.getWidth(), image.getHeight())
          || r.width() < 32
          || r.height() < 16) return;
      Mat surface =
          new Mat(image.getHeight(), image.getWidth(), CvType.CV_8UC4, bytes, p.getRowStride());
      Mat game = surface.submat(r.top, r.bottom, r.left, r.right);
      try {
        Imgproc.resize(game, scaled, FrameTracker.SIZE, 0, 0, Imgproc.INTER_LINEAR);
        Imgproc.cvtColor(scaled, thumb, Imgproc.COLOR_RGBA2GRAY);
        FarmPolicy.Frame current = tracker.track(thumb, now);
        if (current != null) {
          tracked++;
          if (now - lastDecision >= 95) {
            FarmVision.Observation o = new FarmVision.Observation();
            o.state = "battle";
            o.decision = fastPolicy.decide(current, profile);
            lastDecision = now;
            emit(token, o, false, now);
          }
        }
        if (!busy) {
          Imgproc.resize(game, modelRgba, new Size(960, 540), 0, 0, Imgproc.INTER_LINEAR);
          Mat rgb = new Mat();
          Imgproc.cvtColor(modelRgba, rgb, Imgproc.COLOR_RGBA2RGB);
          submit(rgb, thumb.clone(), now);
        }
      } finally {
        game.release();
        surface.release();
      }
      if (now - rateAt >= 1000) {
        float scale = 1000f / (now - rateAt),
            c = captured * scale,
            t = tracked * scale,
            a = analyzed * scale;
        int ticket = token;
        long ms = latency;
        main.post(() -> output.rates(ticket, c, t, a, ms));
        rateAt = now;
        captured = tracked = analyzed = 0;
      }
    } catch (IllegalStateException e) {
      if (!closed && running) error(e);
    } catch (RuntimeException | LinkageError e) {
      if (!closed) error(e);
    }
  }

  private void submit(Mat rgb, Mat anchors, long now) {
    busy = true;
    int ticket = token;
    FarmPolicy.Brawler b = profile;
    boolean again = repeat;
    inference.execute(
        () -> {
          FarmVision.Observation observation = null;
          String failure = null;
          try {
            if (vision == null || !loadedProfile.equals(b.name)) {
              if (vision != null) {
                vision.close();
                vision = null;
              }
              vision = new FarmVision(app, b);
              loadedProfile = b.name;
            }
            if (modelEpoch != ticket) {
              vision.reset();
              modelEpoch = ticket;
            }
            observation = vision.observeRgb(rgb, now / 1000., again);
          } catch (Exception | LinkageError e) {
            failure = e.getClass().getSimpleName() + " · " + e.getMessage();
          } finally {
            rgb.release();
          }
          FarmVision.Observation o = observation;
          String problem = failure;
          if (!frames.post(
              () -> {
                try {
                  busy = false;
                  if (closed || token != ticket || !running) return;
                  if (problem != null) {
                    running = false;
                    main.post(() -> output.failed(ticket, problem));
                    return;
                  }
                  analyzed++;
                  latency = SystemClock.elapsedRealtime() - now;
                  if (latency > 1200) {
                    tracker.clear();
                    return;
                  }
                  tracker.seed(anchors, o.scene, now);
                  if (o.scene == null) fastPolicy.reset();
                  emit(ticket, o, true, now);
                } finally {
                  anchors.release();
                }
              })) anchors.release();
        });
  }

  private void emit(int epoch, FarmVision.Observation o, boolean full, long when) {
    main.post(() -> output.result(epoch, o, full, when));
  }

  private void error(Throwable e) {
    int ticket = token;
    running = false;
    main.post(() -> output.failed(ticket, e.getClass().getSimpleName()));
  }

  @Override
  public void close() {
    closed = true;
    frames.post(
        () -> {
          running = false;
          tracker.close();
          thumb.release();
          scaled.release();
          modelRgba.release();
          inference.execute(
              () -> {
                if (vision != null) vision.close();
                thread.quitSafely();
              });
          inference.shutdown();
        });
  }
}
