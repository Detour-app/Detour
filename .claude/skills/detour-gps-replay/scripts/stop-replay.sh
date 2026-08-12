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

"${ADB[@]}" shell am stopservice -n "$HARNESS/.MockService" | tr -d '\r'
echo
echo "last MockLocation log lines:"
"${ADB[@]}" logcat -d -s MockLocation | tail -15
echo
echo "The test providers are removed by onDestroy. If the device still reports a route"
echo "position, re-run with --recover."
