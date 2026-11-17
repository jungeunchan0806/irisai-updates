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

// Typed Android client adapter. Native game callbacks only read cached monotonic state.
function createLicense(Java, handlers, options = {}) {
  const now = options.now || createMonotonicClock();
  let host = null, deadline = 0, waiting = 0;
  let state = {ready:false,checked:false,licenseStatus:'unregistered',pending:false,error:null,active:false};
  function sync(value) {
    host = value;
    const data = JSON.parse(String(host.licenseStatus()));
    const remaining = Number(data.remainingMs || 0);
    if (!Number.isFinite(remaining) || remaining < 0 || remaining > 300000) throw Error('Invalid Android lease');
    deadline = remaining > 0 ? now() + remaining : 0;
    state = {ready: data.ready === true, checked: data.checked === true, licenseStatus: data.status,
      pending: data.pending === true, error: data.error || null, active: remaining > 0};
    if (waiting && Number(data.completed) >= waiting) {
      waiting = 0;
      if (!state.error && remaining > 0) handlers.toggle();
      else if (handlers.cancel) handlers.cancel();
    }
  }
  return {
    sync,
    tap() { if (!host) return; waiting = Number(host.authorize()); sync(host); },
    copy() { if (host) host.copyHwid(); },
    bindView() {},
    isActive: () => state.ready && deadline > now(),
    press() {},
    status: () => ({...state, active: state.ready && deadline > now()})
  };
}
var init_licensing = __esm({
  "licensing.mjs"() { init_node_globals(); init_core(); init_monotonic(); }
});
