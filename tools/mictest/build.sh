#!/usr/bin/env bash
# Builds mictest.apk with plain SDK tools (no Gradle). Needs ANDROID_HOME and a JDK.
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
PLATFORM="$SDK/platforms/$(ls "$SDK/platforms" | sort -V | head -1)/android.jar"
OUT=build

rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/dex"
"$BT/aapt2" link -o "$OUT/base.apk" --manifest AndroidManifest.xml -I "$PLATFORM"
javac --release 8 -nowarn -classpath "$PLATFORM" -d "$OUT/classes" $(find src -name '*.java') 2>&1 | grep -v '^warning' || true
"$BT/d8" --min-api 21 --lib "$PLATFORM" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

KS=debug.keystore
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias debug \
  -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=kinframe mictest" >/dev/null 2>&1
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --out mictest.apk "$OUT/aligned.apk"
echo "built $(pwd)/mictest.apk"
