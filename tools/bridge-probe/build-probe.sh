#!/usr/bin/env bash
# Builds the Immich bridge probe APK twice:
#   build/probe-trusted.apk    signed with the project keystore (positive tests)
#   build/probe-untrusted.apk  signed with a throwaway key (denial test)
#
# Run on device with:
#   adb install -r build/probe-trusted.apk
#   adb shell am start -n dev.immich.bridge.probe/.ProbeActivity
#   adb logcat -d -s ImmichBridgeProbe
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PATCHES="$(cd "$ROOT/../.." && pwd)"
[ -f "$PATCHES/.env" ] && . "$PATCHES/.env"

ANDROID_JAR="${ANDROID_JAR:-/opt/android-sdk/platforms/android-36/android.jar}"
BUILD_TOOLS="${BUILD_TOOLS:-/opt/android-sdk/build-tools/37.0.0}"
OUT="$ROOT/build"
PKG_DIR="dev/immich/bridge/probe"

log() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/res/values"

cat > "$OUT/res/values/strings.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<resources><string name="app_name">Immich Bridge Probe</string></resources>
EOF

log "javac"
javac -classpath "$ANDROID_JAR" -d "$OUT/classes" "$ROOT/src/$PKG_DIR/ProbeActivity.java"

log "d8"
"$BUILD_TOOLS/d8" --release --lib "$ANDROID_JAR" --min-api 26 --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class')

log "aapt2 link"
"$BUILD_TOOLS/aapt2" compile --dir "$OUT/res" -o "$OUT/res.zip" >/dev/null
"$BUILD_TOOLS/aapt2" link -o "$OUT/base.apk" -I "$ANDROID_JAR" \
  --manifest "$ROOT/AndroidManifest.xml" "$OUT/res.zip" >/dev/null

python3 - "$OUT/base.apk" "$OUT/dex/classes.dex" <<'PY'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, "a") as z:
    z.write(dex, "classes.dex")
print("classes.dex injected")
PY

"$BUILD_TOOLS/zipalign" -f 4 "$OUT/base.apk" "$OUT/probe-aligned.apk"

if [ -n "${KEYSTORE_PATH:-}" ] && [ -f "$KEYSTORE_PATH" ]; then
  log "signing trusted build with $KEYSTORE_PATH"
  "$BUILD_TOOLS/apksigner" sign \
    --ks "$KEYSTORE_PATH" --ks-key-alias "${KEYSTORE_ALIAS:-immich-bridge}" \
    --ks-pass "pass:$KEYSTORE_PASSWORD" --key-pass "pass:$KEYSTORE_PASSWORD" \
    --out "$OUT/probe-trusted.apk" "$OUT/probe-aligned.apk"
else
  log "no KEYSTORE_PATH in .env — skipping the trusted build"
fi

log "linking untrusted variant under its own package id"
"$BUILD_TOOLS/aapt2" link -o "$OUT/base-untrusted.apk" -I "$ANDROID_JAR" \
  --manifest "$ROOT/AndroidManifest.xml" \
  --rename-manifest-package dev.immich.bridge.probe.untrusted \
  "$OUT/res.zip" >/dev/null

python3 - "$OUT/base-untrusted.apk" "$OUT/dex/classes.dex" <<'PY'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, "a") as z:
    z.write(dex, "classes.dex")
print("classes.dex injected (untrusted)")
PY

"$BUILD_TOOLS/zipalign" -f 4 "$OUT/base-untrusted.apk" "$OUT/probe-untrusted-aligned.apk"

log "signing untrusted build with a throwaway key"
[ -f "$ROOT/throwaway.keystore" ] || keytool -genkeypair -keystore "$ROOT/throwaway.keystore" -alias probe \
  -keyalg RSA -keysize 2048 -validity 3650 \
  -storepass probe-throwaway -keypass probe-throwaway \
  -dname "CN=Probe Untrusted" >/dev/null 2>&1
"$BUILD_TOOLS/apksigner" sign \
  --ks "$ROOT/throwaway.keystore" --ks-key-alias probe \
  --ks-pass pass:probe-throwaway --key-pass pass:probe-throwaway \
  --out "$OUT/probe-untrusted.apk" "$OUT/probe-untrusted-aligned.apk"

log "done:"; ls -la "$OUT"/*.apk
