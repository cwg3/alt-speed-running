#!/bin/bash
# Refuses to publish seed data, filter yields or pipeline throughput.
#
#   check-no-stats.sh --staged      scan what is about to be committed
#   check-no-stats.sh --msg <file>  scan a commit message
#   check-no-stats.sh --history     audit every commit message already pushed
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

	# Pool COMPOSITION - how many of each type are drawable or quarantined.
	#
	# This is the same leak as a yield wearing different clothes. A yield says
	# what a type costs to produce; a per-type depth says which type the
	# producing has failed to keep up with, which is the map of where the
	# ladder is thin stated outright rather than inferred. SPEC.md carried
	# exactly that table from 2026-09-25 to 2026-09-27 and this guard passed
	# it, because --staged only scans ADDED lines and the table predated the
	# guard. Being unable to catch what is already committed is the right
	# trade; being unable to catch the next one was not.
	#
	# The vocabulary alone was not enough: SPEC.md also said "Its 29 villages
	# were chosen on..." and matched none of these words, because counting a
	# type needs no pool noun - the type name is the noun.
	#
	# But the type names cannot simply be ADDED to the vocabulary. Tried that:
	# it flagged fifteen files, all of them the specification itself -
	# "| Village | <= 7 chunks |", "Desert temple | 7 iron",
	# "SHIPWRECK(7, INGOT_EQUIVALENT...)". In this repo a type name sitting
	# near a number is usually a GUARANTEE, and a guard that fires on the spec
	# it protects gets overridden by reflex.
	#
	# What distinguishes a count is that the number comes FIRST and the type is
	# PLURAL: "29 villages", never "Village | 7 chunks". The digit must also
	# not follow a dot, or "since 1.14 villages are assembled by jigsaw" reads
	# as a count. Together those cut the false positives to three, all named
	# below.
	#
	# Known benign matches, so an override is a decision and not a reflex:
	# MatchHistoryScreen.java uses `drawable` as a local for how many rows fit
	# on screen; topup.sh has f-strings like `{pool_n:>4} drawable` where the
	# digit is a field width - that script PRINTS the number and contains none;
	# check-one-pair.sh quotes the historical "35 of 40 villages" incident,
	# which is the lesson being recorded, not today's pool. None is a leak.
	local PLURALS='(villages|desert temples|ruined portals|shipwrecks|buried treasures|seed pairs)'
	local depth KW2='(drawable|quarantined|pool depth|in the pool)'
	depth=$(printf '%s\n' "$text" \
		| grep -inE "([0-9][^.]{0,8}$KW2|$KW2[^.]{0,8}[0-9]|(^|[^0-9.])[0-9]+ +$PLURALS|[0-9]+ of [0-9]+ +[a-z]*$PLURALS)" || true)
	if [ -n "$depth" ]; then
		echo "!! $label: looks like pool composition (a per-type depth is a yield)"
		printf '%s\n' "$depth" | head -5 | sed 's/^/     /'
		hits=$((hits + 1))
	fi

	# And the table form, which carries no vocabulary at all: a row whose
	# first cell is a seed type and whose other cells are bare counts.
	# "| village | 43 | 9 |" says everything the sentence would have and
	# matches none of the words that would catch it.
	local TYPES='(village|desert[ _-]?temple|ruined[ _-]?portal|shipwreck|buried[ _-]?treasure)'
	local tbl
	tbl=$(printf '%s\n' "$text" \
		| grep -inE "^\|[^|]*$TYPES[^|]*\|([^|]*\|)*[^|a-z]*[0-9]+[^|a-z]*\|" || true)
	if [ -n "$tbl" ]; then
		echo "?? $label: a table row pairing a seed type with counts - fine if those are not pool depths"
		printf '%s\n' "$tbl" | head -5 | sed 's/^/     /'
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
--history)
	# AUDIT EVERYTHING ALREADY PUBLISHED. The hooks only ever see what is
	# being written now, so every rule added to this file is blind to
	# whatever preceded it - and the rules got added because something had
	# already gone wrong, which means the thing that went wrong is exactly
	# what they cannot see.
	#
	# That is not hypothetical. On 2026-09-27, fourteen raw seed values
	# were found sitting in eleven commit SUBJECTS from 2026-09-19 to
	# 2026-09-24 - "Play-confirmed: <type> <seed> / <seed>", both halves of
	# a pair, on a public repo. The files had been redacted and the history
	# of the files had been rewritten; nobody had read the messages. The
	# commit-msg hook that stops this was installed 2026-09-25 and works.
	# It simply arrived four days late, and nothing existed to look back.
	#
	# Run this after changing any rule here, and after any redaction pass.
	echo "scanning every commit message on every ref..."
	while IFS= read -r c; do
		body=$(git log -1 --format='%B' "$c" 2>/dev/null || true)
		[ -n "$body" ] || continue
		before=$hits
		scan "$(git log -1 --format='%h %ad %s' --date=short "$c" 2>/dev/null | cut -c1-72)" "$body"
		[ "$hits" -ne "$before" ] && echo
	done < <(git rev-list --all)
	if [ "$hits" -eq 0 ]; then
		echo "clean: no published commit message carries a seed, yield, throughput or pool depth"
	else
		cat >&2 <<'MSG'

  These are ALREADY PUBLISHED. A hook cannot help now; the options are:

    rotate   make the leaked thing worthless - quarantine the seeds so
             knowing them buys nothing. Immediate, reversible, and it
             does not depend on what GitHub does with old objects.
    rewrite  git filter-repo --message-callback, then force-push. It
             really removes them, but every descendant hash changes, so
             tags and releases must be re-pointed or they end up on a
             tree that no longer exists - see CLAUDE.md.

  Rotating first is usually right: the history is only dangerous while
  the thing it names is still live.
MSG
	fi
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
