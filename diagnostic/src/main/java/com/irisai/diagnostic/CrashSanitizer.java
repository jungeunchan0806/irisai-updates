package com.irisai.diagnostic;

import java.util.ArrayDeque;

/** Keeps exception classes and stack frames, excluding opaque reports and credentials. */
public final class CrashSanitizer {
    private CrashSanitizer() {}
    public static String redact(String text) {
        if (text == null) return "";
        if (text.contains("nv.Q:")) return text.substring(0, text.indexOf("nv.Q:")) + "nv.Q: <opaque report omitted>";
        return text.replaceAll("https?://\\S+", "<url>")
                .replaceAll("(?i)(access_token|token|password|authorization|api_key)\\s*[:=]\\s*\\S+", "$1=<redacted>")
                .replaceAll("[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}", "<email>")
                .replaceAll("[A-Za-z0-9+/=_-]{80,}", "<opaque value>");
    }
    public static String crashLines(String text) {
        ArrayDeque<String> lines = new ArrayDeque<>();
        for (String line : text.split("\\R")) {
            if (!line.contains("AndroidRuntime")) continue;
            int colon = line.indexOf(":");
            String message = (colon < 0 ? line : line.substring(colon + 1)).trim();
            if (!(message.startsWith("FATAL EXCEPTION") || message.startsWith("Process:")
                    || message.startsWith("at ") || message.startsWith("Caused by:") || message.startsWith("Suppressed:")
                    || message.matches("(?:[A-Za-z_$][\\w$]*\\.)+[A-Za-z_$][\\w$]*(?::.*)?"))) continue;
            String clean = redact(message);
            lines.add(clean.length() > 500 ? clean.substring(0, 500) + "…" : clean);
            while (lines.size() > 70) lines.removeFirst();
        }
        return String.join("\n", lines);
    }
}
