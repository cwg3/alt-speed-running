#!/bin/bash
# Builds alt-<version>.mrpack - one file a tester imports.
#
#   ./build-pack.sh
#
# Modrinth pack format: a zip holding modrinth.index.json plus an
# overrides/ tree copied into the instance. The launcher reads the
# dependencies block and installs Minecraft and Fabric loader itself,
# which is the whole point - a tester never picks a loader version, and
# cannot pick the wrong one.
#
# Both mods are EMBEDDED in overrides/ rather than referenced by URL.
# The format supports remote files with hashes, and that keeps the pack
# small, but it adds a download that can fail or move. This pack is
# handed to a handful of invited testers; a megabyte is free and "it
# just works offline" is worth more than the saving.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION=$(grep '^version=' "$ROOT/mod/gradle.properties" | cut -d= -f2)
MC=$(grep '^minecraft_version=' "$ROOT/mod/gradle.properties" | cut -d= -f2)
LOADER=$(grep '^loader_version=' "$ROOT/mod/gradle.properties" | cut -d= -f2)
API_URL="https://cdn.modrinth.com/data/P7dR8mSH/versions/0.18.0%2Bbuild.387-1.16.1/fabric-api-0.18.0%2Bbuild.387-1.16.1.jar"

echo "=== building the mod ==="
(cd "$ROOT/mod" && ./gradlew build -q)

WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/overrides/mods"

cp "$ROOT/mod/build/libs/speedrunmcalt-$VERSION.jar" "$WORK/overrides/mods/"

# Fabric API is fetched from Modrinth, not Maven. The maven.fabricmc.net
# coordinate serves a 2101-byte BOM rather than a runnable bundle, and a
# pack built from it installs cleanly and then dies at mod init.
echo "=== fetching fabric-api ==="
curl -sSL -o "$WORK/overrides/mods/fabric-api.jar" "$API_URL"
size=$(stat -f%z "$WORK/overrides/mods/fabric-api.jar" 2>/dev/null || stat -c%s "$WORK/overrides/mods/fabric-api.jar")
if [ "$size" -lt 600000 ]; then
	echo "fabric-api.jar is $size bytes - that is the BOM stub, not the bundle" >&2
	exit 1
fi
echo "  fabric-api.jar $size bytes"

cat > "$WORK/modrinth.index.json" <<JSON
{
  "formatVersion": 1,
  "game": "minecraft",
  "versionId": "$VERSION",
  "name": "alt",
  "summary": "An open, transparent 1v1 ladder for Minecraft $MC speedrunning. Every deviation from vanilla is published.",
  "files": [],
  "dependencies": {
    "minecraft": "$MC",
    "fabric-loader": "$LOADER"
  }
}
JSON

# The raw jars too, for anyone installing into the official Minecraft
# launcher - it cannot import a modpack file, so those users need the
# mods on their own. Same files the pack embeds.
cp "$WORK/overrides/mods/speedrunmcalt-$VERSION.jar" "$ROOT/pack/"
cp "$WORK/overrides/mods/fabric-api.jar" \
   "$ROOT/pack/fabric-api-0.18.0+build.387-1.16.1.jar"

OUT="$ROOT/pack/alt-$VERSION.mrpack"
rm -f "$OUT"
(cd "$WORK" && zip -qr "$OUT" modrinth.index.json overrides)
echo
echo "=== $OUT ==="
unzip -l "$OUT" | tail -6
