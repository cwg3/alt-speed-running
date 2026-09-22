#!/bin/bash
# Builds a match seed pool end to end.
#
#   ./build-pool.sh [per-type] [table-name]
#
# Three kinds of check, in increasing order of cost, because cubiomes
# cannot answer everything:
#
#   1. cubiomes (seedtypes)  - structures, distances, biomes, bastion
#      type. Pure maths, microseconds per seed.
#   2. jigsaw (SmithCheckHook) - whether a village contains a
#      blacksmith. Jigsaw assembly, which cubiomes does not model, but
#      no chunks needed. ~66ms per seed.
#   3. generated world (RavineCheckHook) - whether an ocean seed has a
#      magma ravine in range. Ravines are carvers, invisible to
#      cubiomes and not derivable from structure data, so the world has
#      to be built and looked at. ~12s per seed, and the reason a pool
#      build takes half an hour rather than a minute.
#
# Both stage 2 and 3 exist because a spec requirement was quietly not
# being checked and a player hit it. Villages shipped without smiths
# until one turned up in a live match; ocean seeds would have shipped
# without a nether route the same way.
#
# Stages 2 and 3 stop as soon as enough seeds pass, so the cost scales
# with what is needed rather than with how many candidates were
# generated. At measured ocean pass rates this is still hours for a
# full pool - run it when nobody is playing, because it will use a core
# continuously and a previous run put someone's game 28 seconds
# behind.
#
# Ruined portal needs no verification stage. cubiomes cannot predict
# whether a portal generates, and about two thirds of RP seeds are
# unusable as generated - missing or underground. Rather than checking
# and discarding those, the mod checks at world creation and builds a
# portal when vanilla's is unusable, keeping vanilla's wherever it is
# good. So RP costs the same as any other type here.
set -euo pipefail

PER_TYPE="${1:-40}"
TABLE="${2:-BackendStack-SeedPoolTableB4C21150-12O8ZBQS8FM8L}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# Candidates per type.
#
# Measured pass rates: village ~56% (blacksmith), and for the ocean
# types under the two-ravine rule, somewhere around 8-17% - low enough
# that the old 3x multiplier ran out of candidates long before the pool
# filled. Twelve times over covers the worst measured rate with room
# for variance.
#
# Stage 1 is cheap even at this size (40/type scanned 26k seeds in 32
# seconds), so over-generating costs seconds. Running out costs a whole
# rebuild.
CANDIDATES=$((PER_TYPE * 12))

echo "=== stage 1: cubiomes ($CANDIDATES candidates per type) ==="
cd "$ROOT/seed-filter"
./seedtypes "$CANDIDATES" 2>&1 | grep -E "Start seed|Scanned|village|desert|ruined|shipwreck|buried|nether"

cd "$ROOT/mod"
mkdir -p run
printf 'eula=true\n' > run/eula.txt
# Clear EVERY harness input, not just the outputs. Each debug hook
# claims a boot by its own input file and yields if another is present,
# so one stale file silently skips a whole stage - which is exactly what
# happened here: a leftover ravine.txt made the blacksmith stage produce
# nothing and the run only failed two stages later.
rm -f run/smith.csv run/ravine.csv \
      run/smithbatch.txt run/ravine.txt run/loot.txt run/lava.txt \
      run/portal.txt run/rpverify.txt run/rpverify.csv run/rppredict.txt \
      run/rppredict.csv run/village.txt run/diag.txt run/results.csv

echo
echo "=== stage 2: blacksmith check (jigsaw, no chunks) ==="
printf 'level-seed=1\nlevel-type=default\nonline-mode=false\nmax-tick-time=-1\nsync-chunk-writes=false\n' > run/server.properties
python3 -c "
import json
d = json.load(open('$ROOT/seed-filter/output/overworld_by_type.json'))
for r in d['village']:
    print(r['seed'], r['structure']['x'], r['structure']['z'])
" > run/smithbatch.txt
rm -rf run/world
./gradlew runServer --offline -q < /dev/null 2>&1 | grep -E "\[smith\]" || true
rm -f run/smithbatch.txt

echo
echo "=== stage 3: magma ravine check (generated worlds, ~12s each) ==="
# One world per seed. Stops as soon as PER_TYPE have passed, so a type
# with a high pass rate costs proportionally less.
for TYPE in shipwreck buried_treasure; do
  passed=0
  checked=0
  python3 -c "
import json
d = json.load(open('$ROOT/seed-filter/output/overworld_by_type.json'))
for r in d['$TYPE']:
    print(r['seed'], r['structure']['x'], r['structure']['z'])
" > /tmp/ravine_$TYPE.txt

  while IFS=' ' read -r seed vx vz; do
    [ -z "$seed" ] && continue
    [ "$passed" -ge "$PER_TYPE" ] && break
    checked=$((checked + 1))
    printf 'level-seed=%s\nlevel-type=default\nonline-mode=false\nmax-tick-time=-1\nsync-chunk-writes=false\n' "$seed" > run/server.properties
    printf '%s %s %s %s\n' "$seed" "$vx" "$vz" "$TYPE" > run/ravine.txt
    rm -rf run/world
    ./gradlew runServer --offline -q < /dev/null > /dev/null 2>&1 || true
    if tail -1 run/ravine.csv 2>/dev/null | grep -q "^$seed,[a-z_]*,true,"; then
      passed=$((passed + 1))
    fi
    if [ $((checked % 8)) -eq 0 ]; then ./gradlew --stop > /dev/null 2>&1 || true; fi
    printf '\r  %s: %d/%d passed (%d checked)' "$TYPE" "$passed" "$PER_TYPE" "$checked"
  done < /tmp/ravine_$TYPE.txt
  echo
done
rm -f run/ravine.txt
./gradlew --stop > /dev/null 2>&1 || true

echo
echo "=== stage 4: keep only seeds that passed ==="
python3 - "$ROOT" "$PER_TYPE" <<'PY'
import csv, json, sys
root, per_type = sys.argv[1], int(sys.argv[2])
path = f'{root}/seed-filter/output/overworld_by_type.json'
data = json.load(open(path))

# Fail loudly on a missing result file rather than quietly shipping an
# unfiltered pool - an unchecked village pool is what put a smithless
# village into a live match.
try:
    smith = {r[0]: r[1] == 'true' for r in csv.reader(open(f'{root}/mod/run/smith.csv'))}
except FileNotFoundError:
    sys.exit('ERROR: no smith.csv - the blacksmith stage did not run. '
             'Refusing to load an unfiltered village pool.')

before = len(data['village'])
data['village'] = [v for v in data['village'] if smith.get(str(v['seed']), False)][:per_type]
print(f"village: {before} -> {len(data['village'])} with a blacksmith")
if len(data['village']) < per_type:
    print(f"  WARNING: only {len(data['village'])} of {per_type} - raise CANDIDATES")

# ravine.csv: seed,type,pass,ravineCount,kelp,magma,stacked,nearest
#
# Column 2 is the pass flag. It was column 3 before the hook's output
# changed, and reading the stale index silently discarded 80 correctly
# verified ocean seeds - "2" == "true" is false for every row, so a
# three-hour build loaded a pool with no ocean types and no error.
# Whenever this format changes, change it here too.
try:
    ravine = {r[0]: r[2] == 'true' for r in csv.reader(open(f'{root}/mod/run/ravine.csv'))}
except FileNotFoundError:
    sys.exit('ERROR: no ravine.csv - the magma ravine stage did not run. '
             'Refusing to load ocean seeds with no nether route.')

for t in ('shipwreck', 'buried_treasure'):
    before = len(data[t])
    data[t] = [v for v in data[t] if ravine.get(str(v['seed']), False)][:per_type]
    print(f'{t}: {before} -> {len(data[t])} with a magma ravine')
    if len(data[t]) < per_type:
        print(f'  WARNING: only {len(data[t])} of {per_type} - raise CANDIDATES')

for t in ('desert_temple', 'ruined_portal'):
    data[t] = data[t][:per_type]
    print(f'{t}: {len(data[t])}')

json.dump(data, open(path, 'w'), indent=2)
PY

echo
echo "=== stage 5: load ==="
cd "$ROOT/backend"
npx tsx scripts/loadSeedPool.ts "$TABLE" --replace 2>&1 | tail -3
