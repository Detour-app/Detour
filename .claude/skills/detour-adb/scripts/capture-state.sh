#!/usr/bin/env bash
#
# Capture what the device is showing right now: screenshot, UI hierarchy, logcat tail.
#
# Why this exists: an unverified device claim is this project's known failure mode — a wrong
# claim in a report gets cited by later work and costs commits to undo. The fix is to name an
# artifact for every assertion, which means capturing all three at the same moment, before
# the screen changes. Doing that by hand is three commands with three different output
# conventions (`exec-out` for binary, a device-side temp file for uiautomator, a flag for the
# logcat buffer), and the ordering matters because the screenshot must precede anything that
# might dismiss a dialog.
#
# The uiautomator dump goes to /dev/tty, not to /sdcard — dumping to a file would leave a
# stray w.xml on the user's device. Nothing here writes to the device or to the repo.
#
# Read-only. Output goes to the directory you name, which should be the session scratchpad.
set -euo pipefail

usage() {
    cat >&2 <<'EOF'
usage: capture-state.sh <outdir> [serial [logcat-tag...]]

  outdir        directory to write into; created if missing. Use the session scratchpad,
                never the repo — screenshots of a real map are movement data.
  serial        adb device serial. Defaults to $ANDROID_SERIAL, or the only attached device.
                Pass it explicitly if you want to name logcat tags after it.
  logcat-tag    one or more tags for `logcat -s`, e.g. DebugTripEnded MockLocation.
                Default: the tail of the whole buffer.

Writes <outdir>/<stamp>-shot.png, -ui.xml and -logcat.txt.
EOF
    exit 2
}

[ "$#" -ge 1 ] || usage
if [ "$1" = "-h" ] || [ "$1" = "--help" ]; then usage; fi

OUTDIR="$1"
shift
SERIAL="${1:-${ANDROID_SERIAL:-}}"
if [ "$#" -gt 0 ]; then shift; fi
TAGS=("$@")

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

# Fail before creating any output file, so a dropped cable does not leave a 0-byte
# "screenshot" in the scratchpad for someone to later mistake for a capture.
if ! "${ADB[@]}" get-state >/dev/null 2>&1; then
    echo "error: device $SERIAL is not connected" >&2
    adb devices >&2
    exit 1
fi

mkdir -p "$OUTDIR"
STAMP="$(date +%Y%m%d-%H%M%S)"
BASE="$OUTDIR/$STAMP"

# Screenshot first: the UI dump takes a second or two, and anything that changes on screen
# in between should be visible as a difference between the two artifacts, not hidden by
# having captured the hierarchy of a screen the picture does not show.
"${ADB[@]}" exec-out screencap -p >"$BASE-shot.png"

"${ADB[@]}" exec-out uiautomator dump /dev/tty 2>/dev/null | tr -d '\r' >"$BASE-ui.xml"

if [ "${#TAGS[@]}" -gt 0 ]; then
    "${ADB[@]}" logcat -d -s "${TAGS[@]}" >"$BASE-logcat.txt"
else
    "${ADB[@]}" logcat -d -t 2000 >"$BASE-logcat.txt"
fi

for f in "$BASE-shot.png" "$BASE-ui.xml" "$BASE-logcat.txt"; do
    printf '%-12s %s\n' "$(wc -c <"$f" | tr -d ' ') bytes" "$f"
done

# A 0-byte or tiny PNG usually means the screen is off or a secure surface is showing.
if [ "$(wc -c <"$BASE-shot.png")" -lt 2000 ]; then
    echo "warning: the screenshot is suspiciously small — screen off, or FLAG_SECURE?" >&2
fi

echo
echo "Grep the UI dump for the text you intend to assert, e.g.:"
echo "  grep -o 'text=\"[^\"]*\"' $BASE-ui.xml | sort -u | head -40"
