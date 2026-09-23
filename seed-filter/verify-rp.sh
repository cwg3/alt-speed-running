#!/bin/bash
# Filters ruined portal candidates on whether their VANILLA portal is
# finishable. See PortalFrame. Serial by default.
set -uo pipefail
IN="${1:?usage: verify-rp.sh <seeds-file: 'seed x z' per line> [workers]}"
WORKERS="${2:-1}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
POOL="/tmp/rp-workers"
TOTAL=$(grep -cve '^[[:space:]]*$' "$IN")
echo "filtering $TOTAL ruined portal candidates, $WORKERS worker(s)"
rm -rf "$POOL" && mkdir -p "$POOL"
for i in $(seq 0 $((WORKERS - 1))); do
  dst="$POOL/w$i"; mkdir -p "$dst"
  rsync -a --exclude 'run/' --exclude '.gradle/' "$ROOT/mod/" "$dst/" 2>/dev/null
  mkdir -p "$dst/run"; printf 'eula=true\n' > "$dst/run/eula.txt"
done
echo "$WORKERS" > "$POOL/worker_count"; rm -f "$POOL"/res_* "$POOL"/w*.claim
grep -ve '^[[:space:]]*$' "$IN" | tr '\t' ' ' \
  | xargs -P "$WORKERS" -I{} "$ROOT/seed-filter/check-one-rp.sh" "{}"
OUT="$ROOT/mod/run/rp-filter.csv"; mkdir -p "$ROOT/mod/run"
{ echo "seed,verdict,detail"; cat "$POOL"/res_* 2>/dev/null; } > "$OUT"
ok=$(grep -c ,PASS, "$OUT" || true); err=$(grep -c ,ERROR, "$OUT" || true)
echo; echo "$(($(wc -l < "$OUT")-1)) checked: $ok pass, $err failed to run  -> $OUT"
