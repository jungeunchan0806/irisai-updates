# BSHACK baseline panel integration

Baseline SHA-256: `8d595381fe0247dbfc06bd03879f68828d1dc1967647e830d0c51745d810f082`.

`build.py BASELINE UI_PAYLOAD_APK UNSIGNED_APK` replaces only `lib/arm64-v8a/libosu.data.so` and adds `assets/iris-panel.dex`. Original manifest, DEX, native code, assets and resources remain byte-identical. `verify.py BASELINE SIGNED_APK` verifies these invariants and all CRCs. The Frida package length is recalculated; stale source maps are removed.

Build the lightweight UI with `./gradlew :bshackui:assembleRelease`. Sign using the **public AOSP test key** matching the provided BSHACK baseline, with apksigner `--alignment-preserved true`, v2/v3 enabled. Verify with apksigner and `zipalign -c -P 16 4`. Public AOSP key sources are `platform/build/target/product/security/testkey.pk8` and `testkey.x509.pem` on android.googlesource.com. This is not the official Supercell signing key.

The license runtime incorporates the Java-thread separation from the user's BSHACK 2.0.1 artifact. It retains server authorization, HWID calculation, explicit first-use consent, 60-second renewal and maximum 300-second leases. Native callbacks use a native monotonic clock. The Android UI queues commands and runs on the main thread; an authentication callback cannot reactivate a cancelled toggle. Leaving the game stops the requested engine state. Hiding UI alone does not stop it.

Tests: `node --test bshack/runtime.test.mjs`, `:controlui:testDebugUnitTest`, and the `bshackcheck` Android in-memory DEX fixture. The fixture has no game native library. The GitHub workflow separately compares baseline/game startup in a disposable emulator; neither test is a friendly match or Samsung API 37 verification.

No official startup protection or signature checks are edited. Recorder-only UI hiding is unsupported and disabled. The original licensed native auto-goal engine is retained, not replaced with the older Iris screen-analysis controller.
