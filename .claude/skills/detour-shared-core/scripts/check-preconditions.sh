#!/usr/bin/env bash
#
# Assert the claims this skill makes about the module graph and commonMain's constraints.
#
# Why this exists: the skill's placement advice is only correct while these hold. One of them
# is an inverted assertion — commonMain must contain ZERO Dispatchers — and an inverted
# assertion is exactly the kind a reader "checks" by glancing at a grep that printed nothing,
# which is also what a mistyped path prints. Running them as pass/fail removes that ambiguity.
# The interface count is no longer inverted-to-zero: commonMain has exactly three — Prefs,
# RelaySocket and BearerSource — and the check pins it to those three files so a fourth appearing
# anywhere is still caught. The two newer ones arrived with the shared convoy relay and each earns
# its keep by the same CONTRIBUTING.md:40 test Prefs does — more than one implementation, and
# genuine platform swaps rather than one class behind an indirection:
#
#   RelaySocket   OkHttpRelaySocket.kt (Android), UrlSessionRelaySocket.swift (iOS), a fake in
#                 ConvoyRelayTest. The transport seam the relay runs against.
#   BearerSource  BearerSource(Auth::bearer) (Android), AuthBearerSource() (Swift), a lambda in
#                 ConvoyRelayTest. A `fun interface` rather than a `suspend () -> String` for two
#                 reasons that are both about the Swift export: a Kotlin suspend *function type*
#                 lowers to KotlinSuspendFunction0, which a Swift closure literal cannot satisfy,
#                 and a function type gives @Throws nowhere to attach — which is how Auth.bearer
#                 once reached Swift unannotated, where an unmarked throw terminates the process.
#
# The wear/ edge is the one that changes decisions most often: "shared" here means phone +
# Android Auto + iOS, NOT the watch. Putting logic in shared/ does not give wear/ access to
# it; that would need a new Gradle edge first, which is a build-file change, not a move.
#
# Read-only: greps the working tree.
set -euo pipefail

if [ "$#" -gt 0 ]; then
    echo "usage: $(basename "$0")            # no arguments; run from anywhere" >&2
    exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
cd "$ROOT"

COMMON=shared/src/commonMain
PLATFORM=$COMMON/kotlin/com/jellemax/detour/data/Platform.kt
RELAYSOCKET=$COMMON/kotlin/com/jellemax/detour/drive/RelaySocket.kt
CONVOYRELAY=$COMMON/kotlin/com/jellemax/detour/drive/ConvoyRelay.kt
ANGLES=$COMMON/kotlin/com/jellemax/detour/data/Angles.kt

fails=0
count() { grep -c "$1" "$2" 2>/dev/null || true; }
files_with() { grep -rl "$1" "$COMMON" 2>/dev/null | tr '\n' ' ' | sed 's/ $//'; }
# Same, but blind to comments. The Dispatchers assertion needs this and nothing else does:
# the rule is documented at the sites that obey it, so five files say "commonMain has no
# Dispatchers" in prose and a raw grep reports all five as violations. The rule's own
# documentation was failing the rule's own check, which is the most misleading way for a
# precondition to break — it reads as real drift.
files_with_code() {
    grep -rl "$1" "$COMMON" 2>/dev/null | while read -r f; do
        sed 's,//.*,,' "$f" | grep -v '^[[:space:]]*/\?\*' | grep -q "$1" && printf '%s\n' "$f"
    done | tr '\n' ' ' | sed 's/ $//'
}
# A bare `interface` is a dependency-inversion seam and CONTRIBUTING.md:40 says one earns its
# keep only with more than one implementation. A `sealed interface` is not that: it is a closed
# sum type whose implementations all sit in the same file and none of which is a platform swap,
# and it is this repo's established way of returning a decision — NavPolicy.Decision,
# GroupSpinRules.SpinRoundOutcome and CameraAuthority.Action all use it. Matching the bare
# string caught those too, which failed the check for following the house pattern.
files_with_open_interface() {
    grep -rl 'interface ' "$COMMON" 2>/dev/null | while read -r f; do
        grep -h 'interface ' "$f" | grep -qv 'sealed interface ' && printf '%s\n' "$f"
    done | tr '\n' ' ' | sed 's/ $//'
}
check() { # check <description> <expected> <actual>
    if [ "$2" = "$3" ]; then
        printf 'PASS  %s\n' "$1"
    else
        printf 'FAIL  %s (expected "%s", got "%s")\n' "$1" "$2" "$3"
        fails=$((fails + 1))
    fi
}

check 'the ONLY file with an expect declaration is Platform.kt' \
    "$PLATFORM" "$(files_with 'expect ')"
check 'Platform.kt declares exactly 4 expects (a fifth is the signal to push the dependency in)' \
    4 "$(count '^expect ' "$PLATFORM")"
check 'commonMain has ZERO Dispatchers in code — make the function suspend and let the caller choose' \
    '' "$(files_with_code 'Dispatchers')"
check 'commonMain has exactly THREE non-sealed interfaces (Prefs, RelaySocket, BearerSource — each with >1 implementation, CONTRIBUTING.md:40)' \
    "$CONVOYRELAY $RELAYSOCKET $PLATFORM" "$(files_with_open_interface)"
check 'wear/ does NOT depend on :shared (so "shared" does not reach the watch)' \
    0 "$(count 'project(":shared")' wear/build.gradle.kts)"
check 'app/ DOES depend on :shared' 1 "$(count 'project(":shared")' app/build.gradle.kts)"
check 'commonMain has a wall clock: internal fun nowMs() exists in Angles.kt' \
    1 "$(count 'internal fun nowMs' "$ANGLES")"

# Line numbers drift and drift is not staleness — report it rather than asserting it.
printf '\nnowMs() is currently at %s\n' \
    "$(grep -n 'internal fun nowMs' "$ANGLES" | cut -d: -f1 | sed "s|^|$ANGLES:|")"
printf '%d checks, %d failed\n' 7 "$fails"
if [ "$fails" -ne 0 ]; then
    echo "The section of SKILL.md that depends on the failed assertion is stale." >&2
    echo "Re-derive it from the tree before trusting the body." >&2
    exit 1
fi
