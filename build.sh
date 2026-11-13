#!/usr/bin/env bash
# Builds the Morphe patch bundle (.mpp) for the Immich media picker patches.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$ROOT/build"
CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/immich-media-patches"

MORPHE_JAR="${MORPHE_JAR:-/home/lucy/Downloads/morphe-desktop-1.15.0-all.jar}"
VERSION="${VERSION:-0.0.0-dev}"
ANDROID_JAR="${ANDROID_JAR:-/opt/android-sdk/platforms/android-36/android.jar}"
BUILD_TOOLS="${BUILD_TOOLS:-/opt/android-sdk/build-tools/37.0.0}"
KOTLIN_VERSION="${KOTLIN_VERSION:-2.4.21}"

KOTLIN_COMPILER="$CACHE/kotlin-compiler-embeddable-$KOTLIN_VERSION.jar"
KOTLIN_STDLIB="$CACHE/kotlin-stdlib-$KOTLIN_VERSION.jar"
COROUTINES_VERSION="${COROUTINES_VERSION:-1.11.0}"
ANNOTATIONS_VERSION="${ANNOTATIONS_VERSION:-26.0.2}"
KOTLIN_COROUTINES="$CACHE/kotlinx-coroutines-core-jvm-$COROUTINES_VERSION.jar"
JETBRAINS_ANNOTATIONS="$CACHE/annotations-$ANNOTATIONS_VERSION.jar"

log() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }

die() { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

for tool in java javac jar curl; do
    command -v "$tool" >/dev/null 2>&1 || die "missing $tool"
done
[ -f "$MORPHE_JAR" ] || die "Morphe patcher jar not found: $MORPHE_JAR"
[ -f "$ANDROID_JAR" ] || die "android.jar not found: $ANDROID_JAR"
[ -x "$BUILD_TOOLS/d8" ] || die "d8 not found in $BUILD_TOOLS"

mkdir -p "$CACHE"

fetch_jar() {
    local target="$1" path="$2"
    if [ -f "$target" ]; then
        return
    fi
    log "Downloading $(basename "$target")"
    curl -fsSL -o "$target.tmp" "https://repo1.maven.org/maven2/$path"
    mv "$target.tmp" "$target"
}

fetch_jar "$KOTLIN_COMPILER" "org/jetbrains/kotlin/kotlin-compiler-embeddable/$KOTLIN_VERSION/kotlin-compiler-embeddable-$KOTLIN_VERSION.jar"
fetch_jar "$KOTLIN_STDLIB" "org/jetbrains/kotlin/kotlin-stdlib/$KOTLIN_VERSION/kotlin-stdlib-$KOTLIN_VERSION.jar"
fetch_jar "$KOTLIN_COROUTINES" "org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/$COROUTINES_VERSION/kotlinx-coroutines-core-jvm-$COROUTINES_VERSION.jar"
fetch_jar "$JETBRAINS_ANNOTATIONS" "org/jetbrains/annotations/$ANNOTATIONS_VERSION/annotations-$ANNOTATIONS_VERSION.jar"

rm -rf "$OUT"
mkdir -p "$OUT/patch-classes" "$OUT/extension-classes" "$OUT/dex" "$OUT/bundle/extensions"

log "Compiling patch module"
java -cp "$KOTLIN_COMPILER:$KOTLIN_STDLIB:$KOTLIN_COROUTINES:$JETBRAINS_ANNOTATIONS" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -classpath "$MORPHE_JAR" \
    -jvm-target 17 \
    -no-reflect \
    -nowarn \
    -d "$OUT/patch-classes" \
    $(find "$ROOT/patches/src" -name '*.kt')

log "Compiling extension"
javac -nowarn -source 8 -target 8 \
    -bootclasspath "$ANDROID_JAR" \
    -d "$OUT/extension-classes" \
    $(find "$ROOT/extension/src" -name '*.java')

log "Dexing extension"
"$BUILD_TOOLS/d8" \
    --lib "$ANDROID_JAR" \
    --min-api 24 \
    --output "$OUT/dex" \
    $(find "$OUT/extension-classes" -name '*.class')

cp "$OUT/dex/classes.dex" "$OUT/bundle/extensions/immich-media.mpe"
cp -r "$OUT/patch-classes/." "$OUT/bundle/"

cat > "$OUT/MANIFEST.MF" <<EOF
Manifest-Version: 1.0
Created-By: immich-media-patches
Implementation-Title: immich-media-patches
Implementation-Version: $VERSION
EOF

log "Packaging bundle"
(cd "$OUT/bundle" && jar --create --file "$OUT/immich-media-patches.mpp" --manifest "$OUT/MANIFEST.MF" .)

log "Built $OUT/immich-media-patches.mpp (version $VERSION)"
unzip -l "$OUT/immich-media-patches.mpp" | tail -n +4 | head -20
