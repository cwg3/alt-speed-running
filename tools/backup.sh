#!/bin/bash
# Backs up the whole project to three places.
#
#   ./tools/backup.sh [--dry-run] [--drive <path>]
#
# Three destinations, chosen because they fail independently:
#
#   s3://alt-backups-<acct>/<stamp>/   survives losing the Mac. Encrypted
#                                      at rest (SSE-S3/AES256, bucket
#                                      default), versioned, public access
#                                      blocked, no lifecycle expiry.
#   ~/alt-backups/alt-<stamp>/         survives losing the AWS account
#   <drive>/alt-<stamp>/               survives losing both, --drive only
#
# ~/alt-vault/pool-<stamp>/ is a FOURTH copy and deliberately not made
# here: it is chflags-locked, so a script that could write it could also
# destroy it, which is the one thing it exists to prevent. Make it by hand
# from ~/alt-backups - ~/alt-vault/HOW-TO-UNLOCK.txt has the two lines.
#
# THIS USED TO BACK UP ONLY WHAT WAS NOT IN GIT, and the gap was not
# obvious: the repo lived in exactly two places, GitHub and this Mac, while
# every bundle carefully preserved the things around it. seed-filter/output
# was worse - one copy, live only. Both are here now.
#
# What it holds:
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
#   repo.bundle the entire git repository, every branch and tag, full
#               history - `git clone repo.bundle` and you have the project
#               with no GitHub. Tags are fetched first, because a release
#               cut with `gh release create` tags on the SERVER and the tag
#               is not local until something pulls it; a bundle taken
#               without that step is missing the release it was taken for.
#   seed-filter/output/
#               the candidate pools seedtypes produced: nether_seeds.json
#               and overworld_by_type.json above all. Gitignored, and until
#               2026-09-27 not backed up anywhere, which made it the only
#               single-copy thing in the project. Losing it does not lose
#               the pool - pool rows carry their own seed values - but
#               every future additive load pairs against nether_seeds.json,
#               and regenerating gives DIFFERENT seeds for the same reason
#               the pool cannot be rebuilt.
#   seed-filter/results/
#               every verification run's CSV. The record of what was
#               checked and what it answered, which is the only evidence a
#               seed in the pool was ever verified at all.
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
DRIVE=""
LOCAL="$HOME/alt-backups"
while [ $# -gt 0 ]; do
	case "$1" in
		--dry-run) DRY=1 ;;
		--drive)   DRIVE="${2:?--drive needs a path}"; shift ;;
		*) echo "usage: backup.sh [--dry-run] [--drive <path>]" >&2; exit 1 ;;
	esac
	shift
done

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

echo "--- repo"
# Fetch tags first. `gh release create` tags on the server, so the tag for
# the release this backup is being taken for is typically NOT local yet - a
# bundle made without this is missing it, and looks complete.
git -C "$ROOT" fetch origin --tags --quiet 2>/dev/null \
	|| echo "  (could not fetch tags - bundling local refs only)"
if git -C "$ROOT" bundle create "$WORK/repo.bundle" --all 2>/dev/null; then
	echo "  repo.bundle $(du -h "$WORK/repo.bundle" | cut -f1), $(git -C "$ROOT" tag | wc -l | tr -d ' ') tags, HEAD $(git -C "$ROOT" rev-parse --short HEAD)"
	# A bundle holds COMMITS. Anything uncommitted is not in it, and the
	# restore would silently come back without it.
	DIRTY=$(git -C "$ROOT" status --porcelain | wc -l | tr -d ' ')
	[ "$DIRTY" != 0 ] && echo "  !! $DIRTY uncommitted change(s) are NOT in the bundle"
	AHEAD=$(git -C "$ROOT" rev-list --count origin/main..HEAD 2>/dev/null || echo 0)
	[ "$AHEAD" != 0 ] && echo "  ($AHEAD commit(s) not yet on origin - the bundle has them, GitHub does not)"
else
	echo "  !! git bundle FAILED - this backup has no copy of the code" >&2
fi

echo "--- submodules"
# A git bundle does NOT contain submodule content. Restoring from
# repo.bundle alone left tools/cubiomes empty, and cubiomes is what
# seedtypes and query are built against - so the restore looked complete
# and could not build the pipeline. Bundle each submodule separately, or
# this backup quietly depends on github.com/Cubitect/cubiomes still
# existing and still having that commit.
mkdir -p "$WORK/submodules"
git -C "$ROOT" submodule status 2>/dev/null | awk '{print $2}' | while read -r sp; do
	[ -n "$sp" ] || continue
	if [ ! -e "$ROOT/$sp/.git" ]; then
		echo "  !! $sp is not initialised - nothing to bundle" >&2
		continue
	fi
	name=$(printf '%s' "$sp" | tr '/' '-')
	if git -C "$ROOT/$sp" bundle create "$WORK/submodules/$name.bundle" --all 2>/dev/null; then
		echo "  $sp -> submodules/$name.bundle $(du -h "$WORK/submodules/$name.bundle" | cut -f1) @ $(git -C "$ROOT/$sp" rev-parse --short HEAD)"
	else
		echo "  !! bundling $sp FAILED" >&2
	fi
done

echo "--- seed pipeline"
# Raw, not tarred: readable on any machine years from now, and a corrupt
# sector costs one file instead of the whole archive.
for d in output results; do
	if [ -d "$ROOT/seed-filter/$d" ]; then
		mkdir -p "$WORK/seed-filter/$d"
		cp -R "$ROOT/seed-filter/$d/." "$WORK/seed-filter/$d/"
		echo "  seed-filter/$d $(find "$WORK/seed-filter/$d" -type f | wc -l | tr -d ' ') file(s), $(du -sh "$WORK/seed-filter/$d" | cut -f1)"
	fi
done

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
- \`repo.bundle\` the whole git repository, every branch and tag, full
  history. Restore with no GitHub:

      git clone repo.bundle alt-speed-running
      cd alt-speed-running
      git remote set-url origin https://github.com/cwg3/alt-speed-running.git
      git config core.hooksPath .githooks   # a fresh clone does not

- \`submodules/\` one bundle per submodule, because repo.bundle does NOT
  contain submodule content - a restore from it alone leaves
  tools/cubiomes empty, and that is what seedtypes and query build
  against. Restore each into place:

      git clone submodules/tools-cubiomes.bundle tools/cubiomes
      # then, so the parent records it normally again:
      git submodule absorbgitdirs 2>/dev/null || true

- \`seed-filter/output/\` the candidate pools. \`nether_seeds.json\` is the
  one that matters: additive pool loads pair against it, and regenerating
  gives different seeds rather than these.
- \`seed-filter/results/\` every verification run's CSV - the evidence that
  the seeds in the pool were actually checked.

The seed pool is the irreplaceable part: \`seedtypes\` picks a random
start seed on every run, so rebuilding produces a different pool, not
this one.

CONTAINS SEED VALUES, in \`dynamodb/\` and \`seed-filter/\`, and anti-cheat
thresholds in \`secrets/\`. Keep this bundle off anything synced or shared.
EOF

SIZE=$(du -sh "$WORK" | cut -f1)

if [ "$DRY" = 1 ]; then
	echo "--- dry run"
	echo "  would write $SIZE to:"
	echo "    $DEST"
	echo "    $LOCAL/alt-$STAMP"
	[ -n "$DRIVE" ] && echo "    $DRIVE/alt-$STAMP"
	find "$WORK" -maxdepth 2 -type d | sed "s|$WORK|      |"
	exit 0
fi

# 1. S3. Encrypted at rest by the bucket's default (SSE-S3/AES256) - no
# per-object flag needed, and --sse would be the wrong thing to reach for
# since the bucket blocks SSE-C.
echo "--- s3"
aws s3 cp "$WORK" "$DEST" --recursive --quiet || { echo "upload failed" >&2; exit 1; }
echo "  $SIZE -> $DEST"

# 2. This Mac. Copied from WORK rather than downloaded from S3: one source
# means the two copies cannot disagree because of a partial upload.
echo "--- local"
mkdir -p "$LOCAL/alt-$STAMP"
cp -R "$WORK/." "$LOCAL/alt-$STAMP/"
echo "  $SIZE -> $LOCAL/alt-$STAMP"

# 3. The drive, if asked. Verified OFF THE DEVICE afterwards: a cp that
# returned 0 says nothing about what reached flash, because the read back
# would be served from the page cache and match either way.
if [ -n "$DRIVE" ]; then
	echo "--- drive"
	if [ ! -d "$DRIVE" ]; then
		echo "  !! $DRIVE is not mounted - no third copy was written" >&2
	else
		cp -R "$WORK" "$DRIVE/alt-$STAMP"
		sync
		# One manifest at the root covering every bundle, so a single
		# command checks the whole drive. Built with find, because a */*
		# glob misses replays/<matchId>/<uuid>.json.gz one level deeper
		# and yields a manifest that passes while covering half the files.
		# OS metadata is excluded: .Spotlight-V100 churns on every mount
		# and would fail the manifest for no reason.
		( cd "$DRIVE" && find . -type f \
			! -name 'SHA256SUMS.txt' ! -name '.DS_Store' ! -name '._*' \
			! -path './.Spotlight-V100/*' ! -path './.fseventsd/*' \
			! -path './.Trashes/*' \
			| sed 's|^\./||' | sort | xargs shasum -a 256 > "$WORK/SHA256SUMS.txt" )
		cp "$WORK/SHA256SUMS.txt" "$DRIVE/SHA256SUMS.txt"
		sync
		echo "  $SIZE -> $DRIVE/alt-$STAMP"
		echo "  manifest $(wc -l < "$DRIVE/SHA256SUMS.txt" | tr -d ' ') lines"
		# Read the device node BEFORE unmounting. Once the volume is gone
		# so is its mount path, so asking diskutil about $DRIVE afterwards
		# returns nothing, the remount is handed a path instead of a device
		# and fails - which left the drive unmounted and reported the
		# manifest as unverifiable when the copy was in fact fine.
		DEV=$(diskutil info "$DRIVE" 2>/dev/null | awk -F': *' '/Device Node/{print $2}' | tr -d ' ')
		if [ -n "$DEV" ] && diskutil unmount "$DRIVE" >/dev/null 2>&1; then
			diskutil mount "$DEV" >/dev/null 2>&1 || true
			for _ in 1 2 3 4 5 6 7 8 9 10; do
				[ -d "$DRIVE" ] && break
				sleep 1
			done
		else
			echo "  (could not cycle the mount - verifying from cache, which proves less)"
		fi
		if [ ! -d "$DRIVE" ]; then
			echo "  !! $DRIVE did not come back after remount - check it by hand" >&2
		elif ( cd "$DRIVE" && shasum -a 256 -c SHA256SUMS.txt >/dev/null 2>&1 ); then
			echo "  verified off the device: all $(wc -l < "$DRIVE/SHA256SUMS.txt" | tr -d ' ') lines OK"
		else
			echo "  !! manifest did NOT verify - do not trust this copy" >&2
		fi
	fi
fi

echo
echo "backup complete"
echo "  the locked vault copy is not made here - see ~/alt-vault/HOW-TO-UNLOCK.txt"
