package com.irisai.inputprobe;
import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.MotionEvent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Color;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Separate laboratory app. It is not Brawl Stars and does not emulate a battle. */
public final class ProbeActivity extends Activity {
    private int count; private String last="Waiting for input";
    @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().getDecorView().setSystemUiVisibility(5894);setContentView(new Pad());}
    final class Pad extends View {
        final Paint paint=new Paint(3);
        Pad(){super(ProbeActivity.this);setFocusable(true);}
        @Override protected void onDraw(Canvas c){c.drawColor(Color.rgb(18,25,36));paint.setColor(Color.WHITE);paint.setTextSize(32);c.drawText("Iris input transport test",40,65,paint);c.drawText(last,40,115,paint);c.drawText("Events: "+count,40,165,paint);}
        @Override public boolean onTouchEvent(MotionEvent e){count++;last="Action "+e.getActionMasked()+" at "+Math.round(e.getX())+", "+Math.round(e.getY());
            String row=String.format(java.util.Locale.US,"{\"action\":%d,\"x\":%.2f,\"y\":%.2f,\"at\":%d,\"downAt\":%d}\n",e.getActionMasked(),e.getX(),e.getY(),SystemClock.uptimeMillis(),e.getDownTime());
            try(FileOutputStream f=openFileOutput("events.jsonl",MODE_APPEND)){f.write(row.getBytes(StandardCharsets.UTF_8));}catch(Exception ignored){}
            invalidate();return true;}
    }
}
