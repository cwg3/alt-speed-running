#!/bin/bash
# Proves every mixin in the mod actually APPLIES, by launching the game.
#
# This exists because `gradlew build` cannot tell you. Mixin target
# signatures are resolved when the target class LOADS, so a mixin
# pointing at a method that does not exist compiles clean, produces a
# jar, and passes every check up to and including a GitHub release
# upload - then takes the game down at Blocks.<clinit>, before there is
# a title screen to put an error on.
#
# That happened. ReplayPickupMixin named sendPickup on PlayerEntity,
# where it is not declared: LivingEntity declares it, ServerPlayerEntity
# overrides it, and mixin resolves a target against the named class
# alone. The jar built, installed, and uploaded to a release. Nothing
# caught it until a seed-filter batch came back 23 rows of ERROR.
#
# A dedicated server is the cheapest thing that loads the whole mixin
# set. Roughly a minute, and it is the only check whose pass means the
# mod actually runs.
#
# No hook, no marker file, no cooperation from the mod: start it, wait
# for it to say Done, kill it. A check that needs the thing it is
# testing to behave is not much of a check.
set -u
cd "$(dirname "$0")"
LOG=$(mktemp -t mixinsmoke)

printf 'level-seed=1\nlevel-type=default\nonline-mode=false\nserver-port=25799\nmax-tick-time=-1\nsync-chunk-writes=false\n' > run/server.properties
printf 'eula=true\n' > run/eula.txt
rm -rf run/world

./gradlew runServer --offline --no-daemon -q < /dev/null > "$LOG" 2>&1 &
GRADLE=$!

STATUS=timeout
for _ in $(seq 1 180); do
  if grep -q "Mixin apply for mod speedrunmcalt failed" "$LOG" 2>/dev/null; then
    STATUS=mixin; break
  fi
  # "Done (Ns)" is the server announcing startup finished, which is
  # past every class the mod touches.
  if grep -q 'Done (' "$LOG" 2>/dev/null; then
    STATUS=ok; break
  fi
  kill -0 $GRADLE 2>/dev/null || { STATUS=died; break; }
  sleep 1
done

# The whole process group: gradle forks the JVM, and killing only the
# wrapper leaves a server holding 25799 for the next run to trip over.
pkill -P $GRADLE 2>/dev/null
kill -9 $GRADLE 2>/dev/null
wait $GRADLE 2>/dev/null
rm -f run/server.properties

case "$STATUS" in
  ok)
    echo "PASS - mixins applied, server reached Done"
    rm -f "$LOG"; exit 0;;
  mixin)
    echo "FAIL - the jar is not loadable. Mixin targets that do not exist:"
    grep -o "Mixin apply for mod speedrunmcalt failed [^[]*" "$LOG" | sort -u
    echo "full log: $LOG"; exit 1;;
  *)
    echo "FAIL ($STATUS) - server never finished starting"
    tail -30 "$LOG"; echo "full log: $LOG"; exit 1;;
esac
