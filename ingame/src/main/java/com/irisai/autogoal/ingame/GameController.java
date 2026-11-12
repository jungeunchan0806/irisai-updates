package com.irisai.autogoal.ingame;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.*;
import android.widget.*;
import com.irisai.autogoal.GoalPlanner;
import com.irisai.autogoal.SessionGate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** All UI/input state is main-thread confined; vision runs serially in worker. */
final class GameController implements AutoCloseable {
    private final Activity activity;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "Iris-Vision"));
    private final SessionGate gate = new SessionGate();
    private final SharedPreferences preferences;
    private final ControlLayer layer;
    private final Button toggle;
    private final ViewTreeObserver.OnWindowFocusChangeListener focus;
    private NativeBridge bridge;
    private Stroke stroke;
    private SurfaceView surface;
    private Engine engine; // only the worker accesses this
    private boolean resumed, closed, busy;
    private String phase = "길게 눌러 위치 설정";
    private final Runnable poll = this::tick;
    private double moveX, moveY, attackX, attackY, gadgetX, gadgetY;

    GameController(Activity activity) {
        this.activity = activity;
        preferences = activity.getSharedPreferences("iris_autogoal_controls_v1", 0);
        loadControls();
        layer = new ControlLayer();
        layer.setClipChildren(false);
        toggle = new Button(activity);
        toggle.setAllCaps(false); toggle.setTextSize(12); toggle.setTextColor(Color.WHITE);
        toggle.setPadding(dp(10), dp(3), dp(10), dp(3)); toggle.setMinHeight(0); toggle.setMinimumHeight(0);
        toggle.setContentDescription("오토골 켜기 또는 끄기. 길게 누르면 조작 위치 설정");
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(190), dp(48), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        params.topMargin = dp(48); layer.addView(toggle, params);
        toggle.setOnClickListener(v -> { if (gate.enabled()) disable("길게 눌러 위치 설정"); else enable(); });
        toggle.setOnLongClickListener(v -> { settings(); return true; });
        focus = hasFocus -> { if (!hasFocus) disable("게임으로 돌아오면 다시 켜세요"); };
        paint();
    }
    void resume() {
        if (closed) return;
        resumed = true;
        ViewGroup root = (ViewGroup) activity.getWindow().getDecorView();
        if (layer.getParent() == null) {
            root.addView(layer, new ViewGroup.LayoutParams(-1, -1));
            root.getViewTreeObserver().addOnWindowFocusChangeListener(focus);
        }
        layer.bringToFront();
        ui.removeCallbacks(poll); ui.post(poll);
    }
    void pause() { resumed = false; layer.cancelCalibration(); disable("게임으로 돌아오면 다시 켜세요"); }
    private void enable() {
        if (closed || !resumed || !activity.hasWindowFocus()) return;
        try {
            if (bridge == null) bridge = new NativeBridge(activity);
            if (!bridge.loaded()) { phase = "게임 로딩 후 다시 켜세요"; paint(); return; }
            surface = bridge.surface();
            if (!validSurface()) { phase = "게임 화면 준비 중"; paint(); return; }
            if (stroke == null) stroke = new Stroke(bridge, (action, delay) -> ui.postDelayed(action, delay));
            gate.setGame(true); gate.setCapture(true); gate.setAccess(false); gate.setEnabled(true);
            long epoch = gate.generation();
            phase = "모델 준비 중"; paint();
            worker.execute(() -> {
                try {
                    if (engine == null) engine = new Engine(activity.getApplicationContext());
                    engine.planner.reset();
                    ui.post(() -> { if (!closed && gate.enabled() && epoch == gate.generation()) { gate.setAccess(true); phase = "브롤볼 대기"; paint(); } });
                } catch (Exception | LinkageError e) { ui.post(() -> fail(epoch, "모델을 시작하지 못했습니다", e)); }
            });
        } catch (Exception | LinkageError e) { fail(gate.generation(), "70.757 내부 입력 연결 실패", e); }
    }
    private void disable(String reason) {
        gate.setEnabled(false); gate.invalidate();
        if (stroke != null) stroke.cancel();
        phase = reason; paint();
    }
    private boolean validSurface() {
        return surface != null && surface.isAttachedToWindow() && surface.isShown()
                && surface.getHolder().getSurface().isValid() && surface.getWidth() > surface.getHeight()
                && surface.getHeight() > 0;
    }
    private boolean allowed(long epoch, long capturedAt) {
        return !closed && resumed && activity.hasWindowFocus() && validSurface()
                && gate.allows(epoch, capturedAt, SystemClock.uptimeMillis());
    }
    private void tick() {
        if (closed) return;
        if (gate.enabled() && (!resumed || !activity.hasWindowFocus() || !validSurface())) disable("게임 화면을 확인하세요");
        if (gate.ready() && !busy && (stroke == null || !stroke.busy())) capture();
        ui.postDelayed(poll, 100);
    }
    private void capture() {
        long epoch = gate.generation(), at = SystemClock.uptimeMillis();
        final double gx = gadgetX, gy = gadgetY;
        final int width = Math.round(surface.getWidth() * surface.getScaleX());
        final int height = Math.round(surface.getHeight() * surface.getScaleY());
        if (width <= 0 || height <= 0) return;
        Bitmap bitmap = Bitmap.createBitmap(960, 540, Bitmap.Config.ARGB_8888);
        busy = true;
        try {
            // Own-process SurfaceView only: no screen sharing or capture permission.
            PixelCopy.request(surface, bitmap, result -> {
                if (result != PixelCopy.SUCCESS || !allowed(epoch, at)) {
                    bitmap.recycle(); busy = false;
                    if (gate.enabled() && epoch == gate.generation()) { phase = "화면을 기다리는 중"; paint(); }
                    return;
                }
                worker.execute(() -> {
                    GoalPlanner.Decision decision = null; Throwable error = null;
                    try { if (gate.allows(epoch, at, SystemClock.uptimeMillis())) decision = engine.observe(bitmap, gx, gy); }
                    catch (Exception | LinkageError e) { error = e; }
                    finally { bitmap.recycle(); }
                    GoalPlanner.Decision outcome = decision; Throwable problem = error;
                    ui.post(() -> {
                        busy = false;
                        if (closed || epoch != gate.generation()) return;
                        if (problem != null) { fail(epoch, "화면 분석 실패", problem); return; }
                        if (!allowed(epoch, at)) { phase = "분석 지연 · 입력 대기"; paint(); return; }
                        // A resize invalidates the coordinate system of the captured frame.
                        if (width != Math.round(surface.getWidth() * surface.getScaleX())
                                || height != Math.round(surface.getHeight() * surface.getScaleY())) return;
                        if (outcome != null) apply(outcome, epoch, at, width, height);
                    });
                });
            }, ui);
        } catch (RuntimeException e) { bitmap.recycle(); busy = false; fail(epoch, "게임 화면 읽기 실패", e); }
    }
    private void apply(GoalPlanner.Decision decision, long epoch, long at, int width, int height) {
        phase = decision.phase; paint();
        if (decision.kind == GoalPlanner.Kind.NONE) { stroke.cancel(); return; }
        boolean shooting = decision.kind == GoalPlanner.Kind.SHOOT;
        double dx = decision.dx * width / 960., dy = decision.dy * height / 540.;
        double length = Math.hypot(dx, dy);
        if (!Double.isFinite(length) || length < 1e-6) return;
        double radius = height * (shooting ? .115 : .09);
        int x = (int) ((shooting ? attackX : moveX) * width), y = (int) ((shooting ? attackY : moveY) * height);
        int endX = Math.max(1, Math.min(width - 2, (int) (x + dx / length * radius)));
        int endY = Math.max(1, Math.min(height - 2, (int) (y + dy / length * radius)));
        boolean accepted = stroke.start(x, y, endX, endY, shooting ? 120 : 180,
                () -> allowed(epoch, at), () -> {
                    if (shooting && !closed) {
                        final double shotAt = SystemClock.uptimeMillis() / 1000.;
                        worker.execute(() -> { if (engine != null && epoch == gate.generation()) engine.planner.shotAccepted(shotAt); });
                    }
                });
        if (!accepted) { phase = "입력 연결 대기"; paint(); }
    }
    private void fail(long epoch, String message, Throwable error) {
        if (closed || epoch != gate.generation()) return;
        Log.e("IrisAutoGoal", message, error); disable(message);
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private void paint() {
        toggle.setText("오토골 " + (gate.enabled() ? "ON" : "OFF") + "\n" + phase);
        GradientDrawable background = new GradientDrawable();
        background.setColor(gate.enabled() ? 0xe6227656 : 0xe629344a);
        background.setCornerRadius(dp(16)); background.setStroke(dp(1), 0x99ffffff);
        toggle.setBackground(background);
    }
    private void loadControls() {
        moveX = preferences.getFloat("moveX", 220f / 1920); moveY = preferences.getFloat("moveY", 870f / 1080);
        attackX = preferences.getFloat("attackX", 1725f / 1920); attackY = preferences.getFloat("attackY", 800f / 1080);
        gadgetX = preferences.getFloat("gadgetX", 1640f / 1920); gadgetY = preferences.getFloat("gadgetY", 990f / 1080);
    }
    private void settings() {
        disable("길게 눌러 위치 설정");
        try {
            if (bridge == null) bridge = new NativeBridge(activity);
            surface = bridge.surface();
        } catch (Exception | LinkageError e) { fail(gate.generation(), "게임 화면 준비 중", e); return; }
        new AlertDialog.Builder(activity).setTitle("오토골 조작 위치")
                .setMessage("브롤볼 화면에서 이동·공격·가젯 버튼의 가운데를 차례로 지정하세요. 직접 게임 화면을 터치하면 오토골은 꺼집니다.")
                .setPositiveButton("위치 지정", (dialog, which) -> { if (validSurface()) layer.beginCalibration(); })
                .setNeutralButton("기본값", (dialog, which) -> { preferences.edit().clear().apply(); loadControls(); })
                .setNegativeButton("닫기", null).show();
    }
    @Override public void close() {
        if (closed) return;
        disable("종료"); closed = true; ui.removeCallbacks(poll);
        ViewGroup root = (ViewGroup) activity.getWindow().getDecorView();
        if (root.getViewTreeObserver().isAlive()) root.getViewTreeObserver().removeOnWindowFocusChangeListener(focus);
        root.removeView(layer);
        worker.execute(() -> { if (engine != null) { engine.close(); engine = null; } }); worker.shutdown();
    }
    private final class ControlLayer extends FrameLayout {
        private int step = -1;
        private final double[] values = new double[6];
        private TextView instruction;
        ControlLayer() { super(activity); setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); }
        void beginCalibration() {
            step = 0; toggle.setVisibility(View.GONE);
            instruction = new TextView(activity); instruction.setTextColor(Color.WHITE); instruction.setTextSize(18);
            instruction.setBackgroundColor(0xd0222938); instruction.setPadding(dp(16), dp(10), dp(16), dp(10));
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            p.topMargin = dp(50); addView(instruction, p); prompt();
        }
        private void prompt() { instruction.setText(new String[]{"① 이동 조이스틱 가운데를 누르세요", "② 빨간 공격 버튼 가운데를 누르세요", "③ 가젯 버튼 가운데를 누르세요"}[step]); }
        void cancelCalibration() {
            if (step < 0) return;
            removeView(instruction); instruction = null; step = -1; toggle.setVisibility(View.VISIBLE);
        }
        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            if (step >= 0) {
                if (event.getActionMasked() == MotionEvent.ACTION_UP && validSurface()) {
                    Rect hint = new Rect(); instruction.getHitRect(hint);
                    if (hint.contains((int) event.getX(), (int) event.getY())) { cancelCalibration(); return true; }
                    int[] origin = new int[2]; surface.getLocationOnScreen(origin);
                    double x = (event.getRawX() - origin[0]) / surface.getWidth(), y = (event.getRawY() - origin[1]) / surface.getHeight();
                    if (x > .02 && x < .98 && y > .1 && y < .98) {
                        values[step * 2] = x; values[step * 2 + 1] = y; step++;
                        if (step == 3) {
                            preferences.edit().putFloat("moveX", (float) values[0]).putFloat("moveY", (float) values[1])
                                    .putFloat("attackX", (float) values[2]).putFloat("attackY", (float) values[3])
                                    .putFloat("gadgetX", (float) values[4]).putFloat("gadgetY", (float) values[5]).apply();
                            loadControls(); removeView(instruction); instruction = null; step = -1;
                            toggle.setVisibility(View.VISIBLE); phase = "위치 저장됨"; paint();
                        } else prompt();
                    }
                }
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                Rect bounds = new Rect(); toggle.getHitRect(bounds);
                if (!bounds.contains((int) event.getX(), (int) event.getY()) && gate.enabled()) disable("수동 조작 · 다시 누르면 ON");
            }
            return super.dispatchTouchEvent(event);
        }
    }
}
