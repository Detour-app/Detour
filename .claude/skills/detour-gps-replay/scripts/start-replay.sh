#!/usr/bin/env bash
#
# Push a route file to the mock-location harness and start the replay.
#
# Why this exists: the sequence has one step that is easy to skip and expensive to skip, and
# one step that is easy to get subtly wrong.
#
#   * Force-stopping the RELEASE app first is not optional. It monitors for trips whenever it
#     is installed, so a mock stream otherwise records a fabricated ride into the user's real
#     trip history. There is no undo for that short of deleting the trip by hand.
#   * The route must be pushed into the harness's own data directory with run-as, not to
#     /sdcard. tools/mocklocation requests no storage permission, so under scoped storage it
#     cannot read a file pushed to shared storage — it just logs an error and stops. (The
#     KDoc in MockService.kt says to use /sdcard/Download. The KDoc is wrong;
#     docs/PLAY_LOCATION_DECLARATION.md:171-178 is right.)
#
# It also validates the route file before touching the device, because the format's traps are
# silent: readRoute parses parts[0] as LONGITUDE, unparseable lines are skipped without
# complaint, and under 2 usable points the service logs an error and stops. The validation is
# honest about its limit — a lat/lon swap cannot be proven from the numbers in mid-latitude
# Europe, so it is reported as a warning with the reasoning, not as a verdict.
#
# This DOES change device state: it force-stops one app and starts a service. It installs
# nothing, clears nothing and uninstalls nothing.
set -euo pipefail

usage() {
    cat >&2 <<'EOF'
usage: start-replay.sh <route.txt> [serial] [interval-ms] [speedup]

  route.txt     one "lon lat" pair per line — longitude FIRST. Build one with gpx2route.py.
  serial        adb device serial. Defaults to $ANDROID_SERIAL, or the only attached device.
  interval-ms   replay interval, default 1000. Must match the interval the route file was
                resampled to, or every reported speed is wrong by that ratio.
  speedup       wall-clock compression, default 1, capped at 20. The route arrives this
                many times faster while every fix still reports the speed the drive was
                actually done at, and the debug app is told to scale its own clock to
                match, so the trip it records is the drive's duration and not the
                replay's. A release install cannot be told, so replay it at 1.
EOF
    exit 2
}

[ "$#" -ge 1 ] || usage
if [ "$1" = "-h" ] || [ "$1" = "--help" ]; then usage; fi
[ "$#" -le 4 ] || usage

ROUTE="$1"
SERIAL="${2:-${ANDROID_SERIAL:-}}"
INTERVAL="${3:-1000}"
SPEEDUP="${4:-1}"
HARNESS=com.jellemax.mocklocation
RELEASE=io.github.maxke24.detour
DEBUG_APP=io.github.maxke24.detour.debug
CLOCK_RECEIVER="$DEBUG_APP/com.jellemax.detour.debug.DebugReplayClockReceiver"
REMOTE="/data/data/$HARNESS/files/route.txt"

[ -f "$ROUTE" ] || { echo "error: no such route file: $ROUTE" >&2; exit 2; }
case "$INTERVAL" in
    '' | *[!0-9]*) echo "error: interval-ms must be a positive integer" >&2; exit 2 ;;
esac
case "$SPEEDUP" in
    '' | *[!0-9]* | 0) echo "error: speedup must be a positive integer" >&2; exit 2 ;;
esac
# 20 is ScaledClock.MAX_SCALE, and MockService clamps to the same number. Refused rather
# than clamped here, because a run at a factor other than the one you asked for is a
# measurement you would go on to trust.
if [ "$SPEEDUP" -gt 20 ]; then
    echo "error: speedup $SPEEDUP exceeds the cap of 20 (ScaledClock.MAX_SCALE)" >&2
    exit 2
fi
if [ "$SPEEDUP" -gt 10 ]; then
    echo "warning: at ${SPEEDUP}x the Overpass prefetch issues a whole drive's requests in" >&2
    echo "         $((100 / SPEEDUP))% of the wall time, into a mirror that rate-limits by IP." >&2
fi

# --- validate the route file locally, before any device state changes -------------------
read -r lines dist_km mean_kmh abs1 abs2 < <(
    awk -v interval="$INTERVAL" '
        function hav(la1, lo1, la2, lo2,   r, p1, p2, dp, dl, x) {
            r = 6371000; p1 = la1 * 3.14159265358979 / 180; p2 = la2 * 3.14159265358979 / 180
            dp = p2 - p1; dl = (lo2 - lo1) * 3.14159265358979 / 180
            x = sin(dp / 2) ^ 2 + cos(p1) * cos(p2) * sin(dl / 2) ^ 2
            return 2 * r * atan2(sqrt(x), sqrt(1 - x))
        }
        { gsub(/[,\t]+/, " ") }
        NF >= 2 && $1 + 0 == $1 && $2 + 0 == $2 {
            lon = $1 + 0; lat = $2 + 0
            if (lon < -180 || lon > 180 || lat < -90 || lat > 90) { bad++; next }
            n++
            a1 += (lon < 0 ? -lon : lon); a2 += (lat < 0 ? -lat : lat)
            if (n > 1) d += hav(plat, plon, lat, lon)
            plat = lat; plon = lon
        }
        END {
            printf "%d %.1f %.0f %.1f %.1f\n", n, d / 1000,
                   (n > 1) ? d / ((n - 1) * interval / 1000) * 3.6 : 0,
                   (n ? a1 / n : 0), (n ? a2 / n : 0)
            if (bad > 0) print "out-of-range coordinates on " bad " line(s)" > "/dev/stderr"
        }' "$ROUTE"
)
if [ "$lines" -lt 2 ]; then
    echo "error: $ROUTE has $lines usable 'lon lat' lines; MockService needs at least 2" >&2
    exit 1
fi
printf '%s: %s lines, %s km, mean %s km/h at %s ms\n' \
    "$(basename "$ROUTE")" "$lines" "$dist_km" "$mean_kmh" "$INTERVAL"
if [ "$mean_kmh" -gt 300 ]; then
    echo "error: mean speed above 300 km/h — the columns are almost certainly lat-first." >&2
    echo "       MockService.readRoute parses parts[0] as LONGITUDE." >&2
    exit 1
fi
# A lat/lon swap is not detectable from ranges alone — both columns stay in range across
# mid-latitude Europe. This is the one signal that survives: in Belgium longitude is ~4 and
# latitude ~51, so a first column that is larger in magnitude than the second is suspect.
if [ "${abs1%.*}" -gt "${abs2%.*}" ]; then
    echo "warning: column 1 averages |$abs1| and column 2 |$abs2|. Column 1 must be" >&2
    echo "         LONGITUDE; over Belgium that is the smaller of the two. Check the file." >&2
fi

# --- resolve the device ------------------------------------------------------------------
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

# `grep -x` rather than `grep -qx`: -q exits on the first match, `tr` then dies of
# SIGPIPE, and `set -o pipefail` turns that into a failed pipeline — so a harness that IS
# installed reports as missing. Without -q, grep reads to EOF and nothing gets a SIGPIPE.
if ! "${ADB[@]}" shell pm list packages | tr -d '\r' | grep -x "package:$HARNESS" >/dev/null
then
    cat >&2 <<EOF
error: $HARNESS is not installed on $SERIAL.
       One-time setup, from tools/mocklocation/ (Gradle runs in the devcontainer):
         ./gradlew assembleDebug
         adb -s $SERIAL install -r build/outputs/apk/debug/DetourMockLocation-debug.apk
         adb -s $SERIAL shell appops set $HARNESS android:mock_location allow
EOF
    exit 1
fi

if ! "${ADB[@]}" shell appops get "$HARNESS" android:mock_location 2>/dev/null \
        | tr -d '\r' | grep 'allow' >/dev/null; then   # not -q: see the SIGPIPE note above
    echo "error: $HARNESS is not the designated mock-location app. Grant it with:" >&2
    echo "  adb -s $SERIAL shell appops set $HARNESS android:mock_location allow" >&2
    echo "  (fused only honours mocks from a designated app — granting the shell does not help)" >&2
    exit 1
fi

# --- from here on, device state changes --------------------------------------------------
echo "stopping the release app so the replay cannot be recorded into real trip history"
"${ADB[@]}" shell am force-stop "$RELEASE"

echo "pushing $(basename "$ROUTE") into the harness's own files dir"
# A freshly installed harness has no files/ yet — MockService only ever *reads* a path, so
# nothing has called getFilesDir(). Without this the push fails with
# "sh: can't create files/route.txt: No such file or directory".
"${ADB[@]}" shell "run-as $HARNESS mkdir -p files"
"${ADB[@]}" shell "run-as $HARNESS sh -c 'cat > files/route.txt'" <"$ROUTE"

# The redirect has to happen *inside* run-as. run-as sets the cwd to the app's data dir only
# for the process it execs, so a `< files/route.txt` left to the device shell is resolved
# against / and fails with "can't open files/route.txt".
remote_lines="$("${ADB[@]}" shell "run-as $HARNESS sh -c 'wc -l < files/route.txt'" \
    | tr -dc '0-9')"
if [ "$remote_lines" != "$(wc -l <"$ROUTE" | tr -dc '0-9')" ]; then
    echo "error: pushed $remote_lines lines, local file has $(wc -l <"$ROUTE") — push failed" >&2
    exit 1
fi

# Before the fixes, so no fix is ever timed at the wrong factor. Ignored by a release
# install, which has no such receiver — and harmless when the debug app is not installed.
if [ "$SPEEDUP" -gt 1 ]; then
    "${ADB[@]}" shell am broadcast -n "$CLOCK_RECEIVER" --ei speedup "$SPEEDUP" >/dev/null 2>&1 \
        || echo "warning: could not reach $DEBUG_APP's replay clock — is the debug build installed?" >&2
fi

"${ADB[@]}" shell am start-foreground-service -n "$HARNESS/.MockService" \
    --es route "$REMOTE" --ei intervalMs "$INTERVAL" --ei speedup "$SPEEDUP" >/dev/null
printf 'replay started: %s lines at %s ms, %sx = %d s wall (%d s of drive)\n' \
    "$lines" "$INTERVAL" "$SPEEDUP" \
    $((lines * INTERVAL / 1000 / SPEEDUP)) $((lines * INTERVAL / 1000))

cat <<EOF

Before trusting anything you see:
  * Turn off Wi-Fi scanning, cell positioning and any paired GPS accessory. MockService
    registers test providers on gps, fused, network and passive, but fused blends whatever
    is enabled — a live real provider makes the device alternate between the route and your
    desk, which reads as teleporting hundreds of kilometres between fixes.
  * Test against the .debug variant, which has its own applicationId and its own data.
  * Watch it: adb -s $SERIAL logcat -s MockLocation
  * Stop it with stop-replay.sh — NOT with force-stop, see that script's header.
EOF
