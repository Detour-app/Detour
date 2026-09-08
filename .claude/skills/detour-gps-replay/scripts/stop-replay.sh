#!/usr/bin/env bash
#
# Stop the mock-location replay cleanly and show what the harness logged.
#
# Why this exists as a script rather than a remembered command: `am stopservice` and
# `am force-stop` look interchangeable and are not. MockService calls removeTestProvider on
# all four providers (gps, fused, network, passive) from onDestroy. `am stopservice` runs
# onDestroy; force-stopping the process does not, which leaves the device pinned to a stale
# mock position — the phone then quietly reports the last replayed coordinate as its real
# location until something clears it.
#
# The recovery from that state is to start the service again and stop it properly, because
# onStartCommand calls removeTestProvider before re-adding each provider. That is what
# --recover does.
#
# This DOES change device state: it stops a service (and with --recover, briefly starts it).
# It installs nothing, clears nothing and uninstalls nothing.
set -euo pipefail

usage() {
    cat >&2 <<'EOF'
usage: stop-replay.sh [serial] [--recover]

  serial      adb device serial. Defaults to $ANDROID_SERIAL, or the only attached device.
  --recover   the device is stuck at a mock position because the harness was force-stopped:
              start the service and stop it again so removeTestProvider actually runs.
EOF
    exit 2
}

SERIAL=""
RECOVER=0
for arg in "$@"; do
    case "$arg" in
        -h | --help) usage ;;
        --recover) RECOVER=1 ;;
        -*) usage ;;
        *) [ -z "$SERIAL" ] || usage; SERIAL="$arg" ;;
    esac
done

HARNESS=com.jellemax.mocklocation
RELEASE=io.github.maxke24.detour
[ -n "$SERIAL" ] || SERIAL="${ANDROID_SERIAL:-}"
if [ -z "$SERIAL" ]; then
    mapfile -t devs < <(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
    if [ "${#devs[@]}" -ne 1 ]; then
        echo "error: ${#devs[@]} devices attached; pass a serial explicitly" >&2
        adb devices >&2
        exit 2
    fi
    SERIAL="${devs[0]}"
fi
ADB=(adb -s "$SERIAL")
"${ADB[@]}" get-state >/dev/null 2>&1 || { echo "error: $SERIAL not connected" >&2; exit 1; }

if [ "$RECOVER" = 1 ]; then
    echo "recover: starting the service so onStartCommand clears the stale test providers"
    "${ADB[@]}" shell am start-foreground-service -n "$HARNESS/.MockService" >/dev/null || true
fi

# `|| true` and not by preference: `am stopservice` exits non-zero with "Service not stopped:
# was not running", which is the *normal* case once a replay has run to completion — and under
# `set -e` that aborted this script here, silently skipping every restore below it. That is how
# a release app stayed disabled after a run and how the app stayed in mock-only mode at 5x.
"${ADB[@]}" shell am stopservice -n "$HARNESS/.MockService" 2>&1 | tr -d '\r' || true

# Everything that puts the device back is in restore(), on an EXIT trap, so it runs even when
# something above it fails. A cleanup script that only cleans up on the happy path is worse
# than none: it is the one you stop checking.
restore() {
    # Undo start-replay.sh's disable, and only its own — the marker it left is the evidence
    # that this script put the app in that state, so a release app the rider disabled for
    # their own reasons is left alone. One shell string: the multi-argument form of run-as
    # loses the command and reports success against the data directory root instead.
    if "${ADB[@]}" shell "run-as $HARNESS cat files/release-disabled" 2>/dev/null | grep -q 1; then
        "${ADB[@]}" shell pm enable "$RELEASE" 2>&1 | tr -d '\r' || true
        "${ADB[@]}" shell "run-as $HARNESS rm -f files/release-disabled" >/dev/null 2>&1 || true
        if "${ADB[@]}" shell pm list packages -d 2>/dev/null | tr -d '\r' | grep -qx "package:$RELEASE"; then
            echo "WARNING: $RELEASE is still disabled. Re-enable it by hand:" >&2
            echo "  adb -s $SERIAL shell pm enable $RELEASE" >&2
        fi
    fi

    # Back to real time and back to believing the world. Both live in the app's own process,
    # so a replay that ended at 5x in mock-only mode would otherwise leave the next trip — a
    # hand-driven one included — recording five minutes of drive per wall minute while
    # ignoring every real fix. Silent when the debug build is absent.
    "${ADB[@]}" shell am broadcast \
        -n io.github.maxke24.detour.debug/com.jellemax.detour.debug.DebugReplayReceiver \
        --ez mock_only false --ei speedup 1 >/dev/null 2>&1 || true
}
trap restore EXIT
echo
echo "last MockLocation log lines:"
"${ADB[@]}" logcat -d -s MockLocation | tail -15
echo
echo "The test providers are removed by onDestroy. If the device still reports a route"
echo "position, re-run with --recover."
