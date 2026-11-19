package com.irisai.farm;

import java.util.ArrayDeque;
import org.opencv.core.Core;
import org.opencv.core.Mat;

/**
 * One short, subsampled visual history per inference. All Mat ownership stays on the capture
 * thread.
 */
final class FrameHistory implements AutoCloseable {
  private static final long INTERVAL_MS = 60, MAX_HISTORY_MS = 6000;
  private static final int MAX_SAMPLES = (int) (MAX_HISTORY_MS / INTERVAL_MS) + 1;

  private static final class Sample {
    final long when;
    final Mat gray;

    Sample(long when, Mat gray) {
      this.when = when;
      this.gray = gray.clone();
    }
  }

  private final ArrayDeque<Sample> samples = new ArrayDeque<>();

  void remember(Mat gray, long when) {
    if (!samples.isEmpty() && when - samples.getLast().when < INTERVAL_MS) return;
    if (samples.size() == MAX_SAMPLES) samples.removeFirst().gray.release();
    samples.addLast(new Sample(when, gray));
  }

  FarmPolicy.Frame confirm(
      FrameTracker tracker,
      Mat original,
      FarmPolicy.Frame scene,
      long captured,
      Mat current,
      long latest) {
    tracker.clear();
    if (scene == null || latest < captured) return null;
    // A prior wall map is unusable after a camera jump or stage change.
    double backgroundChange = Core.norm(original, current, Core.NORM_L1) / original.total();
    // If the scene is visually almost identical, verify each character on the current image.
    // This also works after a model cold start that lasted longer than the short history.
    if (backgroundChange < 2.5) {
      tracker.seed(original, scene, latest);
      return tracker.track(current, latest);
    }
    if (backgroundChange > 32 || latest - captured > MAX_HISTORY_MS) return null;
    tracker.seed(original, scene, captured);
    for (Sample sample : samples) {
      if (sample.when > captured
          && sample.when < latest
          && tracker.track(sample.gray, sample.when) == null) return null;
    }
    return tracker.track(current, latest);
  }

  public void close() {
    while (!samples.isEmpty()) samples.removeFirst().gray.release();
  }
}
