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
# SPOT CAPACITY IS NOT A CHECK FAILURE. A reclaimed instance uploads
# nothing, so the run reads exactly like a crash, and the stages already
# paid for upstream - a whole generation stage, in the 15:00 run on
# 2026-09-28 - go in the bin over a capacity shortage that a second ask
# usually clears. So: ask again. Only for a capacity or price
# termination; a watchdog deadline or a crash reproduces, and relaunching
# into it just spends the money twice.
LAUNCH_ATTEMPTS="${LAUNCH_ATTEMPTS:-3}"
RELAUNCH_WAIT="${RELAUNCH_WAIT:-120}"
# The LAST attempt pays on-demand. Retrying spot into a region that has
# just run out tends to find it still out, and the run is already bounded
# by the watchdog, so the worst case is MAX_MINUTES of one instance -
# single-digit dollars against a top-up that otherwise fails every
# twelve hours until capacity returns. ONDEMAND_FALLBACK=0 turns it off
# and keeps every attempt on spot.
ONDEMAND_FALLBACK="${ONDEMAND_FALLBACK:-1}"

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

# WHY THE WATCHDOG REPORTS. A bare shutdown made a deadline look
# identical to a crash, and threw away every completed shard sitting on
# local disk. It now uploads what finished, the log, and a marker, and
# the wait loop below reads that marker.
#
# AND THE CALLER NOW GETS THOSE SHARDS. Uploading them was only ever half
# the fix: the wait loop read the marker, printed where the partials
# were, and exited 1, so they stayed forensics and the caller still saw
# nothing. On 2026-09-28 a portalfilter batch had every shard but one
# finished and uploaded, while that one sat on a single seed until the
# timer ran out. Nearly every verdict existed, in S3, and the top-up threw
# all of them away and reported that the check had produced no rows.
#
# A missing verdict is SAFE HERE, which is what makes harvesting them
# sound rather than merely convenient. Every consumer of these CSVs
# builds a set of the seeds that said PASS and keeps only those
# (overnight-rebuild.sh), so a seed with no row is a seed that does not
# enter the pool - identical in effect to a FAIL, and in the direction
# that cannot contaminate anything. The rule that a check producing NO
# rows has not run still holds, and still stops the build.
#
# --- user data --------------------------------------------------------
# THIS HEREDOC IS UNQUOTED, so $VAR, $( ) and backticks are expanded HERE,
# on this machine, at build time - not on the instance. A COMMENT IN THIS
# BODY IS NOT INERT. The paragraph above used to live inside it and named
# a command in backticks; that ran the command. Since a bare shutdown
# means "in one minute", this orchestrator powered itself off mid-run
# while its own log showed nothing wrong, and left a work instance with
# nobody to collect it.
#
# So: no prose in this block, and no backticks at all. A literal $ must be
# written \$ and anything needing a real backtick belongs outside.
USERDATA=$(cat <<SCRIPT
#!/bin/bash
exec > /var/log/seedwork.log 2>&1
set -x
# Watchdog first, so a hang still ends in a terminated instance.
(
  sleep $((MAX_MINUTES * 60))
  echo "WATCHDOG: $MAX_MINUTES minutes elapsed, giving up"
  aws s3 cp /work/ s3://$BUCKET/$RUN/out/ --recursive \
    --exclude '*' --include 'out-*.csv' --include 'gen-*.json' || true
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

# WAS THIS INSTANCE RECLAIMED? Asked of the SPOT REQUEST, not the
# instance: describe-instances reports a reclaimed box as plainly
# 'terminated', with a StateReason that says Server.SpotInstanceTermination
# only sometimes and not at all once the record ages. The spot request
# keeps the real answer in Status.Code, and keeps it after the instance
# is gone.
spot_status() {
  aws ec2 describe-spot-instance-requests --region "$REGION" \
    --filters "Name=instance-id,Values=$1" \
    --query 'SpotInstanceRequests[0].Status.Code' --output text 2>/dev/null || true
}

# One launch, one wait. Returns:
#   0  the done marker is in the bucket - the batch finished
#   3  terminated by SPOT CAPACITY or price - nothing ran, worth re-asking
#   4  terminated some other way - a watchdog deadline or a crash, whose
#      partial output is in the bucket and gets harvested below
# Anything unreachable still exits the script outright, for the reason
# the loop's own comment gives.
launch_and_wait() {
  local use_spot="$1" MARKET=()
  if [ "$use_spot" = 1 ]; then
    MARKET=(--instance-market-options '{"MarketType":"spot"}')
    echo "launching $ITYPE spot (ami $AMI)"
  else
    echo "launching $ITYPE ON-DEMAND (ami $AMI)"
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

  # A FAILED describe-instances IS NOT A DEAD INSTANCE.
  #
  # This loop used to read `... 2>/dev/null || echo gone` and then treat
  # "gone" exactly like "terminated". An API call that fails says nothing
  # about the instance - and right after run-instances there is a window
  # where describe-instances has not caught up with the new id yet and
  # answers InvalidInstanceID.NotFound.
  #
  # That is what ended the 19:26 top-up on 2026-09-26. The village stage
  # launched at 00:46:35Z; 37 seconds later the run declared "instance
  # ended without writing results", called the check broken, refused to
  # load anything and shut the control host down. The instance it gave up
  # on was fine, and went on working for another hour with nothing left
  # alive to collect it. Four hours of spawn and portalfilter went in the
  # bin over one unlucky API call.
  #
  # So the wait ends on facts only: a successful describe that says
  # 'terminated', or the done marker. Anything else is retried, and if the
  # API stays unreachable the run says THAT, because "I cannot see the
  # instance" and "the instance died" need different responses from
  # whoever reads the log.
  local UNREACHABLE=0
  local UNREACHABLE_MAX=10   # x30s = 5 minutes of silence before giving up
  local state
  while ! aws s3 ls "s3://$BUCKET/$RUN/out/done" >/dev/null 2>&1; do
    if state=$(aws ec2 describe-instances --instance-ids "$IID" \
        --query 'Reservations[0].Instances[0].State.Name' --output text 2>/dev/null) \
        && [ -n "$state" ] && [ "$state" != None ]; then
      UNREACHABLE=0
    else
      # Not an answer about the instance. A throttle, a network blip, or
      # an id the API has not propagated yet - all of which resolve by
      # asking again. A terminated instance stays visible to
      # describe-instances for about an hour, so a genuine termination
      # arrives as the WORD 'terminated', never as a lookup failure.
      state=unknown
      UNREACHABLE=$((UNREACHABLE + 1))
      if [ "$UNREACHABLE" -ge "$UNREACHABLE_MAX" ]; then
        echo "cannot reach the EC2 API - $UNREACHABLE failed lookups in a row" \
          "($((UNREACHABLE * 30))s)."
        echo "  THIS IS NOT A VERDICT ON THE CHECK. The instance is probably still"
        echo "  running and still billing. Look before re-running:"
        echo "    aws ec2 describe-instances --instance-ids $IID \\"
        echo "      --query 'Reservations[0].Instances[0].State.Name' --output text"
        echo "    aws s3 ls s3://$BUCKET/$RUN/out/"
        exit 1
      fi
      sleep 30
      continue
    fi
    if [ "$state" = terminated ]; then
      # It may have written the marker in the moment between the S3 check
      # at the top of this loop and this one - the instance terminates
      # itself the instant it finishes, so that window is real and the
      # results are already in the bucket.
      if aws s3 ls "s3://$BUCKET/$RUN/out/done" >/dev/null 2>&1; then
        return 0
      fi
      # Say WHICH failure this was. "No results" covers a crash, an OOM, a
      # watchdog deadline and a spot reclaim, and they need different
      # responses - the first two are bugs, the third means the batch was
      # bigger than the timer, and the fourth is not about this job at all.
      local sstat
      sstat=$(spot_status "$IID")
      case "$sstat" in
        *no-capacity*|*capacity-oversubscribed*|*terminated-by-price*|*capacity-not-available*)
          echo "the SPOT REQUEST was reclaimed: $sstat"
          echo "  Nothing ran. This is AWS capacity, not a broken check."
          return 3 ;;
      esac
      if aws s3 cp "s3://$BUCKET/$RUN/out/watchdog" - 2>/dev/null; then
        echo "the WATCHDOG stopped it - the batch needed longer than $MAX_MINUTES min."
        echo "  whatever finished is in s3://$BUCKET/$RUN/out/ and is harvested below."
        echo "  if this keeps happening, raise MINUTES_PER_SEED or shard smaller."
      else
        echo "instance ended without writing results and without hitting the watchdog"
        [ -n "$sstat" ] && [ "$sstat" != None ] && echo "  spot request status: $sstat"
      fi
      aws s3 cp "s3://$BUCKET/$RUN/out/seedwork.log" - 2>/dev/null | tail -30 \
        || aws ec2 get-console-output --instance-id "$IID" --output text 2>/dev/null | tail -30 \
        || true
      return 4
    fi
    sleep 30
  done
  return 0
}

attempt=1
while : ; do
  use_spot="$SPOT"
  # The last attempt buys capacity instead of bidding for it.
  if [ "$SPOT" = 1 ] && [ "$ONDEMAND_FALLBACK" = 1 ] \
     && [ "$attempt" -eq "$LAUNCH_ATTEMPTS" ] && [ "$LAUNCH_ATTEMPTS" -gt 1 ]; then
    use_spot=0
    echo "  last attempt - paying on-demand rather than losing the run"
    echo "  (bounded by the $MAX_MINUTES min watchdog; ONDEMAND_FALLBACK=0 to refuse)"
  fi
  [ "$attempt" -gt 1 ] && echo "=== launch attempt $attempt of $LAUNCH_ATTEMPTS ==="
  rc=0; launch_and_wait "$use_spot" || rc=$?
  [ "$rc" != 3 ] && break
  if [ "$attempt" -ge "$LAUNCH_ATTEMPTS" ]; then
    echo "out of launch attempts - spot capacity never came back."
    break
  fi
  # A reclaimed attempt uploaded nothing, but clear out/ anyway: harvesting
  # below globs whatever is there, and a half-written shard from a previous
  # attempt would be counted alongside the same shard from this one.
  aws s3 rm "s3://$BUCKET/$RUN/out/" --recursive --quiet 2>/dev/null || true
  echo "waiting ${RELAUNCH_WAIT}s before asking for capacity again"
  sleep "$RELAUNCH_WAIT"
  attempt=$((attempt + 1))
done

# HARVEST. rc 0 is a finished batch; rc 4 is a batch that stopped early
# and left some of its shards behind. Both are collected the same way -
# the difference is only in how much is there, and that is counted and
# said out loud rather than assumed. rc 3 means no instance ever ran.
if [ "${rc:-0}" = 3 ]; then
	echo "nothing was produced - no instance completed a batch." >&2
	exit 1
fi
PARTIAL=0
[ "${rc:-0}" = 4 ] && PARTIAL=1

RESDIR="$(cd "$(dirname "$0")/.." && pwd)/seed-filter/results"
mkdir -p "$RESDIR"
if [ "$CHECK" = generate ]; then
	dest="$RESDIR/$RUN"
	mkdir -p "$dest"
	aws s3 cp "s3://$BUCKET/$RUN/out/" "$dest/" --recursive --exclude '*' --include 'gen-*.json' --quiet
	if [ -z "$(ls "$dest"/gen-*.json 2>/dev/null)" ]; then
		echo "no generator shards in s3://$BUCKET/$RUN/out/ - nothing to merge." >&2
		exit 1
	fi
	[ "$PARTIAL" = 1 ] && echo "  PARTIAL: merging the $(ls "$dest"/gen-*-overworld.json 2>/dev/null | wc -l | tr -d ' ') shard(s) that finished, of $shards"
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
	# combined.csv only exists on the clean path - the instance writes it
	# after the last container exits. When the batch stopped early there is
	# no combined.csv and the per-shard CSVs are the whole result, so build
	# it here from whatever shards arrived. Same file either way, so no
	# caller has to know which path it came down.
	# Counting rows in a file that may be empty or absent. grep -c exits 1
	# on no match, which under set -e is a dead script rather than a zero.
	_rows() { local c; c=$(grep -cve '^[[:space:]]*$' "$1" 2>/dev/null) || c=0; echo "${c:-0}"; }
	shdir="$TMP/harvest"; mkdir -p "$shdir"
	if ! aws s3 cp "s3://$BUCKET/$RUN/out/combined.csv" "$OUT" --quiet 2>/dev/null; then
		aws s3 cp "s3://$BUCKET/$RUN/out/" "$shdir/" --recursive \
			--exclude '*' --include 'out-shard-*.csv' --quiet 2>/dev/null || true
		cat "$shdir"/out-shard-*.csv > "$OUT" 2>/dev/null || : > "$OUT"
	fi
	got=$(_rows "$OUT")
	if [ "$got" -eq 0 ]; then
		# The original rule, unchanged: no rows at all means the check did
		# not run, and the caller must not build a pool on it.
		echo "=== 0 rows: the '$CHECK' check produced nothing ===" >&2
		exit 1
	fi
	if [ "$PARTIAL" = 1 ] || [ "$got" -lt "$n" ]; then
		# Name the shards that came up short. A total says a seed was
		# missed; WHICH shard says where to look, and a shard that returned
		# nothing at all is a different animal from one that stopped a
		# couple of seeds from the end.
		echo "=== PARTIAL: $got of $n seeds got a verdict ==="
		echo "  the rest were never judged, so they simply do not enter the pool."
		# The per-shard CSVs may not be local yet - combined.csv existing but
		# short is the one way to get here without having downloaded them.
		if [ -z "$(ls "$shdir" 2>/dev/null)" ]; then
			aws s3 cp "s3://$BUCKET/$RUN/out/" "$shdir/" --recursive \
				--exclude '*' --include 'out-shard-*.csv' --quiet 2>/dev/null || true
		fi
		# Only break it down per shard if the per-shard CSVs are actually
		# here. Without them every shard reads as "0 of 10" next to a line
		# saying most of them got a verdict, and a report that contradicts
		# itself is worse than one that admits what it could not fetch.
		if [ -n "$(ls "$shdir" 2>/dev/null)" ]; then
			for f in "$TMP"/shard-*; do
				sn=$(basename "$f"); want=$(_rows "$f"); have=$(_rows "$shdir/out-$sn.csv")
				if [ "$have" -lt "$want" ]; then echo "    $sn: $have of $want"; fi
			done
		else
			echo "    (per-shard CSVs not in the bucket - cannot say which shards)"
		fi
		echo "  full output, including any worker log: s3://$BUCKET/$RUN/out/"
	fi
	echo "=== $got rows -> $OUT ==="
	awk -F, '{print $2}' "$OUT" | sort | uniq -c | sort -rn | head
fi
