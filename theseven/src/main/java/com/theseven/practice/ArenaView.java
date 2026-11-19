package com.theseven.practice;

import android.content.*;
import android.graphics.*;
import android.view.*;
import org.json.*;

final class ArenaView extends View {
  JSONObject f;
  Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

  ArenaView(Context c) {
    super(c);
    setContentDescription("독립 연습 시뮬레이터");
  }

  void show(JSONObject frame) {
    f = frame;
    invalidate();
  }

  void dot(Canvas c, String x, String y, int color, float size) {
    p.setColor(color);
    c.drawCircle((float) f.optDouble(x), (float) f.optDouble(y), size, p);
  }

  protected void onDraw(Canvas c) {
    c.drawColor(0xff173937);
    if (f == null) return;
    c.save();
    c.scale(getWidth() / 960f, getHeight() / 530f);
    p.setColor(0xff31524e);
    for (int i = 0; i < 960; i += 60) c.drawLine(i, 0, i, 530, p);
    for (int i = 0; i < 530; i += 60) c.drawLine(0, i, 960, i, p);
    if (f.optInt("shield") > 0) dot(c, "allyX", "allyY", 0xffd4f7ff, 30);
    if (f.optInt("mark") > 0) dot(c, "enemyX", "enemyY", 0xffffef73, 28);
    dot(c, "x", "y", 0xff7ac4ff, 22);
    dot(c, "allyX", "allyY", 0xff92e6a1, 18);
    dot(c, "enemyX", "enemyY", 0xffff9971, 20);
    if (f.optBoolean("moth")) dot(c, "mothX", "mothY", 0xffffd8ff, 10);
    c.restore();
  }
}
