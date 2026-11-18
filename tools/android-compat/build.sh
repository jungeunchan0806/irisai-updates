#!/usr/bin/env bash
set -euo pipefail
base=tools/android-compat
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
bt="$ANDROID_HOME/build-tools/35.0.0"
jarpath="$ANDROID_HOME/platforms/android-35/android.jar"
mkdir "$work/classes"
python "$base/prepare.py" "$work/assets"
javac --release 8 -classpath "$jarpath" -d "$work/classes" "$base/GameApp.java"
jar cf "$work/classes.jar" -C "$work/classes" .
"$bt/d8" --lib "$jarpath" --min-api 32 --output "$work" "$work/classes.jar"
"$bt/aapt2" link -o "$work/test.apk" --manifest "$base/AndroidManifest.xml" --min-sdk-version 32 --target-sdk-version 36 -A "$work/assets" -I "$jarpath"
(cd "$work" && zip -q test.apk classes.dex)
"$bt/zipalign" 4 "$work/test.apk" "$work/aligned.apk"
keytool -genkeypair -keystore "$work/qa.jks" -storepass android -keypass android -alias qa -keyalg RSA -keysize 2048 -validity 2 -dname CN=IrisLocalTest
"$bt/apksigner" sign --ks "$work/qa.jks" --ks-pass pass:android --out /tmp/iris-platform-qa.apk "$work/aligned.apk"
