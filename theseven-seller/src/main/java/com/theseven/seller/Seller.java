package com.theseven.seller;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.WindowManager;
import android.widget.*;
import com.theseven.common.*;
import java.util.function.Consumer;
import org.json.*;

public final class Seller extends Activity {
  LinearLayout panel;
  TextView state;
  String plan = "Practice";
  int generation;

  public void onCreate(Bundle b) {
    super.onCreate(b);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    home();
    Updates.check(this, null, false, this::home);
  }

  void request(JSONObject p, Consumer<JSONObject> complete) {
    int g = generation;
    new Thread(
            () -> {
              JSONObject response;
              try {
                response = Net.call("seller", p);
              } catch (Exception e) {
                response = Net.fields("status", "network");
              }
              JSONObject r = response;
              runOnUiThread(
                  () -> {
                    if (!isDestroyed() && g == generation) complete.accept(r);
                  });
            })
        .start();
  }

  void home() {
    generation++;
    panel = Ui.screen(this);
    Ui.text(panel, "더세븐 오토팜 판매자", 25);
    state = Ui.text(panel, "기기 승인 확인 중…", 17);
    Ui.button(panel, "다시 확인", this::home);
    Ui.button(panel, "업데이트 확인", () -> Updates.check(this, null, true, this::home));
    request(
        Net.fields("action", "history"),
        r -> {
          if (!r.optString("status").equals("approved")) {
            state.setText(Net.status(r.optString("status")));
            if ("unapproved".equals(r.optString("status")))
              Ui.button(
                  panel,
                  "등록용 기기 지문 복사",
                  () -> {
                    try {
                      copy(Net.fingerprint(), false);
                    } catch (Exception e) {
                      state.setText("기기 키를 읽을 수 없습니다");
                    }
                  });
            return;
          }
          state.setText("승인된 판매자 기기");
          String[] plans = {"Practice", "Plus"};
          Ui.select(panel, "플랜", plans, plan.equals("Plus") ? 1 : 0, i -> plan = plans[i]);
          Ui.text(panel, "유효기간 (발급일부터 일수)", 15);
          EditText days = new EditText(this);
          days.setInputType(2);
          days.setText("30");
          panel.addView(days);
          Button issue = Ui.button(panel, "키 발급", () -> {});
          issue.setOnClickListener(
              v -> {
                int n;
                try {
                  n = Integer.parseInt(days.getText().toString());
                  if (n < 1 || n > 3650) throw new NumberFormatException();
                } catch (Exception e) {
                  state.setText("1~3650일을 입력하세요");
                  return;
                }
                issue.setEnabled(false);
                request(
                    Net.fields("action", "issue", "plan", plan, "days", n),
                    res -> {
                      issue.setEnabled(true);
                      if (!res.has("key")) {
                        state.setText(Net.status(res.optString("status")));
                        return;
                      }
                      String key = res.optString("key");
                      new AlertDialog.Builder(this)
                          .setTitle("발급한 키 · 지금 복사하세요")
                          .setMessage(key)
                          .setPositiveButton("복사", (d, w) -> copy(key, true))
                          .setNegativeButton("닫기", null)
                          .setOnDismissListener(d -> home())
                          .show();
                    });
              });
          Ui.text(panel, "발급 이력", 22);
          JSONArray history = r.optJSONArray("history");
          if (history != null)
            for (int i = 0; i < history.length(); i++) {
              JSONObject row = history.optJSONObject(i);
              Ui.button(
                  panel,
                  row.optString("plan")
                      + " · "
                      + Net.status(row.optString("status"))
                      + "\n발급 "
                      + Net.date(row.optLong("created"))
                      + "\n만료 "
                      + Net.date(row.optLong("expires")),
                  () -> manage(row));
            }
        });
  }

  void manage(JSONObject row) {
    String[] labels = {"철회", "복원", "삭제"};
    String[] actions = {"revoke", "restore", "delete"};
    new AlertDialog.Builder(this)
        .setTitle("발급 키 관리")
        .setItems(
            labels,
            (d, i) ->
                new AlertDialog.Builder(this)
                    .setMessage(labels[i] + "하시겠습니까?")
                    .setPositiveButton(
                        "확인",
                        (q, w) ->
                            request(
                                Net.fields("action", actions[i], "id", row.optString("id")),
                                r -> {
                                  if ("ok".equals(r.optString("status"))) home();
                                  else state.setText(Net.status(r.optString("status")));
                                }))
                    .setNegativeButton("취소", null)
                    .show())
        .show();
  }

  void copy(String s, boolean sensitive) {
    ClipData clip = ClipData.newPlainText("더세븐", s);
    if (sensitive) {
      PersistableBundle extras = new PersistableBundle();
      extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
      clip.getDescription().setExtras(extras);
    }
    getSystemService(ClipboardManager.class).setPrimaryClip(clip);
  }
}
