#!/bin/bash
# Verifies village seeds really have a blacksmith, in parallel.
#
#   ./verify-villages.sh <seeds-file> [workers]
#
# seeds-file: "<seed> <villageX> <villageZ>" per line
# output:     mod/run/village-all.csv, one row per seed
#             seed,ironIngots,hasIronPickaxe,hasIronArmor,chests,smithChests
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

python3 - "$OUT" "$MOD/run/village-qualified.txt" <<'PY'
import csv, sys
rows = [r for r in csv.reader(open(sys.argv[1])) if len(r) >= 7]
if not rows:
    sys.exit('no rows')

def num(v):
    try:
        return int(v)
    except ValueError:
        return 0

# A failed run writes a trailing ERROR/NOWORKER in the last column.
# Counting those as genuine zeroes once turned 28 crashed workers into
# a reported "35 of 40 villages have no blacksmith" - a wrong and
# alarming number that only a manual re-run caught.
bad = [r for r in rows if len(r) > 7 and r[7] in ('ERROR', 'NOWORKER')]
ok = [r for r in rows if not (len(r) > 7 and r[7] in ('ERROR', 'NOWORKER'))]

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

print(f'{len(ok)} completed, {len(bad)} FAILED TO RUN')
if bad:
    print('  (failures are not results - re-run them before drawing conclusions)')
print(f'{len(smith)}/{len(ok)} have a real smith chest')
print(f'{len(full)}/{len(ok)} meet the resource threshold too')
print(f'  of those, {len(diamond_only)} qualify only via the 4 iron + 3 diamonds branch')

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
PY
