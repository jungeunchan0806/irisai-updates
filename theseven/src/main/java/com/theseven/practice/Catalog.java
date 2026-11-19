package com.theseven.practice;

import java.util.*;
import org.json.*;

public final class Catalog {
  final JSONObject root, items;
  final LinkedHashMap<String, JSONObject> profiles = new LinkedHashMap<>();
  static final String[] SLOTS = {"gadget", "star", "gear1", "gear2"};

  Catalog(String raw) throws JSONException {
    root = new JSONObject(raw);
    if (root.getInt("schema") != 1 || root.getInt("version") < 1)
      throw new JSONException("데이터 버전 오류");
    items = root.getJSONObject("items");
    JSONArray rows = root.getJSONArray("brawlers");
    for (int i = 0; i < rows.length(); i++) {
      JSONObject b = rows.getJSONObject(i);
      String id = b.getString("id");
      if (profiles.put(id, b) != null || id.trim().isEmpty()) throw new JSONException("브롤러 ID 오류");
      for (String key : new String[] {"ko", "en", "mechanic"}) b.getString(key);
      JSONObject reload = b.getJSONObject("reload"), hyper = b.getJSONObject("hyper");
      for (String key : List.of("hp", "range", "speed"))
        if (!b.has(key)) throw new JSONException("능력치 필드 누락");
      if (!reload.has("type")
          || !reload.has("seconds")
          || !hyper.has("ko")
          || !hyper.has("en")
          || !hyper.has("effect")) throw new JSONException("능력 데이터 누락");
      for (int level = 1; level <= 11; level++) {
        JSONObject stats = b.getJSONArray("powers").getJSONObject(level - 1);
        if (stats.getInt("power") != level || !stats.has("attack") || !stats.has("super"))
          throw new JSONException("파워 단계 오류");
      }
      if (b.getJSONArray("gadget").length() != 2 || b.getJSONArray("star").length() != 2)
        throw new JSONException("장비 수 오류");
      if (b.getJSONArray("powers").length() != 11) throw new JSONException("파워 데이터 오류");
      for (String group : new String[] {"gear", "gadget", "star"}) {
        JSONArray choices = b.getJSONArray(group);
        for (int j = 0; j < choices.length(); j++)
          if (!items.getJSONObject(choices.getString(j)).getString("category").equals(group))
            throw new JSONException("장비 분류 오류");
      }
    }
    for (String id : List.of("shelly", "thunder_frogs", "vince", "cosmo", "wendy", "nori"))
      if (!profiles.containsKey(id)) throw new JSONException("필수 브롤러 누락");
  }

  static JSONObject obj(JSONObject o, String k) {
    JSONObject x = o.optJSONObject(k);
    return x == null ? new JSONObject() : x;
  }

  static JSONObject put(JSONObject o, String k, Object v) {
    try {
      return o.put(k, v);
    } catch (JSONException e) {
      throw new IllegalArgumentException(e);
    }
  }

  static JSONObject json(String raw) {
    try {
      return new JSONObject(raw);
    } catch (JSONException e) {
      throw new IllegalArgumentException(e);
    }
  }

  String name(String id, String lang) {
    JSONObject b = profiles.get(id);
    return b == null ? id : b.optString(lang, id);
  }

  String item(String id, String lang) {
    JSONObject v = items.optJSONObject(id);
    if (v == null) return id;
    if (!v.isNull(lang)) return v.optString(lang);
    String group = v.optString("category");
    return (group.equals("star") ? "스타파워" : "가젯")
        + " "
        + id.substring(id.lastIndexOf('.') + 1)
        + " · 이름 미확인";
  }

  static String group(String slot) {
    return slot.startsWith("gear") ? "gear" : slot;
  }

  static List<String> strings(JSONArray a) {
    List<String> s = new ArrayList<>();
    if (a != null) for (int i = 0; i < a.length(); i++) s.add(a.optString(i));
    return s;
  }

  JSONObject equipment(String id, JSONObject config, JSONObject inventory, JSONObject choices) {
    JSONObject out = new JSONObject(), b = profiles.get(id);
    if (b == null) return out;
    Set<String> taken = new HashSet<>();
    int gears = inventory.optInt("slots", 0);
    // Reserve all eligible manual gear first, including a choice in the second slot.
    List<String> order = new ArrayList<>(Arrays.asList(SLOTS));
    order.sort(Comparator.comparing(s -> choices.optString(s, "auto").equals("auto")));
    for (String slot : order) {
      JSONObject result = new JSONObject();
      put(out, slot, result);
      String preference = choices.optString(slot, "auto");
      boolean auto = preference.equals("auto");
      put(result, "selection", preference);
      String reason = "보유·장착 가능으로 확인된 항목 없음", best = null;
      int max = -1;
      List<String> eligible = strings(b.optJSONArray(group(slot)));
      if (slot.startsWith("gear") && Character.digit(slot.charAt(4), 10) > gears) {
        put(result, "reason", "활성 기어 슬롯 미확인 또는 비활성");
        continue;
      }
      for (String option : eligible) {
        if (!auto && !preference.equals(option)) continue;
        if (!inventory.optBoolean(option, false) || taken.contains(option)) continue;
        List<String> tags = strings(items.optJSONObject(option).optJSONArray("tags"));
        List<String> matches = new ArrayList<>();
        int score = 0;
        for (String field : List.of("mode", "map", "style"))
          if (tags.contains(config.optString(field, ""))) {
            matches.add(config.optString(field));
            score += 2;
          }
        for (String tag : strings(b.optJSONArray("tags")))
          if (tags.contains(tag)) {
            score++;
            matches.add(name(id, "ko"));
            break;
          }
        if (score > max) {
          max = score;
          best = option;
          reason =
              !auto
                  ? "직접 선택"
                  : matches.isEmpty()
                      ? "보유·장착 가능 · 브롤러 호환"
                      : "조건 일치 · " + String.join(", ", matches);
        }
      }
      if (best != null) {
        put(result, "id", best);
        taken.add(best);
      } else if (!auto) reason = "직접 지정 유지 · 보유, 슬롯 또는 중복 조건 불충족";
      put(result, "reason", reason);
    }
    return out;
  }

  static JSONObject pick(List<String> ids, int page, String id, boolean locked) {
    final int size = 8,
        last = Math.max(0, (ids.size() - 1) / size),
        start = Math.max(0, Math.min(last, page));
    List<Integer> pages = new ArrayList<>();
    for (int i = start; i <= last; i++) pages.add(i);
    for (int i = last - 1; i >= 0; i--) pages.add(i);
    JSONArray visited = new JSONArray();
    JSONObject result = new JSONObject();
    put(result, "pages", visited);
    for (int p : pages) {
      visited.put(p);
      for (int i = p * size; i < Math.min(ids.size(), (p + 1) * size); i++) {
        if (!ids.get(i).equals(id)) continue;
        put(result, "page", p);
        if (locked) put(result, "error", "잠긴 브롤러");
        else put(result, "id", id);
        return result;
      }
    }
    return put(result, "error", "목록에 없는 브롤러");
  }
}
