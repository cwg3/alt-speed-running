#!/bin/bash
# Wood near spawn (and chests for ocean types) for ONE candidate.
#   check-one-spawn.sh "<seed> <structX> <structZ> <type>"
set -u
read -r SEED SX SZ TYPE <<< "$1"
POOL="/tmp/spawn-workers"
WORKERS=$(cat "$POOL/worker_count" 2>/dev/null || echo 1)
DIR=""
for a in $(seq 1 900); do
  for i in $(seq 0 $((WORKERS-1))); do
    if mkdir "$POOL/w$i.claim" 2>/dev/null; then DIR="$POOL/w$i"; CLAIM="$POOL/w$i.claim"; break 2; fi
  done; sleep 1
done
[ -n "$DIR" ] || { echo "$SEED,ERROR,no worker" > "$POOL/res_$SEED"; exit 0; }
trap 'rmdir "$CLAIM" 2>/dev/null' EXIT
printf 'level-seed=%s\nlevel-type=default\nonline-mode=false\nmax-tick-time=-1\nsync-chunk-writes=false\nserver-port=%s\n' "$SEED" $((26300 + ${DIR##*w})) > "$DIR/run/server.properties"
printf '%s %s %s %s\n' "$SEED" "$SX" "$SZ" "$TYPE" > "$DIR/run/spawncheck.txt"
rm -rf "$DIR/run/world"; rm -f "$DIR/run/spawncheck.csv"
(cd "$DIR" && ./gradlew runServer --offline --no-daemon -q < /dev/null > "$POOL/log_$SEED.txt" 2>&1)
rm -f "$DIR/run/spawncheck.txt"
if [ -s "$DIR/run/spawncheck.csv" ]; then tail -1 "$DIR/run/spawncheck.csv" > "$POOL/res_$SEED"
else echo "$SEED,ERROR,server produced no result" > "$POOL/res_$SEED"; fi
