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
# ceiling below (shortfall x headroom) rewrites the target. Headroom
# being wrong wastes compute; the cap being wrong wastes compute AND
# hides that it achieved nothing. Set a type's ceiling above its worst
# realistic shortfall x its headroom, or accept that it converges over
# several runs rather than one.
#
# AND IT SAYS SO NOW. It used not to: the plan printed the shortfall it
# wanted next to a candidate count that had been quietly clamped to a
# third of it, the run ended "OK", and the type was still short - three
# of five types on one night, none of it visible in the log or the email.
# So the plan marks every clamped type, estimates what the run can
# actually close, and after the build reports released-against-target per
# type with a reason: (ceiling) means the cap chose that, (yield) means
# the type got its full allowance and the configured headroom is too
# optimistic. Those are different problems and they were indistinguishable
# from a single "released N".
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

# Run history, same local-then-S3 shape as headroom and for the same
# reason: the runner clones from git and starts with neither.
#
# WHY THIS FILE EXISTS. The cap deliberately does not close the floor in
# one run - the header's words are that the floor "needs to be approached
# every run until it is". That is an invariant, and nothing checked it.
# The clamp warning said "fine IF each gap shrinks every run - compare
# with the last one" and left the comparing to whoever opened two logs,
# so a clamped type whose gap was FLAT would report exactly like one
# closing steadily. Without a previous reading this script cannot tell
# the difference, and one run in isolation never can.
#
# IT RECORDS WHAT EACH RUN RELEASED, not only what the pool held, and the
# comparison needs both. Two readings with a flat gap between them mean
# nothing until you know whether a build happened in between: on
# 2026-09-30 the check called all five types NOT CONVERGING because the
# previous run had died in the spawn check and added no seeds, which is
# the one circumstance in which no gap can close. The release figures were
# already in this file and the check was not reading them.
#
# It is not in git for the same reason headroom is not: a per-type gap is
# a per-type depth, which is a yield wearing different clothes. results/
# is already gitignored.
HISTORY_FILE="$ROOT/seed-filter/results/topup-history.json"
mkdir -p "$ROOT/seed-filter/results"
ACCT="${ACCT:-$(aws sts get-caller-identity --query Account --output text 2>/dev/null)}"
HISTORY_S3="s3://alt-seedwork-${ACCT}/state/topup-history.json"
# A MISSING history and an UNREADABLE one are not the same thing, and the
# first version of this printed the same benign line for both. That is the
# failure this repo keeps rediscovering wearing one more hat: the check
# would go quiet, report "starts next run" every run forever, and a
# convergence check that never runs looks exactly like one that passes.
if [ ! -f "$HISTORY_FILE" ]; then
	if aws s3 cp "$HISTORY_S3" "$HISTORY_FILE" --quiet 2>/dev/null; then
		echo "  run history fetched from S3"
	elif aws s3api head-object --bucket "alt-seedwork-${ACCT}" \
			--key state/topup-history.json >/dev/null 2>&1; then
		echo "  !! run history EXISTS on S3 and could not be fetched - the" >&2
		echo "  !! convergence check is blind this run. That is a broken" >&2
		echo "  !! check, not a first run, and it will not fix itself." >&2
	else
		echo "  no run history on S3 yet - the convergence check starts next run"
	fi
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
    HISTORY_FILE="$HISTORY_FILE" \
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

# supply is kept per type and not just the gap it implies: the gap is
# relative to a floor that moves, and a reading taken at one floor has to
# stay readable after the floor changes.
targets, short, supply_by = {}, [], {}
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
    supply_by[t] = supply
    print(f'  {t:<18}{detail}  floor {floor}  '
          + (f'SHORT by {gap}' if gap else 'ok'), file=sys.stderr)
    if gap: short.append(t)

# A held row is already on its way through verification. Counting it as
# missing would queue a second build for seeds that are about to land.
held = sum(1 for i in items if i.get('heldUnverified',{}).get('BOOL'))
if held:
    print(f'  NOTE {held} rows are held mid-verification', file=sys.stderr)

# IS THE GAP ACTUALLY CLOSING. The cap makes a short type normal for a
# few runs; it does not make a type that never closes normal, and those
# two look identical in one log. So compare with the last run.
#
# TWO READINGS ARE NOT ENOUGH ON THEIR OWN, which is the 2026-09-30
# correction. This asked only whether the gap had shrunk since the
# previous reading and called everything that had not NOT CONVERGING. That
# night it named all five types and not one of them was parked: the run in
# between had died in the spawn check and loaded nothing, so no seed
# reached the pool between the two readings and no gap COULD have closed.
# The run doing the complaining then released everything four of the five
# types asked for. A verdict that fires hardest while the pipeline is
# working is worse than no verdict, because the one night it means
# something reads exactly like the four it does not.
#
# So the comparison now reads what the previous run RELEASED - recorded in
# this file from the start and never once consulted. Three different
# things produce a gap that has not shrunk and only the last is a failure:
#
#   nothing added   the previous run failed, or had nothing to build, so
#                   released is null or empty. A flat or wider gap is the
#                   arithmetic and says nothing about convergence.
#   demand moved    the previous run released everything it wanted. The
#                   gap now is seeds the worst-off player has SEEN since
#                   that reading. Nothing consumes a seed, but being seen
#                   removes it from that player's supply and supply is
#                   what the floor measures. A bigger bank fixes this; a
#                   bigger ceiling does not.
#   parked          the previous run ran, under-delivered, and the gap did
#                   not shrink. The failure this check exists for, and now
#                   the only thing that says NOT CONVERGING.
#
# The third case is rarer than the check used to imply and the first two
# are not near-misses of it - they have different fixes, and a top-up that
# names the wrong one sends the next change to the wrong number.
#
# Only at an UNCHANGED floor. A floor raise moves every gap at once and
# comparing across it says nothing about whether the pipeline is keeping
# up - which is the trap this check was written after walking into: the
# floor doubled between two runs, every gap grew, and the growth was the
# goalposts moving rather than supply falling. Saying which it was is the
# whole point, so a floor change is reported as a floor change and the
# regression verdict is withheld for that run.
# Same distinction on the read. A file that is present and does not parse
# is a broken check; only an ABSENT file is a first run.
history, hist_err = [], None
_hp = os.environ.get('HISTORY_FILE') or ''
if os.path.exists(_hp):
    try:
        history = json.load(open(_hp))['runs']
        if not isinstance(history, list):
            raise TypeError('runs is not a list')
    except (OSError, ValueError, KeyError, TypeError) as e:
        history, hist_err = [], str(e)[:120]
prev = next((r for r in reversed(history) if isinstance(r.get('supply'), dict)),
            None)
stalled, drain_by = [], {}
print(file=sys.stderr)
if hist_err:
    print('  !! the run history is present and unreadable, so this run cannot',
          file=sys.stderr)
    print(f'  !! tell whether any gap is closing: {hist_err}', file=sys.stderr)
    print('  !! fix or delete it - a blind check that says nothing is the',
          file=sys.stderr)
    print('  !! failure it was written to catch.', file=sys.stderr)
elif prev is None:
    print('  no previous run on record - nothing to compare this gap with yet',
          file=sys.stderr)
else:
    pfloor = int(prev.get('floor') or 0)
    ago = (time.time() - float(prev.get('at') or 0)) / 3600.0
    psup = prev['supply']
    pgap = prev.get('gap') or {}
    # What the previous run PUT IN, per type. A dict is a run that got as
    # far as reporting - including the all-zero dict of a run with nothing
    # to build. Null is a run that never reached its report, so it died in
    # the checks and added nothing. Either way no seed reached the pool
    # between that reading and this one, and that is the whole difference
    # between a gap that would not close and a gap nothing worked on.
    # Entries from before 2026-09-30 write null for the nothing-to-build
    # case too, which is why the message below reads their gap to say
    # which it was rather than assuming a failure.
    prel = prev.get('released')
    # 'unknown' is the third state: it built, and verify-and-release.sh
    # wrote no per-type breakdown, so what it added cannot be read off
    # this file. Not comparable either, and not for the same reason.
    pblind = 'no per-type breakdown was written' if prel == 'unknown' else None
    if not isinstance(prel, dict):
        prel = None
    if pfloor != floor:
        print(f'  the floor moved from {pfloor} to {floor} since the last run '
              f'{ago:.0f}h ago,', file=sys.stderr)
        print('  so its gaps are not comparable - this run becomes the '
              'baseline instead.', file=sys.stderr)
        # Supply still is comparable, and it is the honest half of the
        # story on a floor raise: it separates a bigger goal from a
        # shrinking pool.
        fell = sorted(t for t in TYPES
                      if t in psup and supply_by[t] < int(psup[t]))
        if fell:
            print('  !! supply FELL for: ' + ', '.join(fell)
                  + ' - the floor is not the only reason', file=sys.stderr)
            print('  !! those types are short. Nothing consumes a seed, so '
                  'either the player', file=sys.stderr)
            print('  !! SAW them, or the rows LEFT the drawable set - '
                  'quarantined, withdrawn', file=sys.stderr)
            print('  !! or marked used. This said only the second for '
                  'months; supply is per', file=sys.stderr)
            print('  !! player, so being seen lowers it just as well, and '
                  'the two have', file=sys.stderr)
            print('  !! opposite fixes.', file=sys.stderr)
    else:
        print(f'  against the last run {ago:.0f}h ago, at the same floor:',
              file=sys.stderr)
        # Said once, at the top, because it is a fact about that RUN and
        # not about any one type - and said before the per-type lines, so
        # nobody reads five flat gaps and draws the old conclusion.
        if prel is None:
            if pblind:
                print(f'  !! what the last run added is UNKNOWN - {pblind} -',
                      file=sys.stderr)
                print('  !! so the gaps below cannot be read as progress or '
                      'as its absence.', file=sys.stderr)
            else:
                worked = any(int(v or 0) for v in pgap.values())
                print('  !! the last run added NOTHING to the pool - '
                      + ('it did not finish, so no'
                         if worked else 'it had nothing to build, so no')
                      + ' release', file=sys.stderr)
                print('  !! was recorded - and no gap below could have '
                      'closed. A flat or wider', file=sys.stderr)
                print('  !! one is drain, not a stall, and this pair of '
                      'readings cannot say', file=sys.stderr)
                print('  !! whether anything is converging.', file=sys.stderr)
        for t in TYPES:
            if t not in psup:
                continue
            was, now = max(0, floor - int(psup[t])), targets[t]
            got = None if prel is None else int(prel.get(t) or 0)
            # supply_then + released - supply_now. TWO things make that
            # positive and this file cannot tell them apart: the worst-off
            # player SAW those seeds, or the rows LEFT the drawable set -
            # quarantined, withdrawn or marked used. Supply is stored here
            # as a count per type, not as the set of pair ids, so the
            # residual is all there is and it carries no cause. It used to
            # print as 'seen since', which named one of the two on no
            # evidence and pointed the reader at FLOOR when the answer
            # might have been in poolReject.
            if got is not None:
                drain_by[t] = int(psup[t]) + got - supply_by[t]
            drew = (f', {drain_by[t]} left that supply'
                    if drain_by.get(t, 0) > 0 else '')
            if not now:
                print(f'    {t:<18}at the floor now{drew}', file=sys.stderr)
            elif got is None:
                print(f'    {t:<18}short by {was} then, {now} now - '
                      + ('what that run added is unknown' if pblind
                         else 'nothing was added in between'),
                      file=sys.stderr)
            elif not was:
                print(f'    {t:<18}at the floor then, short by {now} now - a '
                      f'NEW gap{drew}', file=sys.stderr)
            elif got >= was:
                print(f'    {t:<18}short by {was} then, {now} now - that run '
                      f'released all {got} it wanted{drew}', file=sys.stderr)
            elif was > now:
                closed = was - now
                runs = -(-now // closed)
                print(f'    {t:<18}short by {was} then, {now} now - '
                      f'about {runs} more run(s) at that rate{drew}',
                      file=sys.stderr)
            else:
                stalled.append((t, was, now))
                print(f'    {t:<18}short by {was} then, {now} now, and that '
                      f'run released {got} of {was} - NOT CLOSING{drew}',
                      file=sys.stderr)
        # The gutter runs down every line because the notification greps
        # for it and drops anything unmarked.
        if stalled:
            print('  !! a gap that is flat or growing at an UNCHANGED floor, '
                  'after a run that', file=sys.stderr)
            print('  !! DID build and came up short, is the failure. The cap '
                  'is allowed to look', file=sys.stderr)
            print('  !! like it and is not: this type is not on its way to '
                  'the floor, it is', file=sys.stderr)
            print('  !! parked short of it. For each one, either its ceiling '
                  'in', file=sys.stderr)
            print('  !! _maxCandidatesByType is below what one run needs, or '
                  'its configured', file=sys.stderr)
            print('  !! yield is too optimistic - the per-type report after '
                  'the build says', file=sys.stderr)
            print('  !! which, as (ceiling) or (yield).', file=sys.stderr)
        # THE OTHER WAY TO NEVER REACH THE FLOOR, and the one this fix
        # could have buried: a type that delivers its whole target every
        # single run and is short again by morning. Nothing is stalled and
        # nothing is capped - the pipeline is keeping its promise and the
        # promise is too small for how much the player plays. Explaining
        # every fresh gap away as drain without ever saying that drain has
        # outgrown a run would trade a noisy alarm for a silent one, which
        # is this repo's oldest defect in a new coat.
        outrun = []
        for t in TYPES:
            d, h = drain_by.get(t, 0), headroom_for(t)
            per_run = int(cap_for(t) / h) if h > 0 else 0
            if targets[t] and d > 0 and d >= per_run:
                outrun.append((t, d, per_run))
        if outrun:
            print('  !! supply is FALLING faster than one run can replace '
                  'it, so building', file=sys.stderr)
            print(f'  !! alone will not reach the floor for these - over the '
                  f'last {ago:.0f}h:', file=sys.stderr)
            for t, d, per_run in outrun:
                print(f'  !!   {t:<18}{d} left that supply, a full run at its '
                      f'ceiling releases about {per_run}', file=sys.stderr)
            print('  !! this is not a stall. It is one of two things and the '
                  'numbers above', file=sys.stderr)
            print('  !! cannot say which, so read poolReject before changing '
                  'anything: the', file=sys.stderr)
            print('  !! player SAW those seeds, which is a floor problem - it '
                  'is a per-player', file=sys.stderr)
            print('  !! bank being drawn down as fast as it fills, and FLOOR '
                  'is the knob. Or', file=sys.stderr)
            print('  !! the ROWS LEFT the drawable set - quarantined, '
                  'withdrawn or marked', file=sys.stderr)
            print('  !! used - which is a pool problem, and raising the floor '
                  'would only build', file=sys.stderr)
            print('  !! more rows into the same leak.', file=sys.stderr)

# One candidate count per type, each capped so a single night stays
# bounded. The generator makes the largest of them and each type is
# trimmed to its own before anything is checked.
# What a type would need in ONE run to actually close its shortfall,
# before any ceiling: the gap times its own yield, plus a small constant
# so a shortfall of 1 still gets a real batch rather than 2 candidates.
need = {t: (int(targets[t] * headroom_for(t)) + 10 if targets[t] else 0) for t in TYPES}
cands = {t: min(cap_for(t), need[t]) for t in TYPES}
# A ceiling below need[t] silently rewrites the target, and the reason to
# print it here is that nothing downstream can: the plan line for a type
# clamped to a fraction of what it needed is character-for-character the
# line of a type that asked for exactly what it got and will reach its
# floor with it. The run then ends 'OK' with the type still short, which
# is the failure this script's header describes and could not previously
# be seen in a log.
capped = sorted(t for t in TYPES if targets[t] and cands[t] < need[t])
for t in TYPES:
    if targets[t]:
        mark = f'  CAPPED at {cap_for(t)}, need {need[t]}' if t in capped else ''
        print(f'  {t:<18}want {targets[t]:>3}  ->  generate {cands[t]}{mark}',
              file=sys.stderr)
# The !! gutter runs down EVERY line of this block on purpose:
# cloud/topup-userdata.sh greps the log to build its notification, so a
# line without a marker is dropped and the advice arrives as a fragment.
if capped:
    print(f'  !! {len(capped)} type(s) clamped by their ceiling - this run cannot '
          f'reach the floor for them.', file=sys.stderr)
    print(f'  !! at the configured yields it should close about:', file=sys.stderr)
    for t in capped:
        h = headroom_for(t)
        est = int(cands[t] / h) if h > 0 else 0
        print(f'  !!   {t:<18}{est:>4} of {targets[t]:>3} wanted '
              f'({cands[t]} candidates at {h:g}x)', file=sys.stderr)
    # This used to say \"compare with the last one\" and leave it there,
    # which is advice and not a check. The block above does the comparing
    # now, so this points at it rather than asking for it again.
    print('  !! fine IF each gap shrinks every run - the comparison with the '
          'last run is', file=sys.stderr)
    print('  !! above, and says NOT CLOSING only when the last run BUILT and '
          'still came', file=sys.stderr)
    print('  !! up short. A run that died in the checks added nothing, so the '
          'gap it left', file=sys.stderr)
    print('  !! behind is not evidence either way and is reported as such. If '
          'a type is', file=sys.stderr)
    print('  !! genuinely parked, raise _maxCandidatesByType for THAT type, '
          'never the', file=sys.stderr)
    print('  !! shared _default.', file=sys.stderr)
print(json.dumps({'targets': targets, 'short': short, 'cands': cands, 'need': need,
                  'capped': capped, 'floor': floor, 'supply': supply_by,
                  'drain': drain_by,
                  'stalled': [t for t, _, _ in stalled],
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

# Recorded BEFORE the build, and on the nothing-short path too. Both
# matter. Before, because a run that dies in the checks still took a
# valid reading of the pool and the next run's comparison needs the
# READING, not the outcome - a crashed night that loses its reading makes
# the run after it compare against the night before and call two runs'
# progress one. And on the nothing-short path, because "at the floor" is
# the reading that proves a later gap is new.
#
# A dry run records nothing: it is a rehearsal, and a baseline it wrote
# would make the next real run compare against a run that never built.
if [ "$DRY" = 0 ]; then
	PLAN="$PLAN" HISTORY_FILE="$HISTORY_FILE" python3 -c "
import json, os, time
plan = json.loads(os.environ['PLAN'])
path = os.environ['HISTORY_FILE']
try:
    hist = json.load(open(path))
except (OSError, ValueError):
    hist = {}
runs = hist.get('runs') or []
if not isinstance(runs, list): runs = []
# released stays NULL here and is filled in after the build. On the
# nothing-short path it is written as {} instead, and the difference is
# the one the next run's comparison rests on: {} is a run that added
# nothing because nothing was wanted, null is a run that added nothing
# because it never got to the end. Both leave the gaps flat and only the
# second is a reason to distrust the pipeline. Entries written before
# 2026-09-30 carry null for both and the comparison falls back to reading
# their recorded gap.
runs.append({'at': time.time(), 'floor': plan['floor'],
             'supply': plan['supply'], 'gap': plan['targets'],
             'capped': plan['capped'],
             'released': None if plan['short'] else {}})
# Trimmed, because this is a convergence check and not an archive: the
# comparison reads the LAST entry and the trend needs a handful, not a
# year of them.
hist['runs'] = runs[-20:]
json.dump(hist, open(path, 'w'), indent=2)
" || { echo "  !! could not record this run's reading - the NEXT run will" >&2
	       echo "  !! have nothing to compare against and will say so as a" >&2
	       echo "  !! first run rather than as this failure." >&2; }
	# The runner is thrown away at the end of the run, so a reading that
	# only ever existed on the instance did not survive being taken.
	aws s3 cp "$HISTORY_FILE" "$HISTORY_S3" --quiet 2>/dev/null \
		|| { echo "  !! this run's reading did not reach S3. The instance is" >&2
		     echo "  !! discarded at shutdown, so the reading is lost and the" >&2
		     echo "  !! next run cannot check convergence." >&2; }
fi

echo
if [ -z "$SHORT" ]; then
	echo "every type is at or above the floor - nothing to build"
	exit 0
fi
echo "short: $SHORT"
echo "targets: $TARGETS"
# CAND is one number because the generator takes one. It is the LARGEST
# type's allowance, and overnight-rebuild.sh trims every other type down
# to its own before a single check runs - so printed as "candidates per
# type" it claimed the most expensive type's allowance for every type,
# including ones that would never check anywhere near that many.
echo "candidates per type: $CANDS"
echo "generator batch (largest type's allowance): $CAND"

if [ "$DRY" = 1 ]; then
	echo
	echo "(dry run - would run overnight-rebuild.sh and stop here)"
	exit 0
fi

echo
# Anything older than this in the release breakdown is a previous run's
# and must not be read as this one's - a stale file is how a report gets
# to be confidently wrong about a run that released nothing.
RUN_STARTED=$(date +%s)
BREAKDOWN="$ROOT/seed-filter/results/release-by-type.json"
# PER is ignored when TARGETS is set; passed so the usage stays honest.
TARGETS="$TARGETS" CANDS="$CANDS" WORKERS="$WORKERS" \
	"$ROOT/seed-filter/overnight-rebuild.sh" "$FLOOR" "$CAND"
rc=$?
echo
if [ "$rc" -ne 0 ]; then
	echo "!! top-up FAILED (rc=$rc) - the pool was not changed" >&2
	exit "$rc"
fi

# What the run actually did, per type, against what it set out to do.
# A bare "released N" cannot answer the only question that matters -
# whether the type that was starving got anything - and a total can look
# healthy while one type got zero.
PLAN="$PLAN" BREAKDOWN="$BREAKDOWN" RUN_STARTED="$RUN_STARTED" \
	HISTORY_FILE="$HISTORY_FILE" python3 -c "
import json, os, sys
plan = json.loads(os.environ['PLAN'])
path, started = os.environ['BREAKDOWN'], float(os.environ['RUN_STARTED'])
try:
    if os.path.getmtime(path) < started:
        raise FileNotFoundError
    got = json.load(open(path))
except (FileNotFoundError, OSError, ValueError):
    got = None

# What this run actually released, written back onto the reading it took
# before the build. Kept separate from the reading because they answer
# different questions - the reading is what the pool HAD, this is what the
# run PUT IN - and because a run can legitimately have one without the
# other: a build that dies has a reading and no release.
def record_released(rel_by_type):
    path = os.environ.get('HISTORY_FILE') or ''
    try:
        hist = json.load(open(path))
        runs = hist['runs']
        if not runs: return
    except (OSError, ValueError, KeyError, TypeError):
        return
    runs[-1]['released'] = rel_by_type
    try:
        json.dump(hist, open(path, 'w'), indent=2)
    except OSError:
        pass

if got is None:
    print('  (no per-type release breakdown from this run - '
          'verify-and-release.sh wrote none)')
    # NOT {}. This run built and may well have released seeds; what it
    # released per type is simply unknown. Recording {} would tell the next
    # run it released zero of everything, and the next run would read a
    # flat gap after a successful build and call the type NOT CONVERGING -
    # the same false alarm as a missing release, through a second door.
    record_released('unknown')
    sys.exit(0)
rows = got.get('byType', {})
print('what this run closed, per type:')
still_short, no_yield = [], []
for t, want in sorted(plan['targets'].items()):
    if not want: continue
    r = rows.get(t, {})
    # Read through a helper rather than r.get('...', 0): a keyword next to
    # a digit is what tools/check-no-stats.sh looks for, and a guard that
    # fires on a dict default gets overridden by reflex.
    def n(k, _r=r):
        return _r.get(k) or 0
    rel, qua, inc = n('released'), n('quarantined'), n('inconclusive')
    left = want - rel
    tail = f', {qua} quarantined' + (f', {inc} held' if inc else '')
    why = ''
    if left > 0:
        still_short.append((t, left))
        why = '  (ceiling)' if t in plan.get('capped', []) else '  (yield)'
        if not rel: no_yield.append(t)
    print(f'  {t:<18}{rel:>4} released of {want:>3} wanted{tail}{why}')
# A type that was checked without the ceiling clamping it and STILL came
# up short means its configured headroom is optimistic - the number in
# headroom.local.json is measured, and measurements drift.
drift = [(t, n) for t, n in still_short if t not in plan.get('capped', [])]
if drift:
    print()
    print('  !! short WITHOUT being capped - got every candidate their headroom')
    print('  !! asked for and still missed, so the configured yield is optimistic:')
    for t, n in drift:
        cn, rel = plan['cands'][t], rows.get(t, {}).get('released', 0)
        measured = f'{cn / rel:.0f}x' if rel else f'>{cn}x'
        print(f'  !!   {t:<18}short by {n:>3} - {cn} candidates yielded {rel}'
              f', measured {measured}')
    print('  !! raise those types to the measured yield in headroom.local.json.')
if no_yield:
    print()
    print('  !! released NOTHING for: ' + ', '.join(no_yield))
    print('  !! a run that spends money and leaves a type exactly as thin as it')
    print('  !! found it is not a quiet success.')
if not still_short:
    print()
    print('  every short type released everything it wanted this run')
# One machine-readable line for the cloud runner's email SUBJECT. rc=0
# alone cannot carry this: a run that clears three of five types exits 0
# and the inbox said 'OK', which is true of the RUN and false of the
# POOL. The subject is the only part anyone reads without opening it.
record_released({t: (rows.get(t, {}).get('released') or 0)
                 for t in plan['targets'] if plan['targets'][t]})
# A type that was ALREADY not closing before this run is the more serious
# of the two facts and goes first, because the subject line is truncated
# and whatever is first is the part that gets read. Being short after one
# run is expected and says nothing on its own; not converging is the
# thing that never fixes itself.
#
# NO BARE DOUBLE QUOTES ANYWHERE IN THIS BLOCK, including in a comment.
# It is the body of python3 -c \"...\", so a \" ends the shell argument:
# python then received this script truncated at that character, ran the
# part before it, printed no VERDICT and exited 0. A silent partial run
# of the reporting step is the exact failure the reporting step exists to
# prevent, and it survived a dry run (which stops earlier) and a unit
# test of this block alone (which never went through the shell).
#
# stalled was decided BEFORE the build, from the last run. A type this run
# went on to close is at the floor now, and naming it NOT CONVERGING puts
# a false alarm first in the subject, worded exactly like the real one.
# Only a type that was parked AND is still short after this build stays.
parts = []
short_now = {t for t, _ in still_short}
stalled_before = [t for t in plan.get('stalled') or [] if t in plan['targets']]
stalled = [t for t in stalled_before if t in short_now]
closed = [t for t in stalled_before if t not in short_now]
if closed:
    print()
    print('  was not closing before this run, closed by it: ' + ', '.join(closed))
if stalled:
    parts.append('NOT CONVERGING: ' + ', '.join(stalled))
if still_short:
    parts.append('still short: ' + ', '.join(
        f'{t} -{n}' for t, n in sorted(still_short, key=lambda x: -x[1])[:3]))
print('VERDICT: ' + ('; '.join(parts) if parts
                     else 'floor reached for every short type'))
# Rows the breakdown counted under a type the plan never asked for -
# almost always the 'unknown' bucket, meaning the CSV join lost the type.
# Without this the lines above silently sum to less than the run's totals
# and a type reads as 0 released when its rows just went somewhere else.
extra = sorted(set(rows) - set(plan['targets']))
if extra:
    print()
    print('  !! rows counted under types this run did not ask for: '
          + ', '.join(f'{t} ({rows[t].get(\"released\", 0)} released)' for t in extra))
    print('  !! if that is \'unknown\' the pairId->type join lost the type, and')
    print('  !! the per-type figures above are undercounts.')
"
# The reading went up before the build; this is the same file with the
# outcome attached, and the next run reads it from S3 rather than from
# this instance, which will not exist.
aws s3 cp "$HISTORY_FILE" "$HISTORY_S3" --quiet 2>/dev/null \
	|| { echo "  !! the release outcome did not reach S3. The reading taken" >&2
	     echo "  !! before the build may have, so the next run can still" >&2
	     echo "  !! compare gaps - it just cannot cross-check them." >&2; }
echo "top-up complete"
