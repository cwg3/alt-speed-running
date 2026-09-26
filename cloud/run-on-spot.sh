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
# m7g, not c7g. Both are 16 vCPUs, but c7g.4xlarge has 32GiB and the
# standard 16 workers at 2G each do not fit - the memory guard below
# refuses the launch, the check produces no rows, and the caller sees an
# empty CSV rather than an error. verify-and-release.sh carried a comment
# explaining this and passed the type explicitly; run-check.sh did not,
# so every pool-build check silently refused to launch. Fixing the
# DEFAULT fixes both callers and any future one.
ITYPE="${4:-m7g.4xlarge}"
REGION="${REGION:-us-west-2}"
# Left EMPTY on purpose. A caller's value wins; otherwise it is derived
# from the batch once the shard size is known, below. It used to default
# to a flat 180 here, which is what silently decapitated a bigger batch.
MAX_MINUTES="${MAX_MINUTES:-}"
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

# Does WORKERS x HEAP actually fit the instance?
#
# The first cloud run did not: 16 workers at the default 2G heap on a
# c7g.4xlarge is 32GiB of heap on a 32GiB machine, and the kernel
# OOM-killed containers mid-batch. vCPU count is the obvious number to
# size workers by and it is the wrong one - these are JVMs generating
# chunks, so MEMORY binds first.
# Only the JVM checks are memory-bound. generate runs seedtypes, a C
# binary with no heap at all, so applying the JVM arithmetic to it
# refuses instance types that would be entirely fine - which is
# exactly what happened the first time it ran.
if [ "$CHECK" = generate ]; then
	echo "memory: generate runs a C binary, no heap - check skipped"
else
MEM_MIB=$(aws ec2 describe-instance-types --region "$REGION" \
  --instance-types "$ITYPE" --query 'InstanceTypes[0].MemoryInfo.SizeInMiB' --output text)
case "$HEAP" in
	*G|*g) HEAP_MIB=$(( ${HEAP%[Gg]} * 1024 )) ;;
	*M|*m) HEAP_MIB=${HEAP%[Mm]} ;;
	*)     HEAP_MIB=$(( HEAP / 1048576 )) ;;
esac
# Each JVM needs roughly its heap again for metaspace, GC structures,
# thread stacks and direct buffers; leave 2GiB for the OS and docker.
NEED_MIB=$(( WORKERS * HEAP_MIB * 13 / 10 + 2048 ))
echo "memory: $WORKERS x $HEAP needs ~${NEED_MIB}MiB, $ITYPE has ${MEM_MIB}MiB"
if [ "$NEED_MIB" -gt "$MEM_MIB" ]; then
	fit=$(( (MEM_MIB - 2048) * 10 / 13 / HEAP_MIB ))
	echo
	echo "REFUSING TO LAUNCH: this will OOM-kill containers mid-batch." >&2
	echo "  drop to $fit workers, lower HEAP, or pick a larger instance." >&2
	exit 1
fi
fi

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
# "I cannot see it" is not "it is not there". The check used to be
# `get-role || create-role`, so an identity without iam:GetRole fell
# straight through to CreateRole and failed with AccessDenied on the
# CREATE - which reads as though the role were missing when it has
# existed for days. The nightly runner hit exactly that, and the real
# problem was one missing read permission.
#
# Creating a role is a SETUP step. A scheduled runner should not have
# that power, so when it cannot confirm the role it must say which of the
# two situations it is in and stop.
_role_err=$(aws iam get-role --role-name "$ROLE" 2>&1 >/dev/null) || _role_missing=1
if [ "${_role_missing:-0}" = 1 ] && ! printf '%s' "$_role_err" | grep -q NoSuchEntity; then
  echo "ERROR: cannot verify IAM role $ROLE." >&2
  printf '  %s\n' "$_role_err" >&2
  echo "  The role probably exists and this identity lacks iam:GetRole on it." >&2
  echo "  Creating roles is a setup step; run this once from an identity that can." >&2
  exit 1
fi
[ "${_role_missing:-0}" = 1 ] && {
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

# WATCHDOG BUDGET. This was a flat value, sized for the batches of the
# day it was written, and a larger batch was silently lost to it: raising
# the pool floor grew the batch, the instance ran into the timer, shut
# itself down having written nothing at all, and the orchestrator could
# only report "ended without writing results" - which reads like a crash
# rather than a deadline. Every finished shard went with it.
#
# Shards run in PARALLEL, so the wall clock is set by the LONGEST one -
# `per`, not $n. MINUTES_PER_SEED is measured rather than guessed, and
# then doubled; spawn is the slowest check, so applying its rate to every
# check errs long. That is the right direction for a timer whose only job
# is to stop a hang from billing forever: too short destroys finished
# work, too long costs pennies.
MINUTES_PER_SEED="${MINUTES_PER_SEED:-4}"
BOOT_MINUTES="${BOOT_MINUTES:-15}"
if [ -z "$MAX_MINUTES" ]; then
	MAX_MINUTES=$(( per * MINUTES_PER_SEED + BOOT_MINUTES ))
	[ "$MAX_MINUTES" -lt 180 ] && MAX_MINUTES=180
fi
echo "  watchdog $MAX_MINUTES min (longest shard is $per seeds)"

# --- user data --------------------------------------------------------
USERDATA=$(cat <<SCRIPT
#!/bin/bash
exec > /var/log/seedwork.log 2>&1
set -x
# Watchdog first, so a hang still ends in a terminated instance. It now
# SAYS it fired, and salvages what finished: a bare `shutdown` made a
# deadline look identical to a crash, and threw away every completed
# shard sitting on local disk.
(
  sleep $((MAX_MINUTES * 60))
  echo "WATCHDOG: $MAX_MINUTES minutes elapsed, giving up"
  aws s3 cp /work/ s3://$BUCKET/$RUN/out/ --recursive \
    --exclude '*' --include 'out-*.csv' || true
  echo "watchdog fired after $MAX_MINUTES minutes" > /work/watchdog
  aws s3 cp /work/watchdog s3://$BUCKET/$RUN/out/watchdog || true
  aws s3 cp /var/log/seedwork.log s3://$BUCKET/$RUN/out/seedwork.log || true
  shutdown -h now
) &
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
cat /work/out-*.csv > /work/combined.csv 2>/dev/null || : > /work/combined.csv
aws s3 cp /work/combined.csv s3://$BUCKET/$RUN/out/combined.csv
aws s3 cp /work/ s3://$BUCKET/$RUN/out/ --recursive --exclude '*' --include 'out-*.csv'
# generate mode writes JSON, not csv rows. Uploading only out-*.csv
# would have finished cleanly and left the whole run's output on a
# terminated instance.
aws s3 cp /work/ s3://$BUCKET/$RUN/out/ --recursive --exclude '*' --include 'gen-*.json'
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
    # Say WHICH failure this was. "No results" covers a crash, an OOM and
    # a watchdog deadline, and they need different fixes - the first two
    # are bugs, the third just means the batch was bigger than the timer.
    if aws s3 cp "s3://$BUCKET/$RUN/out/watchdog" - 2>/dev/null; then
      echo "the WATCHDOG stopped it - the batch needed longer than $MAX_MINUTES min."
      echo "  partial shards, if any, are in s3://$BUCKET/$RUN/out/"
      echo "  re-run with a bigger MINUTES_PER_SEED or fewer seeds per shard."
    else
      echo "instance ended without writing results and without hitting the watchdog"
    fi
    aws s3 cp "s3://$BUCKET/$RUN/out/seedwork.log" - 2>/dev/null | tail -30 \
      || aws ec2 get-console-output --instance-id "$IID" --output text 2>/dev/null | tail -30 \
      || true
    exit 1
  fi
  sleep 30
done

RESDIR="$(cd "$(dirname "$0")/.." && pwd)/seed-filter/results"
mkdir -p "$RESDIR"
if [ "$CHECK" = generate ]; then
	dest="$RESDIR/$RUN"
	mkdir -p "$dest"
	aws s3 cp "s3://$BUCKET/$RUN/out/" "$dest/" --recursive --exclude '*' --include 'gen-*.json' --quiet
	echo "=== $(ls "$dest" | grep -c overworld) shards -> $dest ==="
	python3 - "$dest" <<'PYEOF'
import json, sys, glob, os
d = sys.argv[1]
merged, nether = {}, []
for f in sorted(glob.glob(os.path.join(d, 'gen-*-overworld.json'))):
    for t, vs in json.load(open(f)).items():
        merged.setdefault(t, []).extend(vs)
for f in sorted(glob.glob(os.path.join(d, 'gen-*-nether.json'))):
    n = json.load(open(f))
    nether.extend(n if isinstance(n, list) else n.get('seeds', []))
seen, uniq = set(), {}
for t, vs in merged.items():
    uniq[t] = [v for v in vs if not (v['seed'] in seen or seen.add(v['seed']))]
json.dump(uniq, open(os.path.join(d, 'overworld_by_type.json'), 'w'), indent=2)
json.dump(nether, open(os.path.join(d, 'nether_seeds.json'), 'w'), indent=2)
for t in sorted(uniq):
    print('  %-16s %d' % (t, len(uniq[t])))
print('  nether seeds     %d' % len(nether))
PYEOF
else
	OUT="$RESDIR/$RUN.csv"
	aws s3 cp "s3://$BUCKET/$RUN/out/combined.csv" "$OUT" --quiet
	echo "=== $(wc -l < "$OUT" | tr -d ' ') rows -> $OUT ==="
	awk -F, '{print $2}' "$OUT" | sort | uniq -c | sort -rn | head
fi
