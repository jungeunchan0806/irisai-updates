package com.irisai.diagnostic;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.widget.*;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/** Temporary same-package diagnostic update. Does not run or alter the game. */
public final class DiagnosticActivity extends Activity {
    private final ExecutorService reader = Executors.newSingleThreadExecutor();
    private TextView report;
    private Button refresh, copy;
    private String lastReport = "";
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(24), dp(20), dp(16)); layout.setBackgroundColor(0xff101827);
        layout.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
            view.setPadding(dp(20) + bars.left, dp(24) + bars.top, dp(20) + bars.right, dp(16) + bars.bottom);
            return insets;
        });
        TextView title = text("Iris 오류 확인", 23); layout.addView(title);
        TextView help = text("게임 대신 표시되는 임시 진단 화면입니다.\n이전 종료 기록을 확인하며 게임 데이터는 삭제하지 않습니다.\n아래 결과를 복사해서 대화에 붙여넣어 주세요.", 14);
        help.setPadding(0, dp(14), 0, dp(14)); layout.addView(help);
        report = text("기록 확인 중…", 12); report.setTypeface(android.graphics.Typeface.MONOSPACE); report.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this); scroll.addView(report);
        layout.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout buttons = new LinearLayout(this);
        copy = new Button(this); copy.setText("내용 복사"); copy.setEnabled(false);
        refresh = new Button(this); refresh.setText("다시 확인");
        buttons.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        buttons.addView(refresh, new LinearLayout.LayoutParams(0, -2, 1)); layout.addView(buttons);
        setContentView(layout);
        copy.setOnClickListener(v -> {
            ClipData clip = ClipData.newPlainText("Iris startup report", lastReport);
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean("android.content.extra.IS_SENSITIVE", true); clip.getDescription().setExtras(extras);
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(clip);
            Toast.makeText(this, "복사했습니다. 대화에 붙여넣어 주세요.", Toast.LENGTH_SHORT).show();
        });
        refresh.setOnClickListener(v -> collect()); collect();
    }
    private TextView text(String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(Color.WHITE); return view;
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private void collect() {
        refresh.setEnabled(false); copy.setEnabled(false);
        reader.execute(() -> {
            String value;
            try { value = buildReport(); }
            catch (Exception e) { value = "진단 읽기 오류: " + e.getClass().getName() + "\n" + device(); }
            final String result = value;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                lastReport = result; report.setText(result); copy.setEnabled(true); refresh.setEnabled(true);
            });
        });
    }
    private String device() {
        return "기기: " + Build.MANUFACTURER + " " + Build.MODEL
                + "\nAndroid: " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT
                + "\nABI: " + String.join(", ", Build.SUPPORTED_ABIS);
    }
    private String buildReport() {
        StringBuilder out = new StringBuilder("IRIS STARTUP DIAGNOSTIC 1\n").append(device()).append("\n\n최근 종료 기록\n");
        try {
            ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            List<ApplicationExitInfo> exits = manager.getHistoricalProcessExitReasons(getPackageName(), 0, 8);
            SimpleDateFormat date = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT);
            if (exits.isEmpty()) out.append("기록 없음\n");
            for (ApplicationExitInfo exit : exits) {
                out.append(date.format(new Date(exit.getTimestamp()))).append(" · ").append(reason(exit.getReason()))
                        .append(" · status=").append(exit.getStatus()).append('\n');
                String description = CrashSanitizer.redact(exit.getDescription());
                if (!description.isEmpty()) out.append(description.substring(0, Math.min(500, description.length()))).append('\n');
            }
        } catch (RuntimeException e) { out.append("종료 기록 접근 실패: ").append(e.getClass().getSimpleName()).append('\n'); }
        out.append("\n이 앱 UID의 Java 종료 로그\n");
        String logs;
        try { logs = CrashSanitizer.crashLines(ownCrashLog()); }
        catch (Exception e) { logs = "읽기 실패: " + e.getClass().getSimpleName(); }
        out.append(logs.isEmpty() ? "이전 Java 로그 없음 — 기기 정보와 종료 기록만 복사해 주세요." : logs);
        return out.toString();
    }
    private static String reason(int value) {
        switch (value) {
            case ApplicationExitInfo.REASON_CRASH: return "Java 예외 (4)";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "네이티브 충돌 (5)";
            case ApplicationExitInfo.REASON_ANR: return "응답 없음 (6)";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "메모리 부족 (3)";
            case ApplicationExitInfo.REASON_EXIT_SELF: return "앱 내부 종료 (1)";
            case ApplicationExitInfo.REASON_SIGNALED: return "시스템 신호 (2)";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "종료/업데이트 요청 (10)";
            default: return "reason=" + value;
        }
    }
    private static String ownCrashLog() throws Exception {
        Process process = new ProcessBuilder("logcat", "-d", "-b", "crash", "-t", "200", "-v", "brief",
                "--uid=" + android.os.Process.myUid(), "AndroidRuntime:E", "*:S").redirectErrorStream(true).start();
        ScheduledExecutorService timeout = Executors.newSingleThreadScheduledExecutor();
        timeout.schedule(process::destroyForcibly, 4, TimeUnit.SECONDS);
        try (InputStream in = process.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int n;
            while (bytes.size() < 65536 && (n = in.read(buffer, 0, Math.min(buffer.length, 65536 - bytes.size()))) != -1) bytes.write(buffer, 0, n);
            return bytes.toString(StandardCharsets.UTF_8.name());
        } finally { process.destroyForcibly(); timeout.shutdownNow(); }
    }
    @Override public void onDestroy() { reader.shutdownNow(); super.onDestroy(); }
}
