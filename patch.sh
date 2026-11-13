#!/usr/bin/env bash
# Convenience wrapper: ./patch.sh discord|tumblr <input.apk> [output.apk]
#
# Signing: when KEYSTORE_PATH is set in .env, patched APKs are signed with the
# project keystore — the same key signs the custom Immich build, which is what
# makes the bridge's signature-level permission grant. Without it, Morphe's
# built-in signer is used (v1 behaviour).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MORPHE_JAR="${MORPHE_JAR:-/home/lucy/Downloads/morphe-desktop-1.15.0-all.jar}"
BUNDLE="$ROOT/build/immich-media-patches.mpp"

[ -f "$ROOT/.env" ] && . "$ROOT/.env"

: "${IMMICH_URL:?set IMMICH_URL in .env or the environment}"
: "${IMMICH_API_KEY:?set IMMICH_API_KEY in .env or the environment}"

target="${1:-}"
input="${2:-}"
[ -n "$target" ] && [ -n "$input" ] || { echo "usage: $0 <discord|tumblr> <input.apk> [output.apk]" >&2; exit 1; }

case "$target" in
    discord) patch_name="Immich photo picker" ;;
    tumblr) patch_name="Immich photo picker" ;;
    *) echo "unknown target: $target" >&2; exit 1 ;;
esac

output="${3:-$ROOT/build/$target-immich.apk}"

[ -f "$BUNDLE" ] || "$ROOT/build.sh"

KEYSTORE_ARGS=()
if [ -n "${KEYSTORE_PATH:-}" ] && [ -f "$KEYSTORE_PATH" ]; then
    KEYSTORE_ARGS+=(--keystore "$KEYSTORE_PATH")
    [ -n "${KEYSTORE_ALIAS:-}" ] && KEYSTORE_ARGS+=(--keystore-entry-alias "$KEYSTORE_ALIAS")
    if [ -n "${KEYSTORE_PASSWORD:-}" ]; then
        KEYSTORE_ARGS+=(--keystore-password "$KEYSTORE_PASSWORD" --keystore-entry-password "$KEYSTORE_PASSWORD")
    fi
    echo "signing with: $KEYSTORE_PATH"
fi

java -Djava.awt.headless=true -jar "$MORPHE_JAR" patch \
    -p "$BUNDLE" \
    -e "$patch_name" \
    -O "immichBaseUrl=$IMMICH_URL" \
    -O "immichApiKey=$IMMICH_API_KEY" \
    "${KEYSTORE_ARGS[@]}" \
    -o "$output" \
    "$input"
