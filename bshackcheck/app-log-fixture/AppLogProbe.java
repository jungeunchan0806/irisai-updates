package com.irisai.bshack;

import android.Manifest;
import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

public class AppLogProbe extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        new Thread(() -> {
            if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0 ||
                    checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED) {
                Log.e("AppLogProbe", "FAIL fixture privileges");
                return;
            }
            Log.w("FixtureNetwork", "UnknownHostException private-test-host");
            String report = ProcessLogReport.read(true);
            boolean ok = report.contains("DNS lookup failed") && !report.contains("private-test-host");
            Log.i("AppLogProbe", ok ? "PASS own log on Android 17" : "FAIL " + report);
        }).start();
    }
}
