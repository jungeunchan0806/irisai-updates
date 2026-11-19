package com.theseven.common;

import android.content.*;
import android.graphics.Typeface;
import android.view.*;
import android.widget.*;
import java.util.function.Consumer;

public final class Ui {
  public static final int DARK = 0xff132a2a, TEAL = 0xff00695c;

  public static int dp(Context c, int n) {
    return Math.round(c.getResources().getDisplayMetrics().density * n);
  }

  public static LinearLayout col(Context c) {
    LinearLayout l = new LinearLayout(c);
    l.setOrientation(1);
    l.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 12));
    return l;
  }

  public static TextView text(LinearLayout l, String s, int size) {
    TextView t = new TextView(l.getContext());
    t.setText(s);
    t.setTextColor(DARK);
    t.setTextSize(size);
    t.setPadding(0, dp(l.getContext(), 5), 0, dp(l.getContext(), 5));
    if (size >= 21) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    l.addView(t);
    return t;
  }

  public static Button button(LinearLayout l, String s, Runnable r) {
    Button b = new Button(l.getContext());
    b.setText(s);
    b.setAllCaps(false);
    b.setOnClickListener(v -> r.run());
    l.addView(b);
    return b;
  }

  public static void select(
      LinearLayout l, String label, String[] values, int index, Consumer<Integer> on) {
    text(l, label, 14);
    Spinner s = new Spinner(l.getContext());
    ArrayAdapter<String> a =
        new ArrayAdapter<>(l.getContext(), android.R.layout.simple_spinner_item, values);
    a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    s.setAdapter(a);
    s.setSelection(Math.max(0, index), false);
    l.addView(s);
    s.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          int prior = Math.max(0, index);

          public void onNothingSelected(AdapterView<?> p) {}

          public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
            if (pos != prior) {
              prior = pos;
              on.accept(pos);
            }
          }
        });
  }

  public static LinearLayout screen(android.app.Activity a) {
    ScrollView scroll = new ScrollView(a);
    scroll.setFillViewport(true);
    scroll.setBackgroundColor(0xfff5f8f6);
    scroll.setOnApplyWindowInsetsListener(
        (v, w) -> {
          android.graphics.Insets i =
              w.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
          v.setPadding(i.left, i.top, i.right, i.bottom);
          return w;
        });
    LinearLayout l = col(a);
    scroll.addView(l);
    a.setContentView(scroll);
    return l;
  }
}
