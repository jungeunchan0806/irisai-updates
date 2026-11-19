package com.irisai.farm;

import android.content.Context;
import android.content.SharedPreferences;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

final class Profiles {
  final List<String> names = new ArrayList<>();
  final SharedPreferences prefs;
  private final JSONObject data;

  Profiles(Context context) throws Exception {
    prefs = context.getSharedPreferences("player", Context.MODE_PRIVATE);
    data =
        new JSONObject(
            new String(FarmVision.read(context, "brawlers.json"), StandardCharsets.UTF_8));
    data.keys().forEachRemaining(names::add);
    Collections.sort(names);
  }

  String selected() {
    String name = prefs.getString("brawler", "shelly");
    return data.has(name) ? name : "shelly";
  }

  void save(String name, boolean repeat) {
    if (!data.has(name)) throw new IllegalArgumentException("브롤러를 선택하세요");
    prefs.edit().putString("brawler", name).putBoolean("repeat", repeat).apply();
  }

  boolean repeat() {
    return prefs.getBoolean("repeat", false);
  }

  FarmPolicy.Brawler profile() throws JSONException {
    FarmPolicy.Brawler b = new FarmPolicy.Brawler();
    b.name = selected();
    JSONObject v = data.getJSONObject(b.name);
    b.safeRange = Math.floor(v.getDouble("safe_range") * .5);
    b.attackRange = Math.floor(v.getDouble("attack_range") * .5);
    b.superRange = Math.floor(v.getDouble("super_range") * .5);
    b.superType = v.optString("super_type", "damage");
    b.holdSeconds = v.optDouble("hold_attack", 0);
    b.throughWalls = v.optBoolean("ignore_walls_for_attacks");
    b.superThroughWalls = v.optBoolean("ignore_walls_for_supers");
    return b;
  }
}
