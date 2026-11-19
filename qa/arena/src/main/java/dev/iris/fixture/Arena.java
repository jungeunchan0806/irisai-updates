package dev.iris.fixture;

import android.app.Activity;
import android.graphics.*;
import android.os.Bundle;
import android.util.Log;
import android.view.*;
import java.io.InputStream;

/**
 * Offline fixture installed only in CI's blank emulator. Records touches reaching the battle HUD.
 */
public class Arena extends Activity {
  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().setDecorFitsSystemWindows(false);
    Bitmap battle;
    try (InputStream stream = getAssets().open("held_turn.jpg")) {
      battle = BitmapFactory.decodeStream(stream);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    setContentView(
        new View(this) {
          final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
          final Rect screen = new Rect();
          int frames;

          protected void onDraw(Canvas c) {
            screen.set(0, 0, getWidth(), getHeight());
            c.drawBitmap(battle, null, screen, paint);
            paint.setColor(((frames++ % 2) == 0) ? Color.WHITE : Color.BLACK);
            c.drawRect(0, getHeight() - 8, 8, getHeight(), paint);
            postInvalidateOnAnimation();
          }

          public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN
                || e.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) {
              int index = e.getActionIndex();
              float x = e.getX(index) / getWidth(), y = e.getY(index) / getHeight();
              if (y > .58f && (x < .22f || x > .67f))
                Log.i("IrisArena", "hud_touch " + x + " " + y);
            }
            return true;
          }
        });
    View decor = getWindow().getDecorView();
    decor.post(
        () -> {
          decor.getWindowInsetsController().hide(WindowInsets.Type.systemBars());
          Log.i("IrisArena", "fixture_ready");
        });
  }
}
