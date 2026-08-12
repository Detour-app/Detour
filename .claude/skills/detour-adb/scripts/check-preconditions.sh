#!/usr/bin/env bash
#
# Assert that the package-identity table in SKILL.md still matches the build files.
#
# Why this exists: the Kotlin namespace (com.jellemax.detour) and the installed
# applicationId (io.github.maxke24.detour[.debug]) are deliberately different, so every
# component name in the skill was copied out of app/build.gradle.kts rather than out of the
# source. If those declarations move or change, the skill's `am start -n …` lines become
# silently wrong and fail with "Activity class does not exist" — which reads like a build
# problem and is not. Failing loudly here costs a second; debugging that costs ten minutes.
#
# Read-only: greps the working tree. Touches no device.
set -euo pipefail

if [ "$#" -gt 0 ]; then
    echo "usage: $(basename "$0")            # no arguments; run from anywhere" >&2
    exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
cd "$ROOT"

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

check 'release applicationId io.github.maxke24.detour is declared once' \
    1 "$(count 'applicationId = "io.github.maxke24.detour"' app/build.gradle.kts)"
check 'debug applicationIdSuffix ".debug" is declared once' \
    1 "$(count 'applicationIdSuffix = ".debug"' app/build.gradle.kts)"

recv=app/src/debug/java/com/jellemax/detour/debug/DebugTripEndedReceiver.kt
check "$recv exists (debug-only intents are real)" \
    yes "$([ -f "$recv" ] && echo yes || echo no)"

printf '\n%d checks, %d failed\n' 3 "$fails"
if [ "$fails" -ne 0 ]; then
    echo "The identity table in SKILL.md is stale. Re-derive it from app/build.gradle.kts" >&2
    echo "and fix the skill BEFORE running any adb command from it." >&2
    exit 1
fi
