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

# The whole command is one quoted string so the *device* shell parses `''` and the
# receiver gets an empty `port_route`, which is what disarms it. Passed as separate
# argv words, `--es port_route ""` loses the empty argument through adb's argument
# joining and `am` fails with
#   IllegalArgumentException: Argument expected after "port_route"
# — so this script never disarmed anything. A run that reaches the end of its route
# restores the clock and the setting itself (ReplayLocationSource.kt:145), which is
# why the gap went unnoticed: only a run stopped early needed this, and that is
# exactly when it did nothing.
"${ADB[@]}" shell \
    "am broadcast -n $APP/com.jellemax.detour.debug.DebugReplayReceiver --es port_route ''" \
    >/dev/null
echo "port disarmed; fixes come from the platform again, clock back to 1x"
"${ADB[@]}" shell "run-as $APP cat files/replay-run.txt" 2>/dev/null | tr -d '\r' || true
