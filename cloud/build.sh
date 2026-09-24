#!/bin/bash
# Builds the mod and bakes it into the seed-check image.
#
#   ./build.sh [platform]     default linux/arm64
#
# arm64 because the dev machine is arm64 and the target is Graviton
# spot, which is the cheapest way to buy these CPU-hours. Nothing here
# is architecture-specific; pass linux/amd64 for an x86 fleet.
set -euo pipefail
PLATFORM="${1:-linux/arm64}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "=== building the mod ==="
(cd "$ROOT/mod" && ./gradlew build -q)

# The REMAPPED production jar - what players run - not the dev classes
# Loom runs against yarn mappings.
cp "$ROOT/mod/build/libs/speedrunmcalt-0.1.0.jar" "$ROOT/cloud/speedrunmcalt.jar"

echo "=== building the image ($PLATFORM) ==="
docker build --platform "$PLATFORM" -t altseed:latest "$ROOT/cloud"
docker images altseed:latest --format '  {{.Repository}}:{{.Tag}}  {{.Size}}'
