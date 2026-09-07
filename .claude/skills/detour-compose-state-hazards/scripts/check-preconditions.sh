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

# MapScreen.kt and the six files the state-ownership split (#228) delegated its
# effects to. Counted together: the split moved lines, it did not remove any of
# the machinery these three assertions exist to watch.
UI=app/src/main/java/com/jellemax/detour/ui
M="$UI/MapScreen.kt $UI/MapCamera.kt $UI/MapHazardAlerts.kt $UI/MapHazardPrefetch.kt \
   $UI/MapNavigation.kt $UI/MapPermissions.kt $UI/MapCircleMembers.kt"

fails=0
# shellcheck disable=SC2086 — $2 is a list on purpose
count() { cat $2 2>/dev/null | grep -c "$1" || true; }
check() { # check <description> <expected> <actual>
    if [ "$2" = "$3" ]; then
        printf 'PASS  %s\n' "$1"
    else
        printf 'FAIL  %s (expected "%s", got "%s")\n' "$1" "$2" "$3"
        fails=$((fails + 1))
    fi
}

check 'the map files have 18 rememberUpdatedState lines (5 imports + 13 uses) — §2' \
    18 "$(count 'rememberUpdatedState' "$M")"
check 'the map files have 9 lastFix subscriptions (5 raw collectors + 4 collectAsState…) — §4' \
    9 "$(count 'lastFix.collect' "$M")"
check 'the map files have 8 withFrameNanos lines (MapCamera: import + speed/camera/marker pairs + one seed) — §6' \
    8 "$(count 'withFrameNanos' "$M")"
check 'derivedStateOf is used in exactly one place (MapScreen navState, #189) and snapshotFlow nowhere — §6' \
    'app/src/main/java/com/jellemax/detour/ui/MapScreen.kt' "$(grep -rl 'derivedStateOf\|snapshotFlow' app/src/main/java/ 2>/dev/null | tr '\n' ' ' | sed 's/ $//')"
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
