# Candidate 1: physical-device startup failure

Status: **unresolved; no fixed game APK has been produced**. The diagnostic APK is a temporary replacement used to read prior app exits, not a game fix. Its own successful emulator tests do not establish that the game works.

## Evidence

The user supplied a diagnostic report from an ARM64 Android 17 / API 37 phone. The game exits before the logo. The user reports that the official game ran normally on the same phone. The official app's exact version was not independently established, and the assistant has no direct connection to this phone.

Only the relevant stack is retained here; device model, timestamps and process IDs are omitted:

```text
FATAL EXCEPTION: main
nv.s: 01
  at nv.aJ.b(SourceFile:192)
  at nv.aJ.a(SourceFile:0)
  at java.lang.Runtime.nativeLoad(Native Method)
  at java.lang.Runtime.loadLibrary0(...)
  at java.lang.System.loadLibrary(...)
  at nv.A.a(SourceFile:2)
  at nv.A.d(...)
  at nv.A.b(...)
  at nv.A.<clinit>(...)
  at androidx.core.app.CoreComponentFactory.<clinit>(...)
  at android.app.LoadedApk.createAppFactory(...)
```

Read-only inspection of the supplied original APK establishes:

- `nv.A.a()` calls `System.loadLibrary("supercell_brawlstars")`.
- `nv.aJ.b()` maps the leading hexadecimal code `01` to `nv.s`. The class name is obfuscated and does not identify the underlying condition.
- `nv.aJ.a()` reports this exception to the default uncaught-exception handler, which terminates the process.
- The failure occurs while constructing the app component factory, before content providers and the Iris controller/model are initialized.

This is distinct from the prior x86_64 emulator's ARM64-translation failure (`Thread-2`, `nv.Q`) affecting both original and candidate. The emulator result cannot explain away this phone failure.

## Packaging checks relevant to this failure

- `libsupercell_brawlstars.so` exists in both original and candidate and has the same SHA-256: `a29a76d8a1063264e132d521915a9ff95688cf1abc061cc9b55ca38eb7bfa8e9`.
- All original ARM64 native libraries are present. Their bytes were verified unchanged in the original candidate validation.
- The original and added libraries have ELF PT_LOAD alignment of at least 16KB. The APK passed the existing ZIP alignment check. This is a static check, not proof of execution on a 16KB-page device.
- Both manifests set `extractNativeLibs=true`; compressed native libraries in the candidate are therefore not by themselves proof of a loading error.
- The failing library has only Android system libraries in DT_NEEDED, so an OpenCV/ONNX C++ dependency is not the identified failure path.
- The candidate uses a different signing certificate from the original. APK v2/v3 signature validity establishes Android installability, not acceptance by the game's own startup/integrity logic.

## Interpretation and repair limits

A repackaging/re-signing-related failure in the original game's startup or integrity checks is the leading hypothesis. Neither the exact meaning of `01` nor a specific failing native condition has been established. The report is not evidence of an account ban, and it does not establish a general Android 17 incompatibility.

Delaying Iris initialization or changing model loading does not address a failure that precedes Iris initialization. Suppressing the exception would not demonstrate that the protected library initialized correctly and is not a verified repair.

The available source is the Iris module, not the original game/protection build. No supported way to rebuild that protected startup code with the modified package has been established. No fixed APK is being represented as complete. Gameplay remains untested.

## Packaging retry: a verified defect, not a verified startup repair

A subsequent comparison found that candidate 1 omitted all 128 original `META-INF` entries, including `META-INF/services/fi` and `META-INF/services/zd1`. Both the merge flag `-clean-meta` and the injector's directory-wide deletion were overly broad: this directory also contains runtime service registrations, Kotlin/Android metadata and notices, not only obsolete JAR signatures.

`IrisAutoGoal-BrawlStars-70.757-packaging2-test.apk` preserves that metadata and removes only obsolete signature artifacts. Validation now compares every original nonsignature metadata entry with the source APK, in addition to the prior DEX/native/assets checks. The old candidate fails this new check and the retry passes it.

The retry adds exactly those 128 entries. All existing ZIP file contents remain byte-identical to candidate 1; the game manifest, Iris and original DEX, models, libraries, native compression and game resources are unchanged. The whole-APK signature changes as expected for changed archive content, using the same signing certificate, package and version code.

There is no evidence yet linking this metadata loss to the native `01` condition. Startup on the user's phone is untested for the retry; it may still fail for the original native startup/integrity reason. The retry is published as an experiment to test this concrete packaging correction, not as a completed fix. No game protection checks were patched or bypassed.

See [retry validation](packaging2-validation.json) and [installation instructions](../distribution/packaging2/RELEASE-SECTION-KO.md). The 489KB binary delta uses the exact signed candidate 1 as its source and is for reproducible artifact publication; the regular phone-installable file remains the full APK.
