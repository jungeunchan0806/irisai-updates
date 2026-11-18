package com.irisai.bshack.check;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.inspector.WindowInspector;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/** Exercises the installed UI DEX inside the app's normal, unprivileged process. */
final class ProcessLogChecks {
    static void run(Instrumentation instrument, Activity activity, Class<?> type, List<String> checks) throws Exception {
        if (activity.checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED)
            throw new AssertionError("Fixture has privileged log access");
        Field singleton = type.getDeclaredField("host");
        singleton.setAccessible(true);
        Object host = singleton.get(null);
        Method show = type.getDeclaredMethod("diagnostics");
        show.setAccessible(true);
        Log.w("IrisFixture", "UnknownHostException private-fixture-value");
        instrument.runOnMainSync(() -> {
            try {show.invoke(host);}
            catch (Exception e) {throw new AssertionError(e);}
        });
        long deadline = SystemClock.uptimeMillis() + 10000;
        boolean[] complete = {false};
        while (!complete[0] && SystemClock.uptimeMillis() < deadline) {
            instrument.runOnMainSync(() -> {
                for (View root : WindowInspector.getGlobalWindowViews()) {
                    TextView message = root.findViewById(android.R.id.message);
                    View copy = root.findViewById(android.R.id.button1);
                    if (message == null || copy == null || !copy.isEnabled()) continue;
                    String report = message.getText().toString();
                    if (!report.startsWith("IRIS ")) continue;
                    if (!(report.contains("DNS lookup failed") || report.contains("DNS 조회 실패")) ||
                        report.contains("private-fixture-value")) throw new AssertionError(report);
                    copy.performClick();
                    complete[0] = true;
                    return;
                }
            });
            if (!complete[0]) SystemClock.sleep(80);
        }
        if (!complete[0]) throw new AssertionError("App log summary dialog never became ready");
        checks.add("diagnostics dialog classifies own PID log without READ_LOGS and omits raw messages");
    }
}
