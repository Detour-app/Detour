#!/usr/bin/env bash
#
# Set the replay speed multiplier — the harness's pacing and the app's clock, in one call.
#
# Why one script rather than two commands: the two halves have to move together. Re-pacing the
# fixes without moving the app's clock is the bug #297 fixed (a 5x stream read as 5x the
# speed); moving the clock without re-pacing is the same lie inverted (the app times a 1 Hz
# drive as though it were five). Either half alone produces a run that looks fine and measures
# wrong, so neither is exposed on its own.
#
# Works before a replay (start-replay.sh takes --speedup for that) and *during* one:
# MockService reads its factor per fix, so a run can be ramped without losing the route
# position or the trip in progress.
#
# This DOES change device state: it re-paces a running service and moves the debug app's
# drive clock. It installs nothing, clears nothing and uninstalls nothing.
set -euo pipefail

MAX=50   # MockService.MAX_SPEEDUP and ScaledClock.MAX_SCALE; keep all three in step.

usage() {
    cat >&2 <<EOF
usage: replay-speed.sh <factor> [--serial <serial>]

  factor    wall-clock multiplier, 1..$MAX. 1 restores real time.
  --serial  adb device serial. Defaults to \$ANDROID_SERIAL, or the only attached device.

Sets both halves: the harness's pacing (if a replay is running) and the debug app's clock.
Only the .debug variant has a clock that can be moved; a release install ignores this.
EOF
    exit 2
}

FACTOR=""
SERIAL="${ANDROID_SERIAL:-}"
while [ "$#" -gt 0 ]; do
    case "$1" in
        -h | --help) usage ;;
        --serial) [ "$#" -ge 2 ] || usage; SERIAL="$2"; shift 2 ;;
        -*) usage ;;
        *) [ -z "$FACTOR" ] || usage; FACTOR="$1"; shift ;;
    esac
done

[ -n "$FACTOR" ] || usage
case "$FACTOR" in
    '' | *[!0-9]* | 0) echo "error: factor must be a positive integer" >&2; exit 2 ;;
esac
if [ "$FACTOR" -gt "$MAX" ]; then
    echo "error: factor $FACTOR exceeds the cap of $MAX (MockService.MAX_SPEEDUP)" >&2
    exit 2
fi

if [ -z "$SERIAL" ]; then
    mapfile -t devs < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
    if [ "${#devs[@]}" -ne 1 ]; then
        echo "error: ${#devs[@]} devices attached — pass --serial" >&2
        adb devices >&2
        exit 2
    fi
    SERIAL="${devs[0]}"
fi
ADB=(adb -s "$SERIAL")
"${ADB[@]}" get-state >/dev/null 2>&1 || { echo "error: $SERIAL not connected" >&2; exit 1; }

HARNESS=com.jellemax.mocklocation
DEBUG_APP=io.github.maxke24.detour.debug

# The app first. A fix paced at the new factor and timed at the old one is a fix that lies;
# the other order leaves the clock ahead of the pacing for one interval instead, which reads
# as a momentarily slow drive rather than a fast one.
if ! "${ADB[@]}" shell am broadcast \
        -n "$DEBUG_APP/com.jellemax.detour.debug.DebugReplayReceiver" \
        --ei speedup "$FACTOR" >/dev/null 2>&1; then
    echo "warning: could not reach $DEBUG_APP's replay clock — is the debug build installed?" >&2
fi

# Then the pacing. No --es route: MockService's re-pacing branch runs ahead of the route
# check precisely so this command needs no route, and a service that is not running simply
# stops again.
"${ADB[@]}" shell am start-foreground-service -n "$HARNESS/.MockService" \
    --ei speedup "$FACTOR" >/dev/null 2>&1 || true

printf 'speed now %sx on %s\n' "$FACTOR" "$SERIAL"
"${ADB[@]}" logcat -d -s MockLocation DebugReplay DetourReplay 2>/dev/null | tail -2 | tr -d '\r'
