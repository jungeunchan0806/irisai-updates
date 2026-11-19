package com.theseven.practice;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import com.theseven.common.Ui;

public final class QuickService extends Service {
  ImageButton gear;
  WindowManager wm;

  @Override
  public int onStartCommand(Intent intent, int flags, int id) {
    if (intent != null && "stop".equals(intent.getAction()) || !Settings.canDrawOverlays(this)) {
      stopSelf();
      return START_NOT_STICKY;
    }
    NotificationManager n = getSystemService(NotificationManager.class);
    n.createNotificationChannel(
        new NotificationChannel("theseven.quick", "빠른 설정", NotificationManager.IMPORTANCE_LOW));
    PendingIntent close =
        PendingIntent.getService(
            this,
            3,
            new Intent(this, QuickService.class).setAction("stop"),
            PendingIntent.FLAG_IMMUTABLE);
    startForeground(
        7,
        new Notification.Builder(this, "theseven.quick")
            .setSmallIcon(R.drawable.theseven_gear)
            .setContentTitle("더세븐 오토팜 · 빠른 설정")
            .addAction(new Notification.Action.Builder(null, "종료", close).build())
            .build());
    if (gear != null) return START_NOT_STICKY;
    wm = getSystemService(WindowManager.class);
    gear = new ImageButton(this);
    gear.setContentDescription("빠른 설정 열기");
    gear.setImageResource(R.drawable.theseven_gear);
    GradientDrawable bg = new GradientDrawable();
    bg.setShape(GradientDrawable.OVAL);
    bg.setColor(0xff214039);
    gear.setBackground(bg);
    int size = Ui.dp(this, 48);
    WindowManager.LayoutParams lp =
        new WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT);
    lp.setTitle("theseven.quick.gear");
    lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
    lp.y = Ui.dp(this, 8);
    gear.setOnClickListener(
        v ->
            startActivity(
                new Intent(this, Quick.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)));
    try {
      wm.addView(gear, lp);
    } catch (RuntimeException e) {
      gear = null;
      stopSelf();
    }
    return START_NOT_STICKY;
  }

  public IBinder onBind(Intent i) {
    return null;
  }

  public void onDestroy() {
    if (gear != null) {
      wm.removeView(gear);
      gear = null;
    }
    stopForeground(STOP_FOREGROUND_REMOVE);
    super.onDestroy();
  }
}
