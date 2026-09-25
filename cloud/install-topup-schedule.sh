#!/bin/bash
# Installs (or removes) the nightly pool top-up schedule.
#
#   ./install-topup-schedule.sh [--dry-run|--remove]
#
#   AT=03:00        UTC time to run
#   FLOOR=50        drawable seeds each type should have
#   ITYPE_RUNNER    orchestrator size (default t4g.small - it only waits)
#
# EventBridge Scheduler calls ec2:RunInstances directly. There is no
# Lambda because the decision to build already lives in topup.sh, and a
# second copy of that rule in a Lambda would drift from the first.
#
# WHAT THIS COSTS ON A QUIET NIGHT. The runner boots, reads the pool,
# finds every type at its floor and terminates - a couple of minutes of a
# t4g.small. It spends real money only when the pool has actually been
# drawn down, which is the whole point of a floor.
set -uo pipefail
REGION="${REGION:-us-west-2}"
AT="${AT:-03:00}"
FLOOR="${FLOOR:-50}"
ROLE=alt-topup-runner
SCHED_ROLE=alt-topup-scheduler
SCHEDULE=alt-pool-topup
ITYPE_RUNNER="${ITYPE_RUNNER:-t4g.small}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ACCT=$(aws sts get-caller-identity --query Account --output text)
MODE="${1:-}"

if [ "$MODE" = "--remove" ]; then
	aws scheduler delete-schedule --region "$REGION" --name "$SCHEDULE" 2>/dev/null \
		&& echo "schedule $SCHEDULE deleted" || echo "no schedule to delete"
	echo "roles left in place - delete them by hand if you want them gone"
	exit 0
fi

# The runner writes the pool, reads and writes the seedwork bucket, and
# launches and terminates the per-check instances. It can pass only its
# own role, so it cannot escalate into an unrelated one.
RUNNER_POLICY=$(cat <<JSON
{"Version":"2012-10-17","Statement":[
 {"Effect":"Allow","Action":["dynamodb:Scan","dynamodb:PutItem","dynamodb:UpdateItem",
   "dynamodb:DeleteItem","dynamodb:BatchWriteItem","dynamodb:DescribeTable"],
  "Resource":"arn:aws:dynamodb:${REGION}:${ACCT}:table/BackendStack-SeedPoolTable*"},
 {"Effect":"Allow","Action":["s3:GetObject","s3:PutObject","s3:ListBucket","s3:DeleteObject"],
  "Resource":["arn:aws:s3:::alt-seedwork-${ACCT}","arn:aws:s3:::alt-seedwork-${ACCT}/*"]},
 {"Effect":"Allow","Action":["ec2:RunInstances","ec2:DescribeInstances",
   "ec2:DescribeInstanceTypes","ec2:DescribeImages","ec2:CreateTags",
   "ec2:TerminateInstances"],"Resource":"*"},
 {"Effect":"Allow","Action":"iam:PassRole","Resource":"arn:aws:iam::${ACCT}:role/alt-*"},
 {"Effect":"Allow","Action":["ecr:GetAuthorizationToken","ecr:BatchGetImage",
   "ecr:GetDownloadUrlForLayer","ecr:BatchCheckLayerAvailability"],"Resource":"*"}]}
JSON
)

if [ "$MODE" = "--dry-run" ]; then
	echo "=== would create ==="
	echo "  IAM role            $ROLE (+ instance profile)"
	echo "  IAM role            $SCHED_ROLE (scheduler -> ec2:RunInstances)"
	echo "  schedule            $SCHEDULE  cron(${AT#*:} ${AT%:*} * * ? *) UTC = ${AT} UTC"
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

AMI=$(aws ssm get-parameter --region "$REGION" \
	--name /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64 \
	--query 'Parameter.Value' --output text)
UD=$(FLOOR="$FLOOR" python3 - "$ROOT/cloud/topup-userdata.sh" <<'PY'
import base64, os, sys
s = open(sys.argv[1]).read().replace('${FLOOR:-50}', os.environ['FLOOR'])
print(base64.b64encode(s.encode()).decode())
PY
)

echo "=== schedule ==="
aws scheduler create-schedule --region "$REGION" --name "$SCHEDULE" \
	--schedule-expression "cron(${AT#*:} ${AT%:*} * * ? *)" \
	--schedule-expression-timezone UTC \
	--flexible-time-window '{"Mode":"OFF"}' \
	--target "{
		\"Arn\":\"arn:aws:scheduler:::aws-sdk:ec2:runInstances\",
		\"RoleArn\":\"arn:aws:iam::${ACCT}:role/${SCHED_ROLE}\",
		\"Input\":\"{\\\"ImageId\\\":\\\"${AMI}\\\",\\\"InstanceType\\\":\\\"${ITYPE_RUNNER}\\\",\\\"MinCount\\\":1,\\\"MaxCount\\\":1,\\\"InstanceInitiatedShutdownBehavior\\\":\\\"terminate\\\",\\\"IamInstanceProfile\\\":{\\\"Name\\\":\\\"${ROLE}\\\"},\\\"UserData\\\":\\\"${UD}\\\",\\\"TagSpecifications\\\":[{\\\"ResourceType\\\":\\\"instance\\\",\\\"Tags\\\":[{\\\"Key\\\":\\\"Name\\\",\\\"Value\\\":\\\"alt-pool-topup\\\"}]}]}\"
	}" 2>&1 | tail -2 \
	|| aws scheduler update-schedule --region "$REGION" --name "$SCHEDULE" \
		--schedule-expression "cron(${AT#*:} ${AT%:*} * * ? *)" \
		--schedule-expression-timezone UTC \
		--flexible-time-window '{"Mode":"OFF"}' >/dev/null

echo
echo "installed: $SCHEDULE runs daily at ${AT} UTC, floor $FLOOR per type"
echo "  watch:  aws logs / or ssm the instance tagged alt-pool-topup"
echo "  remove: ./install-topup-schedule.sh --remove"
