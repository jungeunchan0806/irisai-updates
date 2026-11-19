package com.theseven.practice;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import com.theseven.common.*;
import java.util.*;
import java.util.function.*;
import org.json.*;

public class Home extends Activity {
  State s;
  LinearLayout panel;
  String page = "home";
  boolean inlineQuick, foreground;
  long activeUntil;
  String activePlan = "";
  int licenseRequest;
  final Handler handler = new Handler(Looper.getMainLooper());
  final Runnable tick =
      new Runnable() {
        public void run() {
          JSONObject jobs = s.jobs();
          long now = System.currentTimeMillis();
          Iterator<String> it = jobs.keys();
          while (it.hasNext()) {
            JSONObject j = jobs.optJSONObject(it.next());
            if ("pending".equals(j.optString("status")) && j.optLong("due") <= now) {
              s.due();
              if (page.equals("jobs")) jobs();
              break;
            }
          }
          handler.postDelayed(this, 1000);
        }
      };

  boolean compact() {
    return false;
  }

  boolean quick() {
    return compact() || inlineQuick;
  }

  public void onCreate(Bundle b) {
    super.onCreate(b);
    s = new State(this);
    home();
    if (!compact()) {
      update(false);
      checkSubscription(null, null);
    }
  }

  public void onResume() {
    super.onResume();
    foreground = true;
    if (compact())
      getWindow()
          .setLayout(
              (int) (getResources().getDisplayMetrics().widthPixels * .9),
              WindowManager.LayoutParams.WRAP_CONTENT);
    if (page.equals("home")) home();
    handler.post(tick);
  }

  public void onPause() {
    foreground = false;
    handler.removeCallbacks(tick);
    super.onPause();
  }

  LinearLayout start(String id, String name) {
    page = id;
    panel = Ui.screen(this);
    if (!id.equals("home")) Ui.button(panel, "‹ 돌아가기", this::home);
    Ui.text(panel, name, 25);
    return panel;
  }

  String lang() {
    return s.cfg().optString("lang", "ko");
  }

  String id() {
    return s.cfg().optString("brawler", "shelly");
  }

  void note(String msg) {
    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
  }

  void dialog(String title, String value) {
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(value)
        .setPositiveButton("닫기", null)
        .show();
  }

  static final String[] MODES = {"duel", "duo", "ball"},
      MAPS = {"unknown", "open", "bush"},
      STYLES = {"balanced", "aggressive", "defensive", "support"};
  static final String[] MODE_NAMES = {"결투", "듀오", "브롤볼"},
      MAP_NAMES = {"맵 정보 없음", "개방 지형", "수풀 지형"},
      STYLE_NAMES = {"균형", "공격", "수비", "지원"};

  void choose(String label, String[] ids, String[] labels, String current, Consumer<String> on) {
    new AlertDialog.Builder(this)
        .setTitle(label)
        .setSingleChoiceItems(
            labels,
            Arrays.asList(ids).indexOf(current),
            (dialog, i) -> {
              dialog.dismiss();
              on.accept(ids[i]);
            })
        .setNegativeButton("취소", null)
        .show();
  }

  String display(String value, String[] ids, String[] labels) {
    int i = Arrays.asList(ids).indexOf(value);
    return i < 0 ? value : labels[i];
  }

  void home() {
    boolean q = quick();
    start("home", q ? "빠른 설정" : "더세븐 오토팜");
    JSONObject c = s.cfg();
    Ui.button(
        panel,
        "브롤러 · " + s.catalog.name(id(), lang()),
        () ->
            brawlers(
                id(),
                v -> {
                  s.change("brawler", v);
                  if (q) home();
                  else configure();
                }));
    Ui.button(
        panel,
        "모드 · " + display(c.optString("mode"), MODES, MODE_NAMES),
        () ->
            choose(
                "모드",
                MODES,
                MODE_NAMES,
                c.optString("mode"),
                v -> {
                  s.change("mode", v);
                  home();
                }));
    Ui.button(
        panel,
        "플레이스타일 · " + display(c.optString("style"), STYLES, STYLE_NAMES),
        () ->
            choose(
                "플레이스타일",
                STYLES,
                STYLE_NAMES,
                c.optString("style"),
                v -> {
                  s.change("style", v);
                  home();
                }));
    Ui.button(panel, "브롤러 예약", this::jobs);
    if (q) {
      Ui.button(
          panel,
          "닫기",
          () -> {
            if (compact()) finish();
            else {
              inlineQuick = false;
              home();
            }
          });
      return;
    }
    Ui.button(panel, "연습 시작", () -> runPractice());
    Ui.button(panel, "브롤러와 장비", this::configure);
    Ui.button(panel, "라이선스 등록", this::license);
    Ui.button(panel, "일반 설정", this::settings);
    Ui.button(panel, "앱 정보", this::about);
  }

  List<String> ids() {
    return new ArrayList<>(s.catalog.profiles.keySet());
  }

  void brawlers(String current, Consumer<String> on) {
    List<String> ids = ids();
    String[] labels =
        ids.stream()
            .map(
                i ->
                    s.catalog.name(i, lang())
                        + (s.inventory(i).optBoolean("locked") ? " · 잠김" : ""))
            .toArray(String[]::new);
    choose("브롤러 선택", ids.toArray(new String[0]), labels, current, on);
  }

  void settings() {
    start("settings", "일반 설정");
    JSONObject c = s.cfg();
    Ui.button(
        panel,
        "브롤러 표시 언어 · " + lang(),
        () ->
            choose(
                "표시 언어",
                new String[] {"ko", "en"},
                new String[] {"한국어", "English"},
                lang(),
                v -> {
                  s.change("lang", v);
                  settings();
                }));
    Ui.button(
        panel,
        "맵 · " + display(c.optString("map"), MAPS, MAP_NAMES),
        () ->
            choose(
                "연습 맵",
                MAPS,
                MAP_NAMES,
                c.optString("map"),
                v -> {
                  s.change("map", v);
                  settings();
                }));
    CheckBox repeat = new CheckBox(this);
    repeat.setText("연습 반복");
    repeat.setChecked(s.prefs.getBoolean("repeat", false));
    panel.addView(repeat);
    repeat.setOnCheckedChangeListener((b, on) -> s.prefs.edit().putBoolean("repeat", on).apply());
    Ui.button(
        panel,
        "작은 톱니바퀴 표시",
        () -> {
          if (Settings.canDrawOverlays(this))
            startForegroundService(new Intent(this, QuickService.class));
          else {
            note("권한을 허용한 뒤 표시 버튼을 한 번 더 눌러 주세요");
            try {
              startActivity(
                  new Intent(
                      Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                      Uri.parse("package:" + getPackageName())));
            } catch (ActivityNotFoundException e) {
              inlineQuick = true;
              home();
            }
          }
        });
    Ui.button(panel, "작은 톱니바퀴 닫기", () -> stopService(new Intent(this, QuickService.class)));
    Ui.button(
        panel,
        "앱 안에서 빠른 설정",
        () -> {
          inlineQuick = true;
          home();
        });
    AlarmManager alarms = getSystemService(AlarmManager.class);
    Ui.button(
        panel,
        alarms.canScheduleExactAlarms() ? "정시 예약 허용됨" : "정시 예약 허용 · 절전 중 지연 가능",
        () -> {
          if (!alarms.canScheduleExactAlarms())
            try {
              startActivity(
                  new Intent(
                      Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                      Uri.parse("package:" + getPackageName())));
            } catch (ActivityNotFoundException e) {
              note("이 기기는 일반 예약을 사용합니다");
            }
        });
    Ui.button(panel, "업데이트 확인", () -> update(true));
    Ui.text(panel, "연습 데이터 v" + s.catalog.root.optInt("version"), 14);
  }

  void about() {
    start("about", "앱 정보");
    ImageView img = new ImageView(this);
    img.setImageResource(R.drawable.theseven_logo);
    img.setContentDescription("더세븐 오토팜 앱 아이콘");
    panel.addView(img, Ui.dp(this, 56), Ui.dp(this, 56));
    Ui.text(panel, "더세븐 오토팜 2.0.0", 20);
    Ui.text(panel, "개발자: @asdf027949", 16);
    Ui.button(
        panel,
        "Discord",
        () -> {
          try {
            startActivity(
                new Intent(Intent.ACTION_VIEW, Uri.parse("https://discord.gg/bshack"))
                    .addCategory(Intent.CATEGORY_BROWSABLE));
          } catch (ActivityNotFoundException e) {
            note("링크를 열 브라우저가 없습니다");
          }
        });
  }

  void configure() {
    String id = id();
    start("equipment", s.catalog.name(id, lang()));
    Ui.button(
        panel,
        "브롤러 선택",
        () ->
            brawlers(
                id,
                v -> {
                  s.change("brawler", v);
                  configure();
                }));
    Ui.button(panel, "브롤러 능력치", () -> details(id));
    Ui.button(panel, "연습 보유·장착 가능 설정", () -> inventory(id));
    JSONObject choice = s.choices(id);
    equipment(
        id,
        s.cfg(),
        choice,
        () -> {
          s.save("theseven.choices." + id, choice);
          configure();
        });
  }

  static String value(JSONObject o, String k) {
    return o.isNull(k) ? "미확인" : o.optString(k);
  }

  void details(String id) {
    JSONObject b = s.catalog.profiles.get(id);
    if (b == null) {
      note("목록에 없는 브롤러");
      return;
    }
    StringBuilder out =
        new StringBuilder()
            .append(b.optString("ko"))
            .append(" / ")
            .append(b.optString("en"))
            .append("\n체력 ")
            .append(value(b, "hp"))
            .append(" · 사거리 ")
            .append(value(b, "range"))
            .append(" · 이동속도 ")
            .append(value(b, "speed"))
            .append("\n장전 ");
    JSONObject reload = b.optJSONObject("reload");
    out.append(
            "after_two_rounds".equals(reload.optString("type"))
                ? "두 발 소진 후"
                : value(reload, "type"))
        .append(" · 시간 ")
        .append(value(reload, "seconds"));
    JSONArray levels = b.optJSONArray("powers");
    for (int i = 0; i < levels.length(); i++) {
      JSONObject p = levels.optJSONObject(i);
      out.append("\n파워 ")
          .append(i + 1)
          .append("  일반 ")
          .append(value(p, "attack"))
          .append(" / 특수 ")
          .append(value(p, "super"));
    }
    for (String g : new String[] {"gadget", "star"})
      for (String key : Catalog.strings(b.optJSONArray(g)))
        out.append("\n")
            .append(s.catalog.item(key, lang()))
            .append(" · 효과 ")
            .append(value(s.catalog.items.optJSONObject(key), "effect"));
    out.append("\n하이퍼차지 · ")
        .append(value(b.optJSONObject("hyper"), lang()))
        .append(" · 효과 ")
        .append(value(b.optJSONObject("hyper"), "effect"));
    dialog("브롤러 데이터", out.toString());
  }

  void inventory(String id) {
    start("inventory", "연습 보유·장착 가능 설정");
    JSONObject inv = s.inventory(id), b = s.catalog.profiles.get(id);
    Consumer<Object> save = v -> s.save("theseven.inventory." + id, inv);
    Ui.select(
        panel,
        "활성 기어 슬롯",
        new String[] {"미확인 / 0", "1", "2"},
        inv.optInt("slots"),
        i -> {
          Catalog.put(inv, "slots", i);
          save.accept(i);
        });
    flag(
        "연습 목록에서 잠금",
        inv.optBoolean("locked"),
        v -> {
          Catalog.put(inv, "locked", v);
          save.accept(v);
        });
    for (String group : new String[] {"gadget", "gear", "star"})
      for (String k : Catalog.strings(b.optJSONArray(group)))
        flag(
            s.catalog.item(k, lang()),
            inv.optBoolean(k),
            v -> {
              Catalog.put(inv, k, v);
              save.accept(v);
            });
    Ui.button(panel, "장비 추천 보기", this::configure);
  }

  void flag(String title, boolean on, Consumer<Boolean> save) {
    CheckBox b = new CheckBox(this);
    b.setText(title);
    b.setChecked(on);
    panel.addView(b);
    b.setOnCheckedChangeListener((v, value) -> save.accept(value));
  }

  String slot(String key) {
    switch (key) {
      case "star":
        return "스타파워";
      case "gadget":
        return "가젯";
      case "gear1":
        return "기어 1";
      default:
        return "기어 2";
    }
  }

  void equipment(String id, JSONObject config, JSONObject choices, Runnable changed) {
    JSONObject profile = s.catalog.profiles.get(id);
    if (profile == null) {
      Ui.text(panel, "목록에 없는 브롤러", 16);
      return;
    }
    JSONObject inv = s.inventory(id), rec = s.catalog.equipment(id, config, inv, choices);
    for (String k : Catalog.SLOTS) {
      JSONObject r = rec.optJSONObject(k);
      String selected = choices.optString(k, "auto");
      List<String> opts = new ArrayList<>();
      opts.add("auto");
      for (String option : Catalog.strings(profile.optJSONArray(Catalog.group(k))))
        if (inv.optBoolean(option) || selected.equals(option)) opts.add(option);
      String[] labels =
          opts.stream()
              .map(o -> o.equals("auto") ? "자동 추천" : "직접 선택 · " + s.catalog.item(o, lang()))
              .toArray(String[]::new);
      Ui.button(
          panel,
          slot(k)
              + " · "
              + (selected.equals("auto") ? "자동 추천" : "직접 선택 · " + s.catalog.item(selected, lang())),
          () ->
              choose(
                  slot(k),
                  opts.toArray(new String[0]),
                  labels,
                  selected,
                  o -> {
                    Catalog.put(choices, k, o);
                    changed.run();
                  }));
      Ui.text(
          panel,
          (r.has("id") ? s.catalog.item(r.optString("id"), lang()) : "미장착")
              + " · "
              + r.optString("reason"),
          14);
    }
  }

  void jobs() {
    start("jobs", "브롤러 예약");
    Ui.button(panel, "예약 추가", () -> new JobForm(null).show());
    JSONObject jobs = s.jobs();
    List<String> ids = new ArrayList<>();
    jobs.keys().forEachRemaining(ids::add);
    ids.sort(Comparator.comparingLong(k -> jobs.optJSONObject(k).optLong("due")));
    for (String k : ids) {
      JSONObject j = jobs.optJSONObject(k), res = j.optJSONObject("result");
      String b = j.optJSONObject("config").optString("brawler");
      String status = j.optString("status");
      String[] keys = {"pending", "cancelled", "done", "failed"}, labels = {"대기", "취소", "완료", "중단"};
      Ui.text(
          panel,
          Net.date(j.optLong("due"))
              + " · "
              + s.catalog.name(b, lang())
              + " · "
              + display(status, keys, labels)
              + (res != null && res.has("error") ? " · " + res.optString("error") : ""),
          16);
      if (status.equals("pending")) {
        Ui.button(panel, "예약 수정", () -> new JobForm(k).show());
        Ui.button(
            panel,
            "예약 취소",
            () -> {
              s.cancel(k);
              jobs();
            });
      }
    }
    if (!quick() && s.read("theseven.last").has("frames"))
      Ui.button(panel, "최근 연습 다시 보기", () -> replay(s.read("theseven.last"), true));
    if (ids.isEmpty()) Ui.text(panel, "저장된 예약이 없습니다", 16);
  }

  final class JobForm {
    String key;
    JSONObject config, choices;
    long due;

    JobForm(String edit) {
      key = edit == null ? UUID.randomUUID().toString() : edit;
      JSONObject old = s.jobs().optJSONObject(key);
      config = old == null ? s.cfg() : old.optJSONObject("config");
      choices = old == null ? s.choices(config.optString("brawler")) : old.optJSONObject("choices");
      due = old == null ? System.currentTimeMillis() + 300000 : old.optLong("due");
    }

    void show() {
      start("job", "예약 편집");
      String id = config.optString("brawler");
      Ui.button(
          panel,
          "브롤러 · " + s.catalog.name(id, lang()),
          () ->
              brawlers(
                  id,
                  v -> {
                    if (!id.equals(v)) {
                      Catalog.put(config, "brawler", v);
                      choices = s.choices(v);
                    }
                    show();
                  }));
      field("mode", "모드", MODES, MODE_NAMES);
      field("style", "플레이스타일", STYLES, STYLE_NAMES);
      if (!quick()) field("map", "맵", MAPS, MAP_NAMES);
      Ui.button(
          panel,
          "실행 시각 · " + Net.date(due),
          () -> {
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(due);
            new DatePickerDialog(
                    Home.this,
                    (p, y, m, d) -> {
                      cal.set(y, m, d);
                      new TimePickerDialog(
                              Home.this,
                              (t, h, min) -> {
                                cal.set(Calendar.HOUR_OF_DAY, h);
                                cal.set(Calendar.MINUTE, min);
                                cal.set(Calendar.SECOND, 0);
                                cal.set(Calendar.MILLISECOND, 0);
                                due = cal.getTimeInMillis();
                                show();
                              },
                              cal.get(Calendar.HOUR_OF_DAY),
                              cal.get(Calendar.MINUTE),
                              true)
                          .show();
                    },
                    cal.get(Calendar.YEAR),
                    cal.get(Calendar.MONTH),
                    cal.get(Calendar.DAY_OF_MONTH))
                .show();
          });
      if (!quick()) equipment(id, config, choices, this::show);
      Ui.button(
          panel,
          "예약 저장",
          () -> {
            if (s.saveJob(key, due, config, choices)) jobs();
            else note("미래 시각과 예약 상태를 확인하세요");
          });
    }

    void field(String k, String label, String[] ids, String[] names) {
      Ui.button(
          panel,
          label + " · " + display(config.optString(k), ids, names),
          () ->
              choose(
                  label,
                  ids,
                  names,
                  config.optString(k),
                  v -> {
                    Catalog.put(config, k, v);
                    show();
                  }));
    }
  }

  void license() {
    start("license", "라이선스 등록");
    TextView state = Ui.text(panel, "구독 확인 중…", 18);
    EditText input = new EditText(this);
    input.setHint("구매자 라이선스 키");
    input.setSingleLine();
    input.setInputType(129);
    input.setSaveEnabled(false);
    input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
    panel.addView(input);
    Ui.button(
        panel,
        "키 등록",
        () -> {
          String key = input.getText().toString().trim();
          input.setText("");
          if (key.isEmpty()) {
            state.setText("키를 입력하세요");
            return;
          }
          state.setText("등록 확인 중…");
          checkSubscription(key, state);
        });
    Ui.button(panel, "상태 새로고침", () -> checkSubscription(null, state));
    Ui.button(
        panel,
        "구독 연습 리포트",
        () -> {
          checkSubscription(
              null,
              state,
              () -> {
                JSONObject result = s.read("theseven.last");
                JSONArray frames = result.optJSONArray("frames");
                if (frames == null) {
                  note("연습을 먼저 실행하세요");
                  return;
                }
                JSONObject last = frames.optJSONObject(frames.length() - 1);
                dialog(
                    activePlan + " · 연습 리포트",
                    s.catalog.name(result.optString("id"), lang())
                        + "\n명중 "
                        + last.optInt("hits")
                        + "회 · 연습 점수 "
                        + last.optInt("points")
                        + "\n"
                        + Net.date(result.optLong("at")));
              });
        });
    checkSubscription(null, state);
  }

  void checkSubscription(String key, TextView target) {
    checkSubscription(key, target, null);
  }

  void checkSubscription(String key, TextView target, Runnable activated) {
    int req = ++licenseRequest;
    new Thread(
            () -> {
              JSONObject result;
              try {
                result =
                    Net.call(
                        "subscription",
                        key == null
                            ? Net.fields("action", "check")
                            : Net.fields("action", "redeem", "key", key));
              } catch (Exception e) {
                result = Net.fields("status", "network");
              }
              JSONObject r = result;
              runOnUiThread(
                  () -> {
                    if (isDestroyed() || req != licenseRequest) return;
                    String st = r.optString("status", "network");
                    long server = r.optLong("serverTime", 0), end = r.optLong("expiresAt", 0);
                    boolean active =
                        "active".equals(st)
                            && server > 0
                            && !r.isNull("plan")
                            && (r.optBoolean("permanent") || end > server);
                    activeUntil =
                        active
                            ? SystemClock.elapsedRealtime()
                                + Math.min(
                                    300000, r.optBoolean("permanent") ? 300000 : end - server)
                            : 0;
                    activePlan = active ? r.optString("plan") : "";
                    if (target != null)
                      target.setText(
                          Net.status("active".equals(st) && !active ? "network" : st)
                              + (r.isNull("plan") ? "" : " · " + r.optString("plan"))
                              + (end > 0
                                  ? "\n만료 " + Net.date(end)
                                  : r.optBoolean("permanent") ? " · 영구" : ""));
                    if (activated != null
                        && activeUntil > SystemClock.elapsedRealtime()
                        && foreground) activated.run();
                  });
            })
        .start();
  }

  void update(boolean manual) {
    Updates.check(
        this,
        new Updates.Data() {
          public int version() {
            return s.catalog.root.optInt("version");
          }

          public void apply(String raw) throws Exception {
            synchronized (State.class) {
              Catalog updated = new Catalog(raw), current = new State(Home.this).catalog;
              if (updated.root.getInt("version") <= current.root.getInt("version")) return;
              if (!updated.profiles.keySet().containsAll(current.profiles.keySet()))
                throw new IllegalArgumentException("기존 브롤러 누락");
              if (!s.prefs.edit().putString("theseven.catalog", raw).commit())
                throw new java.io.IOException();
            }
          }
        },
        manual,
        () -> {
          s = new State(this);
          if (page.equals("settings")) settings();
          else home();
        });
  }

  void runPractice() {
    JSONObject cfg = s.cfg();
    JSONObject result = s.practice(cfg, s.choices(id()), s.prefs.getInt("theseven.page", 0));
    if (result.has("error")) {
      dialog("연습 중단", result.optString("error"));
      return;
    }
    s.prefs
        .edit()
        .putInt("theseven.page", result.optInt("page"))
        .putString("theseven.last", result.toString())
        .apply();
    replay(result, true);
  }

  void replay(JSONObject result, boolean carousel) {
    start("arena", "독립 연습장");
    String id = result.optString("id");
    Ui.text(panel, s.catalog.name(id, lang()), 18);
    JSONArray pages = result.optJSONArray("pages"), frames = result.optJSONArray("frames");
    if (frames == null || frames.length() == 0) {
      Ui.text(panel, "기록된 연습이 없습니다", 16);
      return;
    }
    TextView label = Ui.text(panel, "연습 목록 확인 중…", 16);
    ArenaView view = new ArenaView(this);
    panel.addView(view, -1, Ui.dp(this, 215));
    Ui.text(panel, "파란색은 나, 초록색은 아군, 주황색은 연습 표적입니다. 점수와 시간은 연습용 단위입니다.", 14);
    Ui.button(panel, "연습 종료", this::home);
    handler.post(
        new Runnable() {
          int p = 0, t = 0;

          public void run() {
            if (isDestroyed() || !page.equals("arena") || !view.isAttachedToWindow()) return;
            if (!foreground) {
              handler.postDelayed(this, 250);
              return;
            }
            if (carousel && p < pages.length()) {
              int n = pages.optInt(p++);
              List<String> list = ids();
              List<String> names = new ArrayList<>();
              for (int i = n * 8; i < Math.min(list.size(), n * 8 + 8); i++)
                names.add(s.catalog.name(list.get(i), lang()));
              label.setText((n + 1) + "쪽 · " + String.join(" / ", names));
              handler.postDelayed(this, 130);
              return;
            }
            JSONObject f = frames.optJSONObject(t++);
            view.show(f);
            label.setText(f.optString("action") + " · 점수 " + f.optInt("points"));
            if (t < frames.length()) {
              handler.postDelayed(this, 160);
              return;
            }
            if (s.prefs.getBoolean("repeat", false)) {
              t = 0;
              handler.postDelayed(this, 400);
            } else label.append(" · 연습 완료");
          }
        });
  }

  @Override
  public void onBackPressed() {
    if (!page.equals("home")) home();
    else if (inlineQuick) {
      inlineQuick = false;
      home();
    } else super.onBackPressed();
  }
}
