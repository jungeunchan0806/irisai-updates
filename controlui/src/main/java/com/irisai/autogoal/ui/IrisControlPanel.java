package com.irisai.autogoal.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;

/** In-process UI only: no accessibility, external overlay or game-input permission. */
public final class IrisControlPanel extends FrameLayout implements AutoCloseable {
    public interface Actions {
        void toggleAutoGoal();
        void calibrate();
        void resetControls();
        void visibilitySuppressed();
        default boolean nativeEngine() { return false; }
        default void copyHwid() {}
    }
    public static final String DISCORD_URL = "https://discord.gg/bshack";
    public static final int TOGGLE_WIDTH_DP = 160, TOGGLE_HEIGHT_DP = 44;
    private final Activity activity;
    private final Actions actions;
    private final SharedPreferences prefs;
    private final PanelState state = new PanelState();
    private final RecordingWatcher recording;
    private final LinearLayout hud, window;
    private final FrameLayout shade;
    private final Button auto, link, corner, close;
    private final TextView title, subtitle, hint;
    private final LinearLayout tabs;
    private final GridLayout grid;
    private final List<Button> tabButtons = new ArrayList<>();
    private boolean enabled, closed, resumed, streamerAllowed;
    private String phase = "", notice = "";
    private int tab;
    private int lastWidth, lastHeight;
    private TextView autoDetail;
    private LinearLayout autoCard;

    /** streamerAllowed is enabled only for the explicitly selected auto-hide fallback. */
    public IrisControlPanel(Activity activity, Actions actions, boolean streamerAllowed) {
        super(activity); this.activity = activity; this.actions = actions; this.streamerAllowed = streamerAllowed;
        prefs = activity.getSharedPreferences("iris_control_panel_v1", 0);
        state.english = "en".equals(prefs.getString("language", "ko"));
        state.hidden = prefs.getBoolean("hidden", false);
        state.streamer = streamerAllowed && prefs.getBoolean("streamer", false);
        state.suspended = true;
        setClipChildren(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        recording = new RecordingWatcher(activity, this::recordingChanged);

        // Transparent but touchable; stays available when the visible HUD is hidden.
        corner = button(""); corner.setBackgroundColor(Color.TRANSPARENT);
        corner.setElevation(0); corner.setStateListAnimator(null);
        corner.setOnClickListener(v -> { state.toggleMenu(); refresh(); });
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(dp(TOGGLE_WIDTH_DP * 2), dp(TOGGLE_HEIGHT_DP * 2), Gravity.TOP | Gravity.RIGHT);
        addView(corner, cp);

        hud = new LinearLayout(activity); hud.setOrientation(LinearLayout.VERTICAL);
        auto = button(""); auto.setTextSize(12); auto.setMaxLines(2); auto.setEllipsize(android.text.TextUtils.TruncateAt.END);
        auto.setOnClickListener(v -> actions.toggleAutoGoal());
        auto.setOnLongClickListener(v -> { if (actions.nativeEngine()) { actions.copyHwid(); return true; } state.menuOpen = true; tab = 1; refresh(); return true; });
        hud.addView(auto, new LinearLayout.LayoutParams(dp(TOGGLE_WIDTH_DP), dp(TOGGLE_HEIGHT_DP)));
        link = button("discord.gg/bshack"); link.setTextSize(11);
        link.setBackground(tile(0xe51b2949, 0xe5101830, 0xff4677b0, 8));
        link.setOnClickListener(v -> openDiscord());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(TOGGLE_WIDTH_DP), dp(28)); lp.topMargin = dp(5); hud.addView(link, lp);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        hp.leftMargin = dp(12); hp.topMargin = dp(12); addView(hud, hp);

        shade = new FrameLayout(activity); shade.setBackgroundColor(0xa000071a);
        shade.setOnClickListener(v -> { state.menuOpen = false; refresh(); }); addView(shade, new FrameLayout.LayoutParams(-1, -1));
        window = new LinearLayout(activity); window.setOrientation(LinearLayout.VERTICAL);
        window.setPadding(dp(14), dp(10), dp(14), dp(14));
        window.setBackground(tile(0xff203c72, 0xff101a37, 0xff557eb7, 16)); window.setElevation(dp(12)); window.setClickable(true);
        shade.addView(window, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        LinearLayout header = new LinearLayout(activity); header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout headings = new LinearLayout(activity); headings.setOrientation(LinearLayout.VERTICAL);
        title = text(21, Color.WHITE, true); subtitle = text(11, 0xffb9cbe7, false);
        headings.addView(title); headings.addView(subtitle);
        header.addView(headings, new LinearLayout.LayoutParams(0, -2, 1));
        close = button("×"); close.setTextSize(26); close.setBackground(tile(0xffe55d63, 0xff963044, 0xfff08a91, 10));
        close.setOnClickListener(v -> { state.menuOpen = false; refresh(); }); header.addView(close, new LinearLayout.LayoutParams(dp(42), dp(40))); window.addView(header);
        HorizontalScrollView bar = new HorizontalScrollView(activity); bar.setHorizontalScrollBarEnabled(false);
        tabs = new LinearLayout(activity); tabs.setPadding(0, dp(10), 0, dp(9)); bar.addView(tabs);
        for (int i = 0; i < 4; i++) {
            final int index = i; Button b = button(""); b.setTextSize(13);
            b.setOnClickListener(v -> { tab = index; refresh(); });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(124), dp(42)); p.rightMargin = dp(8); tabs.addView(b, p); tabButtons.add(b);
        }
        window.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        ScrollView scroller = new ScrollView(activity); scroller.setFillViewport(false);
        grid = new GridLayout(activity); grid.setUseDefaultMargins(false); scroller.addView(grid, new ScrollView.LayoutParams(-1, -2));
        window.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1));

        hint = text(16, Color.WHITE, true); hint.setPadding(dp(12), dp(10), dp(12), dp(10));
        hint.setBackground(tile(0xee213e6b, 0xee101c36, 0xff7aa6db, 8));
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL); ip.topMargin = dp(12); addView(hint, ip);
        setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets safe = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
            setPadding(safe.left, safe.top, safe.right, safe.bottom); return insets;
        });
        refresh();
    }
    public String text(String ko, String en) { return state.english ? en : ko; }
    public boolean english() { return state.english; }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private Button button(String value) {
        Button b = new Button(activity); b.setAllCaps(false); b.setText(value); b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD); b.setMinHeight(0); b.setMinimumHeight(0); b.setMinWidth(0); b.setMinimumWidth(0);
        b.setPadding(dp(6), dp(2), dp(6), dp(2)); return b;
    }
    private TextView text(int size, int color, boolean bold) {
        TextView v = new TextView(activity); v.setTextSize(size); v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return v;
    }
    private GradientDrawable tile(int top, int bottom, int border, int radius) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
        d.setCornerRadius(dp(radius)); d.setStroke(dp(2), border); return d;
    }
    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w == lastWidth && h == lastHeight) return; lastWidth = w; lastHeight = h;
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) window.getLayoutParams();
        p.width = Math.min(dp(1000), Math.max(dp(260), w - getPaddingLeft() - getPaddingRight() - dp(24)));
        p.height = Math.min(dp(600), Math.max(dp(200), h - getPaddingTop() - getPaddingBottom() - dp(20)));
        window.setLayoutParams(p); if (state.showMenu()) refreshCards();
    }
    public void setEngineState(boolean enabled, String phase) {
        if (this.enabled == enabled && this.phase.equals(phase)) return;
        boolean changed = this.enabled != enabled;
        this.enabled = enabled; this.phase = phase;
        updateHudText();
        if (changed) {
            paintHud();
            if (autoCard != null) {
                autoCard.setBackground(tile(enabled ? 0xff344e42 : 0xff35435d, enabled ? 0xff192e2d : 0xff202b43, enabled ? 0xff96d95e : 0xff0a1427, 9));
                ((TextView) autoCard.getChildAt(0)).setTextColor(enabled ? 0xff9af252 : 0xffffdfa1);
            }
        }
        if (autoDetail != null) autoDetail.setText((enabled ? "ON · " : "OFF · ") + statusText(phase));
    }
    public void resume() {
        if (closed) return; resumed = true; state.suspended = false;
        if (state.streamer && !recording.start()) unavailable(); refresh(); requestApplyInsets();
    }
    public void pause() {
        resumed = false; state.suspended = true; recording.stop(); state.recording = false;
        state.calibrating = false; refresh();
    }
    private void recordingChanged(boolean value) {
        state.recording(value);
        if (state.streamer && value) actions.visibilitySuppressed();
        refresh();
    }
    private void unavailable() {
        state.streamer = false; prefs.edit().putBoolean("streamer", false).apply();
        notice = text("이 환경에서는 녹화 감지를 사용할 수 없습니다", "Recording detection is unavailable here");
    }
    private void toggleStreamer() {
        if (!streamerAllowed || !recording.supported()) return;
        state.streamer = !state.streamer; notice = "";
        if (state.streamer && resumed) { if (!recording.start()) unavailable(); }
        else { recording.stop(); state.recording = false; }
        prefs.edit().putBoolean("streamer", state.streamer).apply(); refresh();
    }
    private void openDiscord() {
        try { activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(DISCORD_URL))); }
        catch (ActivityNotFoundException e) { notice = text("링크를 열 앱이 없습니다", "No app is available to open this link"); refresh(); }
    }
    private void refresh() {
        if (closed) return;
        hud.setVisibility(state.showHud() ? VISIBLE : GONE);
        shade.setVisibility(state.showMenu() ? VISIBLE : GONE);
        hint.setVisibility(state.showHint() ? VISIBLE : GONE);
        corner.setVisibility(state.canOpen() ? VISIBLE : GONE);
        corner.setContentDescription(text("Iris 설정 열기", "Open Iris settings"));
        paintHud();
        title.setText("IRIS  /  " + text("컨트롤 센터", "CONTROL CENTER"));
        subtitle.setText(notice.isEmpty() ? text("오른쪽 위 투명 영역으로 열기 · ", "Open from the invisible top-right area · ") + (enabled ? "ON" : "OFF") : notice);
        close.setContentDescription(text("설정 닫기", "Close settings"));
        String[] labels = state.english ? new String[]{"● All", "⚽ Auto Goal", "▣ Display", "↗ Community"} : new String[]{"● 전체", "⚽ 오토골", "▣ 화면", "↗ 커뮤니티"};
        int[] colors = {0xff80ce3d, 0xff58bbd1, 0xff598ce1, 0xffb65ed1};
        for (int i = 0; i < tabButtons.size(); i++) {
            Button b = tabButtons.get(i); b.setText(labels[i]);
            b.setBackground(tile(colors[i], i == tab ? colors[i] : 0xff25344f, i == tab ? 0xffe1f7ff : 0xff3d526c, 8));
            b.setAlpha(i == tab ? 1f : .76f);
        }
        if (state.showMenu()) refreshCards();
    }
    private void paintHud() {
        updateHudText();
        auto.setContentDescription(text("오토골 켜기 또는 끄기", "Toggle Auto Goal"));
        auto.setBackground(tile(enabled ? 0xff57aa31 : 0xff314c7a, enabled ? 0xff25632c : 0xff182744, enabled ? 0xffa9ed67 : 0xff6387b9, 10));
    }
    private void updateHudText() {
        auto.setText(text("오토골 ", "Auto Goal ") + (enabled ? "ON" : "OFF") + (phase.isEmpty() ? "" : "\n" + statusText(phase)));
    }
    private void refreshCards() {
        if (grid == null) return;
        grid.removeAllViews(); autoDetail = null; autoCard = null;
        int available = lastWidth - getPaddingLeft() - getPaddingRight();
        int columns = available >= dp(720) ? 3 : available >= dp(430) ? 2 : 1;
        grid.setColumnCount(columns);
        if (tab == 0 || tab == 1) {
            autoCard = card(text("오토골", "Auto Goal"), (enabled ? "ON · " : "OFF · ") + statusText(phase), enabled,
                () -> { actions.toggleAutoGoal(); refresh(); });
            autoDetail = (TextView) autoCard.getChildAt(1);
            if (actions.nativeEngine()) {
                card(text("HWID 복사", "Copy HWID"), text("기존 인증용 기기 ID 복사", "Copy the device ID used for registration"), false, actions::copyHwid);
            } else {
            card(text("조작 위치", "Control positions"), text("이동 · 공격 · 가젯 위치 지정", "Set movement, attack and gadget positions"), false,
                () -> { state.menuOpen = false; state.hidden = false; save(); refresh(); actions.calibrate(); });
            card(text("위치 초기화", "Reset positions"), text("조작 위치를 기본값으로 되돌립니다", "Restore default control positions"), false,
                () -> { actions.resetControls(); notice = text("기본 위치로 되돌렸습니다", "Default positions restored"); refresh(); });
            }
        }
        if (tab == 0 || tab == 2) {
            card(text("언어", "Language"), state.english ? "English · 한국어로 전환" : "한국어 · Switch to English", false,
                () -> { state.english = !state.english; notice = ""; save(); refresh(); });
            card(state.hidden ? text("UI 다시 표시", "Show UI") : text("UI 전부 숨기기", "Hide all UI"),
                text("투명 열기 영역은 계속 사용할 수 있습니다", "The invisible opener remains available"), state.hidden,
                () -> { if (state.hidden) state.showAll(); else { state.hideAll(); actions.visibilitySuppressed(); } save(); refresh(); });
        }
        if (tab == 0 || tab == 3) {
            card("Discord", "discord.gg/bshack\n" + text("서버 링크 열기", "Open the server link"), false, this::openDiscord);
        }
    }
    private String statusText(String value) {
        if (!state.english) return value;
        switch (value) {
            case "인증 준비 중": return "Preparing authorization";
            case "인증 확인 중": return "Checking authorization";
            case "인증 연결 실패": return "Authorization connection failed";
            case "HWID 등록 필요": return "Register HWID in the panel";
            case "이용 정지": return "Subscription suspended";
            case "기간 만료": return "Subscription expired";
            case "조이스틱을 한 번 움직이세요": return "Move the joystick once to connect input";
            case "게임 입력 연결 대기": return "Waiting for game input connection";
            case "게임 로딩 중": return "Game is loading";
            case "게임 연결 실패": return "Game connection failed";
            case "오토골 실행 중": return "Auto Goal running";
            case "모델 준비 중": return "Loading models";
            case "브롤볼 대기": return "Waiting for Brawl Ball";
            case "길게 눌러 위치 설정": return "Open settings for control positions";
            case "게임으로 돌아오면 다시 켜세요": return "Enable again after returning to the game";
            case "게임 로딩 후 다시 켜세요": return "Enable again after game loading";
            case "게임 화면 준비 중": return "Waiting for the game surface";
            case "수동 조작 · 다시 누르면 ON": return "Manual input · tap to enable";
            case "모델을 시작하지 못했습니다": return "Could not load models";
            case "화면 분석 실패": return "Frame analysis failed";
            case "게임 화면 읽기 실패": return "Frame capture failed";
            case "분석 지연 · 입력 대기": return "Analysis delayed · waiting";
            case "위치 저장됨": return "Positions saved";
            case "게임 화면을 확인하세요": return "Check the game screen";
            case "화면을 기다리는 중": return "Waiting for a frame";
            case "70.757 내부 입력 연결 실패": return "70.757 in-game input connection failed";
            case "경기 종료": return "Match finished";
            case "캐릭터 찾는 중": return "Finding the player";
            case "골대로 운반": return "Carrying toward the goal";
            case "조준 슛": return "Aiming the shot";
            case "공 획득": return "Getting the ball";
            case "공 찾는 중": return "Finding the ball";
            case "골대 밖으로 이동": return "Moving out of the goal";
            case "경로 대기": return "Waiting for a path";
            case "UI 미리보기 · 게임 연결 없음": return "UI preview · no game connection";
            case "종료": return "Stopped";
            default: return value;
        }
    }
    private LinearLayout card(String heading, String description, boolean active, Runnable click) {
        LinearLayout c = new LinearLayout(activity); c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(12), dp(10), dp(10), dp(12)); c.setMinimumHeight(dp(104));
        c.setBackground(tile(active ? 0xff344e42 : 0xff35435d, active ? 0xff192e2d : 0xff202b43, active ? 0xff96d95e : 0xff0a1427, 9)); c.setElevation(dp(3));
        c.setClickable(true); c.setFocusable(true); c.setContentDescription(heading + ". " + description);
        c.setOnClickListener(v -> click.run());
        TextView name = text(19, active ? 0xff9af252 : 0xffffdfa1, true); name.setText(heading); c.addView(name);
        TextView detail = text(12, 0xffc5d2e5, false); detail.setText(description); detail.setPadding(0, dp(5), 0, 0); c.addView(detail);
        GridLayout.LayoutParams p = new GridLayout.LayoutParams();
        p.width = 0; p.height = -2; p.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        p.setMargins(dp(4), dp(4), dp(4), dp(6)); grid.addView(c, p); return c;
    }
    private void save() { prefs.edit().putString("language", state.english ? "en" : "ko").putBoolean("hidden", state.hidden).apply(); }
    public boolean isUiTouch(float x, float y) {
        if (state.showMenu()) return true;
        return hit(corner, x, y) || hit(hud, x, y) || hit(hint, x, y);
    }
    private boolean hit(View view, float x, float y) {
        if (view.getVisibility() != VISIBLE) return false;
        Rect r = new Rect(); view.getHitRect(r); return r.contains((int) x, (int) y);
    }
    public void calibrationStep(int step) {
        state.calibrating = step >= 0; state.menuOpen = false;
        if (step >= 0) {
            String[] labels = state.english ? new String[]{"① Tap the movement joystick center", "② Tap the attack button center", "③ Tap the gadget button center"}
                : new String[]{"① 이동 조이스틱 가운데를 누르세요", "② 빨간 공격 버튼 가운데를 누르세요", "③ 가젯 버튼 가운데를 누르세요"};
            hint.setText(labels[step] + text(" · 안내를 누르면 취소", " · Tap this hint to cancel"));
        }
        refresh();
    }
    public boolean hitCalibrationHint(float x, float y) { return hit(hint, x, y); }
    @Override public void close() { if (closed) return; pause(); closed = true; recording.stop(); }
}
