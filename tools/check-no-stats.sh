#!/bin/bash
# Refuses to publish seed data, filter yields or pipeline throughput.
#
#   check-no-stats.sh --staged      scan what is about to be committed
#   check-no-stats.sh --msg <file>  scan a commit message
#
# WHY A COMMIT MESSAGE IS SCANNED. On 2026-09-25 the measured throughput
# of a verification batch was scrubbed out of SPEC.md and then typed
# straight into a commit message and a code comment, and pushed. A commit
# message is published exactly as much as a tracked file is - it is on
# GitHub, it is in every clone, and it cannot be edited after the fact
# without rewriting history. Treating it as scratch space is the specific
# mistake this file exists to stop.
#
# WHAT IS SENSITIVE, and why each one:
#
#   seeds            a player who memorises the pool knows the world
#                    before it is dealt. The whole pool was purged from
#                    history for this.
#   filter yields    together they say what a pool costs to build and
#                    which types are expensive - a map of where the
#                    ladder is thin.
#   throughput       the same information wearing a clock.
#   thresholds       hands anyone faking a run the minimum that passes.
#
# WHAT IS DELIBERATELY NOT SENSITIVE: the vanilla probabilities in
# DEVIATIONS.md. Quoting them is that file's entire function, so it is
# exempt from the percentage rule rather than fighting it.
#
# This net is COARSE ON PURPOSE. A false positive costs one override; a
# false negative is public forever. Override only when you have looked:
#
#   ALT_ALLOW_STATS=1 git commit ...
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE="${1:---staged}"
hits=0

scan() {
	local label="$1" text="$2" exempt="${3:-}"

	# A pool seed is a bare integer of 10+ digits. High signal: nothing
	# else in this repo looks like that except a timestamp, and those are
	# 8 digits or hyphenated.
	local seeds
	seeds=$(printf '%s\n' "$text" | grep -nE '(^|[^0-9A-Za-z_.-])[0-9]{10,}([^0-9A-Za-z_]|$)' || true)
	if [ -n "$seeds" ]; then
		echo "!! $label: looks like a raw seed"
		printf '%s\n' "$seeds" | head -5 | sed 's/^/     /'
		hits=$((hits + 1))
	fi

	# Yield and throughput vocabulary sitting NEXT TO a number. The
	# proximity matters: the first version matched a digit anywhere on
	# the line, so any sentence carrying a date and the word
	# "throughput" tripped it - including the commit message that
	# introduced this guard. Discussing the category is not leaking a
	# figure. 8 characters still catches "40 s per pair" (gap of 3) and
	# "yield is <n>" (gap of 4) while letting prose through, and the
	# percentage, seed and count-with-duration rules below remain as
	# backstops for a figure spelled out at a distance.
	local yield KW='(yield|candidates? per|per candidate|pass rate|survived|throughput|per seed|per pair|seeds/(hour|day|min)|pairs/(hour|min))'
	yield=$(printf '%s\n' "$text" \
		| grep -inE "([0-9][^.]{0,8}$KW|$KW[^.]{0,8}[0-9])" || true)
	if [ -n "$yield" ]; then
		echo "!! $label: looks like a filter yield or throughput figure"
		printf '%s\n' "$yield" | head -5 | sed 's/^/     /'
		hits=$((hits + 1))
	fi

	# A count of work next to a duration: "188 pairs ... 26 minutes".
	if printf '%s\n' "$text" | grep -qiE '[0-9]+ (pairs|seeds|candidates)' \
	   && printf '%s\n' "$text" | grep -qiE '[0-9]+ ?(min|mins|minutes|hours|hrs)\b'; then
		echo "!! $label: a work count and a duration together is a throughput figure"
		hits=$((hits + 1))
	fi

	# Percentages, except where publishing them is the point.
	if [ "$exempt" != "vanilla-probabilities" ]; then
		local pct
		pct=$(printf '%s\n' "$text" | grep -nE '[0-9]{1,3}(\.[0-9]+)? ?%' || true)
		if [ -n "$pct" ]; then
			echo "?? $label: percentage - fine if it is a vanilla probability, not if it is a yield"
			printf '%s\n' "$pct" | head -5 | sed 's/^/     /'
			hits=$((hits + 1))
		fi
	fi
}

case "$MODE" in
--staged)
	# Only ADDED lines matter; existing content is already published.
	while IFS= read -r f; do
		[ -z "$f" ] && continue
		case "$f" in
			# These DESCRIBE the rule, so they necessarily contain its
			# own vocabulary and flag themselves. Exempting them is not
			# a loophole - they hold no measurements - but it is the ONLY
			# wholesale exemption, because a guard with a list of
			# exceptions is a guard nobody trusts. Needing an override
			# to install the guard would teach the override reflex,
			# which is worse than the false positive.
			tools/check-no-stats.sh|.githooks/*|CLAUDE.md) continue ;;
			DEVIATIONS.md) ex=vanilla-probabilities ;;
			*.lock|*lock.json|*.png|*.jar) continue ;;
			*) ex="" ;;
		esac
		added=$(git diff --cached -U0 -- "$f" | grep '^+' | grep -v '^+++' | sed 's/^+//' || true)
		[ -n "$added" ] && scan "$f" "$added" "$ex"
	done < <(git diff --cached --name-only --diff-filter=ACM)
	;;
--msg)
	[ -n "${2:-}" ] || { echo "check-no-stats.sh --msg needs a file" >&2; exit 2; }
	# Comment lines are stripped before the message is stored.
	scan "commit message" "$(grep -v '^#' "$2" || true)"
	;;
*) echo "usage: check-no-stats.sh [--staged|--msg <file>]" >&2; exit 2 ;;
esac

if [ "$hits" -gt 0 ]; then
	cat >&2 <<'MSG'

  Refusing to publish. This repo keeps the seed pool, filter yields,
  pipeline throughput and anti-cheat thresholds OUT of git - a commit
  message counts as published.

  If a figure needs keeping, put it in a .local.json beside
  backend/split-rules.local.json and back it up to S3 under secrets/.

  If this is a false positive, look at the lines above first, then:
      ALT_ALLOW_STATS=1 git commit ...
MSG
	exit 1
fi
exit 0
