#!/usr/bin/env bash
#
# Disarm the location port (#306) and hand the app back to the platform.
#
# Also restores the drive clock to 1x and auto-detect drives to whatever it was, both of
# which the port sets while it runs. A run that reaches the end of its route does this
# itself; this is for stopping one early.
set -euo pipefail

SERIAL="${1:-${ANDROID_SERIAL:-}}"
APP=io.github.maxke24.detour.debug
ADB=(adb); [ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")

"${ADB[@]}" shell am broadcast \
    -n "$APP/com.jellemax.detour.debug.DebugReplayReceiver" --es port_route "" >/dev/null
echo "port disarmed; fixes come from the platform again, clock back to 1x"
"${ADB[@]}" shell "run-as $APP cat files/replay-run.txt" 2>/dev/null | tr -d '\r' || true
