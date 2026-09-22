#!/bin/bash
# Verifies POOL PAIRS against the game, as match worlds.
#
#   ./verify-pairs.sh <pairs-file> [workers]
#
# pairs-file: "overworldSeed netherSeed bastionX bastionZ pairId type"
#             per line (tabs or spaces).
#
# Serial by default and on purpose. See verify-villages.sh: the parallel
# harness loses most of its workers, and a lost worker that writes a
# zero is worse than no answer at all.
set -uo pipefail
PAIRS="${1:?usage: verify-pairs.sh <pairs-file> [workers]}"
WORKERS="${2:-1}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MOD="$ROOT/mod"
POOL="/tmp/pair-workers"

TOTAL=$(grep -cve '^[[:space:]]*$' "$PAIRS")
echo "verifying $TOTAL pool pairs as match worlds, $WORKERS worker(s)"

rm -rf "$POOL" && mkdir -p "$POOL"
for i in $(seq 0 $((WORKERS - 1))); do
  dst="$POOL/w$i"; mkdir -p "$dst"
  rsync -a --exclude 'run/' --exclude '.gradle/' "$MOD/" "$dst/" 2>/dev/null
  mkdir -p "$dst/run"; printf 'eula=true\n' > "$dst/run/eula.txt"
done
echo "$WORKERS" > "$POOL/worker_count"
rm -f "$POOL"/res_* "$POOL"/w*.claim

# -I{} (not -L 1) so the whole line arrives as ONE argument and the
# fields stay together. BSD xargs has no -d; -I already reads a line at
# a time, which is what this needs.
grep -ve '^[[:space:]]*$' "$PAIRS" \
  | tr '\t' ' ' \
  | xargs -P "$WORKERS" -I{} "$ROOT/seed-filter/check-one-pair.sh" "{}"

OUT="$MOD/run/pairs-all.csv"
mkdir -p "$MOD/run"
{
  echo "pairId,type,seed,bx,bz,fx,fz,bastionDist,fortressDist,verdict,shippedX,shippedZ,shipError,containers"
  cat "$POOL"/res_* 2>/dev/null
} > "$OUT"

echo
echo "-> $OUT"
