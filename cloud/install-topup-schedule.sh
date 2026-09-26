#!/bin/bash
# Installs (or removes) the recurring pool top-up schedule.
#
#   ./install-topup-schedule.sh [--dry-run|--remove]
#
#   AT=03:00,15:00  UTC times to run, comma separated. Cron has a single
#                   minute field, so every entry must share a minute.
#   FLOOR=100       drawable seeds each type should have
#   ITYPE_RUNNER    orchestrator size (default t4g.small - it only waits)
#
# EventBridge Scheduler calls ec2:RunInstances directly. There is no
# Lambda because the decision to build already lives in topup.sh, and a
# second copy of that rule in a Lambda would drift from the first.
#
# WHAT THIS COSTS ON A QUIET RUN. The runner boots, reads the pool,
# finds every type at its floor and terminates - a couple of minutes of a
# t4g.small. It spends real money only when the pool has actually been
# drawn down, which is the whole point of a floor. That is also why the
# cadence is cheap to raise: twice a day is not twice the cost, it is
# twice the number of chances to notice a type is short, and on a pool
# nobody has drawn down both are free.
#
# TWICE A DAY, because a run is capped and an expensive type therefore
# converges on its floor over several runs rather than one. Halving the
# interval halves how long a type sits below its floor. A run finishes
# well inside the gap between two, so they cannot overlap.
set -uo pipefail
REGION="${REGION:-us-west-2}"
AT="${AT:-03:00,15:00}"
FLOOR="${FLOOR:-100}"
ROLE=alt-topup-runner
SCHED_ROLE=alt-topup-scheduler
SCHEDULE=alt-pool-topup
ITYPE_RUNNER="${ITYPE_RUNNER:-t4g.small}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ACCT=$(aws sts get-caller-identity --query Account --output text)
MODE="${1:-}"

# One cron expression covering every time in AT. Cron has a single
# minute field shared by all its hours, so "03:00,15:30" is not one
# schedule and is rejected here rather than silently running both at :00.
CRON=$(AT="$AT" python3 - <<'PYCRON'
import os, sys
ats = [a.strip() for a in os.environ['AT'].split(',') if a.strip()]
if not ats:
    sys.exit('AT is empty')
mins, hours = set(), []
for a in ats:
    h, sep, m = a.partition(':')
    if not sep or not h.isdigit() or not m.isdigit():
        sys.exit(f'AT entry {a!r} is not HH:MM')
    h, m = int(h), int(m)
    if not (0 <= h < 24 and 0 <= m < 60):
        sys.exit(f'AT entry {a!r} is out of range')
    mins.add(m)
    if h not in hours:
        hours.append(h)
if len(mins) > 1:
    sys.exit(f'every AT entry must share a minute, got {sorted(mins)}')
print(f"cron({mins.pop()} {','.join(str(h) for h in sorted(hours))} * * ? *)")
PYCRON
) || exit 1

# --roles-only creates the IAM pieces and stops, so the runner can be
# launched by hand once before anything recurring exists. The first
# execution of this user-data should be watched, not scheduled.

if [ "$MODE" = "--remove" ]; then
	aws scheduler delete-schedule --region "$REGION" --name "$SCHEDULE" 2>/dev/null \
		&& echo "schedule $SCHEDULE deleted" || echo "no schedule to delete"
	echo "roles left in place - delete them by hand if you want them gone"
	exit 0
fi

# The runner writes the pool, reads and writes the seedwork bucket, and
# launches and terminates the per-check instances. It can pass only its
# own role, so it cannot escalate into an unrelated one.
#
# It also READS the players table, because the shortfall is measured in
# unseen seeds per active player rather than pool depth. Read-only and a
# separate statement: nothing in a top-up should be able to write a
# player's rating, record or seenSeeds, and keeping it apart from the
# pool's read-write grant means widening one cannot silently widen the
# other.
RUNNER_POLICY=$(cat <<JSON
{"Version":"2012-10-17","Statement":[
 {"Effect":"Allow","Action":["dynamodb:Scan","dynamodb:PutItem","dynamodb:UpdateItem",
   "dynamodb:DeleteItem","dynamodb:BatchWriteItem","dynamodb:DescribeTable"],
  "Resource":"arn:aws:dynamodb:${REGION}:${ACCT}:table/BackendStack-SeedPoolTable*"},
 {"Effect":"Allow","Action":["dynamodb:Scan"],
  "Resource":"arn:aws:dynamodb:${REGION}:${ACCT}:table/BackendStack-PlayersTable*"},
 {"Effect":"Allow","Action":["s3:GetObject","s3:PutObject","s3:ListBucket","s3:DeleteObject"],
  "Resource":["arn:aws:s3:::alt-seedwork-${ACCT}","arn:aws:s3:::alt-seedwork-${ACCT}/*"]},
 {"Effect":"Allow","Action":["ec2:RunInstances","ec2:DescribeInstances",
   "ec2:DescribeInstanceTypes","ec2:DescribeImages","ec2:CreateTags",
   "ec2:TerminateInstances"],"Resource":"*"},
 {"Effect":"Allow","Action":"iam:PassRole","Resource":"arn:aws:iam::${ACCT}:role/alt-*"},
 {"Effect":"Allow","Action":["iam:GetRole","iam:GetInstanceProfile"],
  "Resource":["arn:aws:iam::${ACCT}:role/alt-*",
              "arn:aws:iam::${ACCT}:instance-profile/alt-*"]},
 {"Effect":"Allow","Action":["ecr:GetAuthorizationToken","ecr:BatchGetImage",
   "ecr:GetDownloadUrlForLayer","ecr:BatchCheckLayerAvailability"],"Resource":"*"}]}
JSON
)

if [ "$MODE" = "--dry-run" ]; then
	echo "=== would create ==="
	echo "  IAM role            $ROLE (+ instance profile)"
	echo "  IAM role            $SCHED_ROLE (scheduler -> ec2:RunInstances)"
	echo "  schedule            $SCHEDULE  $CRON UTC = ${AT} UTC"
	echo "  runner instance     $ITYPE_RUNNER, terminates itself"
	echo "  floor               $FLOOR per type"
	echo
	echo "=== runner policy ==="
	printf '%s\n' "$RUNNER_POLICY" | python3 -m json.tool
	echo
	echo "=== user-data that would run ==="
	echo "  cloud/topup-userdata.sh  ($(wc -l < "$ROOT/cloud/topup-userdata.sh") lines)"
	exit 0
fi

echo "=== runner role ==="
aws iam get-role --role-name "$ROLE" >/dev/null 2>&1 || {
	aws iam create-role --role-name "$ROLE" --assume-role-policy-document \
		'{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}' >/dev/null
	aws iam attach-role-policy --role-name "$ROLE" \
		--policy-arn arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore
	aws iam create-instance-profile --instance-profile-name "$ROLE" >/dev/null
	aws iam add-role-to-instance-profile --instance-profile-name "$ROLE" --role-name "$ROLE"
	echo "  created $ROLE"
}
aws iam put-role-policy --role-name "$ROLE" --policy-name topup \
	--policy-document "$RUNNER_POLICY"
echo "  policy attached"

echo "=== scheduler role ==="
aws iam get-role --role-name "$SCHED_ROLE" >/dev/null 2>&1 || {
	aws iam create-role --role-name "$SCHED_ROLE" --assume-role-policy-document \
		'{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"scheduler.amazonaws.com"},"Action":"sts:AssumeRole"}]}' >/dev/null
	echo "  created $SCHED_ROLE"
}
aws iam put-role-policy --role-name "$SCHED_ROLE" --policy-name run-topup \
	--policy-document "{\"Version\":\"2012-10-17\",\"Statement\":[
	 {\"Effect\":\"Allow\",\"Action\":\"ec2:RunInstances\",\"Resource\":\"*\"},
	 {\"Effect\":\"Allow\",\"Action\":\"ec2:CreateTags\",\"Resource\":\"*\"},
	 {\"Effect\":\"Allow\",\"Action\":\"iam:PassRole\",\"Resource\":\"arn:aws:iam::${ACCT}:role/${ROLE}\"}]}"

if [ "$MODE" = "--roles-only" ]; then
	echo
	echo "roles ready. Launch one runner by hand with:"
	echo "  ./cloud/run-topup-once.sh"
	exit 0
fi

AMI=$(aws ssm get-parameter --region "$REGION" \
	--name /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64 \
	--query 'Parameter.Value' --output text)
UD=$(FLOOR="$FLOOR" python3 - "$ROOT/cloud/topup-userdata.sh" <<'PY'
import base64, os, sys
# Exact full-line match, and FAIL if it is not there. A .replace() that
# matches nothing returns the string unchanged, so a drifted placeholder
# shipped user-data that silently used the file's own default floor
# instead of the one asked for - a wrong floor that looks like a success.
src = open(sys.argv[1]).read()
_old = 'export FLOOR="${FLOOR:-100}"'
if _old not in src:
    sys.exit('FLOOR placeholder not found in topup-userdata.sh - refusing '
             'to ship user-data with an unknown floor')
s = src.replace(_old, 'export FLOOR="%s"' % os.environ['FLOOR'])
print(base64.b64encode(s.encode()).decode())
PY
)

echo "=== schedule ==="
TARGET="{
	\"Arn\":\"arn:aws:scheduler:::aws-sdk:ec2:runInstances\",
	\"RoleArn\":\"arn:aws:iam::${ACCT}:role/${SCHED_ROLE}\",
	\"Input\":\"{\\\"ImageId\\\":\\\"${AMI}\\\",\\\"InstanceType\\\":\\\"${ITYPE_RUNNER}\\\",\\\"MinCount\\\":1,\\\"MaxCount\\\":1,\\\"InstanceInitiatedShutdownBehavior\\\":\\\"terminate\\\",\\\"IamInstanceProfile\\\":{\\\"Name\\\":\\\"${ROLE}\\\"},\\\"UserData\\\":\\\"${UD}\\\",\\\"TagSpecifications\\\":[{\\\"ResourceType\\\":\\\"instance\\\",\\\"Tags\\\":[{\\\"Key\\\":\\\"Name\\\",\\\"Value\\\":\\\"alt-pool-topup\\\"}]}]}\"
}"

# Ask first, then create or update. This used to be create || update, and
# both halves were broken: the create was piped into tail, so || tested
# TAIL's status and the update never ran; and the update passed no
# --target, which the API requires. Between them, re-running this script
# against an existing schedule silently changed nothing - so a cadence
# edit here looked applied and was not.
if aws scheduler get-schedule --region "$REGION" --name "$SCHEDULE" >/dev/null 2>&1; then
	VERB=update-schedule
	echo "  $SCHEDULE exists - updating it in place"
else
	VERB=create-schedule
	echo "  creating $SCHEDULE"
fi
aws scheduler "$VERB" --region "$REGION" --name "$SCHEDULE" \
	--schedule-expression "$CRON" \
	--schedule-expression-timezone UTC \
	--flexible-time-window '{"Mode":"OFF"}' \
	--target "$TARGET" >/dev/null || {
		echo "!! scheduler $VERB failed - the schedule is unchanged" >&2
		exit 1
	}

echo
echo "installed: $SCHEDULE runs at ${AT} UTC ($CRON), floor $FLOOR per type"
echo "  watch:  aws logs / or ssm the instance tagged alt-pool-topup"
echo "  remove: ./install-topup-schedule.sh --remove"
