package com.irisai.fixture;
import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** CI-only test app. Not the game and never published as a user download. */
public final class CrashActivity extends Activity {
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        try(FileOutputStream out=openFileOutput("iris-ci-preserved-data.txt",MODE_PRIVATE)) { out.write("keep-this-data".getBytes(StandardCharsets.UTF_8)); }
        catch(Exception e){throw new IllegalStateException(e);}
        new Handler(getMainLooper()).postDelayed(()->{throw new IllegalStateException("IRIS_DIAGNOSTIC_FIXTURE");},500);
    }
}
