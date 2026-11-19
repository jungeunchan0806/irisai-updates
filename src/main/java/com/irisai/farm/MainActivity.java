package com.irisai.farm;

import android.app.*;
import android.content.*;
import android.media.projection.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

public final class MainActivity extends Activity {
  static final int SHARE = 134;
  private Button overlay, gestures, start, stop;
  private TextView state;
  private boolean asking;
  private static java.lang.ref.WeakReference<MainActivity> visible =
      new java.lang.ref.WeakReference<>(null);

  static void servicesChanged() {
    MainActivity activity = visible.get();
    if (activity != null) activity.refresh();
  }

  @Override
  protected void onPause() {
    if (visible.get() == this) visible.clear();
    super.onPause();
  }

  @Override
  public void onCreate(Bundle b) {
    super.onCreate(b);
    asking = b != null && b.getBoolean("asking");
    ScrollView scroll = new ScrollView(this);
    scroll.setBackgroundColor(Ui.BG);
    scroll.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          android.graphics.Insets i =
              insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
          v.setPadding(i.left, i.top, i.right, i.bottom);
          return insets;
        });
    LinearLayout col = Ui.column(this, 24);
    scroll.addView(col);
    setContentView(scroll);
    Ui.add(col, Ui.text(this, "IrisAI 플레이어", 28, true), 8);
    Ui.add(col, Ui.text(this, "화면 공유를 시작한 뒤 게임 위의 설정 버튼에서 브롤러를 선택하세요.", 16, false), 8);
    overlay = Ui.button(this, "", false);
    Ui.add(col, overlay, 24);
    overlay.setOnClickListener(
        v ->
            startActivity(
                new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()))));
    gestures = Ui.button(this, "", false);
    Ui.add(col, gestures, 10);
    gestures.setOnClickListener(
        v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
    Ui.add(
        col,
        Ui.text(
            this,
            "접근성 설정에서 ‘IrisAI 게임 터치’를 켜면 브롤스타즈에 이동·공격 터치를 보낼 수 있습니다. 화면은 기기에서 처리합니다.",
            14,
            false),
        10);
    start = Ui.button(this, "화면 공유 시작", true);
    Ui.add(col, start, 22);
    start.setOnClickListener(v -> begin());
    stop = Ui.button(this, "화면 공유 종료", false);
    Ui.add(col, stop, 8);
    stop.setOnClickListener(
        v -> {
          stopService(new Intent(this, CaptureService.class));
          start.postDelayed(this::refresh, 300);
        });
    state = Ui.text(this, "", 14, false);
    Ui.add(col, state, 12);
  }

  private void begin() {
    if (CaptureService.live != null) {
      CaptureService.live.openMenu();
      launchGame();
      return;
    }
    if (asking) return;
    MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
    Intent request =
        Build.VERSION.SDK_INT >= 34
            ? manager.createScreenCaptureIntent(
                MediaProjectionConfig.createConfigForDefaultDisplay())
            : manager.createScreenCaptureIntent();
    asking = true;
    refresh();
    startActivityForResult(request, SHARE);
  }

  @Override
  protected void onActivityResult(int req, int result, Intent data) {
    super.onActivityResult(req, result, data);
    if (req != SHARE) return;
    asking = false;
    if (result == RESULT_OK && data != null) {
      Intent service = new Intent(this, CaptureService.class);
      service.putExtra("code", result);
      service.putExtra("grant", data);
      startForegroundService(service);
      launchGame();
    } else state.setText("화면 공유가 취소됐습니다.");
    refresh();
  }

  private void launchGame() {
    Intent launch = getPackageManager().getLaunchIntentForPackage(TouchService.GAME);
    if (launch != null) {
      startActivity(launch);
    } else state.setText("브롤스타즈를 설치하거나 직접 열어 주세요.");
  }

  @Override
  protected void onSaveInstanceState(Bundle b) {
    super.onSaveInstanceState(b);
    b.putBoolean("asking", asking);
  }

  @Override
  protected void onResume() {
    super.onResume();
    visible = new java.lang.ref.WeakReference<>(this);
    refresh();
  }

  private void refresh() {
    if (isDestroyed() || overlay == null) return;
    boolean allowed = Settings.canDrawOverlays(this),
        access = TouchService.active != null,
        sharing = CaptureService.live != null;
    overlay.setText(allowed ? "다른 앱 위에 표시 · 허용됨" : "다른 앱 위에 표시 허용");
    gestures.setText(access ? "게임 터치 · 허용됨" : "게임 터치 접근성 설정");
    start.setText(sharing ? "게임 설정 열기" : "화면 공유 시작");
    start.setEnabled(allowed && access && !asking);
    stop.setVisibility(sharing ? View.VISIBLE : View.GONE);
  }
}
