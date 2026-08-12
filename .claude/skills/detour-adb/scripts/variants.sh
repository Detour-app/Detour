#!/usr/bin/env bash
#
# Report which Detour variants are installed on a device and which of them `run-as` can read.
#
# Why this exists: both the release and the .debug variant can be installed at once (that is
# what the applicationIdSuffix is for), they look identical on screen, and only the debug one
# is debuggable. Guessing which one you are talking to is how a stateful command lands on the
# user's real install. This answers "which is here, which is readable" in one read-only pass,
# so the answer is a fact in the transcript rather than an assumption.
#
# Debuggability is probed by actually running `run-as <pkg> true` — that is the exact
# capability that decides whether the data-reading half of this skill is available, so it is
# better evidence than a flag in dumpsys.
#
# Read-only: lists packages, reads dumpsys, runs `true` inside the sandbox. Writes nothing.
set -euo pipefail

usage() {
    cat >&2 <<'EOF'
usage: variants.sh [serial]

  serial   adb device serial. Defaults to $ANDROID_SERIAL, or to the only attached
           device if exactly one is attached.
EOF
    exit 2
}

[ "$#" -le 1 ] || usage
if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then usage; fi

SERIAL="${1:-${ANDROID_SERIAL:-}}"
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

model="$("${ADB[@]}" shell getprop ro.product.model | tr -d '\r')"
rel="$("${ADB[@]}" shell getprop ro.build.version.release | tr -d '\r')"
sdk="$("${ADB[@]}" shell getprop ro.build.version.sdk | tr -d '\r')"
printf 'device %s — %s, Android %s (SDK %s)\n\n' "$SERIAL" "$model" "$rel" "$sdk"

installed="$("${ADB[@]}" shell pm list packages | tr -d '\r' | sed 's/^package://')"

report() { # report <package> <role>
    local pkg="$1" role="$2" ver dbg
    if ! grep -qx "$pkg" <<<"$installed"; then
        printf '%-34s %-13s not installed\n' "$pkg" "$role"
        return
    fi
    # No `exit` in the awk body: exiting early SIGPIPEs `tr`, which pipefail turns into a
    # failed pipeline and set -e turns into a dead script.
    ver="$("${ADB[@]}" shell dumpsys package "$pkg" | tr -d '\r' \
        | awk -F= '/versionName=/ && !seen { print $2; seen = 1 }')"
    if "${ADB[@]}" shell run-as "$pkg" true >/dev/null 2>&1; then
        dbg='debuggable — run-as works'
    else
        dbg='NOT debuggable — run-as refused, app data unreadable'
    fi
    printf '%-34s %-13s installed  v%-8s %s\n' "$pkg" "$role" "${ver:-?}" "$dbg"
}

report io.github.maxke24.detour.debug debug
report io.github.maxke24.detour release/wear
report com.jellemax.mocklocation harness

cat <<'EOF'

Reminder: a package that is NOT debuggable is a boundary, not a puzzle — there is no adb
route to its app-private data (see SKILL.md, "What is not readable"). Never uninstall or
`pm clear` a variant to make it readable; that destroys the user's trip history.
EOF
