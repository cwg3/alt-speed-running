#!/bin/bash
# Verifies one nether seed against the GAME. Called by verify-nether.sh.
#
#   check-one-nether.sh <netherSeed>
#
# cubiomes is wrong about bastion positions on roughly a third of
# seeds - measured 6 of 20, by 238 to 810 blocks, in arbitrary
# directions. Every consequence was silent: players walked to empty
# nether, and the bastion loot top-up scanned that emptiness and
# reported "no chests found" while working perfectly.
#
# So the pool stops trusting cubiomes for the nether. This asks the
# game, via the same locator /locate uses, and checks the standard's
# rules against the answer:
#
#   bastion  <= 14 chunks from nether spawn
#   fortress <= 16 chunks FROM THAT BASTION
#
# Row format: seed,bx,bz,fx,fz,bastionDist,fortressDist,PASS|FAIL
set -u
SEED="$1"
POOL="/tmp/nether-workers"
WORKERS=$(cat "$POOL/worker_count" 2>/dev/null || echo 1)

DIR=""
for attempt in $(seq 1 600); do
  for i in $(seq 0 $((WORKERS - 1))); do
    if mkdir "$POOL/w$i.claim" 2>/dev/null; then
      DIR="$POOL/w$i"; CLAIM="$POOL/w$i.claim"; break 2
    fi
  done
  sleep 1
done
[ -n "$DIR" ] || { echo "$SEED,,,,,-1,-1,ERROR" > "$POOL/res_$SEED"; exit 0; }
trap 'rmdir "$CLAIM" 2>/dev/null' EXIT

printf 'level-seed=%s\nlevel-type=default\nonline-mode=false\nmax-tick-time=-1\nsync-chunk-writes=false\n' \
  "$SEED" > "$DIR/run/server.properties"
printf '%s\n' "$SEED" > "$DIR/run/netherlocate.txt"
rm -rf "$DIR/run/world"
rm -f "$DIR/run/netherlocate.csv"

(cd "$DIR" && ./gradlew runServer --offline --no-daemon -q < /dev/null > /dev/null 2>&1)
rm -f "$DIR/run/netherlocate.txt"

if [ -s "$DIR/run/netherlocate.csv" ]; then
  tail -1 "$DIR/run/netherlocate.csv" > "$POOL/res_$SEED"
else
  echo "$SEED,,,,,-1,-1,ERROR" > "$POOL/res_$SEED"
fi
