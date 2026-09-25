#!/bin/bash
# Verifies HELD pool rows as match worlds, then releases or quarantines.
#
#   ./verify-and-release.sh [table]
#
# The pool build's own checks verify a SEED. These two verify a PAIR:
#
#   nether  verify-pairs.sh   the nether the match world generates -
#                             is the bastion where we ship it, and do
#                             the distance rules hold?
#   route   verify-routes.sh  the overworld opening MatchWorldSetup
#                             actually produces - portal, lava, chests,
#                             ravine.
#
# Names are the ones run-check.sh dispatches and SPEC.md's check table
# defines. They used to be "tier 4" and "tier 5", which collided with
# three different scripts' stage numbers.
#
# Neither can run until the rows exist, because both need the PAIRING.
# So the loader writes rows held (used=true, heldUnverified=true) and
# this releases only what passes. A pool that is briefly drawable and
# unchecked is how a player ended up in open ocean with no portal and
# no lava pool, three log warnings deep, with no way to tell our bug
# from their bad luck.
set -uo pipefail
TABLE="${1:-BackendStack-SeedPoolTableB4C21150-12O8ZBQS8FM8L}"

# CLOUD=1 runs both checks on a spot instance instead of this
# machine. Both have always been supported by the image - CHECK=nether
# and CHECK=route - and only this script kept them local, which meant
# a pool rebuild pinned somebody's laptop for hours while every other
# check ran on AWS.
#
# Two things have to be bridged, and neither is optional:
#
#   INPUT  the local harnesses build their own per-seed input files.
#          The cloud runner passes a whole line through, so the lines
#          have to arrive in the shape the mod's hook expects.
#   OUTPUT the cloud writes the seed's own CSV row. The local harness
#          prefixes it with the pair id and type, and everything
#          downstream keys off the pair id, so it has to be re-joined.
CLOUD="${CLOUD:-0}"
WORKERS="${WORKERS:-16}"
# The instance type has to be passed, not left to run-on-spot's
# default. That default is c7g.4xlarge, 32GiB, which cannot hold 16
# workers at 2G each - the memory guard refuses the launch and the
# check produces no rows. m7g.4xlarge is the same 16 vCPUs with 64GiB,
# which is what every check tonight actually ran on.
ITYPE="${ITYPE:-m7g.4xlarge}"
REGION="${REGION:-us-west-2}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export ROOT

# ONLY=nether or ONLY=route re-runs one check and leaves the other's
# existing CSV alone. The release step still reads BOTH, so a stale or
# missing file is refused rather than treated as a pass - which is what
# makes this safe to use after a half-finished run.
ONLY="${ONLY:-}"

# A spot launch is REFUSED, not queued, when the account is at its vCPU
# quota - and the refusal is just a line on stderr. On 2026-09-25 route
# launched the moment nether finished, while a ravine job still held
# the other slot, and 188 verified pairs produced no route rows at all.
# Nothing was lost, because unanswered rows stay HELD, but the whole
# run was wasted waiting for a slot that was ~15 minutes away.
#
# shutting-down counts. An instance that has finished its work still
# holds its vCPUs for a minute or two, and a check that ignores that
# state launches straight into the same refusal.
SPOT_VCPU_QUOTA="${SPOT_VCPU_QUOTA:-32}"
WAIT_FOR_SLOT_SECS="${WAIT_FOR_SLOT_SECS:-3600}"

spot_vcpus_in_use() {
  aws ec2 describe-instances --region "$REGION" \
    --filters "Name=instance-lifecycle,Values=spot" \
              "Name=instance-state-name,Values=pending,running,shutting-down" \
    --query 'Reservations[].Instances[].CpuOptions.[CoreCount,ThreadsPerCore]' \
    --output text 2>/dev/null | awk '{s += $1 * $2} END {print s+0}'
}

wait_for_spot_slot() {
  local need used waited=0
  need=$(aws ec2 describe-instance-types --region "$REGION" \
           --instance-types "$ITYPE" \
           --query 'InstanceTypes[0].VCpuInfo.DefaultVCpus' \
           --output text 2>/dev/null)
  case "$need" in ''|*[!0-9]*) need=16 ;; esac
  while :; do
    used=$(spot_vcpus_in_use)
    if [ $((used + need)) -le "$SPOT_VCPU_QUOTA" ]; then
      [ "$waited" -gt 0 ] && echo "  slot free after ${waited}s"
      return 0
    fi
    if [ "$waited" -ge "$WAIT_FOR_SLOT_SECS" ]; then
      echo "  !! no spot slot after ${waited}s ($used/$SPOT_VCPU_QUOTA vCPUs in use)" >&2
      return 1
    fi
    [ "$waited" -eq 0 ] && \
      echo "  waiting for a spot slot: $used/$SPOT_VCPU_QUOTA vCPUs in use, need $need"
    sleep 30
    waited=$((waited + 30))
  done
}

# Only a file this launch produced may be joined. `ls -t <glob> | head -1`
# picks the newest MATCHING file whether or not the launch wrote it, so a
# refused launch silently re-joins an older run's verdicts and releases
# seeds nobody checked. Empty output is the honest answer; the release
# step then reports them inconclusive and leaves them HELD.
STAMP=/tmp/verify-launch-stamp
newest_since_launch() {
  find "$ROOT/seed-filter/results" -name "$1" -newer "$STAMP" 2>/dev/null \
    | sort | tail -1
}

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
echo "=== nether: the nether as a match world ($COUNT pairs) ==="
if [ -n "$ONLY" ] && [ "$ONLY" != nether ]; then
  echo "  skipped (ONLY=$ONLY) - keeping the existing mod/run/pairs-all.csv"
elif [ "$CLOUD" = 1 ]; then
  # held-pairs:  ow ns bx bz pairId type
  # netherlocate.txt wants: OW BX BZ NS
  awk '{print $1, $3, $4, $2}' /tmp/held-pairs.txt > /tmp/cloud-nether.txt
  wait_for_spot_slot || true
  : > "$STAMP"
  "$ROOT/cloud/run-on-spot.sh" nether /tmp/cloud-nether.txt "$WORKERS" "$ITYPE" || true
  CLOUD_CSV=$(newest_since_launch 'nether-*.csv')
  [ -z "$CLOUD_CSV" ] && echo "  !! nether produced no results this run" >&2
  python3 - "$CLOUD_CSV" <<'JOIN'
import csv, pathlib, sys
# Re-attach pairId and type, which the cloud row does not carry.
byseed = {}
for line in pathlib.Path('/tmp/held-pairs.txt').read_text().split('\n'):
    f = line.split()
    if len(f) >= 6:
        byseed[f[0]] = (f[4], f[5])
out = ['pairId,type,seed,bx,bz,fx,fz,bastionDist,fortressDist,verdict,'
       'shippedX,shippedZ,shipError,containers,secondBastionDist,bastionCount']
src = sys.argv[1] if len(sys.argv) > 1 and sys.argv[1] else None
if src:
    for r in csv.reader(open(src)):
        if not r or not r[0].lstrip('-').isdigit():
            continue
        pid, t = byseed.get(r[0], ('', ''))
        out.append(','.join([pid, t] + r))
pathlib.Path(__import__('os').environ.get('ROOT', '.') + '/mod/run/pairs-all.csv'
             ).write_text('\n'.join(out) + '\n')
print(f'  {len(out)-1} rows -> mod/run/pairs-all.csv')
JOIN
else
  "$ROOT/seed-filter/verify-pairs.sh" /tmp/held-pairs.txt 1
fi

echo
echo "=== route: the overworld opening ($COUNT pairs) ==="
if [ -n "$ONLY" ] && [ "$ONLY" != route ]; then
  echo "  skipped (ONLY=$ONLY) - keeping the existing mod/run/routes-all.csv"
elif [ "$CLOUD" = 1 ]; then
  # held-routes is already the shape routecheck.txt wants, minus the
  # trailing pair id the hook ignores.
  awk '{print $1, $2, $3, $4, $5, $6, $7}' /tmp/held-routes.txt > /tmp/cloud-route.txt
  wait_for_spot_slot || true
  : > "$STAMP"
  "$ROOT/cloud/run-on-spot.sh" route /tmp/cloud-route.txt "$WORKERS" "$ITYPE" || true
  CLOUD_CSV=$(newest_since_launch 'route-*.csv')
  [ -z "$CLOUD_CSV" ] && echo "  !! route produced no results this run" >&2
  python3 - "$CLOUD_CSV" <<'JOIN'
import csv, pathlib, sys, os
byseed = {}
for line in pathlib.Path('/tmp/held-routes.txt').read_text().split('\n'):
    f = line.split()
    if len(f) >= 8:
        byseed[f[1]] = (f[7], f[0])
out = ['pairId,type,seed,lava,chests,verdict,detail,extra']
src = sys.argv[1] if len(sys.argv) > 1 and sys.argv[1] else None
if src:
    for r in csv.reader(open(src)):
        if not r or not r[0].lstrip('-').isdigit():
            continue
        pid, t = byseed.get(r[0], ('', ''))
        out.append(','.join([pid, t] + r))
pathlib.Path(os.environ.get('ROOT', '.') + '/mod/run/routes-all.csv'
             ).write_text('\n'.join(out) + '\n')
print(f'  {len(out)-1} rows -> mod/run/routes-all.csv')
JOIN
else
  "$ROOT/seed-filter/verify-routes.sh" /tmp/held-routes.txt 1
fi

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
        sys.exit(f'ERROR: {path} missing - a verification check did not run. '
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
# Inconclusive rows stay HELD. Re-running the check after fixing
# whatever broke is free; re-deriving a seed that was thrown away is
# the expensive half of this pipeline.
UNANSWERED = ('ERROR', 'MISSING')
for pid in set(nether) | set(routes):
    nv, nd = nether.get(pid, ('MISSING', 'nether produced no row'))
    rv, rd = routes.get(pid, ('MISSING', 'route produced no row'))
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
