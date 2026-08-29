#!/usr/bin/env bash
#
# Assert that the decimation contract this skill is built on is still in force.
#
# Why this exists: every threshold in SKILL.md, and every default in profile-trace.py, is
# derived from one line — `if (gap < 25.0) return` in TripTrackingService.addTracePoint. If
# that is retuned, "a standstill produces no points at all" stops being true, the stop
# detector's 12 s / 8 km/h defaults stop being justified, and the analysis still produces
# confident-looking numbers. This has already produced wrong conclusions twice in this
# project's history, both times from reading a trace as if it were a fix log.
#
# Read-only: greps the working tree.
set -euo pipefail

if [ "$#" -gt 0 ]; then
    echo "usage: $(basename "$0")            # no arguments; run from anywhere" >&2
    exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
cd "$ROOT"

TRACK=app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt
STORE=shared/src/commonMain/kotlin/com/jellemax/detour/data/TraceStore.kt
IOS=iosApp/Detour/TripRecorder.swift

fails=0
count() { grep -c "$1" "$2" 2>/dev/null || true; }
present() { grep -q "$1" "$2" 2>/dev/null && echo 1 || echo 0; }
check() { # check <description> <expected> <actual>
    if [ "$2" = "$3" ]; then
        printf 'PASS  %s\n' "$1"
    else
        printf 'FAIL  %s (expected %s, got %s)\n' "$1" "$2" "$3"
        fails=$((fails + 1))
    fi
}

check 'Android decimates the trace at 25 m (if (gap < 25.0) return)' \
    1 "$(count 'if (gap < 25.0) return' "$TRACK")"
check 'TraceStore still writes traces.jsonl' 1 "$(count 'traces.jsonl' "$STORE")"
check 'iOS uses the same 25 m spacing, so an iOS trace reads the same way' \
    1 "$(count 'traceSpacingMeters = 25.0' "$IOS")"
# The constant above passing proves nothing about *where* traces.jsonl lands — it moved from
# filesDir's root into the per-account bucket (files/accounts/<key>/) without the filename
# changing, which is exactly the kind of move this assertion would otherwise miss.
check 'traces.jsonl is written into the per-account bucket, not straight into filesDir (TraceStore calls accountFile, not deviceFile)' \
    1 "$(present 'accountFile(FILE_NAME)' "$STORE")"
check 'TraceStore never falls back to the device-wide file for traces.jsonl' \
    0 "$(present 'deviceFile(FILE_NAME)' "$STORE")"

printf '\n%d checks, %d failed\n' 5 "$fails"
if [ "$fails" -ne 0 ]; then
    cat >&2 <<'EOF'
Stop. The decimation contract has been retuned, so every threshold in this skill — and the
12 s / 8 km/h stop-detection defaults in profile-trace.py — is stale. Re-derive them from
TripTrackingService.addTracePoint before quoting any number computed from a trace.
EOF
    exit 1
fi
