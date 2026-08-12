#!/usr/bin/env bash
#
# Assert that "never reformat inside a move" is still a discipline rather than a build setting.
#
# Why this exists: the whole procedure rests on copying blocks byte for byte, and the proof
# that it worked is that the source file gained zero lines. If a formatter or an .editorconfig
# is ever added to this repo, a save can silently re-indent a moved block, the zero-added-lines
# proof starts failing for reasons that are not the author's fault, and the rule needs to be
# rewritten as a build-configuration question instead. That is a change to the skill, not
# something to work around — so it is worth detecting before the first file is created.
#
# Read-only: greps the working tree.
set -euo pipefail

if [ "$#" -gt 0 ]; then
    echo "usage: $(basename "$0")            # no arguments; run from anywhere" >&2
    exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
cd "$ROOT"

fails=0
check() { # check <description> <expected> <actual>
    if [ "$2" = "$3" ]; then
        printf 'PASS  %s\n' "$1"
    else
        printf 'FAIL  %s (expected "%s", got "%s")\n' "$1" "$2" "$3"
        fails=$((fails + 1))
    fi
}

WORKED=app/src/main/java/com/jellemax/detour/ui/SpinShare.kt
check "the worked example is still on disk ($WORKED)" \
    yes "$([ -f "$WORKED" ] && echo yes || echo no)"
check 'no .editorconfig' no "$([ -e .editorconfig ] && echo yes || echo no)"
check 'no ktlint / spotless / detekt configured' '' \
    "$(grep -rl 'ktlint\|spotless\|detekt' build.gradle.kts app/build.gradle.kts 2>/dev/null \
        | tr '\n' ' ' | sed 's/ $//')"

printf '\n%d checks, %d failed\n' 3 "$fails"
if [ "$fails" -ne 0 ]; then
    cat >&2 <<'EOF'
Stop and rewrite the skill. If a formatter or an .editorconfig has been added, "never reformat
inside a move" is now a build-configuration question, and the zero-added-lines proof needs to
account for whatever the formatter does on save.
EOF
    exit 1
fi

printf '\ncurrent sizes of the realistic next targets:\n'
for f in ui/MapScreen.kt tracking/TripTrackingService.kt ui/SettingsScreen.kt \
    ui/FriendsScreen.kt ui/MapLibreMap.kt ui/CirclesScreen.kt; do
    p="app/src/main/java/com/jellemax/detour/$f"
    [ -f "$p" ] && printf '  %5d  %s\n' "$(wc -l <"$p")" "$p"
done
