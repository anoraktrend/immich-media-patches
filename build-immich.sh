#!/usr/bin/env bash
# Builds the custom Immich Android APK (v2 orchestrator) from the sparse checkout.
# Encodes every caveat found while getting the baseline build working — see
# docs/v2-orchestrator.md ("Build from source" / "Operational notes").
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="${IMMICH_SRC:-/home/lucy/media/Android/immich-app}"
FLUTTER_DIR="${FLUTTER_DIR:-$HOME/fvm/versions/3.47.2}"
ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}"
TARGET_PLATFORM="${TARGET_PLATFORM:-android-arm64}"
CLANG_RESOURCE_DIR="${CLANG_RESOURCE_DIR:-/usr/lib/clang/23}"
RESTORE_FFIGEN=0

[ -f "$ROOT/.env" ] && . "$ROOT/.env"

log() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }

[ -d "$SRC" ] || {
    echo "error: missing Immich checkout at $SRC" >&2
    echo "see docs/v2-orchestrator.md § Operational notes for the sparse clone recipe" >&2
    exit 1
}

export PATH="$FLUTTER_DIR/bin:$PATH"
export ANDROID_HOME JAVA_HOME

cd "$SRC"

log "install:ci (open-api dart client + pub get)"
MISE_YES=1 mise //mobile:install:ci

# ffigen: libclang on this machine cannot find stdarg.h without an explicit
# resource dir; append compiler-opts for the run, then restore the file.
if ! grep -q "resource-dir" native/native_core/ffigen.yaml; then
    cat >> native/native_core/ffigen.yaml <<EOF

compiler-opts:
  - '-resource-dir=$CLANG_RESOURCE_DIR'
  - '-I$CLANG_RESOURCE_DIR/include'
EOF
    RESTORE_FFIGEN=1
fi
log "codegen:native (cargo build + ffigen)"
MISE_YES=1 mise //mobile:codegen:native
if [ "$RESTORE_FFIGEN" = 1 ]; then
    git checkout -- native/native_core/ffigen.yaml
fi

# The umbrella `codegen` task aborts on the two steps above/below, so the
# remaining generators are invoked individually.
log "codegen:dart (build_runner)"
MISE_YES=1 mise //mobile:codegen:dart
log "codegen:drift:migration"
MISE_YES=1 mise //mobile:codegen:drift:migration
log "codegen:pigeon"
MISE_YES=1 mise //mobile:codegen:pigeon
log "i18n loader (easy_localization)"
MISE_YES=1 mise //mobile:i18n:loader
log "i18n keys (typed translations)"
MISE_YES=1 mise //mobile:i18n:keys

# Signing: the project keystore when configured (.env), else debug fallback.
if [ -n "${KEYSTORE_PATH:-}" ] && [ -f "$KEYSTORE_PATH" ]; then
    log "signing with $KEYSTORE_PATH"
    cat > "$SRC/mobile/android/key.properties" <<EOF
storeFile=$KEYSTORE_PATH
keyAlias=${KEYSTORE_ALIAS:-immich-bridge}
keyPassword=$KEYSTORE_PASSWORD
storePassword=$KEYSTORE_PASSWORD
EOF
else
    log "no KEYSTORE_PATH in .env — release build will fall back to debug signing"
fi

log "flutter build apk (${TARGET_PLATFORM})"
cd "$SRC/mobile"
flutter build apk --release --target-platform "$TARGET_PLATFORM"

log "done: $SRC/mobile/build/app/outputs/flutter-apk/app-release.apk"
