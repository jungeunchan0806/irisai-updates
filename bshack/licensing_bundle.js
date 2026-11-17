// core.mjs
var Gesture, Lease;
var init_core = __esm({
  "core.mjs"() {
    init_node_globals();
    Gesture = class {
      constructor(now, hold, tap) {
        this.now = now;
        this.hold = hold;
        this.tap = tap;
        this.g = null;
      }
      down(x, y, slop = 24) {
        this.g = { at: this.now(), x, y, slop, up: null, hit: false, done: false, cancel: false };
      }
      move(x, y) {
        const g = this.g;
        if (g && Math.hypot(x - g.x, y - g.y) > g.slop) g.cancel = true;
      }
      cancel() {
        if (this.g) this.g.cancel = true;
      }
      up() {
        const g = this.g;
        if (!g) return;
        g.up = this.now();
        this.finish();
      }
      nativePress() {
        const g = this.g;
        if (!g) return false;
        if (g.done || g.cancel || g.up !== null && this.now() - g.up > 500) return true;
        g.hit = true;
        this.finish();
        return true;
      }
      tick() {
        this.finish();
      }
      finish() {
        const g = this.g;
        if (!g || !g.hit || g.done || g.cancel) return;
        const end = g.up === null ? this.now() : g.up;
        if (end - g.at >= 700) {
          g.done = true;
          this.hold();
        } else if (g.up !== null) {
          g.done = true;
          this.tap();
        }
      }
    };
    Lease = class {
      constructor(now) {
        this.now = now;
        this.until = 0;
        this.permanent = false;
        this.expiry = null;
        this.status = "unregistered";
      }
      active() {
        return this.until > 0 && this.now() < this.until;
      }
      accept(data) {
        if (!data || typeof data.active !== "boolean") throw Error("Invalid license response");
        if (!data.active) {
          this.until = 0;
          this.status = data.status || "unregistered";
          return;
        }
        if (!Number.isFinite(data.serverTime) || !Number.isInteger(data.leaseSeconds) || data.leaseSeconds < 1 || data.leaseSeconds > 300) throw Error("Invalid lease");
        if (data.permanent !== true && (!Number.isFinite(data.expiresAt) || data.expiresAt <= data.serverTime || data.leaseSeconds * 1e3 > data.expiresAt - data.serverTime)) throw Error("Invalid expiration");
        this.until = this.now() + data.leaseSeconds * 1e3;
        this.permanent = data.permanent === true;
        this.expiry = data.expiresAt;
        this.status = "active";
      }
      deny() {
        this.until = 0;
        this.status = "unregistered";
      }
    };
  }
});

// monotonic.mjs
function createMonotonicClock() {
  const libc = Process.getModuleByName("libc.so");
  const read2 = new NativeFunction(libc.getExportByName("clock_gettime"), "int", ["int", "pointer"]);
  const value = Memory.alloc(Process.pointerSize * 2);
  let clockId = 7;
  if (read2(clockId, value) !== 0) clockId = 1;
  return () => {
    if (read2(clockId, value) !== 0) throw Error("monotonic clock unavailable");
    return Number(value.readS64()) * 1e3 + Number(value.add(8).readS64()) / 1e6;
  };
}
var init_monotonic = __esm({
  "monotonic.mjs"() {
    init_node_globals();
  }
});

// licensing.mjs
function createLicense(Java, handlers, options = {}) {
  const now = options.now || createMonotonicClock();
  const later = options.later || setImmediate;
  const delay = options.delay || setTimeout;
  const repeat = options.repeat || setInterval;
  const BASE = "https://bshack-license-panel.jungeunchan0806.chatgpt.site";
  let ready = false, initError = null, ctx = null, hwid = null, prefs = null, view = null, job = null, dialog = null, input = null, lease = new Lease(now);
  let StringJ, Toast, ClipData, Clipboard, Builder, EditText, URL, Http, Thread2, Runnable, Click, Dismiss;
  function report(where, error) {
    initError = where + ": " + String(error);
    console.error("[BSHACK 2.0.1] " + initError);
    if (handlers.notice) handlers.notice("\uC778\uC99D \uC624\uB958: " + where + " \xB7 \uB2E4\uC2DC \uB20C\uB7EC \uC8FC\uC138\uC694.");
  }
  function inJava(fn) {
    try {
      return Java.performNow(fn);
    } catch (e) {
      report("Android \uD638\uCD9C", e);
      return false;
    }
  }
  function main(fn) {
    if (ready) try {
      Java.scheduleOnMainThread(() => {
        try {
          fn();
        } catch (e) {
          report("\uC778\uC99D \uD654\uBA74", e);
        }
      });
    } catch (e) {
      report("\uD654\uBA74 \uC608\uC57D", e);
    }
  }
  function deferred(fn) {
    later(() => inJava(() => {
      try {
        fn();
      } catch (e) {
        report("\uBC84\uD2BC \uCC98\uB9AC", e);
      }
    }));
  }
  function toast(text) {
    main(() => Toast.makeText(ctx, StringJ.$new(text), 0).show());
  }
  function copy2() {
    if (!ready) {
      return;
    }
    main(() => {
      const cm2 = Java.cast(ctx.getSystemService("clipboard"), Clipboard);
      cm2.setPrimaryClip(ClipData.newPlainText(StringJ.$new("BSHACK HWID"), StringJ.$new(hwid)));
      Toast.makeText(ctx, StringJ.$new("HWID\uB97C \uBCF5\uC0AC\uD588\uC2B5\uB2C8\uB2E4."), 0).show();
    });
  }
  function consent() {
    return prefs && prefs.getBoolean("license_consent_v1", false);
  }
  function allow() {
    prefs.edit().putBoolean("license_consent_v1", true).apply();
  }
  function refresh(key = null, after = null) {
    if (!ready || job || !consent()) {
      if (after && !job) after(false);
      return;
    }
    job = { key, after };
    try {
      Thread2.$new(Runnable.$new()).start();
    } catch (e) {
      job = null;
      report("\uC778\uC99D \uC694\uCCAD \uC2DC\uC791", e);
      if (after) after(false, String(e));
    }
  }
  function network() {
    const req = job;
    let connection = null, response = null, error = null;
    try {
      connection = Java.cast(URL.$new(BASE + "/api/v1/license/" + (req.key ? "redeem" : "check")).openConnection(), Http);
      connection.setConnectTimeout(5e3);
      connection.setReadTimeout(5e3);
      connection.setInstanceFollowRedirects(false);
      connection.setRequestMethod("POST");
      connection.setDoOutput(true);
      connection.setUseCaches(false);
      connection.setRequestProperty("Content-Type", "application/json");
      connection.setRequestProperty("Accept", "application/json");
      const bytes = StringJ.$new(JSON.stringify({ hwid, ...req.key ? { key: req.key } : {} })).getBytes("UTF-8");
      connection.setFixedLengthStreamingMode(bytes.length);
      const out = connection.getOutputStream();
      try {
        out.write(bytes);
        out.flush();
      } finally {
        out.close();
      }
      const status = connection.getResponseCode();
      if (status !== 200) throw Error("HTTP " + status + " \xB7 \uC778\uC99D \uC11C\uBC84 \uC751\uB2F5\uC744 \uD655\uC778\uD574 \uC8FC\uC138\uC694.");
      const reader = Java.use("java.io.BufferedReader").$new(Java.use("java.io.InputStreamReader").$new(connection.getInputStream(), "UTF-8"));
      let text = "", line;
      try {
        while ((line = reader.readLine()) !== null) {
          text += String(line);
          if (text.length > 4096) throw Error("\uC778\uC99D \uC751\uB2F5\uC774 \uB108\uBB34 \uD07D\uB2C8\uB2E4.");
        }
      } finally {
        reader.close();
      }
      response = JSON.parse(text);
    } catch (e) {
      error = String(e);
    } finally {
      if (connection) try {
        connection.disconnect();
      } catch (e) {
        error = error || String(e);
      }
    }
    deferred(() => {
      job = null;
      try {
        if (response) lease.accept(response);
      } catch (e) {
        error = String(e);
      }
      if (error) console.error("[BSHACK 2.0.1] " + error);
      if (!lease.active()) handlers.disabled();
      if (req.after) req.after(!error && lease.active(), error);
    });
  }
  function showDialog() {
    if (!ready || dialog || !view) return;
    main(() => {
      const context = view.getContext();
      input = EditText.$new(context);
      input.setSingleLine(true);
      input.setHint(StringJ.$new("\uB77C\uC774\uC120\uC2A4\uD0A4 (HWID\uB97C \uB4F1\uB85D\uD588\uB2E4\uBA74 \uBE44\uC6CC\uB450\uAE30)"));
      input.setInputType(1 | 524288);
      const b = Builder.$new(context);
      b.setTitle(StringJ.$new("BSHACK \uAD6C\uB3C5 \uB4F1\uB85D"));
      b.setMessage(StringJ.$new("HWID: " + hwid + "\n\uC0C1\uD0DC: " + lease.status + (initError ? "\n\uC624\uB958: " + initError.slice(0, 180) : "") + "\n\n\uAD00\uB9AC\uC790\uAC00 \uC774 HWID\uB97C \uB4F1\uB85D\uD588\uC73C\uBA74 \uAD6C\uB3C5 \uD655\uC778\uC744 \uB204\uB974\uC138\uC694. \uBC1C\uAE09\uBC1B\uC740 \uD0A4\uAC00 \uC788\uB2E4\uBA74 \uC785\uB825\uD558\uC138\uC694.\n\n\uAD6C\uB3C5 \uD655\uC778 \uC2DC \uC774 \uC571 \uC804\uC6A9 \uAE30\uAE30 \uC2DD\uBCC4\uAC12\uC774 BSHACK \uC778\uC99D \uC11C\uBC84\uB85C \uC804\uC1A1\uB429\uB2C8\uB2E4."));
      b.setView(input);
      b.setPositiveButton(StringJ.$new("\uAD6C\uB3C5 \uD655\uC778 \xB7 \uB4F1\uB85D"), Click.$new());
      b.setNeutralButton(StringJ.$new("HWID \uBCF5\uC0AC"), Click.$new());
      b.setNegativeButton(StringJ.$new("\uB2EB\uAE30"), Click.$new());
      dialog = b.create();
      dialog.setOnDismissListener(Dismiss.$new());
      dialog.show();
    });
  }
  function tap() {
    if (!ready) return;
    if (lease.active()) {
      handlers.toggle();
      return;
    }
    if (!consent()) { showDialog(); return; }
    const activate = (ok, error) => {
      if (ok) {
        initError = null;
        toast(lease.permanent ? "\uC601\uAD6C \uAD6C\uB3C5\uC774 \uD65C\uC131\uD654\uB418\uC5C8\uC2B5\uB2C8\uB2E4." : "\uAD6C\uB3C5\uC774 \uD65C\uC131\uD654\uB418\uC5C8\uC2B5\uB2C8\uB2E4.");
        handlers.toggle();
      } else {
        if (handlers.cancel) handlers.cancel();
        if (error) {
          initError = error;
          toast(error.slice(0, 150));
        }
        showDialog();
      }
    };
    if (job) {
      job.after = activate;
      toast("\uAD6C\uB3C5\uC744 \uD655\uC778\uD558\uACE0 \uC788\uC2B5\uB2C8\uB2E4.");
      return;
    }
    allow();
    toast("HWID \uAD6C\uB3C5\uC744 \uD655\uC778\uD569\uB2C8\uB2E4.");
    refresh(null, activate);
  }
  const gesture = new Gesture(now, () => deferred(copy2), () => deferred(tap));
  function initialize2() {
    try {
      Java.perform(() => {
        try {
          const App = Java.use("android.app.ActivityThread").currentApplication();
          if (!App) {
            delay(initialize2, 500);
            return;
          }
          ctx = Java.retain(App.getApplicationContext());
          StringJ = Java.use("java.lang.String");
          Toast = Java.use("android.widget.Toast");
          ClipData = Java.use("android.content.ClipData");
          Clipboard = Java.use("android.content.ClipboardManager");
          Builder = Java.use("android.app.AlertDialog$Builder");
          EditText = Java.use("android.widget.EditText");
          URL = Java.use("java.net.URL");
          Http = Java.use("java.net.HttpURLConnection");
          Thread2 = Java.use("java.lang.Thread");
          prefs = ctx.getSharedPreferences("bshack_license_v1", 0);
          let androidId = Java.use("android.provider.Settings$Secure").getString(ctx.getContentResolver(), "android_id");
          if (!androidId) {
            androidId = prefs.getString("fallback_id", null);
            if (!androidId) {
              androidId = Java.use("java.util.UUID").randomUUID().toString();
              prefs.edit().putString("fallback_id", androidId).commit();
            }
          }
          const raw = StringJ.$new("bshack-license-v1|" + ctx.getPackageName() + "|" + androidId).getBytes("UTF-8");
          const bytes = Java.use("java.security.MessageDigest").getInstance("SHA-256").digest(raw);
          hwid = "BS-" + Array.from(bytes, (b) => (b & 255).toString(16).padStart(2, "0")).join("").toUpperCase();
          Runnable = Java.registerClass({ name: "app.bshack.license.NetworkJob", implements: [Java.use("java.lang.Runnable")], methods: { run: network } });
          Click = Java.registerClass({ name: "app.bshack.license.DialogClick", implements: [Java.use("android.content.DialogInterface$OnClickListener")], methods: { onClick(d, which) {
            if (which === -3) {
              copy2();
              return;
            }
            if (which !== -1) return;
            const key = input ? String(input.getText().toString()).trim().toUpperCase() : "";
            allow();
            refresh(key || null, (ok, error) => {
              if (ok) {
                toast("\uAD6C\uB3C5\uC774 \uD65C\uC131\uD654\uB418\uC5C8\uC2B5\uB2C8\uB2E4.");
                handlers.toggle();
              } else {
                initError = error || null;
                toast(error ? error.slice(0, 150) : "\uB4F1\uB85D\uB41C \uAD6C\uB3C5\uC774 \uC5C6\uC2B5\uB2C8\uB2E4. \uAD00\uB9AC\uC790\uC5D0\uAC8C HWID\uB97C \uC804\uB2EC\uD574 \uC8FC\uC138\uC694.");
              }
            });
          } } });
          Dismiss = Java.registerClass({ name: "app.bshack.license.DialogDismiss", implements: [Java.use("android.content.DialogInterface$OnDismissListener")], methods: { onDismiss() {
            dialog = null;
            input = null;
            if (!job && handlers.cancel) handlers.cancel();
          } } });
          const Surface = Java.use("com.supercell.titan.GL2JNISurfaceView");
          const original = Surface.onTouchEvent.overload("android.view.MotionEvent");
          original.implementation = function(event) {
            try {
              if (!view || !view.equals(this)) {
                if (view) view.$dispose();
                view = Java.retain(this);
              }
              if (handlers.surface) handlers.surface(this);
              const action = event.getActionMasked(), density = this.getResources().getDisplayMetrics().density.value;
              if (action === 0) gesture.down(event.getX(), event.getY(), Math.max(16, 12 * density));
              else if (action === 2) gesture.move(event.getX(), event.getY());
              else if (action === 1) gesture.up();
              else if (action === 3 || action === 5) gesture.cancel();
            } catch (e) {
              report("\uD130\uCE58 \uCC98\uB9AC", e);
              gesture.cancel();
            }
            return original.call(this, event);
          };
          ready = true;
          initError = null;
          if (consent()) refresh();
        } catch (e) {
          report("\uC778\uC99D \uCD08\uAE30\uD654", e);
          handlers.disabled();
        }
      });
    } catch (e) {
      report("\uC778\uC99D \uCD08\uAE30\uD654", e);
      handlers.disabled();
    }
  }
  delay(initialize2, 0);
  repeat(() => {
    if (ready) {
      try {
        gesture.tick();
        if (!lease.active()) handlers.disabled();
      } catch (e) {
        report("\uAD6C\uB3C5 \uC0C1\uD0DC", e);
      }
    }
  }, 100);
  repeat(() => {
    if (ready) deferred(() => {
      if (consent()) refresh();
    });
  }, 6e4);
  return { tap: () => { initError = null; deferred(tap); }, copy: () => deferred(copy2), dialog: () => deferred(showDialog),
    bindView(value) { if (!view || !view.equals(value)) { if (view) view.$dispose(); view = Java.retain(value); } },
    isActive: () => ready && lease.active(), press: () => {
    try {
      if (ready) {
        if (!gesture.nativePress()) deferred(tap);
      } else if (handlers.notice) {
        handlers.notice(initError ? "\uC778\uC99D \uCD08\uAE30\uD654 \uC624\uB958 \xB7 \uAC8C\uC784\uC744 \uB2E4\uC2DC \uC2E4\uD589\uD574 \uC8FC\uC138\uC694." : "\uC778\uC99D\uC744 \uC900\uBE44\uD558\uACE0 \uC788\uC2B5\uB2C8\uB2E4.");
      }
    } catch (e) {
      report("\uBC84\uD2BC \uC785\uB825", e);
    }
  }, status: () => ({ ready, pending: !!job || !!dialog, error: initError, active: lease.active() }) };
}
var init_licensing = __esm({
  "licensing.mjs"() {
    init_node_globals();
    init_core();
    init_monotonic();
  }
});

