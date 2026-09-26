#!/bin/bash
# Boot script for the nightly top-up runner. EventBridge Scheduler calls
# ec2:RunInstances with this as user-data; the instance decides for itself
# whether there is anything to build, does it, and terminates.
#
# WHY NO LAMBDA. The decision - is any type below its floor - already
# lives in seed-filter/topup.sh and is tested there. Putting a copy in a
# Lambda would be a second implementation of the same rule, and the two
# would drift. So the schedule launches this, topup.sh decides, and a
# night with nothing to do costs a couple of minutes of a small instance.
#
# WHY THIS IS NOT THE CHECK INSTANCE. The orchestrator needs git, python,
# node and the AWS CLI; it does NOT need Java, Gradle or Minecraft,
# because CLOUD=1 sends every check to its own spot instance running the
# container image. So this stays small and cheap while the heavy work
# happens on machines that exist only as long as a check takes.
set -uo pipefail
LOG=/var/log/topup.log
exec > >(tee "$LOG") 2>&1
STAMP=$(date -u '+%Y%m%d-%H%M%S')
echo "=== top-up runner booted $(date -u '+%Y-%m-%dT%H:%M:%SZ') ==="

# The log has to leave the instance, because the instance does not
# outlive the run. Without this a failed night is indistinguishable from
# a quiet one: nothing built, nothing to read, nobody told. Pushed every
# 30s and once more on the way out, so even a hang leaves evidence of how
# far it got.
ACCT=$(aws sts get-caller-identity --query Account --output text 2>/dev/null)
BUCKET="alt-seedwork-${ACCT}"
S3LOG="s3://${BUCKET}/topup-logs/${STAMP}.log"
echo "log -> $S3LOG"
( while :; do sleep 30; aws s3 cp "$LOG" "$S3LOG" --quiet 2>/dev/null || true; done ) &
PUSHER=$!
push_log() { kill "$PUSHER" 2>/dev/null || true; aws s3 cp "$LOG" "$S3LOG" --quiet 2>/dev/null || true; }

# instance-initiated-shutdown-behavior=terminate is set on the launch, so
# a shutdown here ends the instance. This trap means it terminates on ANY
# exit path, including a failure - an orchestrator that dies and leaves
# itself running bills until someone notices.
trap 'echo "=== shutting down $(date -u +%H:%M:%SZ) ==="; push_log; shutdown -h now' EXIT

# ONE RUNNER AT A TIME. The schedule fires every 12h and a catch-up run
# on a raised floor can take most of that, so two orchestrators
# overlapping stopped being hypothetical the moment the floor went up.
# Two at once would both load HELD rows and both release them, double the
# spend, and interleave their logs.
#
# The check is "is another instance tagged alt-pool-topup* still
# running", not a lock file, because there is no lock to go stale: an
# orchestrator that dies stops being running and the next fire proceeds.
# The wildcard also covers the -test tag from run-topup-once.sh, so a
# manual run and a scheduled one cannot collide either.
TOK=$(curl -sf -X PUT http://169.254.169.254/latest/api/token \
        -H 'X-aws-ec2-metadata-token-ttl-seconds: 300' 2>/dev/null)
SELF=$(curl -sf -H "X-aws-ec2-metadata-token: ${TOK:-}" \
        http://169.254.169.254/latest/meta-data/instance-id 2>/dev/null)
if [ -n "$SELF" ]; then
	# Without a known SELF the != filter below would be != '', which
	# matches every instance including this one - so the guard is
	# skipped rather than run wrong. A missed run costs one cycle; a
	# runner that stands down against its own reflection costs every
	# cycle, silently, and looks exactly like a quiet night.
	OTHERS=$(aws ec2 describe-instances --region "${REGION:-us-west-2}" \
		--filters 'Name=tag:Name,Values=alt-pool-topup*' \
		          'Name=instance-state-name,Values=pending,running' \
		--query "Reservations[].Instances[?InstanceId!='${SELF}'].InstanceId" \
		--output text 2>/dev/null | tr -d '[:space:]')
	if [ -n "$OTHERS" ]; then
		echo "=== another top-up runner is already going - standing down ==="
		echo "=== top-up SKIPPED $(date -u '+%Y-%m-%dT%H:%M:%SZ') : overlap ==="
		exit 0
	fi
	echo "  overlap guard: no other runner"
else
	echo "  note: could not read own instance-id - overlap guard skipped"
fi

export DEBIAN_FRONTEND=noninteractive
# gcc and make are for seedtypes, which is compiled and NOT in git.
dnf install -y git python3 nodejs gcc make 2>/dev/null \
  || { apt-get update -qq && apt-get install -y -qq git python3 nodejs npm gcc make; }

cd /opt
# Public repo, so no credentials. Shallow, but WITH submodules: cubiomes
# is one, and seedtypes will not build without its headers and archive.
git clone --depth 1 --recurse-submodules --shallow-submodules \
  https://github.com/cwg3/alt-speed-running.git
cd alt-speed-running

# Build the candidate generator, exactly as cloud/Dockerfile does.
echo "=== building seedtypes ==="
make -C tools/cubiomes release >/dev/null 2>&1
( cd seed-filter && cc -O3 -o seedtypes seedtypes.c \
    ../tools/cubiomes/libcubiomes.a -lm -lpthread )
if [ ! -x seed-filter/seedtypes ]; then
  echo "!! seedtypes did not build - cannot generate candidates" >&2
  exit 1
fi
echo "  built $(stat -c%s seed-filter/seedtypes) bytes"

# The pool scripts need the submodule only to BUILD cubiomes, and the
# container image already carries a built seedtypes. Nothing here compiles.
export CLOUD=1
export WORKERS="${WORKERS:-16}"
export FLOOR="${FLOOR:-100}"
export MAX_CANDIDATES="${MAX_CANDIDATES:-}"
export ITYPE="${ITYPE:-m7g.4xlarge}"

cd backend && npm ci --omit=dev --silent 2>&1 | tail -2; cd ..

./seed-filter/topup.sh
RC=$?
if [ "$RC" -eq 0 ]; then
  echo "=== top-up OK $(date -u '+%Y-%m-%dT%H:%M:%SZ') ==="
else
  # `rc=$?` straight after the call reported 0 on a failed build once,
  # because nothing in the chain propagated the error and the empty-input
  # guard then said "nothing to do" - which is what a QUIET night says.
  # A failure that reads as a quiet night is the worst of both.
  echo "=== top-up FAILED rc=$RC $(date -u '+%Y-%m-%dT%H:%M:%SZ') ===" >&2
fi
exit "$RC"
