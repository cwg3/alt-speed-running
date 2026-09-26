#!/bin/bash
# Tops the pool up to a floor per seed type. Builds nothing if nothing is
# short.
#
#   ./topup.sh [--dry-run]
#
#   FLOOR=50            drawable seeds each type should have
#   MAX_CANDIDATES=     cap candidates per type for ONE run, overriding
#                       the config. For proving the pipeline completes
#                       without paying for a full build - the thing being
#                       tested is whether it finishes unattended, and
#                       that does not depend on volume.
#   HEADROOM=1.6        candidates to generate per seed wanted
#   TABLE=...           pool table
#   WORKERS=16          parallel workers for the checks
#
# WHY A FLOOR AND NOT A DAILY QUOTA. The requirement is a replenishment
# RATE, not a pool depth: a strong runner burns about 25 matches a day,
# and a seed is consumed per PLAYER, so demand is linear in player count.
# But a fixed nightly quota pays for seeds whether or not anyone played,
# and right now almost nobody has. A floor spends only when the pool has
# actually been drawn down, and scales itself as play picks up without
# anyone re-tuning a number.
#
# WHY PER TYPE. A match asks for a SPECIFIC opening. A pool of 200 that
# is all desert temple cannot deal a shipwreck, so the total is not the
# thing that matters - the thinnest type is. Buried treasure is the one
# to watch: its yield is the lowest of the five, so it is the first to
# run dry and the slowest to refill.
#
# HEADROOM exists because most candidates die in the checks. Generating
# exactly the shortfall would leave every type short after the first
# pass, and a top-up that never reaches its floor would run every night
# forever.
#
# IT IS PER TYPE, because the types are not close. One multiplier either
# starves the expensive openings or wastes hours checking candidates the
# cheap ones never needed - the first run of this script gave shipwreck
# the same allowance as desert temple and shipwreck finished with
# nothing.
#
# A CAP keeps one night bounded. Reaching the floor for the most
# expensive type in a single run would mean generating and checking
# thousands of candidates; the floor does not need to be reached tonight,
# it needs to be approached every night until it is.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FLOOR="${FLOOR:-50}"
# Fallback only. The real per-type numbers live in headroom.local.json,
# which is NOT in git: together they say which openings are expensive to
# produce, and that is a map of where the ladder is thin. A missing file
# degrades to this single loose value rather than failing - the same way
# split-rules.local.json does - because a top-up that refuses to run is
# worse than one that over-generates.
HEADROOM="${HEADROOM:-6}"
TABLE="${TABLE:-BackendStack-SeedPoolTableB4C21150-12O8ZBQS8FM8L}"
REGION="${REGION:-us-west-2}"
WORKERS="${WORKERS:-16}"
DRY=0
[ "${1:-}" = "--dry-run" ] && DRY=1

echo "=== pool top-up $(date -u '+%Y-%m-%dT%H:%M:%SZ') : floor $FLOOR per type ==="

# Local file first, then the S3 mirror - the cloud runner clones from
# git and so has neither until it fetches one.
HEADROOM_FILE="$ROOT/seed-filter/headroom.local.json"
if [ ! -f "$HEADROOM_FILE" ]; then
	ACCT=$(aws sts get-caller-identity --query Account --output text 2>/dev/null)
	aws s3 cp "s3://alt-seedwork-${ACCT}/secrets/headroom.local.json" \
		"$HEADROOM_FILE" --quiet 2>/dev/null \
		&& echo "  per-type headroom fetched from S3" \
		|| echo "  no headroom file - falling back to a flat ${HEADROOM}x for every type"
fi

PLAN=$(aws dynamodb scan --region "$REGION" --table-name "$TABLE" \
         --projection-expression "seedType,#u,heldUnverified,poolReject" \
         --expression-attribute-names '{"#u":"used"}' \
         --output json 2>/dev/null \
  | FLOOR="$FLOOR" HEADROOM="$HEADROOM" HEADROOM_FILE="$HEADROOM_FILE" \
    MAX_CANDIDATES="${MAX_CANDIDATES:-}" python3 -c "
import json, os, sys, collections
floor = int(os.environ['FLOOR']); flat = float(os.environ['HEADROOM'])
try:
    cfg = json.load(open(os.environ['HEADROOM_FILE']))
except Exception:
    cfg = {}
cap = int(os.environ.get('MAX_CANDIDATES') or cfg.get('_maxCandidatesPerType', 400))
def headroom_for(t):
    v = cfg.get(t)
    return float(v) if isinstance(v, (int, float)) else flat
TYPES = ['village','desert_temple','ruined_portal','shipwreck','buried_treasure']
items = json.load(sys.stdin)['Items']
draw = collections.Counter()
for i in items:
    t = i['seedType']['S']
    if i.get('poolReject',{}).get('BOOL'): continue
    if i.get('heldUnverified',{}).get('BOOL'): continue
    if i.get('used',{}).get('BOOL'): continue
    draw[t] += 1

targets, short = {}, []
for t in TYPES:
    gap = max(0, floor - draw[t])
    targets[t] = gap
    print(f'  {t:<18}{draw[t]:>4} drawable  floor {floor}  '
          + (f'SHORT by {gap}' if gap else 'ok'), file=sys.stderr)
    if gap: short.append(t)

# A held row is already on its way through verification. Counting it as
# missing would queue a second build for seeds that are about to land.
held = sum(1 for i in items if i.get('heldUnverified',{}).get('BOOL'))
if held:
    print(f'  NOTE {held} rows are held mid-verification', file=sys.stderr)

# One candidate count per type, each capped so a single night stays
# bounded. The generator makes the largest of them and each type is
# trimmed to its own before anything is checked.
cands = {t: (min(cap, int(targets[t] * headroom_for(t)) + 10) if targets[t] else 0)
         for t in TYPES}
for t in TYPES:
    if targets[t]:
        print(f'  {t:<18}want {targets[t]:>3}  ->  generate {cands[t]}', file=sys.stderr)
print(json.dumps({'targets': targets, 'short': short, 'cands': cands,
                  'cand': max(cands.values()) if short else 0}))
")
rc=$?
if [ $rc -ne 0 ] || [ -z "$PLAN" ]; then
	echo "!! could not read the pool - refusing to build blind" >&2
	exit 1
fi

SHORT=$(printf '%s' "$PLAN" | python3 -c "import json,sys; print(' '.join(json.load(sys.stdin)['short']))")
CAND=$(printf '%s' "$PLAN"  | python3 -c "import json,sys; print(json.load(sys.stdin)['cand'])")
TARGETS=$(printf '%s' "$PLAN" | python3 -c "import json,sys; print(json.dumps(json.load(sys.stdin)['targets']))")
CANDS=$(printf '%s' "$PLAN" | python3 -c "import json,sys; print(json.dumps(json.load(sys.stdin)['cands']))")

echo
if [ -z "$SHORT" ]; then
	echo "every type is at or above the floor - nothing to build"
	exit 0
fi
echo "short: $SHORT"
echo "targets: $TARGETS"
echo "candidates per type: $CAND"

if [ "$DRY" = 1 ]; then
	echo
	echo "(dry run - would run overnight-rebuild.sh and stop here)"
	exit 0
fi

echo
# PER is ignored when TARGETS is set; passed so the usage stays honest.
TARGETS="$TARGETS" CANDS="$CANDS" WORKERS="$WORKERS" \
	"$ROOT/seed-filter/overnight-rebuild.sh" "$FLOOR" "$CAND"
rc=$?
echo
if [ "$rc" -ne 0 ]; then
	echo "!! top-up FAILED (rc=$rc) - the pool was not changed" >&2
	exit "$rc"
fi
echo "top-up complete"
