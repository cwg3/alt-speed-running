#!/bin/bash
# Rebuilds the pool to N seeds per type, through EVERY filter we have.
#
#   ./overnight-rebuild.sh [per-type] [candidates-per-type]
#
# Order matters: cheap checks first, and no seed reaches the pool until
# a generated world has been looked at. Per type:
#
#   cubiomes      structures, distances, biomes         (instant)
#   spawn         real logs near spawn, not a biome;    (~40s each)
#                 plus 3 chests + food for shipwreck
#   village       a blacksmith chest, and its contents  (~15s each)
#   ravine        two magma ravines        (ocean types only)
#   portalfilter  a frame that can be completed  (RP only)
#   load HELD     not a check: nothing drawable until verified
#   nether        the nether as a MATCH world builds it  (~40s)
#   route         the overworld opening MatchWorldSetup makes
#   release       not a check: clears the flag on what passed both
#
# Check names are the ones run-check.sh dispatches and SPEC.md's check
# table defines. There are no stage numbers on purpose: three scripts
# each had their own "stage 3" and none of them meant the same check.
#
# Everything is serial and writes explicit ERROR markers: a crashed
# worker counted as a zero is how "35 of 40 villages have no
# blacksmith" got published once.
set -uo pipefail
PER="${1:-3}"
CAND="${2:-60}"
# TARGETS is a JSON map of seedType -> how many to produce, and overrides
# the uniform PER. topup.sh sets it from each type's shortfall, because a
# uniform count rebuilds types that are not short: desert temple sat at
# twice the floor while shipwreck was the one starving, and checking
# desert temple candidates to then discard them is the most expensive way
# to do nothing. A type whose target is 0 is dropped before the spawn
# check, not after.
TARGETS="${TARGETS:-}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# CLOUD=1 sends every check to a spot instance instead of this machine.
WORKERS="${WORKERS:-16}"
source "$ROOT/seed-filter/run-check.sh"
LOG=/tmp/overnight.log
exec > >(tee -a "$LOG") 2>&1
echo "=== overnight rebuild started $(date) : $PER per type, $CAND candidates each ==="

cd "$ROOT/seed-filter"
# seedtypes is COMPILED and gitignored, so a fresh clone does not have it.
# Without this check the generator silently does nothing, the candidate
# JSON is never written, and the first thing to complain is a traceback
# three steps later that says nothing about the cause. A nightly runner
# hit exactly that on its first execution.
if [ ! -x "$ROOT/seed-filter/seedtypes" ]; then
  echo "ERROR: $ROOT/seed-filter/seedtypes is missing or not executable." >&2
  echo "  It is built from seedtypes.c and is not in git. Build it with:" >&2
  echo "    make -C tools/cubiomes release" >&2
  echo "    cc -O3 -o seed-filter/seedtypes seed-filter/seedtypes.c \\" >&2
  echo "       tools/cubiomes/libcubiomes.a -lm -lpthread" >&2
  exit 1
fi
rm -rf /tmp/onr && mkdir -p /tmp/onr && cd /tmp/onr
"$ROOT/seed-filter/seedtypes" "$CAND" 2>&1 | grep -E "Start seed|Scanned|nether" || true
if [ ! -s /tmp/onr/output/overworld_by_type.json ]; then
  echo "ERROR: seedtypes produced no candidate JSON - refusing to continue." >&2
  exit 1
fi
echo

python3 - "$ROOT" <<'PY'
import json, pathlib, sys
root = sys.argv[1]
import os
d = json.load(open('/tmp/onr/output/overworld_by_type.json'))

# Drop types we do not need before paying for any check on them, and
# trim the rest to their OWN candidate count. seedtypes generates the
# largest type's allowance for every type, so without this the cheap
# openings get spawn-checked at the expensive one's volume - which is
# most of the cost of the run spent on types that were already at their
# floor.
targets = json.loads(os.environ['TARGETS']) if os.environ.get('TARGETS') else None
cands = json.loads(os.environ['CANDS']) if os.environ.get('CANDS') else None
if targets is not None:
    skipped = [t for t in list(d) if targets.get(t, 0) <= 0]
    for t in skipped:
        del d[t]
    if skipped:
        print('not short, skipping entirely:', ', '.join(sorted(skipped)))
    if cands:
        for t in list(d):
            n = cands.get(t)
            if isinstance(n, int) and n > 0 and len(d[t]) > n:
                print(f'  {t}: {len(d[t])} generated -> {n} to check')
                d[t] = d[t][:n]
    json.dump(d, open('/tmp/onr/output/overworld_by_type.json','w'), indent=2)

out = pathlib.Path('/tmp/onr/spawn-in.txt')
rows = []
for t, vs in d.items():
    for v in vs:
        rows.append(f"{v['seed']} {v['structure']['x']} {v['structure']['z']} {t}")
out.write_text('\n'.join(rows) + '\n')
print(f'spawn: {len(rows)} candidates to check for wood at spawn')
PY

if [ ! -s /tmp/onr/spawn-in.txt ]; then
  # NOT "nothing to do". topup.sh only calls this when a type is short,
  # and the generator has already been checked, so no candidates here
  # means generation came up empty for the types that needed them. The
  # first version said "nothing to do" and exited 0 - which is what a
  # genuinely quiet night says, so a broken run read as a calm one.
  echo "ERROR: no candidates for the short types, though generation ran." >&2
  echo "  Raise the candidate count, or check seedtypes output." >&2
  exit 1
fi

run_check_or_die spawn /tmp/onr/spawn-in.txt "$WORKERS" /tmp/onr/spawn.csv

python3 - "$ROOT" <<'PY'
import json, csv, pathlib, sys
root = sys.argv[1]
ok = {r[0] for r in list(csv.reader(open('/tmp/onr/spawn.csv')))[1:] if r and r[1]=='PASS'}
d = json.load(open('/tmp/onr/output/overworld_by_type.json'))
for t in list(d):
    before = len(d[t])
    d[t] = [v for v in d[t] if str(v['seed']) in ok]
    print(f'  {t}: {before} -> {len(d[t])} with real wood at spawn')
json.dump(d, open('/tmp/onr/output/overworld_by_type.json','w'), indent=2)
PY

# Ruined portal: the frame must be finishable.
python3 - <<'PY'
import json, pathlib
d = json.load(open('/tmp/onr/output/overworld_by_type.json'))
rows = [f"{v['seed']} {v['structure']['x']} {v['structure']['z']}" for v in d.get('ruined_portal', [])]
pathlib.Path('/tmp/onr/rp-in.txt').write_text('\n'.join(rows) + '\n')
print(f'portalfilter: {len(rows)} ruined portal candidates to frame-check')
PY
if [ -s /tmp/onr/rp-in.txt ]; then
  run_check_or_die portalfilter /tmp/onr/rp-in.txt "$WORKERS" /tmp/onr/rp.csv
  python3 - <<'PY'
import json, csv
ok = {r[0] for r in list(csv.reader(open('/tmp/onr/rp.csv')))[1:] if r and r[1]=='PASS'}
d = json.load(open('/tmp/onr/output/overworld_by_type.json'))
before = len(d.get('ruined_portal', []))
d['ruined_portal'] = [v for v in d.get('ruined_portal', []) if str(v['seed']) in ok]
print(f'  ruined_portal: {before} -> {len(d["ruined_portal"])} finishable')
json.dump(d, open('/tmp/onr/output/overworld_by_type.json','w'), indent=2)
PY
fi

# Village: the blacksmith must exist AND hold iron.
#
# This check and the ravine one below were named in the header for a
# long time and never actually called. Ruined portals got their frame
# check; villages shipped on the jigsaw's word alone and ocean seeds
# shipped with no ravine check at all.
#
# A piece name is not a chest: a taiga village satisfies "has a
# weaponsmith piece" and can generate no smith chest whatever. SPEC.md
# is the rule, and the half that belongs HERE is "blacksmith
# present". The 3 iron in its chest
# is guaranteed later by LootTopUp, so it is not a seed criterion.
python3 - <<'PY'
import json, pathlib
d = json.load(open('/tmp/onr/output/overworld_by_type.json'))
rows = [f"{v['seed']} {v['structure']['x']} {v['structure']['z']}" for v in d.get('village', [])]
pathlib.Path('/tmp/onr/village-in.txt').write_text(('\n'.join(rows) + '\n') if rows else '')
print(f'village: {len(rows)} candidates to blacksmith-check')
PY
if [ -s /tmp/onr/village-in.txt ]; then
  run_check_or_die village /tmp/onr/village-in.txt "$WORKERS" /tmp/onr/village.csv
  python3 - <<'PY'
import json, csv, sys
# seed,ironIngots,hasIronPickaxe,hasIronArmor,chests,smithChests,diamonds
rows = [r for r in csv.reader(open('/tmp/onr/village.csv')) if len(r) > 5]
# A crashed worker is not a village without a smith. This is the guard
# the ravine check went without, which turned 388 servers that never
# bound a port into 388 seeds "with no ravine".
bad = [r for r in rows if not r[1].lstrip('-').isdigit()]
if bad:
    print(f'  ABORT: {len(bad)} of {len(rows)} village checks returned no verdict')
    print('  These are crashes, not failures. Fix the cause and re-run.')
    sys.exit(1)
# smithChests only. The 3-iron half of the SPEC line is a GUARANTEE
# the mod provides, not a property to filter on: LootTopUp.VILLAGE is
# (3, INGOTS), and it tops up a smithless village too. Filtering on it
# as well rejected 12 of 17 perfectly good villages in a real sample -
# a large cut in yield for a condition that is true by the time anyone
# plays the seed.
ok = {r[0] for r in rows if int(r[5]) >= 1}
d = json.load(open('/tmp/onr/output/overworld_by_type.json'))
before = len(d.get('village', []))
d['village'] = [v for v in d.get('village', []) if str(v['seed']) in ok]
print(f'  village: {before} -> {len(d["village"])} with a real smith chest')
json.dump(d, open('/tmp/onr/output/overworld_by_type.json','w'), indent=2)
PY
fi

# Ocean: two findable magma ravines.
#
# A ravine is the first thing a runner looks for on an ocean seed -
# before bubbles, before kelp - because it is the nether portal. Both
# ocean types need one and neither was ever checked here.
python3 - <<'PY'
import json, pathlib
d = json.load(open('/tmp/onr/output/overworld_by_type.json'))
rows = []
for t in ('shipwreck', 'buried_treasure'):
    for v in d.get(t, []):
        rows.append(f"{v['seed']} {v['structure']['x']} {v['structure']['z']} {t}")
pathlib.Path('/tmp/onr/ravine-in.txt').write_text(('\n'.join(rows) + '\n') if rows else '')
print(f'ravine: {len(rows)} ocean candidates to ravine-check')
PY
if [ -s /tmp/onr/ravine-in.txt ]; then
  run_check_or_die ravine /tmp/onr/ravine-in.txt "$WORKERS" /tmp/onr/ravine.csv
  python3 - <<'PY'
import json, csv, sys
rows = [r for r in csv.reader(open('/tmp/onr/ravine.csv')) if len(r) > 3]
bad = [r for r in rows if r[2] not in ('true', 'false')]
if bad:
    kinds = {}
    for r in bad:
        kinds[r[2]] = kinds.get(r[2], 0) + 1
    print(f'  ABORT: {len(bad)} of {len(rows)} ravine checks did not return a verdict')
    for k, n in sorted(kinds.items()):
        print(f'    {k}: {n}')
    print('  These are crashes, not failures. Do not let them count as')
    print('  seeds without ravines - that mistake cost a whole batch once.')
    sys.exit(1)
ok = {r[0] for r in rows if r[2] == 'true'}
d = json.load(open('/tmp/onr/output/overworld_by_type.json'))
for t in ('shipwreck', 'buried_treasure'):
    before = len(d.get(t, []))
    d[t] = [v for v in d.get(t, []) if str(v['seed']) in ok]
    print(f'  {t}: {before} -> {len(d[t])} with two magma ravines')
json.dump(d, open('/tmp/onr/output/overworld_by_type.json','w'), indent=2)
PY
fi

python3 - "$PER" <<'PY'
import json, os, sys
per = int(sys.argv[1])
targets = json.loads(os.environ['TARGETS']) if os.environ.get('TARGETS') else None
d = json.load(open('/tmp/onr/output/overworld_by_type.json'))
print()
print('after the cheap-to-mid filters:')
for t in sorted(d):
    want = targets.get(t, 0) if targets is not None else per
    d[t] = d[t][:want]
    mark = 'OK ' if len(d[t]) >= want else 'SHORT'
    print(f'  {mark} {t}: {len(d[t])}/{want}')
json.dump(d, open('/tmp/onr/output/overworld_by_type.json','w'), indent=2)
PY

# seed-filter/output/ is gitignored, so a fresh clone does not have it -
# and `cp a.json b.json <missing-dir>/` fails outright while `cp one.json
# <missing-dir>/` quietly creates a FILE called output. Either way the
# loader then cannot find overworld_by_type.json. A nightly runner spent
# forty minutes on the checks and loaded nothing because of this.
mkdir -p "$ROOT/seed-filter/output"
cp /tmp/onr/output/*.json "$ROOT/seed-filter/output/" || {
  echo "ERROR: could not copy the candidate JSON to seed-filter/output" >&2
  exit 1
}
if [ ! -s "$ROOT/seed-filter/output/overworld_by_type.json" ]; then
  echo "ERROR: overworld_by_type.json is missing after the copy." >&2
  exit 1
fi

echo
echo "=== loading HELD (nothing drawable until verified) ==="
cd "$ROOT/backend"
# The loader's exit code was piped into tail, which returns tail's - so
# a loader that could not read its input reported success and the run
# carried on to release nothing and call that fine.
set -o pipefail
npx tsx scripts/loadSeedPool.ts BackendStack-SeedPoolTableB4C21150-12O8ZBQS8FM8L --held 2>&1 | tail -3
rc=${PIPESTATUS[0]}
set +o pipefail
if [ "$rc" -ne 0 ]; then
  echo "ERROR: the loader failed (rc=$rc) - nothing was written to the pool." >&2
  exit 1
fi

echo
echo "=== nether + route, then release ==="
"$ROOT/seed-filter/verify-and-release.sh"
echo
echo "=== finished $(date) ==="
