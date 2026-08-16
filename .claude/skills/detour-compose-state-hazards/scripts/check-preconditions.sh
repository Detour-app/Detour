#!/usr/bin/env bash
#
# Assert the counts the six hazard sections are written against.
#
# Why this exists: three of these are counts of things that are invisible in a diff — how
# many rememberUpdatedState guards exist, how many independent collectors sit on one
# conflating StateFlow, how many frame loops write snapshot state. If one of them changes,
# the section describing it is describing a file that no longer exists, and nothing in CI
# would say so: there is no Robolectric, no compose-ui-test and no androidTest source set
# here, so the automated gate is the Kotlin compiler and R8 and everything in this skill
# ships past both.
#
# The last two assertions are inverted and load-bearing: this app uses NO derivedStateOf and
# NO snapshotFlow anywhere, and MainActivity handles NO configuration changes itself. §5 and
# §6 both depend on that still being true.
#
# Read-only: greps the working tree.
set -euo pipefail

if [ "$#" -gt 0 ]; then
    echo "usage: $(basename "$0")            # no arguments; run from anywhere" >&2
    exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
cd "$ROOT"

M=app/src/main/java/com/jellemax/detour/ui/MapScreen.kt

fails=0
count() { grep -c "$1" "$2" 2>/dev/null || true; }
check() { # check <description> <expected> <actual>
    if [ "$2" = "$3" ]; then
        printf 'PASS  %s\n' "$1"
    else
        printf 'FAIL  %s (expected "%s", got "%s")\n' "$1" "$2" "$3"
        fails=$((fails + 1))
    fi
}

check 'MapScreen has 9 rememberUpdatedState lines (1 import + 8 uses) — §2' \
    9 "$(count 'rememberUpdatedState' "$M")"
check 'MapScreen has 6 lastFix subscriptions (5 raw collectors + 1 collectAsState…) — §4' \
    6 "$(count 'lastFix.collect' "$M")"
check 'MapScreen has 7 withFrameNanos lines (import + speed/camera/marker lastNs pairs) — §6' \
    7 "$(count 'withFrameNanos' "$M")"
check 'the app still uses NO derivedStateOf and NO snapshotFlow anywhere — §6' \
    '' "$(grep -rl 'derivedStateOf\|snapshotFlow' app/src/main/java/ 2>/dev/null | tr '\n' ' ' | sed 's/ $//')"
check 'MainActivity still handles NO configChanges, so a rotate recreates it — §5' \
    0 "$(count 'configChanges' app/src/main/AndroidManifest.xml)"

printf '\n%d checks, %d failed\n' 5 "$fails"
if [ "$fails" -ne 0 ]; then
    cat >&2 <<'EOF'
The body of SKILL.md is stale for at least one hazard class. Re-derive that section against
the tree before quoting a line number from it — these files move, and a wrong citation in a
report gets cited by later work.
EOF
    exit 1
fi
