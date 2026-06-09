#!/usr/bin/env bash
# Wire the built artifacts into the compose mount dirs so `docker compose up` has
# everything it needs. Run after building the mod jar and sqlite3.wasm:
#
#   nix develop -c ./gradlew :mod:build     # -> mod/build/libs/mod.jar
#   nix develop -c make resources           # -> engine/.../modules/sqlite3.wasm
#   make e2e-setup                          # (this script)
#
# Idempotent: safe to re-run after a rebuild to refresh the staged files.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"

MOD_JAR="$REPO/mod/build/libs/mod.jar"
SQLITE_WASM="$REPO/engine/src/test/resources/modules/sqlite3.wasm"
CONFIG_SRC="$HERE/config/wasm-cc.json"

DATA="$HERE/data"
MODS_DST="$DATA/mods"
CONFIG_DST="$DATA/config"
MODULES_DST="$CONFIG_DST/wasm-modules"

fail() { echo "error: $*" >&2; exit 1; }

[ -f "$MOD_JAR" ] || fail "missing $MOD_JAR
  build it first: nix develop -c ./gradlew :mod:build"
[ -f "$SQLITE_WASM" ] || fail "missing $SQLITE_WASM
  build it first: nix develop -c make resources"
[ -f "$CONFIG_SRC" ] || fail "missing $CONFIG_SRC (should be committed)"

mkdir -p "$MODS_DST" "$MODULES_DST"

cp -f "$MOD_JAR"     "$MODS_DST/wasm-cc.jar"
cp -f "$SQLITE_WASM" "$MODULES_DST/sqlite3.wasm"
# Only seed the config if absent, so a hand-edited httpAllow etc. survives re-runs.
if [ ! -f "$CONFIG_DST/wasm-cc.json" ]; then
  cp "$CONFIG_SRC" "$CONFIG_DST/wasm-cc.json"
fi

echo "staged:"
echo "  $MODS_DST/wasm-cc.jar"
echo "  $MODULES_DST/sqlite3.wasm"
echo "  $CONFIG_DST/wasm-cc.json"
echo
echo "next: ./e2e/launch.sh up    (then join localhost:25565)"
