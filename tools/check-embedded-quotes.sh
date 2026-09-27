#!/bin/bash
# Refuses a bare double quote inside an embedded `python3 -c "..."` body.
#
# WHY THIS EXISTS. topup.sh builds its plan and its report as python passed
# with -c inside a double-quoted shell word. A bare " in that body - even
# in a PYTHON COMMENT - closes the shell argument, so python receives the
# script truncated at that character, runs the part before it, and exits 0.
#
# Nothing else catches it. `bash -n` passes, because the truncated result
# is still valid shell. A dry run passes, because it returns before the
# report block. Unit-testing the block alone passes, because extracting it
# skips the shell that does the damage. It shipped once exactly that way:
# a comment quoting the phrase it was explaining silently removed the
# VERDICT line from every run, and the run still said OK.
#
#   check-embedded-quotes.sh [file ...]    default: the scripts that embed python
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FILES=("$@")
if [ ${#FILES[@]} -eq 0 ]; then
	FILES=("$ROOT/seed-filter/topup.sh" "$ROOT/seed-filter/verify-and-release.sh" \
	       "$ROOT/seed-filter/overnight-rebuild.sh" "$ROOT/cloud/topup-userdata.sh")
fi
rc=0
for f in "${FILES[@]}"; do
	[ -f "$f" ] || continue
	FILE="$f" python3 -c '
import os, re, sys
path = os.environ["FILE"]
lines = open(path).read().split("\n")
bad = blocks = 0
i = 0
while i < len(lines):
    # An opener is a line ENDING in `python3 -c "` - the body starts on the
    # next line. A one-liner `python3 -c "..."` is not a block and is not
    # scanned; its quoting is visible on the line itself.
    if re.search(r"python3 -c \"$", lines[i]):
        blocks += 1
        j = i + 1
        # The body ends at the first line that STARTS with the closing
        # quote. It is not always a bare `\"`: the plan block closes with
        # `\")` inside a command substitution and the recorder closes with
        # `\" || { ... }`, and assuming a bare quote walked straight past
        # both and swallowed the rest of the file.
        while j < len(lines) and not lines[j].startswith("\""):
            j += 1
        for k in range(i + 1, min(j, len(lines))):
            if "\"" in re.sub(r"\\\"", "", lines[k]):
                print("  !! %s:%d  %s" % (path, k + 1, lines[k].strip()[:88]))
                bad += 1
        i = j
    i += 1
print("%-42s %d block(s), %d bare quote(s)" % (os.path.basename(path), blocks, bad))
sys.exit(1 if bad else 0)
' || rc=1
done
exit $rc
