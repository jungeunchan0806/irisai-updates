package com.theseven.practice;

import android.app.*;
import android.content.*;
import android.os.*;
import java.util.*;
import org.json.*;

public final class Scheduler extends BroadcastReceiver {
  static void arm(Context c, JSONObject jobs) {
    AlarmManager manager = c.getSystemService(AlarmManager.class);
    PendingIntent alarm =
        PendingIntent.getBroadcast(
            c,
            19,
            new Intent(c, Scheduler.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    long next = Long.MAX_VALUE;
    Iterator<String> it = jobs.keys();
    while (it.hasNext()) {
      JSONObject j = jobs.optJSONObject(it.next());
      if ("pending".equals(j.optString("status"))) next = Math.min(next, j.optLong("due"));
    }
    if (next == Long.MAX_VALUE) {
      manager.cancel(alarm);
      return;
    }
    if (manager.canScheduleExactAlarms())
      manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, alarm);
    else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, alarm);
  }

  @Override
  public void onReceive(Context c, Intent i) {
    PendingResult pending = goAsync();
    new Thread(
            () -> {
              try {
                new State(c).due();
              } finally {
                pending.finish();
              }
            })
        .start();
  }
}
