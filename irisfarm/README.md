# IrisAI 0.0.9 Android farm port

The included `upstream/` files are unchanged references from the user-provided
[0.0.9 source release](https://github.com/jungeunchan0806/irisai-updates/releases/tag/v0.0.9).
`upstream/PROVENANCE.json` records their hashes; retain the CC BY-NC 4.0 license.
The Java port and the Android input/capture integration are new modifications.

`FarmPolicy` ports the default `aggressive_universal.iris` range hysteresis,
enemy selection, wall checks and ability conditions. `MovementRecovery` adapts
`navigation.py` to bounded OpenCV optical flow, camera-adjusted displacement,
escape directions and failed-path memory. It uses median translation instead
of the desktop affine transform and keeps the existing Iris A* wall navigation.
`FarmVision` uses the unchanged Iris main/tile ONNX models, state templates,
ability colors, and resets navigation after player loss/teleport.

Choose the game's current brawler in Farm settings. This port automates that
brawler's combat and optional lobby/result requeue. The desktop's OCR brawler
queue, trophy-limit rotation, official API integration, Mac window management
and ADB recovery are not included. It does not claim complete Mac app parity.
Default in-game button positions and a landscape game surface are required.
Unrecognized screens pause input rather than guessing which button to press.

The Android controller lazily loads its models on a single worker and copies
only the current game's own `SurfaceView` with `PixelCopy`. It calls that same
process's `GameApp.nOnTouchEvent` JNI entry on the main thread. It does not use
MediaProjection, accessibility, ADB or cross-app touch injection. Main-thread
epoch checks reject late inference after stop/pause/resize. An authorization
lease, UI heartbeat, window focus and visible game surface are required.
Opening the Iris panel pauses inputs. Native Auto Goal has movement priority
while handling the ball; native Auto Fire suppresses duplicate normal attacks.

The recorded JPEG fixtures come from the same source release's
`tests/fixtures/brawl_ball/`. The non-text report/replay result icons are crops
from `result.jpg`: `(1293,36,1338,80)` and `(1767,24,1854,87)` at 1920×1080.
Both icons plus the blue proceed-button region are required to recognize the
statistics page. Language-specific upstream result/title templates remain
subject to the game's UI/language/layout matching their original appearance.

Tests: `:irisfarm:testDebugUnitTest` runs real CPU ONNX/OpenCV on recorded frames
and checks battle, result, highlight, no-player, range and camera/stuck cases.
`bshackcheck/FarmChecks` loads the exact in-memory DEX and Android JNI libraries,
copies a rendered SurfaceView, records game-boundary calls, and verifies stop,
pause, goal priority and authorization expiry. No live game server/friendly
match or Samsung Android 17 hardware testing is implied.
