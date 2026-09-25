#!/bin/bash
# Tops up the OCEAN pools - shipwreck and buried treasure - only.
#
#   ./ocean-rebuild.sh [per-type] [candidates] [workers]
#
# Exists because the ocean types are the starved end of the pool (2 and
# 1 against 18 ruined portals) and because topping them up through
# overnight-rebuild.sh would re-run every land type as well.
#
# It also closes a gap in that script. Its header lists a magma-ravine
# stage for ocean types:
#
#     3  type-specific   blacksmith chest (village),
#                        magma ravine (ocean),
#                        finishable portal frame (RP)
#
# and the body never calls it - grep finds "ravine" in that comment and
# nowhere else. verify-ravines.sh was written, works, and was simply
# never wired in, so every ocean seed in the pool today reached it
# without the check the comment claims. It runs here.
#
# Stages:
#   1  cubiomes          structures, distances, biomes
#   2  ocean only        the land types are not short and cost hours
#   3  spawn resources   real logs near spawn, and for a wreck: supply
#                        and treasure chests, food in the supply chest,
#                        nothing solid directly above either
#   4  magma ravines     two within reach of the ship, plus kelp
#   5  load HELD         nothing drawable until verified
#   6  tiers 4 and 5     the nether as a match world, and the overworld
#                        opening MatchWorldSetup actually makes
#
# No --replace anywhere: the loader adds alongside the existing pool,
# so the land seeds are untouched.
set -uo pipefail
PER="${1:-6}"
CAND="${2:-400}"
WORKERS="${3:-1}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# CLOUD=1 sends every stage to a spot instance instead of this machine.
source "$ROOT/seed-filter/run-check.sh"
WORK=/tmp/ocean
LOG=/tmp/ocean-rebuild.log
exec > >(tee -a "$LOG") 2>&1
echo "=== ocean rebuild started $(date) : $PER per type, $CAND candidates, $WORKERS workers ==="

# The stage json is a shared file and --only reads it to decide what to
# delete. Leaving an ocean-only file there would make a later
# "--only=village" see zero villages and delete every one of them.
STAMP=$(date +%Y%m%d-%H%M%S)
mkdir -p "$ROOT/seed-filter/output-backup/$STAMP"
cp "$ROOT/seed-filter/output/"*.json "$ROOT/seed-filter/output-backup/$STAMP/" 2>/dev/null \
  && echo "backed up previous output json -> output-backup/$STAMP"

rm -rf "$WORK" && mkdir -p "$WORK" && cd "$WORK"
echo "=== stage 1: cubiomes, $CAND candidates ==="
"$ROOT/seed-filter/seedtypes" "$CAND" 2>&1 | grep -E "Start seed|Scanned|nether" || true

echo
echo "=== stage 2: ocean types only ==="
python3 - <<'PY'
import json
d = json.load(open('/tmp/ocean/output/overworld_by_type.json'))
keep = {}
for t in ('shipwreck', 'buried_treasure'):
    keep[t] = d.get(t, [])
    print(f'  {t}: {len(keep[t])} candidates')
dropped = sorted(t for t in d if t not in keep)
print(f'  dropped land types: {", ".join(dropped) if dropped else "none"}')
json.dump(keep, open('/tmp/ocean/output/overworld_by_type.json', 'w'), indent=2)
rows = [f"{v['seed']} {v['structure']['x']} {v['structure']['z']} {t}"
        for t, vs in keep.items() for v in vs]
open('/tmp/ocean/spawn-in.txt', 'w').write('\n'.join(rows) + '\n')
print(f'  -> {len(rows)} to check')
PY

if [ ! -s "$WORK/spawn-in.txt" ]; then
  echo "no ocean candidates at all - raise the candidate count"; exit 1
fi

echo
echo "=== stage 3: wood at spawn, and the wreck's own chests ==="
run_check spawn "$WORK/spawn-in.txt" "$WORKERS" "$WORK/spawn.csv"
python3 - <<'PY'
import json, csv
rows = [r for r in csv.reader(open('/tmp/ocean/spawn.csv')) if r]
err = [r for r in rows[1:] if r[1] not in ('PASS', 'FAIL')]
if err:
    print(f'  WARNING: {len(err)} rows are neither PASS nor FAIL (crashed worker?)')
    for r in err[:5]:
        print('   ', ','.join(r))
ok = {r[0] for r in rows[1:] if r[1] == 'PASS'}
d = json.load(open('/tmp/ocean/output/overworld_by_type.json'))
for t in list(d):
    before = len(d[t])
    d[t] = [v for v in d[t] if str(v['seed']) in ok]
    print(f'  {t}: {before} -> {len(d[t])}')
json.dump(d, open('/tmp/ocean/output/overworld_by_type.json', 'w'), indent=2)
rows = [f"{v['seed']} {v['structure']['x']} {v['structure']['z']} {t}"
        for t, vs in d.items() for v in vs]
open('/tmp/ocean/ravine-in.txt', 'w').write('\n'.join(rows) + '\n')
PY

if [ ! -s "$WORK/ravine-in.txt" ]; then
  echo "nothing survived the spawn stage - stopping"; exit 1
fi

echo
echo "=== stage 4: two magma ravines (the stage overnight-rebuild never ran) ==="
run_check ravine "$WORK/ravine-in.txt" "$WORKERS" "$WORK/ravine.csv"
python3 - "$PER" <<'PY'
import json, csv, sys
per = int(sys.argv[1])
# columns: seed, type, twoRavinesAndKelp, count
rows = [r for r in csv.reader(open('/tmp/ocean/ravine.csv')) if len(r) > 3]
# A crashed check is not a verdict. The first run of this script tested
# only for 'true' and silently treated 388 ERROR rows - servers that
# never bound a port and never generated a world - as seeds without
# ravines. The spawn stage above had this guard; this one did not.
bad = [r for r in rows if r[2] not in ('true', 'false')]
if bad:
    kinds = {}
    for r in bad:
        kinds[r[2]] = kinds.get(r[2], 0) + 1
    print(f'  ABORT: {len(bad)} of {len(rows)} checks did not return a verdict')
    for k, n in sorted(kinds.items()):
        print(f'    {k}: {n}')
    print('  These are crashes, not failures. Fix the cause and re-run the')
    print('  ravine stage; do not let them count as seeds without ravines.')
    sys.exit(1)
ok = {r[0] for r in rows if r[2] == 'true'}
d = json.load(open('/tmp/ocean/output/overworld_by_type.json'))
print()
for t in sorted(d):
    before = len(d[t])
    d[t] = [v for v in d[t] if str(v['seed']) in ok][:per]
    mark = 'OK   ' if len(d[t]) >= per else 'SHORT'
    print(f'  {mark} {t}: {before} -> {len(d[t])}/{per} with two ravines')
json.dump(d, open('/tmp/ocean/output/overworld_by_type.json', 'w'), indent=2)
PY

cp "$WORK/output/"*.json "$ROOT/seed-filter/output/"
echo
echo "=== stage 5: load HELD (nothing drawable until verified) ==="
cd "$ROOT/backend"
npx tsx scripts/loadSeedPool.ts BackendStack-SeedPoolTableB4C21150-12O8ZBQS8FM8L --held 2>&1 | tail -3

echo
echo "=== stage 6: tiers 4 and 5, then release ==="
"$ROOT/seed-filter/verify-and-release.sh"
echo
echo "=== finished $(date) ==="
