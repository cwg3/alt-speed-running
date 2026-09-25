#!/bin/bash
# One dispatcher for a verification check: cloud or local, same output.
#
#   run_check <check> <input-file> <workers> <dest-csv>
#
# CLOUD=1 sends the check to a spot instance; anything else runs the
# local harness. Either way the caller gets <dest-csv> in the shape it
# already parses, which is the whole point - an orchestrator should not
# have to know where the work happened.
#
# THE SHAPES ARE NOT IDENTICAL, and pretending they were is how this
# goes wrong quietly:
#
#   spawn   the local harness prepends a "seed,verdict,detail" header
#           and every parser skips row 0. The cloud writes no header,
#           so feeding its output straight in drops the first real
#           seed of every batch - a silent off-by-one in the data, not
#           a crash.
#   portalfilter
#           same as spawn - verify-rp.sh writes the same header and
#           the parser skips row 0.
#   ravine  the mod's own CSV already carries the type, so both paths
#           agree and nothing is added.
#   village no header either side.
#
# Anything new added here needs the same question asked of it.
set -uo pipefail
_RC_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# A check that produced no rows has not "found nothing" - it has not
# run. Every caller in the pool build must treat that as fatal, because
# carrying on loads a pool assembled from missing data and then reports
# success. The first unattended build did exactly that: four checks
# refused to launch, every one logged "produced no results", and the run
# finished by announcing "top-up OK".
run_check_or_die() {
	run_check "$@" || {
		echo "ERROR: the '$1' check produced no rows - stopping." >&2
		echo "  Nothing has been loaded. Fix the check and re-run." >&2
		exit 1
	}
}

run_check() {
	local check="$1" input="$2" workers="$3" dest="$4"
	local local_script local_out header

	case "$check" in
		spawn)        local_script=verify-spawn.sh;    local_out=spawn-filter.csv; header="seed,verdict,detail" ;;
		ravine)       local_script=verify-ravines.sh;  local_out=ravine-all.csv;   header="" ;;
		village)      local_script=verify-villages.sh; local_out=village-all.csv;  header="" ;;
		portalfilter) local_script=verify-rp.sh;       local_out=rp-filter.csv;    header="seed,verdict,detail" ;;
		*) echo "run_check: unknown check '$check'" >&2; return 2 ;;
	esac

	if [ "${CLOUD:-0}" = 1 ]; then
		echo "  -> $check on AWS ($workers workers)"
		# The type must be PASSED. Leaving it to the default is what broke
		# every cloud check in the first unattended pool build.
		"$_RC_ROOT/cloud/run-on-spot.sh" "$check" "$input" "$workers" \
			"${ITYPE:-m7g.4xlarge}" || true
		local csv
		csv=$(ls -t "$_RC_ROOT/seed-filter/results/$check"-*.csv 2>/dev/null | head -1)
		if [ -z "$csv" ]; then
			echo "  !! $check produced no results - leaving $dest untouched" >&2
			return 1
		fi
		if [ -n "$header" ]; then
			{ echo "$header"; cat "$csv"; } > "$dest"
		else
			cp "$csv" "$dest"
		fi
	else
		echo "  -> $check locally"
		bash "$_RC_ROOT/seed-filter/$local_script" "$input" "$workers" || true
		cp "$_RC_ROOT/mod/run/$local_out" "$dest"
	fi
	echo "  $(grep -cve '^[[:space:]]*$' "$dest") rows -> $dest"
}
