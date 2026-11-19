package com.irisai.farm;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;

final class Ui {
  static final int INK = 0xff17271f, MUTED = 0xff496254, ACCENT = 0xff196239, BG = 0xfff3f8f1;

  static int dp(Context c, int v) {
    return Math.round(v * c.getResources().getDisplayMetrics().density);
  }

  static GradientDrawable surface(int color, int radius, Context c) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(c, radius));
    return d;
  }

  static TextView text(Context c, String value, int sp, boolean bold) {
    TextView v = new TextView(c);
    v.setText(value);
    v.setTextSize(sp);
    v.setTextColor(bold ? INK : MUTED);
    if (bold) v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    return v;
  }

  static Button button(Context c, String title, boolean primary) {
    Button b = new Button(c);
    b.setText(title);
    b.setTextSize(15);
    b.setAllCaps(false);
    b.setTextColor(primary ? Color.WHITE : INK);
    b.setBackground(surface(primary ? ACCENT : 0xffdfecdf, 12, c));
    b.setMinHeight(dp(c, 46));
    return b;
  }

  static void add(LinearLayout parent, View child, int gap) {
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.topMargin = dp(parent.getContext(), gap);
    parent.addView(child, p);
  }

  static LinearLayout column(Context c, int pad) {
    LinearLayout p = new LinearLayout(c);
    p.setOrientation(LinearLayout.VERTICAL);
    int n = dp(c, pad);
    p.setPadding(n, n, n, n);
    return p;
  }
}
