#!/bin/bash
# Verifies HELD pool rows as match worlds, then releases or quarantines.
#
#   ./verify-and-release.sh [table]
#
# The pool build's own stages verify a SEED. These two verify a PAIR:
#
#   tier 4  verify-pairs.sh   the nether the match world generates -
#                             is the bastion where we ship it, and do
#                             the distance rules hold?
#   tier 5  verify-routes.sh  the overworld opening MatchWorldSetup
#                             actually produces - portal, lava, chests,
#                             ravine.
#
# Neither can run until the rows exist, because both need the PAIRING.
# So the loader writes rows held (used=true, heldUnverified=true) and
# this releases only what passes. A pool that is briefly drawable and
# unchecked is how a player ended up in open ocean with no portal and
# no lava pool, three log warnings deep, with no way to tell our bug
# from their bad luck.
set -uo pipefail
TABLE="${1:-BackendStack-SeedPoolTableB4C21150-12O8ZBQS8FM8L}"
REGION="${REGION:-us-west-2}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "=== collecting held rows ==="
aws dynamodb scan --region "$REGION" --table-name "$TABLE" --output json \
  | python3 -c "
import json,sys,pathlib
def num(it,k):
    v=it.get(k)
    if not v or 'NULL' in v: return 0
    return int(v['N']) if 'N' in v else 0
pairs=[]; routes=[]
for it in json.load(sys.stdin)['Items']:
    if not it.get('heldUnverified',{}).get('BOOL'): continue
    if it.get('poolReject',{}).get('BOOL'): continue
    pid=it['seedPairId']['S']; t=it['seedType']['S']
    ow=num(it,'overworldSeed'); ns=num(it,'netherSeed')
    sx=num(it,'structureX'); sz=num(it,'structureZ')
    bx=num(it,'bastionX'); bz=num(it,'bastionZ')
    mx=num(it,'smithX'); mz=num(it,'smithZ')
    pairs.append('%d %d %d %d %s %s' % (ow, ns, bx, bz, pid, t))
    routes.append('%s %d %d %d %d %d %d %s' % (t, ow, ns, sx, sz, mx, mz, pid))
pathlib.Path('/tmp/held-pairs.txt').write_text('\n'.join(pairs)+'\n' if pairs else '')
pathlib.Path('/tmp/held-routes.txt').write_text('\n'.join(routes)+'\n' if routes else '')
print(len(pairs),'held rows')
"

# grep -c exits 1 when the count is zero, so a `|| echo 0` fallback
# fires IN ADDITION to grep's own "0" and COUNT becomes two lines -
# which made the empty case die on "[: 0\n0: integer expression
# expected" instead of saying "nothing held". `|| true` keeps the count
# grep already printed.
COUNT=$(grep -cve '^[[:space:]]*$' /tmp/held-pairs.txt 2>/dev/null || true)
COUNT=${COUNT:-0}
if [ "$COUNT" -eq 0 ]; then
  echo "nothing held - nothing to do"
  exit 0
fi

echo
echo "=== tier 4: nether as a match world ($COUNT pairs) ==="
"$ROOT/seed-filter/verify-pairs.sh" /tmp/held-pairs.txt 1

echo
echo "=== tier 5: overworld opening ($COUNT pairs) ==="
"$ROOT/seed-filter/verify-routes.sh" /tmp/held-routes.txt 1

echo
echo "=== releasing what passed ==="
python3 - "$ROOT" "$TABLE" "$REGION" <<'PY'
import csv, subprocess, sys, json
root, table, region = sys.argv[1], sys.argv[2], sys.argv[3]

def verdicts(path, pid_col, verdict_col, detail_cols):
    out = {}
    try:
        rows = list(csv.reader(open(path)))
    except FileNotFoundError:
        sys.exit(f'ERROR: {path} missing - a verification stage did not run. '
                 'Refusing to release unverified seeds.')
    for r in rows[1:]:
        if not r or len(r) <= verdict_col:
            continue
        detail = ' '.join(r[c] for c in detail_cols if c < len(r) and r[c])
        out[r[pid_col]] = (r[verdict_col], detail)
    return out

# pairs-all.csv:  pairId,type,seed,bx,bz,fx,fz,bastionDist,fortressDist,verdict,...
# routes-all.csv: pairId,type,seed,lava,chests,verdict,detail,extra
nether = verdicts(f'{root}/mod/run/pairs-all.csv', 0, 9, [7, 8, 12, 13])
routes = verdicts(f'{root}/mod/run/routes-all.csv', 0, 5, [6, 7])

released = rejected = 0
failed = []
inconclusive = []
# ERROR and MISSING are the harness failing to answer, not the seed
# failing. check-one-pair.sh goes out of its way to emit an explicit
# ERROR rather than a plausible-looking zero, precisely so a crashed
# worker is never mistaken for a result - and then this stage read
# "not PASS" as "bad seed" and quarantined on it anyway.
#
# That cost 23 verified shipwrecks. Every worker had died the same way,
# on a mixin that could not load, and all 23 were written off with
# "nether ERROR: -1 -1 -1". They were fine. The jar was not.
#
# Inconclusive rows stay HELD. Re-running the tier after fixing
# whatever broke is free; re-deriving a seed that was thrown away is
# the expensive half of this pipeline.
UNANSWERED = ('ERROR', 'MISSING')
for pid in set(nether) | set(routes):
    nv, nd = nether.get(pid, ('MISSING', 'tier 4 produced no row'))
    rv, rd = routes.get(pid, ('MISSING', 'tier 5 produced no row'))
    if nv in UNANSWERED or rv in UNANSWERED:
        inconclusive.append(pid)
        continue
    ok = nv == 'PASS' and rv == 'PASS'
    if ok:
        expr = 'SET #u = :f REMOVE heldUnverified'
        vals = {':f': {'BOOL': False}}
        names = {'#u': 'used'}
        released += 1
    else:
        reason = []
        if nv != 'PASS':
            reason.append(f'nether {nv}: {nd}'.strip())
        if rv != 'PASS':
            reason.append(f'route {rv}: {rd}'.strip())
        # used stays TRUE: a quarantined row must not become drawable.
        # No #u here, so no names either - DynamoDB refuses a call that
        # declares an attribute name the expression never uses, and
        # that failure would look exactly like a successful release.
        expr = 'SET poolReject = :t, poolRejectReason = :r REMOVE heldUnverified'
        vals = {':t': {'BOOL': True}, ':r': {'S': ' | '.join(reason)[:900]}}
        names = None
        rejected += 1
    cmd = [
        'aws', 'dynamodb', 'update-item', '--region', region, '--table-name', table,
        '--key', json.dumps({'seedPairId': {'S': pid}}),
        '--update-expression', expr,
        '--expression-attribute-values', json.dumps(vals),
    ]
    if names:
        cmd += ['--expression-attribute-names', json.dumps(names)]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        # Never let a failed write be counted as a release.
        print(f'  FAILED {pid}: {r.stderr.strip()[:200]}')
        failed.append(pid)

print(f'released {released}, quarantined {rejected}, inconclusive {len(inconclusive)}')
if inconclusive:
    print(f'  {len(inconclusive)} rows stayed HELD - the harness did not answer for them.')
    print('  Fix the harness and re-run; nothing was thrown away.')
if failed:
    print(f'WARNING: {len(failed)} writes FAILED - those rows are still held: {failed}')
PY
