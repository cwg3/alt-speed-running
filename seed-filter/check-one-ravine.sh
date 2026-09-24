#!/bin/bash
# Verifies one seed. Called by verify-ravines.sh via xargs -P.
#
#   check-one-ravine.sh <seed> <x> <z> <type>
#
# Claims a worker directory atomically rather than being told which one
# to use. An earlier version assigned directories by line number, but
# xargs runs N at a time regardless of line number, so two seeds sharing
# an index ran in the same directory at the same time - producing
# duplicated rows, missing rows, and empty results that looked like
# genuine failures.
#
# `mkdir` is the lock: it succeeds for exactly one caller and fails for
# the rest, with no flock, which macOS does not ship.
#
# --no-daemon matters as much as the separate project copies. Gradle
# runs one daemon per Gradle home, and builds queue on it no matter
# which project they come from, so parallel workers were still running
# one at a time. Its own JVM per build costs a few seconds of startup
# and buys actual concurrency.
set -u
SEED="$1"; VX="$2"; VZ="$3"; TYPE="$4"
POOL="/tmp/ravine-workers"
WORKERS=$(cat "$POOL/worker_count" 2>/dev/null || echo 6)

DIR=""
for attempt in $(seq 1 600); do
  for i in $(seq 0 $((WORKERS - 1))); do
    if mkdir "$POOL/w$i.claim" 2>/dev/null; then
      DIR="$POOL/w$i"
      CLAIM="$POOL/w$i.claim"
      break 2
    fi
  done
  sleep 1
done
[ -n "$DIR" ] || { echo "$SEED,$TYPE,NOWORKER,0,false,0,0,-1" > "$POOL/res_$SEED"; exit 0; }
trap 'rmdir "$CLAIM" 2>/dev/null' EXIT

# Unique port per worker. Without it every worker fights over
# 25565: the losers boot, log FAILED TO BIND TO PORT, shut down
# writing no csv, and the caller records ERROR. That was 388 of
# 539 rows on one ocean run, and because the filter tested only
# for a PASS value, every crashed check was silently counted as
# a failed one. check-one-spawn.sh had this from the start,
# which is why its stage ran 1000/1000 clean the same night.
printf 'level-seed=%s\nlevel-type=default\nonline-mode=false\nmax-tick-time=-1\nsync-chunk-writes=false\nserver-port=%s\n' \
  "$SEED" $((26400 + ${DIR##*w})) > "$DIR/run/server.properties"
printf '%s %s %s %s\n' "$SEED" "$VX" "$VZ" "$TYPE" > "$DIR/run/ravine.txt"
rm -rf "$DIR/run/world"
: > "$DIR/run/ravine.csv"

(cd "$DIR" && ./gradlew runServer --offline --no-daemon -q < /dev/null \
  > "$DIR/run/gradle-out.txt" 2>&1)

# One result file per seed, merged at the end - no shared file, so no
# lock needed and no interleaving possible.
if [ -s "$DIR/run/ravine.csv" ]; then
  tail -1 "$DIR/run/ravine.csv" > "$POOL/res_$SEED"
else
  # Keep the evidence. This used to discard gradle's output entirely,
  # so an ERROR row said only "no csv" and the reason had to be
  # reproduced by hand afterwards. A port collision announces itself
  # clearly in the server log and cost a whole run to rediscover.
  echo "$SEED,$TYPE,ERROR,0,false,0,0,-1" > "$POOL/res_$SEED"
  {
    echo "=== seed $SEED ($TYPE) produced no ravine.csv ==="
    echo "--- gradle ---"
    tail -15 "$DIR/run/gradle-out.txt" 2>/dev/null
    echo "--- server log ---"
    grep -iE "FAILED TO BIND|Exception|Error|Caused by" \
      "$DIR/run/logs/latest.log" 2>/dev/null | head -10
  } > "$POOL/err_$SEED" 2>&1
fi
