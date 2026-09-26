#!/bin/bash
# Backs up everything that is not in git.
#
#   ./tools/backup.sh [--dry-run]
#
# The repo holds the code. This holds what the code cannot rebuild:
#
#   dynamodb/   every table, as JSON. The seed pool is the expensive one
#               - it is hours of world generation and exists nowhere
#               else, since the candidate JSON is gitignored and
#               seedtypes picks a random start seed on every run, so a
#               rebuild produces a DIFFERENT pool, not the same one.
#   replays/    the replay bucket. No expiry, and unreproducible.
#   secrets/    the *.local.json files. Gitignored on purpose: the
#               anti-cheat thresholds and the per-type candidate
#               allowances would each tell a reader something the pool
#               is meant to keep. A new machine needs them before its
#               first deploy or top-up.
#   the pack    what a tester actually installs.
#   schedule/   the nightly top-up schedule and the roles it runs
#               under, as AWS currently has them. The install script
#               recreates all of it, so this is not strictly needed to
#               restore - but it records what was ACTUALLY running,
#               which is the question you have after something changes
#               unexpectedly and the script no longer matches reality.
#
# WHY A SCRIPT. The previous backups were run by hand and then stopped
# happening - the last one predated a leaderboard, a ladder reset, a
# publish guard and the whole top-up pipeline. A backup nobody can run
# with one command is a backup that silently ages.
#
# PITR covers the tables for 35 days, which protects against a bad
# write. This protects against losing the account, and gives a bundle
# that can be read without restoring anything.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REGION="${REGION:-us-west-2}"
ACCT=$(aws sts get-caller-identity --query Account --output text)
BUCKET="alt-backups-${ACCT}"
STAMP=$(date -u +%Y%m%d-%H%M%S)
DEST="s3://${BUCKET}/${STAMP}"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
DRY=0
[ "${1:-}" = "--dry-run" ] && DRY=1

echo "=== backup $STAMP ==="

mkdir -p "$WORK/dynamodb" "$WORK/secrets"

echo "--- dynamodb"
for t in $(aws dynamodb list-tables --region "$REGION" --query 'TableNames[]' --output text); do
	short=$(printf '%s' "$t" | sed 's/^BackendStack-//; s/-[A-Z0-9]*$//')
	aws dynamodb scan --region "$REGION" --table-name "$t" --output json \
		> "$WORK/dynamodb/${short}.json" 2>/dev/null
	n=$(python3 -c "import json;print(json.load(open('$WORK/dynamodb/${short}.json'))['Count'])" 2>/dev/null || echo '?')
	printf '  %-34s %s rows\n' "$short" "$n"
done

echo "--- replays"
REPLAY_BUCKET=$(aws s3 ls 2>/dev/null | awk '/replaybucket/ {print $3}' | head -1)
if [ -n "$REPLAY_BUCKET" ]; then
	aws s3 sync "s3://$REPLAY_BUCKET" "$WORK/replays" --quiet
	echo "  $(find "$WORK/replays" -type f 2>/dev/null | wc -l | tr -d ' ') object(s)"
else
	echo "  no replay bucket found"
fi

echo "--- secrets"
for f in "$ROOT/backend/split-rules.local.json" "$ROOT/seed-filter/headroom.local.json"; do
	[ -f "$f" ] && cp "$f" "$WORK/secrets/" && echo "  $(basename "$f")"
done

echo "--- schedule"
mkdir -p "$WORK/schedule"
aws scheduler get-schedule --region "$REGION" --name alt-pool-topup \
	> "$WORK/schedule/alt-pool-topup.json" 2>/dev/null \
	&& echo "  alt-pool-topup ($(python3 -c "import json;d=json.load(open('$WORK/schedule/alt-pool-topup.json'));print(d['State'],d['ScheduleExpression'])" 2>/dev/null))" \
	|| echo "  no schedule installed"
for r in alt-topup-runner alt-topup-scheduler alt-seedwork; do
	aws iam list-role-policies --role-name "$r" >/dev/null 2>&1 || continue
	{
		echo "{\"role\": \"$r\","
		echo " \"inline\": ["
		first=1
		for pol in $(aws iam list-role-policies --role-name "$r" --query 'PolicyNames[]' --output text 2>/dev/null); do
			[ "$first" = 1 ] || echo ","
			first=0
			aws iam get-role-policy --role-name "$r" --policy-name "$pol" --output json 2>/dev/null
		done
		echo " ],"
		echo " \"attached\":"
		aws iam list-attached-role-policies --role-name "$r" --output json 2>/dev/null
		echo "}"
	} > "$WORK/schedule/iam-${r}.json"
	echo "  iam $r"
done

echo "--- pack"
PACK=$(ls -t "$ROOT"/pack/*.mrpack 2>/dev/null | head -1)
[ -n "$PACK" ] && cp "$PACK" "$WORK/" && echo "  $(basename "$PACK")"

cat > "$WORK/README.md" <<EOF
# alt backup $STAMP

Taken $(date -u '+%Y-%m-%d %H:%M:%SZ') by tools/backup.sh.

- \`dynamodb/\` one JSON per table, as scanned. Restore with a loader or
  by hand; the shapes are DynamoDB's own.
- \`replays/\` the replay bucket, keyed \`<matchId>/<playerUuid>.json.gz\`.
- \`secrets/\` gitignored config. A new machine needs these before its
  first \`cdk deploy\` (split-rules) or pool top-up (headroom). Without
  them both degrade to loose defaults rather than failing, which is the
  quiet failure they are kept out of git to avoid.
- \`schedule/\` the nightly top-up schedule and its IAM roles as they
  actually were. \`cloud/install-topup-schedule.sh\` recreates them, so
  this is a record rather than a restore path.
- the \`.mrpack\` a tester installs.

The seed pool is the irreplaceable part: \`seedtypes\` picks a random
start seed on every run, so rebuilding produces a different pool, not
this one.
EOF

echo "--- upload"
if [ "$DRY" = 1 ]; then
	echo "  (dry run) would upload $(du -sh "$WORK" | cut -f1) to $DEST"
	find "$WORK" -maxdepth 2 -type d | sed "s|$WORK|  |"
	exit 0
fi
aws s3 cp "$WORK" "$DEST" --recursive --quiet || { echo "upload failed" >&2; exit 1; }
echo "  $(du -sh "$WORK" | cut -f1) -> $DEST"
echo
echo "backup complete"
