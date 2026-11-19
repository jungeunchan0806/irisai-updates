package com.theseven.practice;

import android.content.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

final class State {
  final Context context;
  final SharedPreferences prefs;
  final Catalog catalog;

  State(Context c) {
    context = c.getApplicationContext();
    prefs = c.getSharedPreferences("player", 0);
    try {
      String applied = prefs.getString("theseven.catalog", null);
      try (InputStream in = c.getAssets().open("theseven_catalog.json")) {
        Catalog bundled =
            new Catalog(
                new String(com.theseven.common.Net.read(in, 2000000), StandardCharsets.UTF_8));
        catalog = Catalog.effective(bundled, applied);
      }
    } catch (Exception e) {
      throw new IllegalStateException("연습 데이터 읽기 실패", e);
    }
  }

  JSONObject cfg() {
    JSONObject c = read("theseven.settings");
    Catalog.put(c, "brawler", prefs.getString("brawler", "shelly"));
    for (String[] pair :
        new String[][] {
          {"mode", "duel"}, {"map", "unknown"}, {"style", "balanced"}, {"lang", "ko"}
        }) if (!c.has(pair[0])) Catalog.put(c, pair[0], pair[1]);
    return c;
  }

  JSONObject read(String k) {
    return Catalog.json(prefs.getString(k, "{}"));
  }

  void change(String k, String v) {
    synchronized (State.class) {
      if (k.equals("brawler")) {
        prefs.edit().putString(k, v).apply();
        return;
      }
      JSONObject c = read("theseven.settings");
      Catalog.put(c, k, v);
      prefs.edit().putString("theseven.settings", c.toString()).apply();
    }
  }

  JSONObject inventory(String id) {
    return read("theseven.inventory." + id);
  }

  JSONObject choices(String id) {
    return read("theseven.choices." + id);
  }

  void save(String key, JSONObject o) {
    if (!prefs.edit().putString(key, o.toString()).commit())
      throw new IllegalStateException("저장 공간 확인 필요");
  }

  JSONObject jobs() {
    return read("theseven.jobs");
  }

  boolean saveJob(String id, long due, JSONObject config, JSONObject choices) {
    synchronized (State.class) {
      JSONObject jobs = jobs(), existing = jobs.optJSONObject(id);
      if (existing != null && !existing.optString("status").equals("pending")) return false;
      if (due <= System.currentTimeMillis()) return false;
      JSONObject j = new JSONObject();
      Catalog.put(j, "due", due);
      Catalog.put(j, "config", config);
      Catalog.put(j, "choices", choices);
      Catalog.put(j, "status", "pending");
      Catalog.put(jobs, id, j);
      save("theseven.jobs", jobs);
      Scheduler.arm(context, jobs);
      return true;
    }
  }

  boolean cancel(String id) {
    synchronized (State.class) {
      JSONObject jobs = jobs();
      if (!transition(jobs, id, "cancelled")) return false;
      save("theseven.jobs", jobs);
      Scheduler.arm(context, jobs);
      return true;
    }
  }

  static boolean transition(JSONObject jobs, String id, String next) {
    JSONObject j = jobs.optJSONObject(id);
    if (j == null || !j.optString("status").equals("pending")) return false;
    Catalog.put(j, "status", next);
    return true;
  }

  JSONObject practice(JSONObject config, JSONObject choices, int start) {
    String id = config.optString("brawler");
    JSONObject result =
        Catalog.pick(
            new ArrayList<>(catalog.profiles.keySet()),
            start,
            id,
            inventory(id).optBoolean("locked"));
    if (result.has("error")) return result;
    Catalog.put(result, "equipment", catalog.equipment(id, config, inventory(id), choices));
    Catalog.put(result, "config", config);
    Catalog.put(result, "choices", choices);
    Arena a = new Arena(catalog.profiles.get(id), config);
    JSONArray frames = new JSONArray();
    for (int i = 0; i < 48; i++) {
      a.step();
      frames.put(a.frame());
    }
    Catalog.put(result, "frames", frames);
    Catalog.put(result, "at", System.currentTimeMillis());
    return result;
  }

  void due() {
    synchronized (State.class) {
      JSONObject jobs = jobs();
      long now = System.currentTimeMillis();
      List<String> ids = new ArrayList<>();
      jobs.keys().forEachRemaining(ids::add);
      ids.sort(Comparator.comparingLong(i -> jobs.optJSONObject(i).optLong("due")));
      SharedPreferences.Editor edit = prefs.edit();
      int page = prefs.getInt("theseven.page", 0);
      boolean changed = false;
      for (String id : ids) {
        JSONObject job = jobs.optJSONObject(id);
        if (job.optLong("due") > now || !transition(jobs, id, "done")) continue;
        JSONObject result =
            practice(job.optJSONObject("config"), job.optJSONObject("choices"), page);
        if (result.has("error")) Catalog.put(job, "status", "failed");
        else {
          page = result.optInt("page");
          Catalog.put(result, "job", id);
          edit.putString("theseven.scheduled", id);
          edit.putString("theseven.last", result.toString()).putInt("theseven.page", page);
        }
        JSONObject summary = Catalog.json(result.toString());
        JSONArray frames = (JSONArray) summary.remove("frames");
        if (frames != null)
          Catalog.put(
              summary, "points", frames.optJSONObject(frames.length() - 1).optInt("points"));
        Catalog.put(job, "result", summary);
        changed = true;
      }
      if (changed && !edit.putString("theseven.jobs", jobs.toString()).commit())
        throw new IllegalStateException("예약 저장 실패");
      Scheduler.arm(context, jobs);
    }
  }
}
