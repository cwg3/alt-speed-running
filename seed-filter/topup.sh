#!/bin/bash
# Tops the pool up to a floor per seed type. Builds nothing if nothing is
# short.
#
#   ./topup.sh [--dry-run]
#
#   FLOOR=50            drawable seeds each type should have
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
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FLOOR="${FLOOR:-50}"
HEADROOM="${HEADROOM:-1.6}"
TABLE="${TABLE:-BackendStack-SeedPoolTableB4C21150-12O8ZBQS8FM8L}"
REGION="${REGION:-us-west-2}"
WORKERS="${WORKERS:-16}"
DRY=0
[ "${1:-}" = "--dry-run" ] && DRY=1

echo "=== pool top-up $(date -u '+%Y-%m-%dT%H:%M:%SZ') : floor $FLOOR per type ==="

PLAN=$(aws dynamodb scan --region "$REGION" --table-name "$TABLE" \
         --projection-expression "seedType,#u,heldUnverified,poolReject" \
         --expression-attribute-names '{"#u":"used"}' \
         --output json 2>/dev/null \
  | FLOOR="$FLOOR" HEADROOM="$HEADROOM" python3 -c "
import json, os, sys, collections
floor = int(os.environ['FLOOR']); headroom = float(os.environ['HEADROOM'])
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

want = max(targets.values())
print(json.dumps({'targets': targets, 'short': short,
                  'cand': int(want * headroom) + 10 if want else 0}))
")
rc=$?
if [ $rc -ne 0 ] || [ -z "$PLAN" ]; then
	echo "!! could not read the pool - refusing to build blind" >&2
	exit 1
fi

SHORT=$(printf '%s' "$PLAN" | python3 -c "import json,sys; print(' '.join(json.load(sys.stdin)['short']))")
CAND=$(printf '%s' "$PLAN"  | python3 -c "import json,sys; print(json.load(sys.stdin)['cand'])")
TARGETS=$(printf '%s' "$PLAN" | python3 -c "import json,sys; print(json.dumps(json.load(sys.stdin)['targets']))")

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
TARGETS="$TARGETS" WORKERS="$WORKERS" \
	"$ROOT/seed-filter/overnight-rebuild.sh" "$FLOOR" "$CAND"
