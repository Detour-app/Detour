#!/usr/bin/env bash
# Desk battery/frame profile of one Detour process (#896, spec on #892).
#
#   tools/battery-profile/battery-profile.sh [--serial S] [--rows still,moving,navigated]
#                                            [--seconds N] [--out DIR] [--allow-device]
#
# Per row: replay a trimmed window through tools/mocklocation, wait out its lead-in, then
# measure for --seconds (default 180) with exactly one Detour process alive:
#   ticks/s   Choreographer#doFrame slices on the app's main thread (atrace text capture;
#             gfxinfo misses MapLibre's GL surface)
#   main CPU  main-thread %CPU, from top -H over the window
#   surface   frames/s presented by the app's SurfaceView layer (SurfaceFlinger timestats)
#   wakelock / sensor time, from batterystats --checkin since the --reset
#
# Rows:
#   still      release build, public-stop-start.txt inside its 439 s standstill
#   moving     release build, turn-circle.txt looped, off-route (45 km/h, no route matches it)
#   navigated  DEBUG build (unminified: compare only with itself), public-stop-start.txt as an
#              in-app navigation. Needs a routing server and the operator to start navigating
#              the pushed GPX by hand; see README.md.
#
# Device state this changes: force-stops the Detour variants not under test, grants location
# to the one under test, starts tools/mocklocation, unplugs the battery in batterystats (put
# back by a trap), resets batterystats, and records a replayed ride into the app's history.
# It installs, uninstalls and clears nothing. Meant for a dedicated throwaway AVD: a physical
# device is refused without --allow-device.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(git -C "$HERE" rev-parse --show-toplevel)"
ROUTES="$REPO/tools/mocklocation/routes"

RELEASE=io.github.maxke24.detour
DEBUG_APP=io.github.maxke24.detour.debug
AUTOMOTIVE=io.github.maxke24.detour.automotive
VARIANTS=("$RELEASE" "$DEBUG_APP" "$AUTOMOTIVE")
ACTIVITY=com.jellemax.detour.MainActivity
HARNESS=com.jellemax.mocklocation
RIG_RECEIVER="$DEBUG_APP/com.jellemax.detour.debug.DebugReplayReceiver"
REMOTE_ROUTE="/data/data/$HARNESS/files/route.txt"

SERIAL="${ANDROID_SERIAL:-}"
ROWS="still,moving"
SECONDS_PER_ROW=180
OUT=""
ALLOW_DEVICE=0

usage() { sed -n '2,25p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2; }
die() { printf 'battery-profile.sh: %s\n' "$*" >&2; exit 1; }

while [ "$#" -gt 0 ]; do
    case "$1" in
        --serial) [ "$#" -ge 2 ] || usage; SERIAL="$2"; shift 2 ;;
        --rows) [ "$#" -ge 2 ] || usage; ROWS="$2"; shift 2 ;;
        --seconds) [ "$#" -ge 2 ] || usage; SECONDS_PER_ROW="$2"; shift 2 ;;
        --out) [ "$#" -ge 2 ] || usage; OUT="$2"; shift 2 ;;
        --allow-device) ALLOW_DEVICE=1; shift ;;
        -h | --help) usage ;;
        *) usage ;;
    esac
done
case "$SECONDS_PER_ROW" in '' | *[!0-9]*) die "--seconds must be a positive integer" ;; esac
[ "$SECONDS_PER_ROW" -ge 30 ] || die "--seconds below 30 measures mostly noise"
command -v adb >/dev/null || die "adb is not on PATH"
command -v python3 >/dev/null || die "python3 is not on PATH"

if [ -z "$SERIAL" ]; then
    mapfile -t devs < <(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
    [ "${#devs[@]}" -eq 1 ] || die "${#devs[@]} devices attached; pass --serial"
    SERIAL="${devs[0]}"
fi
a() { adb -s "$SERIAL" "$@"; }
sh_() { a shell "$@" | tr -d '\r'; }
a get-state >/dev/null 2>&1 || die "$SERIAL is not connected"

# A physical phone is somebody's: its history and battery stats are real. Refuse by default.
if [ "$(sh_ getprop ro.kernel.qemu)" != 1 ] && [ "$ALLOW_DEVICE" != 1 ]; then
    die "$SERIAL is not an emulator; profile a dedicated AVD, or pass --allow-device"
fi

OUT="${OUT:-$REPO/build/battery-profile/$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$OUT"
echo "results: $OUT"

installed() { sh_ pm list packages | grep -x "package:$1" >/dev/null; }
pid_of() { sh_ pidof "$1" || true; }
detour_pids() {
    local p n=0
    for p in "${VARIANTS[@]}"; do
        [ -n "$(pid_of "$p")" ] && n=$((n + 1))
    done
    echo "$n"
}

installed "$HARNESS" || die "$HARNESS is not installed; see README.md (one-time setup)"
sh_ appops get "$HARNESS" android:mock_location | grep allow >/dev/null \
    || die "$HARNESS is not the mock-location app: adb shell appops set $HARNESS android:mock_location allow"

# --- restore on any exit --------------------------------------------------------------------
cleanup() {
    a shell am stopservice -n "$HARNESS/.MockService" >/dev/null 2>&1 || true
    a shell dumpsys battery reset >/dev/null 2>&1 || true
    a shell dumpsys SurfaceFlinger --timestats -disable >/dev/null 2>&1 || true
    if installed "$DEBUG_APP"; then
        a shell am broadcast -n "$RIG_RECEIVER" --ez mock_only false --ei speedup 1 >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT

# --- route windows --------------------------------------------------------------------------
# public-stop-start.txt: 53 s moving, then the 439 s standstill (lines 54-492). The still row
# measures from 120 s in, past the HUD/camera easing to rest, for at most 360 s.
make_still() { sed -n '1,492p' "$ROUTES/public-stop-start.txt"; }
# turn-circle.txt: one 150 s lap at 45 km/h; looped so any --seconds fits.
make_moving() {
    local laps=$(((SECONDS_PER_ROW + 60) / 150 + 1)) i
    for ((i = 0; i < laps; i++)); do cat "$ROUTES/turn-circle.txt"; done
}
# Navigated: the standstill window plus 600 s past it, so arrival never ends navigation inside
# the measurement.
make_navigated() { sed -n '1,1100p' "$ROUTES/public-stop-start.txt"; }

lead_in_for() {
    case "$1" in
        still) echo 120 ;;
        moving) echo 30 ;;
        navigated) echo 120 ;;
    esac
}
pkg_for() { [ "$1" = navigated ] && echo "$DEBUG_APP" || echo "$RELEASE"; }

if [ "$SECONDS_PER_ROW" -gt 360 ] && [[ ",$ROWS," == *,still,* ]]; then
    die "--seconds above 360 runs the still row past the end of the standstill"
fi

# The GPX for the navigated row: the same window, with the standstill thinned to one point.
# RouteGpx thins by index, so 439 identical points would become a stack of via stops there.
write_navigated_gpx() {
    make_navigated | python3 -I -c '
import sys
pts = [l.split()[:2] for l in sys.stdin if len(l.split()) >= 2]
out, last = [], None
for lon, lat in pts:
    if (lon, lat) != last:
        out.append((lon, lat))
    last = (lon, lat)
print("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
print("<gpx version=\"1.1\" creator=\"battery-profile.sh\" xmlns=\"http://www.topografix.com/GPX/1/1\">")
print("<trk><name>battery-profile navigated</name><trkseg>")
for lon, lat in out:
    print(f"<trkpt lat=\"{lat}\" lon=\"{lon}\"/>")
print("</trkseg></trk></gpx>")
' >"$OUT/navigated.gpx"
}

# --- one row --------------------------------------------------------------------------------
run_row() {
    local row="$1" pkg lead pid window
    pkg="$(pkg_for "$row")"
    lead="$(lead_in_for "$row")"
    installed "$pkg" || die "$row: $pkg is not installed; see README.md"
    if [ "$pkg" = "$RELEASE" ] && sh_ dumpsys package "$pkg" | grep -q 'flags=\[.*DEBUGGABLE'; then
        die "$row: $pkg is debuggable; profile the minified release build"
    fi
    echo
    echo "== $row ($pkg): lead-in ${lead} s, measure ${SECONDS_PER_ROW} s"

    local other
    for other in "${VARIANTS[@]}"; do
        [ "$other" = "$pkg" ] || a shell am force-stop "$other" >/dev/null 2>&1 || true
    done
    a shell pm grant "$pkg" android.permission.ACCESS_FINE_LOCATION >/dev/null 2>&1 || true
    a shell pm grant "$pkg" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
    a shell am start -n "$pkg/$ACTIVITY" >/dev/null

    window="$OUT/$row-route.txt"
    "make_$row" >"$window"
    a shell "run-as $HARNESS mkdir -p files"
    a shell "run-as $HARNESS sh -c 'cat > files/route.txt'" <"$window"
    if [ "$pkg" = "$DEBUG_APP" ]; then
        # The debug app drops real fixes fused blends into the replay (#47).
        a shell am broadcast -n "$RIG_RECEIVER" --ez mock_only true --ei speedup 1 >/dev/null
    fi

    if [ "$row" = navigated ]; then
        write_navigated_gpx
        a shell mkdir -p /sdcard/Download
        a push "$OUT/navigated.gpx" /sdcard/Download/battery-profile-navigated.gpx >/dev/null
        cat <<EOF
On the emulator: Routes -> import /sdcard/Download/battery-profile-navigated.gpx, and start
navigating it. The replay starts when you press Enter; navigation has to be running by the end
of the ${lead} s lead-in.
EOF
        if [ -t 0 ]; then
            read -r -p "Press Enter once the route is imported and on the map... " _
        else
            die "navigated: needs an operator at the terminal (stdin is not a tty)"
        fi
    fi

    a shell am start-foreground-service -n "$HARNESS/.MockService" \
        --es route "$REMOTE_ROUTE" --ei intervalMs 1000 --ei speedup 1 >/dev/null
    sleep "$lead"

    [ "$(detour_pids)" = 1 ] || die "$row: expected one Detour process, found $(detour_pids)"
    pid="$(pid_of "$pkg")"
    [ -n "$pid" ] || die "$row: $pkg is not running"

    a shell dumpsys battery unplug >/dev/null
    a shell dumpsys batterystats --reset >/dev/null
    a shell dumpsys SurfaceFlinger --timestats -disable -clear >/dev/null
    a shell dumpsys SurfaceFlinger --timestats -enable -clear >/dev/null

    # top's second sample is the average over the -d interval.
    a shell "top -H -b -q -p $pid -d $SECONDS_PER_ROW -n 2 -o TID,%CPU,CMD" >"$OUT/$row-top.txt" &
    local top_job=$!
    # `view` alone carries Choreographer#doFrame; `gfx` would triple the volume and risk the
    # ring buffer wrapping, which summarize.py reports rather than undercounting silently.
    a shell "atrace -a $pkg -b 65536 -t $SECONDS_PER_ROW view" >"$OUT/$row-atrace.txt"
    wait "$top_job" || true

    a shell dumpsys SurfaceFlinger --timestats -dump >"$OUT/$row-timestats.txt"
    a shell dumpsys SurfaceFlinger --timestats -disable >/dev/null
    a shell dumpsys batterystats --checkin >"$OUT/$row-batterystats-checkin.txt"
    a shell dumpsys batterystats --charged "$pkg" >"$OUT/$row-batterystats.txt"
    a shell dumpsys battery reset >/dev/null
    a shell am stopservice -n "$HARNESS/.MockService" >/dev/null 2>&1 || true

    [ "$(detour_pids)" = 1 ] || die "$row: expected one Detour process after, found $(detour_pids)"
    [ "$(pid_of "$pkg")" = "$pid" ] || die "$row: $pkg restarted during the window"

    local uid
    # Android 16 prints appId=, older releases userId=.
    uid="$(sh_ dumpsys package "$pkg" | sed -n 's/^ *\(appId\|userId\)=\([0-9]*\).*/\2/p' | head -1)"
    python3 -I "$HERE/summarize.py" "$row" "$pkg" "$pid" "$uid" "$SECONDS_PER_ROW" "$OUT" \
        >>"$OUT/summary.tsv"
}

printf 'row\tpackage\tminified\tseconds\tticks_per_s\tmain_cpu_pct\tsurface_fps\twakelock_s\tsensor_s\n' \
    >"$OUT/summary.tsv"
IFS=, read -r -a rows <<<"$ROWS"
for row in "${rows[@]}"; do
    case "$row" in
        still | moving | navigated) run_row "$row" ;;
        *) die "unknown row '$row' (still, moving, navigated)" ;;
    esac
done

echo
column -t -s $'\t' "$OUT/summary.tsv"
echo
echo "raw captures and summary.tsv in $OUT"
