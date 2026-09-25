#!/bin/bash
# Nothing may reference a class inside the mixin package.
#
# Classes under com.speedrunmcalt.mixin.* are owned by
# speedrunmcalt.mixins.json. Mixin merges them into their targets, so
# the classes themselves are not loadable: referencing one directly
# fails at class load with
#
#   IllegalClassLoadError: ... is in a defined mixin package ...
#   and cannot be referenced directly
#
# That includes one mixin referencing ANOTHER mixin, and it includes a
# nested class of a mixin - which is what happened: a pickup handoff
# was parked in ReplayPickupMixin$PickupNames and read from a second
# mixin. It compiled, applied cleanly, passed the launch smoke test,
# and killed the game on the first block broken, because the class is
# not touched until an item is collected.
#
# A launch test cannot find this; only every code path could, or this.
# State shared between mixins belongs outside the package - see
# com.speedrunmcalt.match.PendingPickup.
#
# INTERFACES in the package are exempt, and must be. @Accessor and
# @Invoker mixins are interfaces the target is made to implement, and
# casting a vanilla object to one is the entire point of them - this
# codebase does it in eleven places, all legitimate. Only a mixin
# CLASS is merged and unloadable.
set -uo pipefail
cd "$(dirname "$0")"
CLASSES=build/classes/java/main
[ -d "$CLASSES" ] || { echo "no compiled classes - build first"; exit 1; }

BAD=0
while IFS= read -r f; do
  self=$(echo "${f#$CLASSES/}" | sed 's/\.class$//')
  # Outer class: a nested class may legitimately name its own outer.
  outer=${self%%\$*}
  refs=$(javap -p -c "$f" 2>/dev/null \
    | grep -o 'com/speedrunmcalt/mixin/[A-Za-z0-9_$]*' \
    | sort -u)
  for r in $refs; do
    target=${r#com/speedrunmcalt/mixin/}
    [ "${target%%\$*}" = "${outer#com/speedrunmcalt/mixin/}" ] && continue
    # An interface here is an @Accessor/@Invoker, which exists to be
    # cast to. Only a merged mixin CLASS is the problem.
    if javap -p "$CLASSES/com/speedrunmcalt/mixin/$target.class" 2>/dev/null \
         | sed -n '2p' | grep -q 'interface '; then
      continue
    fi
    echo "  $self  ->  com.speedrunmcalt.mixin.$target"
    BAD=1
  done
done < <(find "$CLASSES" -name '*.class')

if [ "$BAD" -ne 0 ]; then
  echo
  echo "FAIL - the references above cross into the mixin package."
  echo "Mixin classes are merged into their targets and cannot be"
  echo "referenced directly. Move shared state outside the package."
  exit 1
fi
echo "PASS - no references into the mixin package"
