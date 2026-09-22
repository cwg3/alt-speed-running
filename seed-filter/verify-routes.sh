#!/bin/bash
# Verifies pool pairs' OVERWORLD OPENINGS against the game.
#
#   ./verify-routes.sh <routes-file> [workers]
#
# routes-file: "type owSeed netherSeed sx sz smithX smithZ pairId" per line.
# Serial by default - see verify-villages.sh for why.
set -uo pipefail
ROUTES="${1:?usage: verify-routes.sh <routes-file> [workers]}"
WORKERS="${2:-1}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MOD="$ROOT/mod"
POOL="/tmp/route-workers"

TOTAL=$(grep -cve '^[[:space:]]*$' "$ROUTES")
echo "verifying $TOTAL overworld openings, $WORKERS worker(s)"

rm -rf "$POOL" && mkdir -p "$POOL"
for i in $(seq 0 $((WORKERS - 1))); do
  dst="$POOL/w$i"; mkdir -p "$dst"
  rsync -a --exclude 'run/' --exclude '.gradle/' "$MOD/" "$dst/" 2>/dev/null
  mkdir -p "$dst/run"; printf 'eula=true\n' > "$dst/run/eula.txt"
done
echo "$WORKERS" > "$POOL/worker_count"
rm -f "$POOL"/res_* "$POOL"/w*.claim

grep -ve '^[[:space:]]*$' "$ROUTES" | tr '\t' ' ' \
  | xargs -P "$WORKERS" -I{} "$ROOT/seed-filter/check-one-route.sh" "{}"

OUT="$MOD/run/routes-all.csv"
mkdir -p "$MOD/run"
{
  echo "pairId,type,seed,lava,chests,verdict,detail,extra"
  cat "$POOL"/res_* 2>/dev/null
} > "$OUT"
echo
echo "-> $OUT"
