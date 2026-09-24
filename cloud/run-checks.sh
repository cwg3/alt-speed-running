#!/bin/bash
# Runs a batch of seed checks in one container and writes one CSV row
# per seed.
#
#   CHECK=spawn|ravine|nether|route|portalfilter
#   IN=/work/in.txt      one "seed [x] [z] [type]" per line
#   OUT=/work/out.csv    one row per input line, appended
#
# One seed per server boot, because level-seed is fixed at startup and
# cannot be changed without restarting. The boot is most of the cost
# (~11s of JVM and Minecraft against a few seconds of actual work), so
# the batching that matters is many seeds per CONTAINER, not many per
# server.
#
# No server-port is set. Each container has its own network namespace,
# so every one of them can bind 25565.
#
# Every input line produces exactly one output row, including failures.
# A crashed check must never be silently absent or indistinguishable
# from a verdict: 388 ERROR rows once got counted as "no ravines" and
# thrown away because the caller tested only for a pass value.
set -uo pipefail

CHECK="${CHECK:?set CHECK to generate|spawn|ravine|nether|route|portalfilter}"
IN="${IN:?set IN to the input file}"
OUT="${OUT:?set OUT to the output csv}"
HEAP="${HEAP:-2G}"

# Candidate generation is a different shape from the per-seed checks:
# no server, no world, one JSON pair per shard rather than a CSV row
# per seed. seedtypes takes a START SEED, so shards scan disjoint
# ranges and parallelise without coordinating.
if [ "$CHECK" = generate ]; then
	OUTDIR=$(dirname "$OUT")
	n=0
	while read -r per start; do
		case "$per" in ''|\#*) continue ;; esac
		n=$((n + 1))
		work=$(mktemp -d)
		cd "$work" || exit 2
		echo "[container] generate: $per per type from $start" >&2
		if seedtypes "$per" "$start" >&2; then
			cp output/overworld_by_type.json "$OUTDIR/gen-$start-overworld.json"
			cp output/nether_seeds.json     "$OUTDIR/gen-$start-nether.json"
		else
			echo "[container] seedtypes FAILED for start $start" >&2
		fi
		cd / && rm -rf "$work"
	done < "$IN"
	echo "[container] done: $n shards" >&2
	exit 0
fi

case "$CHECK" in
	spawn)         INFILE=spawncheck.txt;   CSV=spawncheck.csv   ;;
	ravine)        INFILE=ravine.txt;       CSV=ravine.csv       ;;
	nether)        INFILE=netherlocate.txt; CSV=netherlocate.csv ;;
	route)         INFILE=routecheck.txt;   CSV=routecheck.csv   ;;
	portalfilter)  INFILE=portalfilter.txt; CSV=portalfilter.csv ;;
	*) echo "unknown CHECK '$CHECK'" >&2; exit 2 ;;
esac

cd /srv || exit 2
total=$(grep -cve '^[[:space:]]*$' "$IN")
done=0
echo "[container] $CHECK: $total seeds" >&2

while read -r line; do
	case "$line" in ''|\#*) continue ;; esac
	seed=${line%% *}
	done=$((done + 1))

	# A stale world directory is the difference between generating the
	# seed under test and re-reading the previous one.
	rm -rf world
	rm -f "$INFILE"
	: > "$CSV"
	printf '%s\n' "$line" > "$INFILE"
	printf 'level-seed=%s\nlevel-type=default\nonline-mode=false\nmax-tick-time=-1\nsync-chunk-writes=false\n' \
		"$seed" > server.properties

	# < /dev/null is load-bearing. The server reads stdin for console
	# commands, and without it the first boot swallows the rest of
	# the seed list - a 3-seed batch produced exactly 1 row.
	java -Xmx"$HEAP" -jar fabric-server-launch.jar nogui \
		< /dev/null > /tmp/last.log 2>&1

	if [ -s "$CSV" ]; then
		tail -1 "$CSV" >> "$OUT"
	else
		# Keep the reason. Discarding it is why a port collision took a
		# full run to identify.
		echo "$seed,ERROR,no-csv" >> "$OUT"
		{
			echo "=== $seed produced no $CSV ==="
			grep -iE "FAILED TO BIND|Exception|Error|Caused by" /tmp/last.log | head -8
			tail -5 /tmp/last.log
		} >&2
	fi
	echo "[container] $done/$total" >&2
done < "$IN"

echo "[container] done: $(wc -l < "$OUT" | tr -d ' ') rows" >&2
