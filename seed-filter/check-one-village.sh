#!/bin/bash
# Verifies one village seed against a GENERATED world. Called by
# verify-villages.sh via xargs -P.
#
#   check-one-village.sh <seed> <x> <z>
#
# Exists because the cheap jigsaw check is not sufficient. That check
# asks whether the predicted structure contains a piece whose NAME
# contains "armorer", "weaponsmith" or "toolsmith" - and a taiga
# village satisfied it while generating no smith chest at all. Every
# container in that village was village_taiga_house or an untagged
# workstation barrel. The seed reached a live match and the iron
# guarantee landed in an ordinary house chest a hundred blocks from
# where the player was sent.
#
# A piece name is a proxy. This generates the village and looks at the
# actual loot tables, which is the only thing that answers the question
# the spec asks: is there a blacksmith with resources in it.
#
# Row format, matching LootVerifyHook:
#   seed,ironIngots,hasIronPickaxe,hasIronArmor,chests,smithChests,diamonds
# A failed run writes the same width with a trailing ERROR, so a
# failure can never be mistaken for a genuine zero - that mistake once
# turned 28 crashed workers into a reported "35 of 40 seeds have no
# blacksmith".
#
# Worker claiming, the mkdir lock and --no-daemon all work the way
# check-one-ravine.sh explains - see that file for why each is needed.
set -u
SEED="$1"; VX="$2"; VZ="$3"
POOL="/tmp/village-workers"
WORKERS=$(cat "$POOL/worker_count" 2>/dev/null || echo 4)

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
[ -n "$DIR" ] || { echo "$SEED,0,false,false,0,0,0,NOWORKER" > "$POOL/res_$SEED"; exit 0; }
trap 'rmdir "$CLAIM" 2>/dev/null' EXIT

# Unique port per worker. Without it every worker fights over
# 25565: the losers boot, log FAILED TO BIND TO PORT, shut down
# writing no csv, and the caller records ERROR. That was 388 of
# 539 rows on one ocean run, and because the filter tested only
# for a PASS value, every crashed check was silently counted as
# a failed one. check-one-spawn.sh had this from the start,
# which is why its stage ran 1000/1000 clean the same night.
printf 'level-seed=%s\nlevel-type=default\nonline-mode=false\nmax-tick-time=-1\nsync-chunk-writes=false\nserver-port=%s\n' \
  "$SEED" $((26600 + ${DIR##*w})) > "$DIR/run/server.properties"
printf '%s %s %s\n' "$SEED" "$VX" "$VZ" > "$DIR/run/village.txt"
rm -rf "$DIR/run/world"
# LootVerifyHook APPENDS, so an old file would make a stale row look
# like this seed's result.
rm -f "$DIR/run/results.csv"

(cd "$DIR" && ./gradlew runServer --offline --no-daemon -q < /dev/null > /dev/null 2>&1)

rm -f "$DIR/run/village.txt"

if [ -s "$DIR/run/results.csv" ]; then
  tail -1 "$DIR/run/results.csv" > "$POOL/res_$SEED"
else
  echo "$SEED,0,false,false,0,0,0,ERROR" > "$POOL/res_$SEED"
fi
