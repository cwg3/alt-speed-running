#!/bin/bash
# Builds the mod and puts it where the game will actually load it.
#
#   ./mod/install-local.sh [mods-dir]
#
# Exists because `gradlew build` writes mod/build/libs and nothing else.
# The game loads from the launcher's mods folder, so a build alone
# changes nothing you can see - and the failure is silent and
# convincing: the build succeeds, the jar is newer, and the running game
# is still the old one. A leaderboard was "missing" for ten minutes that
# way; the jar in the mods folder was thirteen hours old.
#
# Same family as the stale release assets in TODO.md. "It built" is not
# "it is installed", and neither is "it is deployed".
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
case "$(uname -s)" in
	Darwin) DEFAULT="$HOME/Library/Application Support/minecraft/mods" ;;
	Linux)  DEFAULT="$HOME/.minecraft/mods" ;;
	*)      DEFAULT="$APPDATA/.minecraft/mods" ;;
esac
MODS="${1:-$DEFAULT}"

echo "=== building ==="
( cd "$ROOT/mod" && ./gradlew build -q ) || { echo "build failed" >&2; exit 1; }

JAR=$(ls -t "$ROOT"/mod/build/libs/speedrunmcalt-*.jar 2>/dev/null | grep -v sources | head -1)
[ -n "$JAR" ] || { echo "no jar in mod/build/libs" >&2; exit 1; }

[ -d "$MODS" ] || { echo "mods folder not found: $MODS" >&2; exit 1; }
cp "$JAR" "$MODS/$(basename "$JAR")" || exit 1

echo "installed $(basename "$JAR") -> $MODS"
echo "  $(stat -f '%z bytes, %Sm' "$MODS/$(basename "$JAR")" 2>/dev/null \
    || stat -c '%s bytes, %y' "$MODS/$(basename "$JAR")")"
echo
echo "RESTART MINECRAFT. A running game keeps the jar it started with."
