# Iris AutoGoal 2.2.5 static standalone

Builds from the original 70.757 APK without its contents being stored in Git.
The bundled engine and UI are immutable for this build: no downloaded-patch loader,
update UI, rollback notification, Anti AFK module, or connection guard is included.
The invisible opener remains at top center. Existing preferences, HWID authorization,
Discord and original native AutoGoal are retained. Real matches remain unverified.

`build.py ORIGINAL_APK UI_PAYLOAD_APK unsigned.apk` builds directly from the original.
`static_release.py repack PUBLISHED_2_2_0_APK distribution/autogoal-static-225/payload.zip unsigned.apk`
reproduces the release using the hash-pinned published APK. Sign using its matching
public AOSP test certificate, then run `static_release.py verify` with the same inputs.
This certificate is not the official game's signing certificate.

`.github/workflows/android-bridgecheck.yml` on `android-autogoal-static-225` verifies
the payload, signer, unchanged game entries, alignment, real isolated Android UI,
legacy update-state immunity, repeated restarts and baseline/candidate launch before publishing.
Fixture license injection exists only in the isolated test APK; never in the game payload.
No live license, private signing key or seller credential is included.

Patch-specific instrumentation has been removed from this static branch.
The new device checks are `bridgecheck/check.py`. Other old patch
guides describe earlier versions and do not apply to 2.2.5-static.
