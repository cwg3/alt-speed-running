#!/bin/bash
# Launches ONE top-up runner now, the way the schedule would, and follows
# its log out of S3. Exists so the first execution of the user-data is
# watched rather than discovered the next morning.
set -uo pipefail
REGION="${REGION:-us-west-2}"
FLOOR="${FLOOR:-50}"
MAX_CANDIDATES="${MAX_CANDIDATES:-}"
ITYPE_RUNNER="${ITYPE_RUNNER:-t4g.small}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ACCT=$(aws sts get-caller-identity --query Account --output text)
AMI=$(aws ssm get-parameter --region "$REGION" \
	--name /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64 \
	--query 'Parameter.Value' --output text)

UD=$(mktemp)
FLOOR="$FLOOR" MAXC="$MAX_CANDIDATES" python3 - "$ROOT/cloud/topup-userdata.sh" > "$UD" <<'PY'
import os, sys
s = open(sys.argv[1]).read().replace('${FLOOR:-50}', os.environ['FLOOR'])
s = s.replace('${MAX_CANDIDATES:-}', os.environ.get('MAXC', ''))
print(s, end='')
PY

echo "=== launching a $ITYPE_RUNNER runner, floor $FLOOR${MAX_CANDIDATES:+, capped at $MAX_CANDIDATES candidates/type} ==="
ID=$(aws ec2 run-instances --region "$REGION" --image-id "$AMI" \
	--instance-type "$ITYPE_RUNNER" --count 1 \
	--instance-initiated-shutdown-behavior terminate \
	--iam-instance-profile Name=alt-topup-runner \
	--user-data "file://$UD" \
	--tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=alt-pool-topup-test}]' \
	--query 'Instances[0].InstanceId' --output text) || exit 1
rm -f "$UD"
echo "instance $ID"
echo
echo "  log:   aws s3 ls s3://alt-seedwork-${ACCT}/topup-logs/ | tail -1"
echo "  shell: aws ssm start-session --target $ID"
echo "  kill:  aws ec2 terminate-instances --instance-ids $ID"
