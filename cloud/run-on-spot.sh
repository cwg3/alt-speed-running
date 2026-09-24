#!/bin/bash
# Runs a batch of seed checks on one Graviton spot instance, then
# terminates it.
#
#   ./run-on-spot.sh <check> <seeds-file> [workers] [instance-type]
#
#   check        spawn | ravine | nether | route | portalfilter
#   seeds-file   "seed [x] [z] [type]" per line
#
# What this creates, and nothing else:
#   - an S3 bucket  alt-seedwork-<account>   (job input and results)
#   - an IAM role   alt-seedwork             (ECR pull, S3 on that bucket)
#   - one SPOT instance, which TERMINATES ITSELF when the work is done
#
# The instance is the only thing here that can cost real money if it is
# forgotten, so it cannot be forgotten by accident:
#   - instance-initiated-shutdown-behavior=terminate, and the user-data
#     ends in `shutdown -h now`
#   - a watchdog terminates it after MAX_MINUTES whatever happens
#   - no SSH, no inbound rules, no key pair
#
# Results land in s3://<bucket>/<run>/out/ and are pulled back here.
set -euo pipefail

CHECK="${1:?usage: run-on-spot.sh <check> <seeds-file> [workers] [type]}"
SEEDS="${2:?}"
WORKERS="${3:-16}"
ITYPE="${4:-c7g.4xlarge}"
REGION="${REGION:-us-west-2}"
MAX_MINUTES="${MAX_MINUTES:-180}"
# Heap PER CONTAINER. Must fit the instance: a t4g.small has 2GB total,
# so one worker at 1400m leaves room for the OS and docker. Chunk
# generation over a wide radius is the memory-hungry part.
HEAP="${HEAP:-2G}"
# Spot is blocked on the AWS Free Plan along with every non-free-tier
# instance type. On-demand for a free-tier type is covered by the
# monthly free hours; SPOT=0 switches to it.
SPOT="${SPOT:-1}"

ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
BUCKET="alt-seedwork-$ACCOUNT"
IMAGE="$ACCOUNT.dkr.ecr.$REGION.amazonaws.com/altseed:latest"
RUN="$CHECK-$(date +%Y%m%d-%H%M%S)"
ROLE=alt-seedwork

n=$(grep -cve '^[[:space:]]*$' "$SEEDS")
echo "=== $RUN: $n seeds, $CHECK, $WORKERS workers on $ITYPE ==="

# --- bucket -----------------------------------------------------------
aws s3api head-bucket --bucket "$BUCKET" 2>/dev/null || {
  echo "creating s3://$BUCKET"
  aws s3api create-bucket --bucket "$BUCKET" --region "$REGION" \
    --create-bucket-configuration LocationConstraint="$REGION" >/dev/null
  aws s3api put-public-access-block --bucket "$BUCKET" \
    --public-access-block-configuration \
    BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
  # Job files and CSVs are worthless a week later; do not pay to keep them.
  aws s3api put-bucket-lifecycle-configuration --bucket "$BUCKET" \
    --lifecycle-configuration '{"Rules":[{"ID":"expire","Status":"Enabled","Filter":{"Prefix":""},"Expiration":{"Days":30}}]}'
}

# --- role -------------------------------------------------------------
aws iam get-role --role-name "$ROLE" >/dev/null 2>&1 || {
  echo "creating IAM role $ROLE"
  aws iam create-role --role-name "$ROLE" --assume-role-policy-document \
    '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}' >/dev/null
  aws iam attach-role-policy --role-name "$ROLE" \
    --policy-arn arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly
  aws iam put-role-policy --role-name "$ROLE" --policy-name s3-seedwork \
    --policy-document "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":[\"s3:GetObject\",\"s3:PutObject\",\"s3:ListBucket\"],\"Resource\":[\"arn:aws:s3:::$BUCKET\",\"arn:aws:s3:::$BUCKET/*\"]}]}"
  aws iam create-instance-profile --instance-profile-name "$ROLE" >/dev/null
  aws iam add-role-to-instance-profile --instance-profile-name "$ROLE" --role-name "$ROLE"
  echo "waiting for the instance profile to propagate"; sleep 15
}

# --- split the work ---------------------------------------------------
# One shard per worker, so each container gets a contiguous slice and
# the whole batch is one `docker run` per shard.
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
grep -ve '^[[:space:]]*$' "$SEEDS" > "$TMP/all.txt"
per=$(( (n + WORKERS - 1) / WORKERS ))
split -l "$per" -d -a 3 "$TMP/all.txt" "$TMP/shard-"
shards=$(ls "$TMP" | grep -c '^shard-')
aws s3 cp "$TMP/" "s3://$BUCKET/$RUN/in/" --recursive --exclude '*' --include 'shard-*' --quiet
echo "uploaded $shards shards of up to $per seeds"

# --- user data --------------------------------------------------------
USERDATA=$(cat <<SCRIPT
#!/bin/bash
exec > /var/log/seedwork.log 2>&1
set -x
# Watchdog first, so a hang still ends in a terminated instance.
( sleep $((MAX_MINUTES * 60)); shutdown -h now ) &
dnf install -y docker awscli-2 || yum install -y docker
systemctl start docker
aws ecr get-login-password --region $REGION | docker login --username AWS --password-stdin $IMAGE
docker pull $IMAGE
mkdir -p /work && cd /work
aws s3 cp s3://$BUCKET/$RUN/in/ /work/ --recursive
for s in /work/shard-*; do
  name=\$(basename \$s)
  docker run -d --rm -v /work:/work \
    -e CHECK=$CHECK -e IN=/work/\$name -e OUT=/work/out-\$name.csv \
    -e HEAP=$HEAP \
    $IMAGE
done
while [ "\$(docker ps -q | wc -l)" -gt 0 ]; do sleep 20; done
cat /work/out-*.csv > /work/combined.csv
aws s3 cp /work/combined.csv s3://$BUCKET/$RUN/out/combined.csv
aws s3 cp /work/ s3://$BUCKET/$RUN/out/ --recursive --exclude '*' --include 'out-*.csv'
echo DONE > /work/done && aws s3 cp /work/done s3://$BUCKET/$RUN/out/done
shutdown -h now
SCRIPT
)

AMI=$(aws ssm get-parameter --region "$REGION" \
  --name /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64 \
  --query Parameter.Value --output text)

if [ "$SPOT" = 1 ]; then
  MARKET=(--instance-market-options '{"MarketType":"spot"}')
  echo "launching $ITYPE spot (ami $AMI)"
else
  MARKET=()
  echo "launching $ITYPE on-demand (ami $AMI) - free-tier hours"
fi
IID=$(aws ec2 run-instances --region "$REGION" \
  --image-id "$AMI" --instance-type "$ITYPE" --count 1 \
  ${MARKET[@]+"${MARKET[@]}"} \
  --iam-instance-profile "Name=$ROLE" \
  --instance-initiated-shutdown-behavior terminate \
  --metadata-options 'HttpTokens=required,HttpEndpoint=enabled' \
  --block-device-mappings '[{"DeviceName":"/dev/xvda","Ebs":{"VolumeSize":40,"VolumeType":"gp3","DeleteOnTermination":true}}]' \
  --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=alt-seedwork-$RUN},{Key=run,Value=$RUN}]" \
  --user-data "$USERDATA" \
  --query 'Instances[0].InstanceId' --output text)

echo "instance $IID"
echo
echo "  watch:      aws ec2 describe-instances --instance-ids $IID --query 'Reservations[0].Instances[0].State.Name' --output text"
echo "  results:    aws s3 cp s3://$BUCKET/$RUN/out/combined.csv ."
echo "  kill now:   aws ec2 terminate-instances --instance-ids $IID"
echo
echo "waiting for results (the instance terminates itself when done)"
while ! aws s3 ls "s3://$BUCKET/$RUN/out/done" >/dev/null 2>&1; do
  state=$(aws ec2 describe-instances --instance-ids "$IID" \
    --query 'Reservations[0].Instances[0].State.Name' --output text 2>/dev/null || echo gone)
  if [ "$state" = terminated ] || [ "$state" = gone ]; then
    echo "instance ended without writing results - check /var/log/seedwork.log via console output"
    aws ec2 get-console-output --instance-id "$IID" --output text 2>/dev/null | tail -30 || true
    exit 1
  fi
  sleep 30
done

OUT="$(cd "$(dirname "$0")/.." && pwd)/seed-filter/results/$RUN.csv"
mkdir -p "$(dirname "$OUT")"
aws s3 cp "s3://$BUCKET/$RUN/out/combined.csv" "$OUT" --quiet
echo "=== $(wc -l < "$OUT" | tr -d ' ') rows -> $OUT ==="
awk -F, '{print $2}' "$OUT" | sort | uniq -c | sort -rn | head
