#!/usr/bin/env bash
# Run from the root of a clean checkout with JDK 17 and the Android SDK.
set -euo pipefail
root=$PWD
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
git archive HEAD -- src build.gradle settings.gradle gradle.properties gradle gradlew qa/arena qa/probe | tar -x -C "$work"
# Only the emulator ABI is needed for this debug binary.
sed -i "s/debug { ndk { abiFilters 'arm64-v8a', 'x86_64' } }/debug { ndk { abiFilters 'x86_64' } }/" "$work/build.gradle"
cd "$work"
bash gradlew :testDebugUnitTest :assembleDebug :qa:arena:assembleDebug :qa:probe:assembleDebug
mkdir -p "$root/qa/emulator-inputs"
tar -czf "$root/qa/emulator-inputs/player-1.0.2-x86_64.tar.gz" build/outputs/apk/debug/*.apk qa/{arena,probe}/build/outputs/apk/debug/*.apk build/test-results/testDebugUnitTest/TEST*.xml
cd "$root"
git ls-files -- src build.gradle 'gradle*' settings.gradle qa/arena qa/probe | LC_ALL=C sort | xargs sha256sum > qa/emulator-inputs/source-sha256.txt
sha256sum qa/emulator-inputs/*.tar.gz > qa/emulator-inputs/archive-sha256.txt
