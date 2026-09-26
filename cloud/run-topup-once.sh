#!/bin/bash
# Launches ONE top-up runner now, the way the schedule would, and follows
# its log out of S3. Exists so the first execution of the user-data is
# watched rather than discovered the next morning.
set -uo pipefail
REGION="${REGION:-us-west-2}"
FLOOR="${FLOOR:-100}"
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
