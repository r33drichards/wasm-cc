#!/usr/bin/env bash
# Launcher for the wasm-cc e2e server. Brings up the compose stack, waits until
# the Minecraft server is healthy, and prints how to connect a real client.
#
# A fully-automated Minecraft *client* is impractical on a headless dev box (it
# needs a GPU/display and a Mojang/Microsoft login), so the pragmatic, genuinely
# working path is: this script boots + health-gates the server, and you join with
# any desktop client (vanilla launcher or Prism/MultiMC) at localhost:25565.
#
# Usage:
#   ./launch.sh up        # build/pull, start detached, wait healthy, print join info (default)
#   ./launch.sh status    # show health + whether wasm-cc loaded
#   ./launch.sh logs      # follow server logs
#   ./launch.sh console   # open the server console (rcon-cli) — run /op, /give, etc.
#   ./launch.sh down      # stop and remove the container (keeps ./data)
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE=(docker compose -f "$HERE/docker-compose.yml")
ADDR="localhost:25565"

cmd="${1:-up}"

wait_healthy() {
  echo "waiting for the server to become healthy (first run downloads MC + mods, can take a few minutes) ..."
  for _ in $(seq 1 180); do
    status="$(docker inspect --format '{{ .State.Health.Status }}' wasm-cc-e2e 2>/dev/null || echo missing)"
    case "$status" in
      healthy) echo "server is healthy."; return 0 ;;
      missing) echo "container not found yet ..." ;;
      *)       printf '  health: %s\r' "$status" ;;
    esac
    sleep 5
  done
  echo
  echo "timed out waiting for health; check './launch.sh logs'." >&2
  return 1
}

print_join() {
  cat <<EOF

================ wasm-cc e2e server is up ================
  Address:  $ADDR   (offline mode — any client can join)
  Version:  Minecraft 1.21.8, Fabric, CC:Tweaked + wasm-cc

Join with a desktop Minecraft client:
  - Vanilla launcher: install the 1.21.8 Fabric profile, then
    Multiplayer -> Add Server -> Server Address: $ADDR -> Join.
  - Prism/MultiMC: create a 1.21.8 instance with the Fabric loader,
    add a server pointing at $ADDR (no client mods are required to
    connect — CC:Tweaked is server-side enough for this demo).

In-game next steps are in e2e/README.md (place a Computer, copy
examples/sqlite_disk.lua onto it, run it, then inspect the .sqlite file
under e2e/data/world/computercraft/computer/<id>/sql/).
=========================================================
EOF
}

case "$cmd" in
  up)
    [ -f "$HERE/data/mods/wasm-cc.jar" ] || {
      echo "error: e2e/data/mods/wasm-cc.jar missing — run 'make e2e-setup' first." >&2
      exit 1
    }
    "${COMPOSE[@]}" up -d
    wait_healthy
    print_join
    ;;
  status)
    docker inspect --format 'health: {{ .State.Health.Status }}' wasm-cc-e2e 2>/dev/null \
      || { echo "container not running."; exit 1; }
    echo "wasm-cc mod in container mods dir:"
    "${COMPOSE[@]}" exec mc sh -c 'ls -1 /data/mods | grep -i wasm || echo "  (not present)"' || true
    ;;
  logs)
    "${COMPOSE[@]}" logs -f
    ;;
  console)
    echo "opening server console (rcon-cli); type 'help' or Ctrl-D to exit."
    "${COMPOSE[@]}" exec mc rcon-cli
    ;;
  down)
    "${COMPOSE[@]}" down
    ;;
  *)
    echo "usage: $0 {up|status|logs|console|down}" >&2
    exit 1
    ;;
esac
