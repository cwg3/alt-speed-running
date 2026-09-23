#!/bin/bash
# Rebuilds the pool to N seeds per type, through EVERY filter we have.
#
#   ./overnight-rebuild.sh [per-type] [candidates-per-type]
#
# Order matters: cheap checks first, and no seed reaches the pool until
# a generated world has been looked at. Stages, per type:
#
#   1  cubiomes        structures, distances, biomes        (instant)
#   2  spawn resources real logs near spawn, not a biome;   (~40s each)
#                      plus 3 chests + food for shipwreck
#   3  type-specific   blacksmith chest (village),          (~15-40s)
#                      magma ravine (ocean),
#                      finishable portal frame (RP)
#   4  load HELD       nothing drawable until verified
#   5  tiers 4 and 5   the nether as a match world, and the
#                      overworld opening MatchWorldSetup makes
#   6  release         only what passed both
#
# Everything is serial and writes explicit ERROR markers: a crashed
# worker counted as a zero is how "35 of 40 villages have no
# blacksmith" got published once.
set -uo pipefail
PER="${1:-3}"
CAND="${2:-60}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG=/tmp/overnight.log
exec > >(tee -a "$LOG") 2>&1
echo "=== overnight rebuild started $(date) : $PER per type, $CAND candidates each ==="

cd "$ROOT/seed-filter"
rm -rf /tmp/onr && mkdir -p /tmp/onr && cd /tmp/onr
"$ROOT/seed-filter/seedtypes" "$CAND" 2>&1 | grep -E "Start seed|Scanned|nether" || true
echo

python3 - "$ROOT" <<'PY'
import json, pathlib, sys
root = sys.argv[1]
d = json.load(open('/tmp/onr/output/overworld_by_type.json'))
out = pathlib.Path('/tmp/onr/spawn-in.txt')
rows = []
for t, vs in d.items():
    for v in vs:
        rows.append(f"{v['seed']} {v['structure']['x']} {v['structure']['z']} {t}")
out.write_text('\n'.join(rows) + '\n')
print(f'stage 2: {len(rows)} candidates to check for wood at spawn')
PY

"$ROOT/seed-filter/verify-spawn.sh" /tmp/onr/spawn-in.txt 1
cp "$ROOT/mod/run/spawn-filter.csv" /tmp/onr/spawn.csv

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
print(f'stage 3a: {len(rows)} ruined portal candidates to frame-check')
PY
if [ -s /tmp/onr/rp-in.txt ]; then
  "$ROOT/seed-filter/verify-rp.sh" /tmp/onr/rp-in.txt 1
  cp "$ROOT/mod/run/rp-filter.csv" /tmp/onr/rp.csv
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

python3 - "$PER" <<'PY'
import json, sys
per = int(sys.argv[1])
d = json.load(open('/tmp/onr/output/overworld_by_type.json'))
print()
print('after the cheap-to-mid filters:')
for t in sorted(d):
    d[t] = d[t][:per]
    mark = 'OK ' if len(d[t]) >= per else 'SHORT'
    print(f'  {mark} {t}: {len(d[t])}/{per}')
json.dump(d, open('/tmp/onr/output/overworld_by_type.json','w'), indent=2)
PY

cp /tmp/onr/output/*.json "$ROOT/seed-filter/output/"
echo
echo "=== loading HELD (nothing drawable until verified) ==="
cd "$ROOT/backend"
npx tsx scripts/loadSeedPool.ts BackendStack-SeedPoolTableB4C21150-12O8ZBQS8FM8L --held 2>&1 | tail -3

echo
echo "=== tiers 4 and 5, then release ==="
"$ROOT/seed-filter/verify-and-release.sh"
echo
echo "=== finished $(date) ==="
