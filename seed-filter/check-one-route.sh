#!/bin/bash
# Verifies ONE pool pair's OVERWORLD OPENING against the game.
#
#   check-one-route.sh "<type> <owSeed> <netherSeed> <sx> <sz> <smithX> <smithZ> <pairId>"
#
# Runs the REAL MatchWorldSetup and then asks whether the opening it was
# supposed to create is actually there. See RouteCheckHook.
#
# Row: pairId,type,seed,lava,chests,verdict,detail,extra
set -u
read -r TYPE OW NS SX SZ MX MZ PAIR <<< "$1"
POOL="/tmp/route-workers"
WORKERS=$(cat "$POOL/worker_count" 2>/dev/null || echo 1)

DIR=""
for attempt in $(seq 1 900); do
  for i in $(seq 0 $((WORKERS - 1))); do
    if mkdir "$POOL/w$i.claim" 2>/dev/null; then
      DIR="$POOL/w$i"; CLAIM="$POOL/w$i.claim"; break 2
    fi
  done
  sleep 1
done
[ -n "$DIR" ] || { echo "$PAIR,$TYPE,$OW,-1,-1,ERROR,no worker," > "$POOL/res_$PAIR"; exit 0; }
trap 'rmdir "$CLAIM" 2>/dev/null' EXIT

# Not 25565 - never fight the player's own game for the port.
PORT=$((25800 + ${DIR##*w}))
STALE=$(lsof -nP -iTCP:$PORT -sTCP:LISTEN -t 2>/dev/null)
if [ -n "$STALE" ]; then kill -9 $STALE 2>/dev/null; sleep 2; fi
printf 'level-seed=%s\nlevel-type=default\nonline-mode=false\nmax-tick-time=-1\nsync-chunk-writes=false\nserver-port=%s\n' \
  "$OW" "$PORT" > "$DIR/run/server.properties"
printf '%s %s %s %s %s %s %s\n' "$TYPE" "$OW" "$NS" "$SX" "$SZ" "$MX" "$MZ" > "$DIR/run/routecheck.txt"
rm -rf "$DIR/run/world"
rm -f "$DIR/run/routecheck.csv"

(cd "$DIR" && ./gradlew runServer --offline --no-daemon -q < /dev/null > "$POOL/log_$PAIR.txt" 2>&1)
rm -f "$DIR/run/routecheck.txt"

if [ -s "$DIR/run/routecheck.csv" ]; then
  printf '%s,%s\n' "$PAIR" "$(tail -1 "$DIR/run/routecheck.csv")" > "$POOL/res_$PAIR"
else
  # Explicit ERROR, never a plausible-looking zero.
  printf '%s,%s,%s,-1,-1,ERROR,server produced no result,\n' "$PAIR" "$TYPE" "$OW" > "$POOL/res_$PAIR"
fi
