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

# The C candidate generator and its library, compiled inside the image
# so the binary matches the image architecture rather than the Mac's.
rm -rf "$ROOT/cloud/cubiomes"
mkdir -p "$ROOT/cloud/cubiomes"
# Copy the tree, do not enumerate it. Listing *.c and *.h by hand
# missed the lowercase makefile and then the tables/ directory of
# generated headers, each failing a layer deep in the image build
# rather than here. Object files and the archive are excluded so the
# image never links the Mac's binaries.
rsync -a --exclude '*.o' --exclude '*.a' --exclude 'docs/' \
  "$ROOT/tools/cubiomes/" "$ROOT/cloud/cubiomes/"
cp "$ROOT/seed-filter/seedtypes.c" "$ROOT/cloud/seedtypes.c"

echo "=== building the image ($PLATFORM) ==="
docker build --platform "$PLATFORM" -t altseed:latest "$ROOT/cloud"
docker images altseed:latest --format '  {{.Repository}}:{{.Tag}}  {{.Size}}'
