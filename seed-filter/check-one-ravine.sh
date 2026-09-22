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

printf 'level-seed=%s\nlevel-type=default\nonline-mode=false\nmax-tick-time=-1\nsync-chunk-writes=false\n' \
  "$SEED" > "$DIR/run/server.properties"
printf '%s %s %s %s\n' "$SEED" "$VX" "$VZ" "$TYPE" > "$DIR/run/ravine.txt"
rm -rf "$DIR/run/world"
: > "$DIR/run/ravine.csv"

(cd "$DIR" && ./gradlew runServer --offline --no-daemon -q < /dev/null > /dev/null 2>&1)

# One result file per seed, merged at the end - no shared file, so no
# lock needed and no interleaving possible.
if [ -s "$DIR/run/ravine.csv" ]; then
  tail -1 "$DIR/run/ravine.csv" > "$POOL/res_$SEED"
else
  echo "$SEED,$TYPE,ERROR,0,false,0,0,-1" > "$POOL/res_$SEED"
fi
