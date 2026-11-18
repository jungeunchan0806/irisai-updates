# BSHACK baseline panel integration

Baseline SHA-256: `8d595381fe0247dbfc06bd03879f68828d1dc1967647e830d0c51745d810f082`.

`build.py BASELINE UI_PAYLOAD_APK UNSIGNED_APK` replaces only `lib/arm64-v8a/libosu.data.so` and adds `assets/iris-panel.dex`, `assets/iris-bootstrap.dex`, and `assets/iris-engine.mjs`. Original manifest, DEX, native code, assets and resources remain byte-identical. `verify.py BASELINE SIGNED_APK` verifies these invariants and all CRCs. The Frida package length is recalculated; stale source maps are removed.

Build the lightweight UI with `./gradlew :bshackui:assembleRelease`. Sign using the **public AOSP test key** matching the provided BSHACK baseline, with apksigner `--alignment-preserved true`, v2/v3 enabled. Verify with apksigner and `zipalign -c -P 16 4`. Public AOSP key sources are `platform/build/target/product/security/testkey.pk8` and `testkey.x509.pem` on android.googlesource.com. This is not the official Supercell signing key.

The typed Android `LicenseClient` retains server authorization, the original HWID calculation/preferences, explicit first-use consent, 60-second renewal and maximum 300-second leases. The JS adapter caches the authorization lease; native callbacks use only a native monotonic clock and cached state. No dynamic Java proxy classes or game-surface touch hook are needed for authorization. The Android UI queues commands on the main thread; an authentication callback cannot reactivate a cancelled toggle. Leaving the game or losing the UI heartbeat stops input. Hiding UI alone does not stop it.

Tests: `node --test bshack/runtime.test.mjs`, `:controlui:testDebugUnitTest`, `bshackcheck` Android in-memory DEX/patch checks, and `bridgecheck` using a real Frida Gadget and the production bootstrap/authorization adapter. The fixture has no game native library. The downloaded full production JS engine is loaded to validate the bootstrap path, but this is not a friendly match or Samsung API 37 verification.

No official startup protection or signature checks are edited. Recorder-only UI hiding is unsupported and disabled. The original licensed native auto-goal engine is retained, not replaced with the older Iris screen-analysis controller.

## 2.1.1 update

`engine_state.js` commits an authorized ON/OFF request without depending on native UI rendering. The Android main-thread scheduler and native battle update both call this pure state transition. Initialization-time taps are retained and can be cancelled. The game panel no longer lists buyer-key registration or streamer mode. HWID authorization remains enforced. Server/administrator device corrections preserve license keys, expiry and suspension.

## 2.2.0 update

The APK bootstrap owns a single Java bridge and loads a verified engine module with `Script.load`. UI/engine patches use a separate pinned RSA update key; do not use the public APK test key to authenticate patches. The `PatchStore` validates every startup, records pending activation, and restores the embedded version after incomplete startup. A patch is marked healthy after UI heartbeats and successful native engine binding. See [the release workflow](UPDATES-KO.md). `bridgecheck/fixtures` is test data and must never be uploaded to the stable update channel.
