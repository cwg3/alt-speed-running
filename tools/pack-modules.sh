#!/bin/bash
# Prints the mod ids the PACK itself installs, read out of the pinned
# Fabric API jar.
#
#   ./tools/pack-modules.sh
#
# The list it prints is pasted into backend/lambda/lib/modRules.ts. Why a
# generated list and not a rule: the client can ask the loader which
# modules are nested inside Fabric API, and a Lambda cannot - it sees a
# flat list of ids. The tempting server-side shortcut is to allow
# anything starting "fabric-", which would wave through a mod called
# fabric-anything. Reading the real ids out of the jar has no such hole.
#
# Re-run this when fabric_api_version changes in mod/gradle.properties.
# The ids are tied to that build, and a module added or renamed upstream
# would otherwise look like an unknown mod to the queue-join check.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR=$(ls "$ROOT"/pack/fabric-api-*.jar 2>/dev/null | head -1)
[ -n "$JAR" ] || { echo "no pack/fabric-api-*.jar - run pack/build-pack.sh first" >&2; exit 1; }
echo "// from $(basename "$JAR")" >&2
python3 - "$JAR" <<'PY'
import io, json, sys, zipfile
jar = sys.argv[1]
z = zipfile.ZipFile(jar)
ids = {json.loads(z.read('fabric.mod.json').decode('utf8', 'replace'))['id']}
for name in z.namelist():
    if not name.endswith('.jar'):
        continue
    sub = zipfile.ZipFile(io.BytesIO(z.read(name)))
    ids.add(json.loads(sub.read('fabric.mod.json').decode('utf8', 'replace'))['id'])
for i in sorted(ids):
    print(f"\t'{i}',")
PY
