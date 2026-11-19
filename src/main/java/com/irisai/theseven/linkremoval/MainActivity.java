package com.irisai.theseven.linkremoval;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class MainActivity extends Activity {
    private static final int INPUT = 1, OUTPUT = 2;
    private static final String OUTPUT_NAME = "TheSeven-Transparent-UI-no-Discord.apk";
    private static volatile boolean busy;
    private static volatile int percent;
    private static volatile String message = "";

    private final Handler main = new Handler(Looper.getMainLooper());
    private Button choose;
    private TextView status;
    private ProgressBar progress;
    private Uri source;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) source = state.getParcelable("source");
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density + .5f);
        page.setPadding(padding, padding, padding, padding);
        TextView title = new TextView(this);
        title.setTextSize(24);
        title.setText("TheSeven 상단 링크 제거");
        page.addView(title);
        TextView detail = new TextView(this);
        detail.setPadding(0, padding / 2, 0, padding);
        detail.setText("다운로드해 둔 TheSeven-Transparent-UI-1.0.apk를 선택한 뒤 새 APK를 저장할 위치를 고르세요. 저장 공간이 약 2GB 필요합니다.");
        page.addView(detail);
        choose = new Button(this);
        choose.setText("기존 APK 선택");
        choose.setOnClickListener(v -> {
            Intent open = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            startActivityForResult(open, INPUT);
        });
        page.addView(choose);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        page.addView(progress);
        status = new TextView(this);
        page.addView(status);
        setContentView(page);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putParcelable("source", source);
    }
    @Override protected void onResume() { super.onResume(); render(); }
    @Override protected void onPause() { main.removeCallbacks(refresh); super.onPause(); }

    private final Runnable refresh = this::render;
    private void render() {
        main.removeCallbacks(refresh);
        boolean running = busy;
        choose.setEnabled(!running);
        progress.setVisibility(running ? View.VISIBLE : View.GONE);
        progress.setProgress(percent);
        status.setText(running ? "파일 확인 및 저장 중 · " + percent + "%" : message);
        if (running) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            main.postDelayed(refresh, 350);
        } else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null || busy) return;
        Uri uri = data.getData();
        if (request == INPUT) {
            source = uri;
            Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/vnd.android.package-archive").putExtra(Intent.EXTRA_TITLE, OUTPUT_NAME);
            startActivityForResult(create, OUTPUT);
        } else if (request == OUTPUT && source != null) {
            if (source.equals(uri)) {
                message = "원본과 다른 위치 또는 파일 이름을 선택하세요.";
                render();
                return;
            }
            Context context = getApplicationContext();
            Uri input = source;
            source = null;
            percent = 0;
            busy = true;
            new Thread(() -> save(context, input, uri), "save-menu-apk").start();
            render();
        }
    }

    private static void save(Context context, Uri inputUri, Uri outputUri) {
        ContentResolver resolver = context.getContentResolver();
        try {
            try (InputStream input = resolver.openInputStream(inputUri);
                 InputStream change = context.getAssets().open("header-change.bin");
                 OutputStream sink = resolver.openOutputStream(outputUri, "wt")) {
                if (input == null || sink == null) throw new IOException("선택한 파일을 열 수 없습니다.");
                BufferedOutputStream target = new BufferedOutputStream(sink);
                HeaderChange.apply(input, change, target, p -> percent = p);
            }
            message = "검증 및 저장 완료. 선택한 폴더에서 저장한 APK를 열 수 있습니다.";
        } catch (IOException | RuntimeException e) {
            boolean removed = false;
            try { removed = DocumentsContract.deleteDocument(resolver, outputUri); }
            catch (Exception ignored) { /* This document provider may not support deletion. */ }
            String detail = e.getLocalizedMessage();
            if (detail == null || detail.isEmpty()) detail = "파일을 읽거나 저장할 수 없습니다.";
            message = "저장 실패 · " + detail
                    + (removed ? "" : "\n저장 폴더에 불완전한 파일이 있으면 삭제하세요.");
        } finally {
            busy = false;
        }
    }
}
