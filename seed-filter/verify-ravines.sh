#!/bin/bash
# Verifies ocean seeds have two findable magma ravines, in parallel.
#
#   ./verify-ravines.sh <seeds-file> [workers]
#
# seeds-file: "<seed> <structureX> <structureZ> <type>" per line
# output:     mod/run/ravine-all.csv, one row per seed
#
# Each check costs about 12 seconds and roughly 11 of those are JVM and
# Minecraft startup, not the work - a single boot checks eighty seeds in
# thirteen seconds once it is up. The cost is per process, so the way to
# make a pool build fast is to run several at once.
#
# Workers get their own COPY of the mod project. Two earlier approaches
# did not work:
#
#   - separate run directories: Loom takes a per-project Gradle lock, so
#     parallel `gradlew runServer` calls queue behind each other. Four
#     workers produced two results; two never started at all.
#   - launching the JVM directly: Loom fills in its JVM arguments lazily,
#     so they come back empty at configuration time and the server will
#     not boot without them.
#
# A copy is about 6MB of build output plus sources; the Gradle cache
# stays shared. Crude, and it actually runs in parallel.
#
# Leave cores spare. A previous run used seven of ten while someone was
# playing and put their game 28 seconds behind.
set -uo pipefail

SEEDS="${1:?usage: verify-ravines.sh <seeds-file> [workers]}"
WORKERS="${2:-6}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MOD="$ROOT/mod"
POOL="/tmp/ravine-workers"

TOTAL=$(grep -cve '^[[:space:]]*$' "$SEEDS")
echo "verifying $TOTAL seeds across $WORKERS workers"

rm -rf "$POOL" && mkdir -p "$POOL"
awk -v n="$WORKERS" '{ print > ("'"$POOL"'/part" (NR % n)) }' "$SEEDS"

echo "preparing worker copies..."
for i in $(seq 0 $((WORKERS - 1))); do
  dst="$POOL/w$i"
  mkdir -p "$dst"
  # Everything the build needs, and none of the accumulated worlds.
  rsync -a --exclude 'run/' --exclude '.gradle/' "$MOD/" "$dst/" 2>/dev/null \
    || cp -R "$MOD/src" "$MOD/build.gradle" "$MOD/gradle.properties" \
             "$MOD/gradlew" "$MOD/gradle" "$dst/" 2>/dev/null
  mkdir -p "$dst/run"
  printf 'eula=true\n' > "$dst/run/eula.txt"
done

# xargs -P does the parallelism. An earlier version used bash
# background jobs and a `wait`, and silently produced two results from
# eight seeds - workers that never started looked identical to workers
# that finished.
echo "$WORKERS" > "$POOL/worker_count"
rm -f "$POOL"/res_* "$POOL"/w*.claim
grep -ve '^[[:space:]]*$' "$SEEDS" \
  | xargs -P "$WORKERS" -L 1 "$ROOT/seed-filter/check-one-ravine.sh"

OUT="$MOD/run/ravine-all.csv"
cat "$POOL"/res_* 2>/dev/null > "$OUT"
echo
echo "done: $(wc -l < "$OUT" | tr -d ' ') rows -> $OUT"

python3 - "$OUT" <<'PY'
import csv, sys
rows = [r for r in csv.reader(open(sys.argv[1])) if len(r) > 3]
for t in ('shipwreck', 'buried_treasure'):
    sub = [r for r in rows if r[1] == t]
    if not sub:
        continue
    two = sum(1 for r in sub if r[2] == 'true')
    one = sum(1 for r in sub if int(r[3]) >= 1)
    print(f'{t}: {two}/{len(sub)} with 2 ravines + kelp, {one}/{len(sub)} with 1+')
PY
