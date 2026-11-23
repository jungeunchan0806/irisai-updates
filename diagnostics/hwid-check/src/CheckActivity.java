package com.irisai.licensecheck;

import android.app.Activity;
import android.os.Bundle;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

public final class CheckActivity extends Activity {
    private static final String ENDPOINT = "https://bshack-license-panel.jungeunchan0806.chatgpt.site/api/v1/license/check";
    private TextView result;
    private Button check;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout layout = new LinearLayout(this);
        layout.setFitsSystemWindows(true);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this);
        title.setText("HWID 인증 확인");
        title.setTextSize(24);
        layout.addView(title);
        TextView help = new TextView(this);
        help.setText("게임 메뉴에서 복사한 HWID를 붙여넣으세요. 확인을 누르면 BSHACK 인증 서버에 전송됩니다.");
        layout.addView(help);
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setHint("BS-…");
        field.setSaveEnabled(false);
        layout.addView(field);
        check = new Button(this);
        check.setText("인증 상태 확인");
        layout.addView(check);
        result = new TextView(this);
        result.setTextSize(18);
        result.setTextIsSelectable(true);
        layout.addView(result);
        setContentView(layout);
        check.setOnClickListener(v -> {
            String id = field.getText().toString().trim().toUpperCase(Locale.ROOT);
            if (!id.matches("BS-[A-F0-9]{64}")) {
                result.setText("HWID 전체를 다시 붙여넣으세요.");
                return;
            }
            check.setEnabled(false);
            result.setText("확인 중…");
            new Thread(() -> {
                String status = request(id);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    result.setText(status);
                    check.setEnabled(true);
                });
            }).start();
        });
    }

    private static String request(String id) {
        HttpsURLConnection conn = null;
        try {
            conn = (HttpsURLConnection) new URL(ENDPOINT).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            byte[] body = new JSONObject().put("hwid", id).toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(body.length);
            try (OutputStream stream = conn.getOutputStream()) { stream.write(body); }
            int code = conn.getResponseCode();
            if (code != 200) return "인증 서버 응답 HTTP " + code;
            StringBuilder text = new StringBuilder();
            try (Reader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
                char[] buffer = new char[512];
                int n;
                while ((n = reader.read(buffer)) != -1) {
                    if (text.length() + n > 4096) return "인증 서버 응답 형식 오류";
                    text.append(buffer, 0, n);
                }
            }
            JSONObject response = new JSONObject(text.toString());
            if (!(response.opt("active") instanceof Boolean)) return "인증 서버 응답 형식 오류";
            if (response.getBoolean("active")) return "구독 활성 · 이 HWID의 인증에 성공했습니다.";
            switch (response.optString("status")) {
                case "expired": return "구독 기간 만료";
                case "revoked": return "구독 이용 정지";
                case "pending": return "구독 등록 대기";
                case "unregistered": return "등록되지 않은 HWID";
                default: return "구독 비활성 · 인증 서버 응답 수신";
            }
        } catch (SocketTimeoutException ex) {
            return "인증 서버 연결 시간 초과";
        } catch (IOException ex) {
            return "인증 서버 네트워크 연결 실패";
        } catch (org.json.JSONException ex) {
            return "인증 서버 응답 형식 오류";
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
