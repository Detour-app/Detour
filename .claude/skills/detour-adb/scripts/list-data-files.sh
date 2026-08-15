#!/usr/bin/env bash
#
# List the debug variant's app-private files, with sizes and timestamps.
#
# Why this exists: `run-as` is the only route to filesDir without root, it works only on the
# .debug variant, and the quoting is a trap — `adb shell run-as PKG sh -c 'ls -l files'`
# loses its arguments and silently lists the data directory root instead. The whole command
# has to be quoted as one string for adb. Getting that wrong produces plausible-looking
# output for the wrong directory, which is worse than an error.
#
# It lists and never cats. `shared_prefs/settings.xml` holds a live auth_token and
# `shared_prefs/routing_server.xml` a Cloudflare Access client secret; a script that prints
# them would end up pasting credentials into a transcript. Read those by hand, deliberately,
# and report the fact rather than the value.
#
# Read-only: `ls` inside the sandbox. Writes nothing, to the device or to the repo.
set -euo pipefail

usage() {
    cat >&2 <<'EOF'
usage: list-data-files.sh [serial] [package]

  serial    adb device serial. Defaults to $ANDROID_SERIAL, or to the only attached device.
  package   defaults to io.github.maxke24.detour.debug — the only debuggable variant.
EOF
    exit 2
}

[ "$#" -le 2 ] || usage
if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then usage; fi

SERIAL="${1:-${ANDROID_SERIAL:-}}"
PKG="${2:-io.github.maxke24.detour.debug}"
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

if ! "${ADB[@]}" shell run-as "$PKG" true >/dev/null 2>&1; then
    echo "error: run-as refused for $PKG — not installed, or not debuggable." >&2
    echo "       The release variant's data is unreachable over adb by design. Do not try" >&2
    echo "       to make it reachable by reinstalling: see SKILL.md, 'Never do these'." >&2
    exit 1
fi

printf 'app-private files for %s on %s\n\n' "$PKG" "$SERIAL"
"${ADB[@]}" shell "run-as $PKG sh -c 'ls -l files shared_prefs'" | tr -d '\r'

cat <<'EOF'

Shapes worth remembering before you read any of these (full table in SKILL.md):
  trips.json    a single JSON array; trips are keyed by startTimeMs, there is no id field
  traces.jsonl  one JSON array per line = one segment, each point
                [lat, lon, timeMs, speedKmh, leanDeg] — `wc -l` counts segments, not points
  shared_prefs/settings.xml, shared_prefs/routing_server.xml
                live credentials. Report "an auth_token key is present", never the value.
EOF
