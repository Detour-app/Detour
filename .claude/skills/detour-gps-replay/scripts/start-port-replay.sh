#!/usr/bin/env bash
#
# Replay a route through the app's own location port (#306) instead of the platform's
# mock provider.
#
# What this rig is for: the app's own arithmetic, at rates the platform cannot deliver.
# Trajectcontrole gating, standstill and auto-stop detection, mode classification, fog of
# war and trip distance are all arithmetic over fixes, and this feeds them as fast as the
# app can consume them -- measured lossless at 50x, where the mock rig delivers 18%.
#
# What it deliberately cannot see, and start-replay.sh still can: provider selection, the
# accuracy gate on a real fix, elapsedRealtimeNanos as the platform stamps it, and fused's
# own blending and thinning. Two of the defects behind #306 lived in exactly that leg. Use
# the other script for those.
#
# No harness app, no mock-location designation, no appops, no root.
set -euo pipefail

ROUTE="${1:-}"
SERIAL="${2:-${ANDROID_SERIAL:-}}"
INTERVAL="${3:-1000}"
SPEEDUP="${4:-1}"
APP=io.github.maxke24.detour.debug
RECEIVER="$APP/com.jellemax.detour.debug.DebugReplayReceiver"
ROUTE_FILE=port-route.txt

if [ -z "$ROUTE" ] || [ ! -f "$ROUTE" ]; then
    echo "usage: $(basename "$0") <route.txt> [serial] [interval-ms] [speedup]" >&2
    exit 2
fi

ADB=(adb); [ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")

# The debug variant only: DebugReplayReceiver and the replay source are both in the debug
# source set, so a release install has no route to either and would silently ignore this.
# Captured first, then matched with `grep -x` -- never `grep -q` inside a pipeline. Under
# `set -o pipefail`, `... | tr | grep -q` reports failure on SUCCESS: grep -q exits the
# moment it matches, tr takes SIGPIPE, and the pipeline's status becomes 141. This package
# sits at line 410 of 495 on a CPH2449, so a match reliably reported "not installed" and
# this script could never start a replay there. -q is omitted below for the same reason:
# it would SIGPIPE the printf feeding it. Without it, grep drains stdin.
installed=$("${ADB[@]}" shell pm list packages | tr -d '\r')
if ! printf '%s\n' "$installed" | grep -x "package:$APP" >/dev/null; then
    echo "$APP is not installed; this rig only exists in the debug variant." >&2
    exit 1
fi

lines=$(grep -cvE '^\s*$' "$ROUTE" || true)
if [ "$lines" -lt 2 ]; then
    echo "$ROUTE: need at least 2 points, found $lines" >&2
    exit 1
fi

# Into the app's OWN files dir -- that is the whole point of the port. The mock rig pushes
# into the harness's directory instead, and needs the harness installed and designated.
"${ADB[@]}" shell "run-as $APP sh -c 'cat > files/$ROUTE_FILE'" < "$ROUTE"
# The redirect has to happen *inside* run-as. Written as `run-as $APP wc -l < files/...`
# the outer shell performs it as uid `shell`, which cannot read the app's private files/ --
# and its cwd is `/`, so the failure reads as "No such file or directory" rather than as a
# permission problem. Under `set -e` the empty command substitution then killed this script
# before the replay was ever broadcast. start-replay.sh:241 always had the quoting right.
pushed=$("${ADB[@]}" shell "run-as $APP sh -c 'wc -l < files/$ROUTE_FILE'" | tr -d '\r ')
echo "pushed $pushed lines into $APP's own files/$ROUTE_FILE"

# The tracking service has to exist before there is a source to arm; the receiver says so
# rather than failing quietly, but starting the app here saves a confusing first run.
"${ADB[@]}" shell am start -n "$APP/com.jellemax.detour.MainActivity" >/dev/null 2>&1 || true

"${ADB[@]}" shell am broadcast -n "$RECEIVER" \
    --es port_route "$ROUTE_FILE" --el interval_ms "$INTERVAL" --ei speedup "$SPEEDUP" >/dev/null

wall=$(( lines * INTERVAL / 1000 / SPEEDUP ))
echo "armed: $lines points at ${INTERVAL}ms, ${SPEEDUP}x = ~${wall}s wall"
echo
echo "  watch:  adb logcat -s DetourPortReplay DetourFixGate DetourReplay"
echo "  count:  adb shell run-as $APP cat files/replay-run.txt   # pushed= vs delivered="
echo "  stop:   $(dirname "$0")/stop-port-replay.sh [serial]"
echo
echo "While armed the fix gate rejects anything not from the port. That is not optional:"
echo "a run without it measured 777 fixes delivered against 768 pushed, because Play"
echo "Services kept delivering real ones after removeLocationUpdates -- and a real fix"
echo "off-route resets the moving clock, so the trip never auto-ends (#47)."
