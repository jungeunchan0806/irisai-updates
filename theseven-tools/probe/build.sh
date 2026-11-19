#!/usr/bin/env bash
set -eu
source_dir="$(cd "$(dirname "$0")" && pwd)"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
bt="$ANDROID_HOME/build-tools/35.0.0"
platform="$ANDROID_HOME/platforms/android-35/android.jar"
mkdir "$out/classes" "$out/dex"
javac --release 8 -classpath "$platform" -d "$out/classes" "$source_dir/Inspect.java"
jar cf "$out/classes.jar" -C "$out/classes" .
"$bt/d8" --lib "$platform" --min-api 32 --output "$out/dex" "$out/classes.jar"
"$bt/aapt2" link -I "$platform" --manifest "$source_dir/AndroidManifest.xml" --min-sdk-version 32 --target-sdk-version 35 -o "$out/probe.apk"
(cd "$out/dex" && zip -q "$out/probe.apk" classes.dex)
"$bt/zipalign" -f 4 "$out/probe.apk" "$out/aligned.apk"
keytool -genkeypair -alias ephemeral -keystore "$out/test.p12" -storepass ephemeral -keypass ephemeral -keyalg RSA -keysize 2048 -validity 1 -dname CN=TemporaryEmulatorInspector >/dev/null 2>&1
"$bt/apksigner" sign --ks "$out/test.p12" --ks-pass pass:ephemeral --out "$1" "$out/aligned.apk"
