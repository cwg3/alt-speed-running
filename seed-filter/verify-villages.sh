#!/bin/bash
# Verifies village seeds really have a blacksmith, in parallel.
#
#   ./verify-villages.sh <seeds-file> [workers]
#
# seeds-file: "<seed> <villageX> <villageZ>" per line
# output:     mod/run/village-all.csv, one row per seed
#             seed,ironIngots,hasIronPickaxe,hasIronArmor,chests,smithChests,diamonds,smithX,smithZ
#             mod/run/village-qualified.txt, the seeds that pass
#             mod/run/village-smith-pos.csv, seed,smithX,smithZ for the loader
#
# The filter's own rule, from the standard: a village seed must have a
# blacksmith (weaponsmith, toolsmith or armorer) with enough to
# progress. Biome is NOT a criterion - all five village types are
# eligible, and the earlier taiga/snowy exclusion has been removed.
#
# Why this costs a world generation per seed: the cheap jigsaw check
# tests a piece NAME, and a taiga village passed it while generating no
# smith chest whatsoever. See check-one-village.sh.
#
# Worker copies exist because Loom takes a per-project Gradle lock -
# verify-ravines.sh documents the three approaches that did not work.
#
# Leave cores spare. A previous run used seven of ten while someone was
# playing and put their game 28 seconds behind.
set -uo pipefail

SEEDS="${1:?usage: verify-villages.sh <seeds-file> [workers]}"
WORKERS="${2:-4}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MOD="$ROOT/mod"
POOL="/tmp/village-workers"

TOTAL=$(grep -cve '^[[:space:]]*$' "$SEEDS")
echo "verifying $TOTAL village seeds across $WORKERS workers"

rm -rf "$POOL" && mkdir -p "$POOL"

echo "preparing worker copies..."
for i in $(seq 0 $((WORKERS - 1))); do
  dst="$POOL/w$i"
  mkdir -p "$dst"
  rsync -a --exclude 'run/' --exclude '.gradle/' "$MOD/" "$dst/" 2>/dev/null \
    || cp -R "$MOD/src" "$MOD/build.gradle" "$MOD/gradle.properties" \
             "$MOD/gradlew" "$MOD/gradle" "$dst/" 2>/dev/null
  mkdir -p "$dst/run"
  printf 'eula=true\n' > "$dst/run/eula.txt"
done

echo "$WORKERS" > "$POOL/worker_count"
rm -f "$POOL"/res_* "$POOL"/w*.claim
grep -ve '^[[:space:]]*$' "$SEEDS" \
  | xargs -P "$WORKERS" -L 1 "$ROOT/seed-filter/check-one-village.sh"

OUT="$MOD/run/village-all.csv"
cat "$POOL"/res_* 2>/dev/null > "$OUT"
echo
echo "done: $(wc -l < "$OUT" | tr -d ' ') rows -> $OUT"

python3 - "$OUT" "$MOD/run/village-qualified.txt" "$MOD/run/village-smith-pos.csv" <<'PY'
import csv, sys

# A failed run writes ERROR/NOWORKER as the row's LAST column
# (check-one-village.sh), and a cloud shard that produced no CSV writes
# "<seed>,ERROR,no-csv" - three columns (cloud/run-checks.sh).
#
# Counting either as a genuine zero once turned 28 crashed workers into a
# reported "35 of 40 villages have no blacksmith" - a wrong and alarming
# number that only a manual re-run caught. The narrow cloud row had the
# opposite failure: a `len(r) >= 7` filter DROPPED it, so a crashed shard
# looked like a seed that was never submitted rather than one that failed.
#
# So look for the sentinel ANYWHERE in the row rather than at a fixed
# index. smithX,smithZ were appended to this format on 2026-09-27 and a
# fixed index would have quietly stopped matching.
SENTINELS = ('ERROR', 'NOWORKER')
rows = [r for r in csv.reader(open(sys.argv[1])) if r]
if not rows:
    sys.exit('no rows')

bad = [r for r in rows if any(c in SENTINELS for c in r)]
clean = [r for r in rows if not any(c in SENTINELS for c in r)]
ok = [r for r in clean if len(r) >= 7]
malformed = [r for r in clean if len(r) < 7]

def num(v):
    try:
        return int(v)
    except ValueError:
        return 0

# The iron golem supplies this much on top of whatever is in chests.
GOLEM_IRON = 4

smith = [r for r in ok if num(r[5]) >= 1]

def qualifies(r):
    iron = num(r[1]) + GOLEM_IRON
    if iron >= 7:
        return True
    # The standard's alternative branch: 4 iron AND 3 diamonds.
    return iron >= 4 and num(r[6]) >= 3

# QUALIFYING = has a real smith chest. Not "has 7 iron".
#
# The standard filters for the iron because it does not place loot. We
# do: LootTopUp puts 3 ingots into the smith's chest at world creation,
# and the golem supplies the other 4. What the SEED has to provide is
# somewhere for that iron to go - a genuine weaponsmith, toolsmith or
# armorer chest.
#
# Requiring natural iron as well discarded 4 of 5 verified-good
# villages for a property we supply ourselves.
full = [r for r in smith if qualifies(r)]
diamond_only = [r for r in smith
                if num(r[1]) + GOLEM_IRON < 7 and qualifies(r)]

# Where the smith chest is, for rows carrying the position. Pre-2026-09-27
# runs are seven columns wide and have none; they are still valid results,
# they just cannot supply a position.
def smith_pos(r):
    if len(r) >= 9 and r[7] != '' and r[8] != '':
        return (r[7], r[8])
    return None

positioned = [r for r in smith if smith_pos(r)]

print(f'{len(ok)} completed, {len(bad)} FAILED TO RUN')
if bad:
    print('  (failures are not results - re-run them before drawing conclusions)')
if malformed:
    print(f'  !! {len(malformed)} rows too short to read and carrying no error '
          'marker - treat as unchecked, not as zeroes')
print(f'{len(smith)}/{len(ok)} have a real smith chest')
print(f'{len(full)}/{len(ok)} meet the resource threshold too')
print(f'  of those, {len(diamond_only)} qualify only via the 4 iron + 3 diamonds branch')
print(f'{len(positioned)}/{len(smith)} carry a smith position')
if len(positioned) < len(smith):
    print('  (the rest are pre-2026-09-27 rows without the column - re-run them '
          'if you need to LOAD them, since a null smith position sends the '
          'guaranteed iron to an ordinary house chest)')

# The qualifying seeds, one per line, for the pool build to draw from.
#
# Written here rather than worked out by whoever is loading the pool,
# so the rule lives in exactly one place. It was applied by hand once -
# on smith presence alone - which silently ignored the resource
# threshold entirely and would have ignored the diamond branch too.
qualified = sys.argv[2] if len(sys.argv) > 2 else None
if qualified:
    with open(qualified, 'w') as f:
        for r in smith:
            f.write(r[0] + '\n')
    print(f'\nwrote {len(smith)} qualifying seeds (smith chest present) -> {qualified}')
    print(f'  for reference, {len(full)} of them also meet the natural-resource threshold')

# The smith positions, for the pool loader.
#
# This is the AUTHORITATIVE source: it comes from generating the village
# and reading its real loot tables, where the loader's previous source
# (smith.csv) derives the position from a jigsaw piece NAME. That filter
# both over-reports by ~3x and under-reports - on 2026-09-27 three seeds
# with a verified smith chest had no jigsaw position and so could not be
# loaded at all.
posfile = sys.argv[3] if len(sys.argv) > 3 else None
if posfile:
    with open(posfile, 'w') as f:
        f.write('seed,smithX,smithZ\n')
        for r in positioned:
            x, z = smith_pos(r)
            f.write(f'{r[0]},{x},{z}\n')
    print(f'wrote {len(positioned)} smith positions -> {posfile}')
PY
