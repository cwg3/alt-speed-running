#!/bin/bash
# Wood near spawn, and three chests for ocean types. See SpawnResourceHook.
set -uo pipefail
IN="${1:?usage: verify-spawn.sh <file: 'seed x z type' per line> [workers]}"
WORKERS="${2:-1}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
POOL="/tmp/spawn-workers"
echo "checking $(grep -cve '^[[:space:]]*$' "$IN") candidates for wood at spawn"
rm -rf "$POOL" && mkdir -p "$POOL"
for i in $(seq 0 $((WORKERS-1))); do
  dst="$POOL/w$i"; mkdir -p "$dst"
  rsync -a --exclude 'run/' --exclude '.gradle/' "$ROOT/mod/" "$dst/" 2>/dev/null
  mkdir -p "$dst/run"; printf 'eula=true\n' > "$dst/run/eula.txt"
done
echo "$WORKERS" > "$POOL/worker_count"; rm -f "$POOL"/res_* "$POOL"/w*.claim
grep -ve '^[[:space:]]*$' "$IN" | tr '\t' ' ' | xargs -P "$WORKERS" -I{} "$ROOT/seed-filter/check-one-spawn.sh" "{}"
OUT="$ROOT/mod/run/spawn-filter.csv"; mkdir -p "$ROOT/mod/run"
{ echo "seed,verdict,detail"; cat "$POOL"/res_* 2>/dev/null; } > "$OUT"
echo; echo "$(($(wc -l < "$OUT")-1)) checked: $(grep -c ,PASS, "$OUT" || true) pass -> $OUT"
