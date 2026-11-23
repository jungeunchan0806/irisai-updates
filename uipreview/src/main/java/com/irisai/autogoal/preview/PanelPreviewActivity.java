package com.irisai.autogoal.preview;

import android.app.Activity;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.widget.FrameLayout;
import com.irisai.autogoal.ui.IrisControlPanel;

/** Reviewable UI fixture. It does not load, patch, launch or control Brawl Stars. */
public final class PanelPreviewActivity extends Activity {
    IrisControlPanel panel;
    FrameLayout root;
    Board board;
    private boolean enabled;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setDecorFitsSystemWindows(false);
        root = new FrameLayout(this); board = new Board(); root.addView(board, new FrameLayout.LayoutParams(-1, -1));
        panel = new IrisControlPanel(this, new IrisControlPanel.Actions() {
            @Override public boolean nativeEngine() { return BuildConfig.TWO_CONTROL_MENU; }
            @Override public void toggleAutoGoal() { enabled = !enabled; update(); }
            @Override public void calibrate() { panel.calibrationStep(0); board.calibration = 0; }
            @Override public void resetControls() { board.calibration = -1; panel.calibrationStep(-1); }
            @Override public void visibilitySuppressed() { board.calibration = -1; panel.calibrationStep(-1); }
        }, false);
        root.addView(panel, new FrameLayout.LayoutParams(-1, -1)); setContentView(root); update();
        root.post(this::hideSystemBars);
    }
    private void hideSystemBars() {
        WindowInsetsController controller = getWindow().getDecorView().getWindowInsetsController();
        if (controller == null) return;
        controller.hide(WindowInsets.Type.systemBars());
        controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }
    @Override public void onWindowFocusChanged(boolean hasFocus) { super.onWindowFocusChanged(hasFocus); if (hasFocus) hideSystemBars(); }
    private void update() { panel.setEngineState(enabled, "UI 미리보기 · 게임 연결 없음"); }
    @Override public void onResume() { super.onResume(); if (panel != null) panel.resume(); }
    @Override public void onPause() { if (panel != null) panel.pause(); super.onPause(); }
    @Override public void onDestroy() { if (panel != null) panel.close(); super.onDestroy(); }
    final class Board extends View {
        int taps, calibration = -1;
        private final Paint paint = new Paint(3);
        Board() { super(PanelPreviewActivity.this); setClickable(true); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            paint.setShader(new LinearGradient(0, 0, w, h, 0xff163f48, 0xff0b192b, Shader.TileMode.CLAMP)); c.drawRect(0, 0, w, h, paint); paint.setShader(null);
            paint.setColor(0xff285765); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2);
            for (int i = 1; i < 12; i++) c.drawLine(w * i / 12, 0, w * i / 12, h, paint);
            for (int i = 1; i < 6; i++) c.drawLine(0, h * i / 6, w, h * i / 6, paint);
            paint.setColor(0xff60808a); c.drawRoundRect(w * .06f, h * .12f, w * .94f, h * .88f, 25, 25, paint);
            c.drawCircle(w / 2, h / 2, h * .23f, paint); c.drawLine(w / 2, h * .12f, w / 2, h * .88f, paint);
            paint.setStyle(Paint.Style.FILL); paint.setColor(0xffa9c4cc); paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD)); paint.setTextSize(h * .042f);
            c.drawText("UI PREVIEW · NO GAME CONNECTION", w / 2, h * .48f, paint);
            paint.setTypeface(Typeface.DEFAULT); paint.setTextSize(h * .031f);
            c.drawText("오른쪽 위 투명 영역을 눌러 설정 열기", w / 2, h * .56f, paint);
            c.drawText("GAME AREA TOUCHES: " + taps, w / 2, h * .94f, paint);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                taps++;
                if (calibration >= 0) { calibration++; if (calibration >= 3) calibration = -1; panel.calibrationStep(calibration); }
                invalidate(); performClick();
            }
            return true;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
    }
}
