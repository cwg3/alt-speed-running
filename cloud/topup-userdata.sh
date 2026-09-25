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
exec > >(tee /var/log/topup.log) 2>&1
echo "=== top-up runner booted $(date -u '+%Y-%m-%dT%H:%M:%SZ') ==="

# instance-initiated-shutdown-behavior=terminate is set on the launch, so
# a shutdown here ends the instance. This trap means it terminates on ANY
# exit path, including a failure - an orchestrator that dies and leaves
# itself running bills until someone notices.
trap 'echo "=== shutting down $(date -u +%H:%M:%SZ) ==="; shutdown -h now' EXIT

export DEBIAN_FRONTEND=noninteractive
dnf install -y git python3 nodejs awscli 2>/dev/null \
  || { apt-get update -qq && apt-get install -y -qq git python3 nodejs npm awscli; }

cd /opt
# Public repo, so no credentials. --depth 1 because history is not needed
# and the pool purge left it large.
git clone --depth 1 https://github.com/cwg3/alt-speed-running.git
cd alt-speed-running

# The pool scripts need the submodule only to BUILD cubiomes, and the
# container image already carries a built seedtypes. Nothing here compiles.
export CLOUD=1
export WORKERS="${WORKERS:-16}"
export FLOOR="${FLOOR:-50}"
export ITYPE="${ITYPE:-m7g.4xlarge}"

cd backend && npm ci --omit=dev --silent 2>&1 | tail -2; cd ..

./seed-filter/topup.sh
echo "=== top-up finished rc=$? $(date -u '+%Y-%m-%dT%H:%M:%SZ') ==="
