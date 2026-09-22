#!/bin/bash
# Verifies nether seeds against the game, keeping only those that meet
# the standard's distance rules.
#
#   ./verify-nether.sh <seeds-file> [workers]
#
# seeds-file: one nether seed per line
# output:     mod/run/nether-all.csv, and nether-qualified.txt
#
# Exists because cubiomes gets nether structures wrong about 30% of the
# time and every consequence was silent. See check-one-nether.sh.
#
# Serial by default: the parallel harness is unreliable (see
# verify-villages.sh) and a crashed worker writing a plausible-looking
# zero is exactly how a wrong conclusion got published earlier.
set -uo pipefail
SEEDS="${1:?usage: verify-nether.sh <seeds-file> [workers]}"
WORKERS="${2:-1}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MOD="$ROOT/mod"
POOL="/tmp/nether-workers"

TOTAL=$(grep -cve '^[[:space:]]*$' "$SEEDS")
echo "verifying $TOTAL nether seeds against the game, $WORKERS worker(s)"

rm -rf "$POOL" && mkdir -p "$POOL"
for i in $(seq 0 $((WORKERS - 1))); do
  dst="$POOL/w$i"; mkdir -p "$dst"
  rsync -a --exclude 'run/' --exclude '.gradle/' "$MOD/" "$dst/" 2>/dev/null
  mkdir -p "$dst/run"; printf 'eula=true\n' > "$dst/run/eula.txt"
done
echo "$WORKERS" > "$POOL/worker_count"
rm -f "$POOL"/res_* "$POOL"/w*.claim

grep -ve '^[[:space:]]*$' "$SEEDS" \
  | xargs -P "$WORKERS" -L 1 "$ROOT/seed-filter/check-one-nether.sh"

OUT="$MOD/run/nether-all.csv"
cat "$POOL"/res_* 2>/dev/null > "$OUT"
QUAL="$MOD/run/nether-qualified.txt"
awk -F, '$8=="PASS" {print $1}' "$OUT" > "$QUAL"

ok=$(grep -c PASS "$OUT" || true)
err=$(grep -c ERROR "$OUT" || true)
echo
echo "$(wc -l < "$OUT" | tr -d ' ') checked: $ok pass, $err failed to run"
echo "qualifying seeds -> $QUAL"
