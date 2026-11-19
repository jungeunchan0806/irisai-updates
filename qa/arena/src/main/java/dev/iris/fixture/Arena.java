package dev.iris.fixture;

import android.app.Activity;
import android.graphics.*;
import android.os.Bundle;
import android.util.Log;
import android.view.*;
import android.widget.*;
import java.io.InputStream;

/**
 * A recorded battle under a tiny animated marker. Only the marker changes between display frames.
 */
public class Arena extends Activity {
  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().setDecorFitsSystemWindows(false);
    FrameLayout arena = new FrameLayout(this);
    ImageView background = new ImageView(this);
    background.setScaleType(ImageView.ScaleType.FIT_XY);
    try (InputStream in = getAssets().open("held_turn.jpg")) {
      background.setImageBitmap(BitmapFactory.decodeStream(in));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    arena.addView(background, new FrameLayout.LayoutParams(-1, -1));
    View marker =
        new View(this) {
          int frames;

          @Override
          protected void onDraw(Canvas c) {
            c.drawColor(((frames++ & 1) == 0) ? Color.WHITE : Color.BLACK);
            postInvalidateOnAnimation();
          }
        };
    arena.addView(marker, new FrameLayout.LayoutParams(8, 8, Gravity.BOTTOM | Gravity.START));
    arena.setOnTouchListener(
        (v, e) -> {
          if (e.getActionMasked() == MotionEvent.ACTION_DOWN
              || e.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) {
            int index = e.getActionIndex();
            float x = e.getX(index) / v.getWidth(), y = e.getY(index) / v.getHeight();
            if (y > .58f && (x < .22f || x > .67f)) Log.i("IrisArena", "hud_touch " + x + " " + y);
          }
          return true;
        });
    setContentView(arena);
    arena.post(
        () -> {
          arena.getWindowInsetsController().hide(WindowInsets.Type.systemBars());
          Log.i("IrisArena", "fixture_ready");
        });
  }
}
