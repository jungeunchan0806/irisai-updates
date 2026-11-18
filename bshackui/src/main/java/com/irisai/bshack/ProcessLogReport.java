package com.irisai.bshack;

import android.os.Process;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Called on demand, off the main thread. Only labels and counts leave this reader. */
final class ProcessLogReport {
    private static final Pattern ENTRY = Pattern.compile(
            "^\\s*(\\d{9,11})\\.(\\d{3})\\s+(?:\\S+\\s+)?(\\d+)\\s+\\d+\\s+[VDIWEFA]\\s+.*");
    private static final ScheduledExecutorService timeout = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "iris-log-timeout");
        t.setDaemon(true);
        return t;
    });

    private enum Signal {
        DNS("DNS 조회 실패", "DNS lookup failed", "UnknownHostException|EAI_(AGAIN|NONAME|NODATA)|Unable to resolve host"),
        TLS("TLS 연결 실패", "TLS failed", "SSLHandshakeException|CERTIFICATE_VERIFY_FAILED|TLS handshake failed"),
        TIMEOUT("연결 시간 초과", "Connection timed out", "SocketTimeoutException|ETIMEDOUT|connection timed out|connect timed out"),
        RESET("연결 거부 또는 끊김", "Connection refused or reset", "ECONNREFUSED|ECONNRESET|connection (refused|reset by peer)"),
        FILES("앱 파일 읽기 오류", "App file load failed", "Resources\\$NotFoundException|Unable to open asset|failed to load asset|dlopen failed|UnsatisfiedLinkError"),
        SPACE("저장 공간 부족", "Out of storage", "ENOSPC|No space left on device"),
        VERSION("클라이언트 업데이트 요구", "Client update required", "client update (is )?required|client version (is )?(too old|unsupported)|mandatory client update");

        final String ko, en;
        final Pattern pattern;
        Signal(String ko, String en, String regex) {
            this.ko = ko;
            this.en = en;
            pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        }
    }

    static String read(boolean english) {
        String unavailable = english ? "App log unavailable" : "앱 실행 기록을 읽을 수 없습니다";
        java.lang.Process child = null;
        ScheduledFuture<?> deadline = null;
        try {
            int pid = Process.myPid();
            long start = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime());
            child = new ProcessBuilder("/system/bin/logcat", "-d", "-t", "1200", "-b", "main", "-b", "crash",
                    "--pid=" + pid, "-v", "threadtime", "-v", "epoch", "*:V").redirectErrorStream(true).start();
            java.lang.Process running = child;
            deadline = timeout.schedule(() -> running.destroyForcibly(), 4, TimeUnit.SECONDS);

            int lines = 0;
            int[] counts = new int[Signal.values().length];
            Signal[] signals = Signal.values();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher m = ENTRY.matcher(line);
                    if (!m.matches() || !Integer.toString(pid).equals(m.group(3))) continue;
                    long time = Long.parseLong(m.group(1)) * 1000L + Long.parseLong(m.group(2));
                    if (time < start) continue;
                    lines++;
                    for (Signal signal : signals) {
                        if (signal.pattern.matcher(line).find()) counts[signal.ordinal()]++;
                    }
                }
            }
            if (child.waitFor() != 0) return unavailable;
            if (lines == 0) return english ? "No readable entries for this run" : "이번 실행에서 읽을 수 있는 기록이 없습니다";
            StringBuilder result = new StringBuilder(english ? "Recent app log entries: " : "이번 실행에서 읽은 최근 로그 줄 수: ").append(lines);
            boolean found = false;
            for (Signal signal : signals) {
                if (counts[signal.ordinal()] == 0) continue;
                found = true;
                result.append('\n').append(english ? signal.en : signal.ko).append(" × ").append(counts[signal.ordinal()]);
            }
            if (!found) result.append(english ? "\nNo matching error in these entries" : "\n이 범위에서 분류된 오류가 없습니다");
            return result.toString();
        } catch (IOException | RuntimeException e) {
            return unavailable;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unavailable;
        } finally {
            if (deadline != null) deadline.cancel(false);
            if (child != null) child.destroyForcibly();
        }
    }

    private ProcessLogReport() {}
}
