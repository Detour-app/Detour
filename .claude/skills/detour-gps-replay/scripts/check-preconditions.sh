#!/usr/bin/env bash
#
# Assert that the mock-location harness and the app's speed gates are still what this skill
# describes.
#
# Why this exists: everything in SKILL.md is arithmetic over four numbers that live in two
# source files — the harness's fixed 4 m accuracy, its route-extra name, the app's 25 m trace
# decimation and its 2.0 m/s moving gate. If any of them is retuned, the standstill
# reconstruction and the "why the fixes clear the accuracy gate" section stop being true, and
# a replay built on them produces a confidently wrong A/B. These are cheap to check and
# impossible to notice by eye.
#
# The mocklocation assertion is the inverted one: it must stay at ZERO. tools/mocklocation is
# a standalone Gradle build on purpose ("this is a test harness, not part of Detour, and
# nothing in the app should ever depend on it"), which is why its APK is under
# tools/mocklocation/build/, never under app/build/.
#
# Read-only: greps the working tree. Touches no device.
set -euo pipefail

if [ "$#" -gt 0 ]; then
    echo "usage: $(basename "$0")            # no arguments; run from anywhere" >&2
    exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
cd "$ROOT"

MOCK=tools/mocklocation/src/main/java/com/jellemax/mocklocation/MockService.kt
TRACK=app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt

fails=0
count() { grep -c "$1" "$2" 2>/dev/null || true; }
check() { # check <description> <expected> <actual>
    if [ "$2" = "$3" ]; then
        printf 'PASS  %s\n' "$1"
    else
        printf 'FAIL  %s (expected %s, got %s)\n' "$1" "$2" "$3"
        fails=$((fails + 1))
    fi
}

check 'MockService still reads the "route" string extra' 1 "$(count 'getStringExtra("route")' "$MOCK")"
check 'MockService still reports a fixed accuracy = 4f (clears MAX_START_ACCURACY_M = 25f)' \
    1 "$(count 'accuracy = 4f' "$MOCK")"
check 'the harness is NOT a module of the root build (its APK is under tools/mocklocation/build)' \
    0 "$(count mocklocation settings.gradle.kts)"
check 'the trace is still decimated at 25 m (gap < 25.0)' 1 "$(count 'gap < 25.0' "$TRACK")"
check 'the moving gate is still 2.0 m/s (if (speed > 2.0) lastMovingMs = now)' \
    1 "$(count 'if (speed > 2.0) lastMovingMs = now' "$TRACK")"

printf '\n%d checks, %d failed\n' 5 "$fails"
if [ "$fails" -ne 0 ]; then
    cat >&2 <<'EOF'
Stop. A failure here means the standstill arithmetic in SKILL.md is stale: re-derive the
thresholds from MockService.kt and TripTrackingService.kt, fix the skill, and only then
build a route. A replay against stale gates produces a comparison that looks valid.
EOF
    exit 1
fi
