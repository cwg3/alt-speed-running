#!/bin/bash
# Verifies ONE POOL PAIR against the game, as a match world.
#
#   check-one-pair.sh "<overworldSeed> <netherSeed> <bastionX> <bastionZ> <seedPairId> <seedType>"
#
# The difference from check-one-nether.sh, and the reason this exists:
# that script generates a world whose seed IS the nether seed, which
# proves a SEED is good and proves nothing about what a MATCH ships. A
# match world is generated from the overworld seed with the nether
# structures redirected to the nether seed by NetherStructureSeedMixin.
# For most of this project those two were different worlds and nobody
# noticed - players walked to a shipped bastion coordinate and found
# empty nether, three separate misdiagnoses deep.
#
# So this builds the world the way a match does: world seed = overworld
# seed, MatchState.netherSeed set, mixin live. Then it asks the game
# where the bastion is, and how far that is from the coordinate we would
# have SHIPPED to the player.
#
# Row: seed,bx,bz,fx,fz,bastionDist,fortressDist,PASS|FAIL,
#      shippedX,shippedZ,shipError,containersAtShipped,
#      secondBastionDist,bastionCount
set -u
# CASTX/CASTZ are optional: the OVERWORLD point the player casts their
# portal from. For land types that is the objective, within tens of
# blocks of spawn, so omitting them (link = origin) is right. For ocean
# types it is the MAGMA RAVINE, which can be hundreds of blocks further
# out - and measuring those from the origin understates the nether walk.
read -r OW NS BX BZ PAIR TYPE CASTX CASTZ <<< "$1"
CASTX="${CASTX:-}"
CASTZ="${CASTZ:-}"
POOL="/tmp/pair-workers"
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
[ -n "$DIR" ] || { echo "$OW,,,,,-1,-1,ERROR,,,,-1,-1,-1" > "$POOL/res_$PAIR"; exit 0; }
trap 'rmdir "$CLAIM" 2>/dev/null' EXIT

# Not 25565. A probe that fights the player's own game for the port is
# how the parallel village harness lost 28 of 40 workers and published a
# confident wrong answer built out of their zeroes.
PORT=$((25700 + ${DIR##*w}))

printf 'level-seed=%s\nlevel-type=default\nonline-mode=false\nmax-tick-time=-1\nsync-chunk-writes=false\nserver-port=%s\n' \
  "$OW" "$PORT" > "$DIR/run/server.properties"
# Four arguments: the fourth is what makes this a MATCH world rather
# than another single-seed probe.
if [ -n "$CASTX" ] && [ -n "$CASTZ" ]; then
  printf '%s %s %s %s %s %s\n' "$OW" "$BX" "$BZ" "$NS" "$CASTX" "$CASTZ" > "$DIR/run/netherlocate.txt"
else
  printf '%s %s %s %s\n' "$OW" "$BX" "$BZ" "$NS" > "$DIR/run/netherlocate.txt"
fi
rm -rf "$DIR/run/world"
rm -f "$DIR/run/netherlocate.csv"

(cd "$DIR" && ./gradlew runServer --offline --no-daemon -q < /dev/null > "$POOL/log_$PAIR.txt" 2>&1)
rm -f "$DIR/run/netherlocate.txt"

if [ -s "$DIR/run/netherlocate.csv" ]; then
  # Prefix the pair id and type so the result is traceable back to the
  # pool row, not just to a seed.
  printf '%s,%s,%s\n' "$PAIR" "$TYPE" "$(tail -1 "$DIR/run/netherlocate.csv")" > "$POOL/res_$PAIR"
else
  # An explicit ERROR marker, never a plausible-looking zero. A crashed
  # worker counted as a genuine result is exactly how "35 of 40 villages
  # have no blacksmith" got published.
  printf '%s,%s,%s,,,,,-1,-1,ERROR,,,,-1,-1,-1\n' "$PAIR" "$TYPE" "$OW" > "$POOL/res_$PAIR"
fi
