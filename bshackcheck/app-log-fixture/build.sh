#!/usr/bin/env bash
set -euo pipefail
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
sdk="$ANDROID_HOME/build-tools/35.0.0"
jarpath="$ANDROID_HOME/platforms/android-35/android.jar"
fixture="bshackcheck/app-log-fixture"
mkdir "$out/classes"
javac --release 8 -classpath "$jarpath" -d "$out/classes" "$fixture/AppLogProbe.java" bshackui/src/main/java/com/irisai/bshack/ProcessLogReport.java
jar cf "$out/classes.jar" -C "$out/classes" .
"$sdk/d8" --lib "$jarpath" --min-api 32 --output "$out" "$out/classes.jar"
"$sdk/aapt2" link -o "$out/fixture.apk" --manifest "$fixture/AndroidManifest.xml" --min-sdk-version 32 --target-sdk-version 36 -I "$jarpath"
(cd "$out" && zip -q fixture.apk classes.dex)
"$sdk/zipalign" 4 "$out/fixture.apk" "$out/aligned.apk"
keytool -genkeypair -keystore "$out/qa.jks" -storepass android -keypass android -alias qa -keyalg RSA -keysize 2048 -validity 1 -dname CN=Test
"$sdk/apksigner" sign --ks "$out/qa.jks" --ks-pass pass:android --out /tmp/iris-log-fixture.apk "$out/aligned.apk"
