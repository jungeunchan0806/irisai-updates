# BSHACK baseline panel integration

Baseline SHA-256: `8d595381fe0247dbfc06bd03879f68828d1dc1967647e830d0c51745d810f082`.

`build.py BASELINE UI_PAYLOAD_APK UNSIGNED_APK` replaces only `lib/arm64-v8a/libosu.data.so` and adds the engine/UI/bootstrap assets, `assets/iris_farm/`, and three ARM64 ONNX/OpenCV libraries. Original manifest, DEX, native code (including `libg`, BRF and libc++), assets and resources remain byte-identical. `verify.py BASELINE SIGNED_APK` verifies these invariants, added assets/library hashes, ELF 16 KiB compatibility and all CRCs. The Frida package length is recalculated; stale source maps are removed.

Build the lightweight UI with `./gradlew :bshackui:assembleRelease`. Sign using the **public AOSP test key** matching the provided BSHACK baseline, with apksigner `--alignment-preserved true`, v2/v3 enabled. Verify with apksigner and `zipalign -c -P 16 4`. Public AOSP key sources are `platform/build/target/product/security/testkey.pk8` and `testkey.x509.pem` on android.googlesource.com. This is not the official Supercell signing key.

The typed Android `LicenseClient` retains server authorization, the original HWID calculation/preferences, explicit first-use consent, 60-second renewal and maximum 300-second leases. The JS adapter caches the authorization lease; native callbacks use only a native monotonic clock and cached state. No dynamic Java proxy classes or game-surface touch hook are needed for authorization. The Android UI queues commands on the main thread; an authentication callback cannot reactivate a cancelled toggle. Leaving the game or losing the UI heartbeat stops input. Hiding UI alone does not stop it.

Tests: `node --test bshack/runtime.test.mjs`, `:controlui:testDebugUnitTest`, `bshackcheck` Android in-memory DEX/patch checks, and `bridgecheck` using a real Frida Gadget and the production bootstrap/authorization adapter. The fixture has no game native library. The downloaded full production JS engine is loaded to validate the bootstrap path, but this is not a friendly match or Samsung API 37 verification.

No official startup protection or signature checks are edited. Recorder-only UI hiding is unsupported and disabled. The original licensed native auto-goal engine is retained, not replaced with the older Iris screen-analysis controller.

## 2.1.1 update

`engine_state.js` commits an authorized ON/OFF request without depending on native UI rendering. The Android main-thread scheduler and native battle update both call this pure state transition. Initialization-time taps are retained and can be cancelled. The game panel no longer lists buyer-key registration or streamer mode. HWID authorization remains enforced. Server/administrator device corrections preserve license keys, expiry and suspension.

## 2.2.0 update

The APK bootstrap owns a single Java bridge and loads a verified engine module with `Script.load`. UI/engine patches use a separate pinned RSA update key; do not use the public APK test key to authenticate patches. The `PatchStore` validates every startup, records pending activation, and restores the embedded version after incomplete startup. A patch is marked healthy after responsive UI/authorization bridge heartbeats; native game readiness is deliberately not a startup condition (2.2.2 regression fix). See [the release workflow](UPDATES-KO.md). `bridgecheck/fixtures` is test data and must never be uploaded to the stable update channel.

## 2.3.0 integrated features

The user's TheSeven APK and Auto Goal baseline have identical `libg.so` and
matching BRF feature implementations; their BRF differences are menu text/UI.
No original C++ source was supplied or reconstructed. `features_native.c` and
`features_bridge.js` connect the **already included** native feature/config API
to the Iris menu. The bootstrap explicitly starts the bundled BRF library with
`System.load`, including its `JNI_OnLoad`; the baseline's TitanApplication only
loads Gadget. Iris waits for BRF's renderer before installing its own hooks to
keep native initialization ordered. The exact baseline
game SHA-256 and the original in-memory build ID remain required: BRF-modified
function prologues are accepted only after the installed verifier matches that
exact game file and the native feature renderer is ready. Instruction checks
also guard the fixed BRF offsets. Mutable CModule data lives in
host-allocated memory, and queued changes apply on the original render thread.
The old native menu/hit regions are replaced; original gameplay code remains.
The original TheSeven farm and BRF auto-goal are forced OFF. The existing Iris
licensed goal engine remains the only goal controller, with its ON/OFF state
and authorization covered by the runtime regression tests.

Available controls: Aim, Auto Dodge, Auto Fire, Hold to Shoot, X-Ray, Anti AFK,
Shade center aim, Nani mode, Empty Pin, enemy ammo, camera shake, FPS display
and FPS limit, plus their attack/super/bot/reaction/chance settings. Iris goal,
license/HWID, update checks, diagnostics, language, UI hiding, top-center opener
and `discord.gg/bshack` are retained. [Iris farm](../irisfarm/README.md) is a
separate Android port of the user's original default farm policy and models.

This dependency-bearing release requires one APK update signed with the same
Auto Goal key. Subsequent UI/engine patches use base `bshack-70648-panel7-v1`
and `android-bshack-integrated-updates`; the old 2.2.x channel is left intact.
Do not distribute this DEX through the old channel: those APKs lack its native
libraries/models. Model/native-library changes still require an APK update.
