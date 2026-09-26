#!/bin/bash
# Tops the pool up to a floor per seed type. Builds nothing if nothing is
# short.
#
#   ./topup.sh [--dry-run]
#
#   FLOOR=100           UNSEEN seeds of each type every active player
#                       should have. Not a pool depth - see below.
#   ACTIVE_DAYS=30      a player counts as active if they logged in
#                       within this many days
#   PLAYERS_TABLE=...   players table, read to measure demand
#   MAX_CANDIDATES=     cap candidates per type for ONE run, overriding
#                       every per-type ceiling in the config. For proving
#                       the pipeline completes without paying for a full
#                       build - the thing being tested is whether it
#                       finishes unattended, and that does not depend on
#                       volume.
#   HEADROOM=1.6        candidates to generate per seed wanted
#   TABLE=...           pool table
#   WORKERS=16          parallel workers for the checks
#
# WHAT THE FLOOR ACTUALLY BUYS. It is NOT a buffer against consumption.
# Seeds are not consumed: claimSeedPair stopped setting used = true, and
# exclusion is per-player seenSeeds (backend/lambda/lib/seedPool.ts). A
# type's depth is therefore a PER-PLAYER LIFETIME BUDGET - 100 shipwreck
# means each player gets 100 shipwreck matches ever, and then the
# type-first draw quietly stops offering them shipwreck at all. Five
# types at 100 is 500 lifetime matches per player, about 20 days for a
# runner doing 25 a day.
#
# SO THE FLOOR IS A BANK, and it has to keep rising. A fixed floor is a
# permanent cap on how long anyone can play. Raise it as real players
# approach it rather than treating any one value as finished.
#
# SO THE SHORTFALL IS MEASURED PER PLAYER, not from the pool. It used to
# be drawable ROW COUNT, and that number never decreases, because nothing
# is consumed: once every type reached its floor this script reported
# "nothing to build" forever while every player's personal supply drained
# toward zero, and said OK each time. Counting rows was measuring a proxy
# for supply instead of supply itself, which is the defect this repo
# keeps rediscovering.
#
# What it counts now, per type: the number of drawable seeds the WORST-OFF
# active player has not already seen. One player short is a shortage, so
# it is a min and not a mean - a new player with everything unseen must
# not mask a veteran who has run out. Adding N seeds raises every
# player's unseen count by N, because a new seed is unseen by definition,
# so the shortfall converts straight into a build target.
#
# WITH NOBODY ACTIVE it degrades to exactly the old drawable count, which
# is the right answer rather than a fallback: no active players means
# nothing is draining, and the pool depth is all there is to ask about.
# A players table that cannot be READ is a different thing entirely and
# is fatal - see below.
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
# A CAP keeps one run bounded. Reaching the floor for the most
# expensive type in a single run would mean generating and checking
# thousands of candidates; the floor does not need to be reached tonight,
# it needs to be approached every run until it is.
#
# THE CAP IS ALSO PER TYPE, and for a sharper reason than headroom. A
# ceiling below (shortfall x headroom) silently rewrites the target, and
# nothing downstream says so: the plan prints the shortfall it wanted,
# the run ends "OK", and the type is still short. Headroom being wrong
# wastes compute; the cap being wrong wastes compute AND hides that it
# achieved nothing. Set a type's ceiling above its worst realistic
# shortfall x its headroom, or accept that it converges over several
# runs rather than one.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FLOOR="${FLOOR:-100}"
# Fallback only. The real per-type numbers live in headroom.local.json,
# which is NOT in git: together they say which openings are expensive to
# produce, and that is a map of where the ladder is thin. A missing file
# degrades to this single loose value rather than failing - the same way
# split-rules.local.json does - because a top-up that refuses to run is
# worse than one that over-generates.
HEADROOM="${HEADROOM:-6}"
TABLE="${TABLE:-BackendStack-SeedPoolTableB4C21150-12O8ZBQS8FM8L}"
PLAYERS_TABLE="${PLAYERS_TABLE:-BackendStack-PlayersTable70A03D78-1LMEI6GSB9FIU}"
# A player who left for good should stop pinning the floor up forever;
# one who comes back after a break will drop the supply figure on the
# next run and be caught up over the following few. The window is the
# knob for how long "gone" takes to mean gone.
ACTIVE_DAYS="${ACTIVE_DAYS:-30}"
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

# Demand. This is FATAL if it fails, and the difference from "no active
# players" matters: zero players is a real answer meaning nothing is
# draining, while an unreadable table is no answer at all. Treating the
# second as the first would compute the shortfall from pool depth, report
# OK, and under-build for whoever is actually playing - a wrong build
# that looks exactly like a correct quiet one.
PLAYERS_JSON=$(mktemp)
trap 'rm -f "$PLAYERS_JSON"' EXIT
aws dynamodb scan --region "$REGION" --table-name "$PLAYERS_TABLE" \
	--projection-expression "#i,#s,#l,#n" \
	--expression-attribute-names \
	  '{"#i":"uuid","#s":"seenSeeds","#l":"lastLoginAt","#n":"username"}' \
	--output json > "$PLAYERS_JSON" 2>/dev/null
rc=$?
if [ $rc -ne 0 ] || [ ! -s "$PLAYERS_JSON" ]; then
	echo "!! could not read the players table - refusing to build blind" >&2
	echo "   the floor is measured in unseen seeds per player; without" >&2
	echo "   seenSeeds there is no shortfall to compute." >&2
	exit 1
fi

PLAN=$(aws dynamodb scan --region "$REGION" --table-name "$TABLE" \
         --projection-expression "seedPairId,seedType,#u,heldUnverified,poolReject" \
         --expression-attribute-names '{"#u":"used"}' \
         --output json 2>/dev/null \
  | FLOOR="$FLOOR" HEADROOM="$HEADROOM" HEADROOM_FILE="$HEADROOM_FILE" \
    PLAYERS_JSON="$PLAYERS_JSON" ACTIVE_DAYS="$ACTIVE_DAYS" \
    MAX_CANDIDATES="${MAX_CANDIDATES:-}" python3 -c "
import json, os, sys, collections, time
floor = int(os.environ['FLOOR']); flat = float(os.environ['HEADROOM'])
active_days = float(os.environ.get('ACTIVE_DAYS') or 30)
try:
    cfg = json.load(open(os.environ['HEADROOM_FILE']))
except Exception:
    cfg = {}
# _maxCandidatesPerType is either one number for every type, or an
# object of per-type ceilings with a _default. It has to be per type for
# the same reason headroom is: a ceiling sized for the cheap openings
# clamps an expensive one to a fraction of its shortfall, so the run
# reports OK, spends real money, and still leaves the type short - a
# top-up that succeeds and does nothing.
# Read a NEW key, and leave _maxCandidatesPerType a plain number in the
# file. The runner clones this script from GitHub, so config on S3 goes
# live before code does: a dict under the old key would have crashed the
# deployed version's int() the moment it was uploaded. Old code reads the
# number and caps everything at it; this reads the per-type object.
capcfg = cfg.get('_maxCandidatesByType') or cfg.get('_maxCandidatesPerType', 400)
env_cap = os.environ.get('MAX_CANDIDATES')
def cap_for(t):
    if env_cap: return int(env_cap)
    if isinstance(capcfg, dict):
        return int(capcfg.get(t, capcfg.get('_default', 400)))
    return int(capcfg)
def headroom_for(t):
    v = cfg.get(t)
    return float(v) if isinstance(v, (int, float)) else flat
TYPES = ['village','desert_temple','ruined_portal','shipwreck','buried_treasure']
items = json.load(sys.stdin)['Items']

# The ids the draw can actually reach, per type - not just how many.
# Which ones matters now, because a player has seen specific seeds.
drawable = collections.defaultdict(set)
for i in items:
    t = i['seedType']['S']
    if i.get('poolReject',{}).get('BOOL'): continue
    if i.get('heldUnverified',{}).get('BOOL'): continue
    if i.get('used',{}).get('BOOL'): continue
    pid = i.get('seedPairId',{}).get('S')
    if pid: drawable[t].add(pid)

# Mirrors SYNTHETIC_PLAYERS in backend/lambda/lib/seedPool.ts. A second
# copy of a list can drift, so it is worth saying why this one is safe
# if it does: recordSeedsSeen never writes seenSeeds for a synthetic
# player, so a bot carries an empty set, its unseen count is the whole
# pool, and it can never be the minimum below. The filter is here for
# legacy rows written before that exemption existed.
SYNTHETIC = {'bot-rival'}
cutoff = time.time() * 1000 - active_days * 86400000
active = []
for p in json.load(open(os.environ['PLAYERS_JSON']))['Items']:
    uuid = p.get('uuid', {}).get('S', '')
    if not uuid or uuid in SYNTHETIC: continue
    lv = p.get('lastLoginAt', {})
    try: last = float(lv.get('N') or lv.get('S') or 0)
    except ValueError: last = 0.0
    if last < cutoff: continue
    sv = p.get('seenSeeds', {})
    seen = set(sv.get('SS') or [])
    if not seen and 'L' in sv:
        seen = {x.get('S') for x in sv['L'] if x.get('S')}
    active.append((p.get('username', {}).get('S') or uuid[:8], seen))

if active:
    print(f'  demand from {len(active)} active player(s) in the last '
          f'{int(active_days)}d: ' + ', '.join(n for n, _ in active), file=sys.stderr)
else:
    print(f'  no player active in {int(active_days)}d - '
          'measuring pool depth only', file=sys.stderr)

targets, short = {}, []
for t in TYPES:
    pool_n = len(drawable[t])
    if active:
        who, supply = min(((n, len(drawable[t] - seen)) for n, seen in active),
                          key=lambda x: x[1])
        detail = f'{pool_n:>4} drawable, {supply:>4} unseen by {who}'
    else:
        who, supply = None, pool_n
        detail = f'{pool_n:>4} drawable'
    gap = max(0, floor - supply)
    targets[t] = gap
    print(f'  {t:<18}{detail}  floor {floor}  '
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
cands = {t: (min(cap_for(t), int(targets[t] * headroom_for(t)) + 10) if targets[t] else 0)
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
