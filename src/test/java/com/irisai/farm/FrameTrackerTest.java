package com.irisai.farm;

import static org.junit.Assert.*;

import com.irisai.autogoal.Geometry.P;
import java.util.List;
import org.junit.BeforeClass;
import org.junit.Test;
import org.opencv.core.*;

public class FrameTrackerTest {
  @BeforeClass
  public static void load() {
    nu.pattern.OpenCV.loadLocally();
  }

  private FarmPolicy.Frame scene(int playerX) {
    FarmPolicy.Frame f = new FarmPolicy.Frame();
    f.player = new P(playerX * 3, 240);
    f.enemies = List.of(new P(690, 270));
    return f;
  }

  private void draw(Mat gray, Mat texture, int x, int y) {
    Mat dst = gray.submat(new Rect(x - 5, y - 5, 11, 11));
    try {
      texture.copyTo(dst);
    } finally {
      dst.release();
    }
  }

  @Test
  public void tracksPositionsAcross120DistinctFrames() {
    Mat gray = Mat.zeros(180, 320, CvType.CV_8U), pattern = new Mat(11, 11, CvType.CV_8U);
    Core.randu(pattern, 0, 255);
    try (FrameTracker tracker = new FrameTracker()) {
      for (int i = 0; i < 120; i++) {
        int x = 70 + i;
        gray.setTo(Scalar.all(0));
        draw(gray, pattern, x, 75);
        draw(gray, pattern, 230, 85);
        long when = i * 1000L / 120;
        if (i % 30 == 0) tracker.seed(gray, scene(x), when);
        FarmPolicy.Frame frame = tracker.track(gray, when);
        assertNotNull(frame);
        assertEquals(x * 3, frame.player.x, 0.1);
        assertEquals(240, frame.player.y, 0.1);
        assertEquals(690, frame.enemies.get(0).x, 0.1);
      }
      assertNull(tracker.track(gray, 2000));
    } finally {
      gray.release();
      pattern.release();
    }
  }

  @Test
  public void rejectsBlankAndDisappearingAnchors() {
    Mat gray = Mat.zeros(180, 320, CvType.CV_8U);
    try (FrameTracker tracker = new FrameTracker()) {
      tracker.seed(gray, scene(100), 0);
      assertNull(tracker.track(gray, 1));
      Core.randu(gray, 0, 255);
      tracker.seed(gray, scene(100), 2);
      gray.setTo(Scalar.all(0));
      assertNull(tracker.track(gray, 3));
    } finally {
      gray.release();
    }
  }
}
