#!/usr/bin/env bash
# Convenience wrapper: ./patch.sh discord|tumblr <input.apk> [output.apk]
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

java -Djava.awt.headless=true -jar "$MORPHE_JAR" patch \
    -p "$BUNDLE" \
    -e "$patch_name" \
    -O "immichBaseUrl=$IMMICH_URL" \
    -O "immichApiKey=$IMMICH_API_KEY" \
    -o "$output" \
    "$input"
