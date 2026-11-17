package com.supercell.brawlstars;
import android.app.Activity;
import android.os.Bundle;
import android.widget.FrameLayout;
public final class GameApp extends Activity {
 public FrameLayout root;
 @Override public void onCreate(Bundle state) { super.onCreate(state); root = new FrameLayout(this); root.setBackgroundColor(0xff163b42);setContentView(root); }
}
