#!/bin/bash
# Filters ONE ruined portal candidate: is its VANILLA portal finishable?
#   check-one-rp.sh "<seed> <structX> <structZ>"
# Row: seed,PASS|FAIL,detail
set -u
read -r SEED SX SZ <<< "$1"
POOL="/tmp/rp-workers"
WORKERS=$(cat "$POOL/worker_count" 2>/dev/null || echo 1)
DIR=""
for attempt in $(seq 1 900); do
  for i in $(seq 0 $((WORKERS - 1))); do
    if mkdir "$POOL/w$i.claim" 2>/dev/null; then DIR="$POOL/w$i"; CLAIM="$POOL/w$i.claim"; break 2; fi
  done
  sleep 1
done
[ -n "$DIR" ] || { echo "$SEED,ERROR,no worker" > "$POOL/res_$SEED"; exit 0; }
trap 'rmdir "$CLAIM" 2>/dev/null' EXIT

PORT=$((26000 + ${DIR##*w}))
printf 'level-seed=%s\nlevel-type=default\nonline-mode=false\nmax-tick-time=-1\nsync-chunk-writes=false\nserver-port=%s\n' "$SEED" "$PORT" > "$DIR/run/server.properties"
printf '%s %s %s\n' "$SEED" "$SX" "$SZ" > "$DIR/run/portalfilter.txt"
rm -rf "$DIR/run/world"; rm -f "$DIR/run/portalfilter.csv"
(cd "$DIR" && ./gradlew runServer --offline --no-daemon -q < /dev/null > "$POOL/log_$SEED.txt" 2>&1)
rm -f "$DIR/run/portalfilter.txt"
if [ -s "$DIR/run/portalfilter.csv" ]; then
  tail -1 "$DIR/run/portalfilter.csv" > "$POOL/res_$SEED"
else
  echo "$SEED,ERROR,server produced no result" > "$POOL/res_$SEED"
fi
