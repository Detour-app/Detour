#!/usr/bin/env bash
# Is Overpass actually usable for a replay right now?
#
# Gate on a JSON body, never on the status code. A loaded mirror answers HTTP
# 200 with an HTML error page, so `curl -w '%{http_code}'` reports 200 and the
# app then parses nothing — which is how a session concluded "Overpass is back"
# and sent a 24-minute replay into a dead mirror. It also fails the other way:
# a mirror can refuse the TCP connection outright for one WAN IP while
# answering another, so a green result here is about *this* host.
#
# The app's own budget is the second gate. SpeedCameras.near and
# RoadRoulette.speedLimitWays both issue `[out:json][timeout:20]` and the HTTP
# client gives up well before a slow mirror finishes, so a mirror that answers
# in 15 s is reachable and still useless: the fetch returns null, which the app
# treats as a network blip by design, and the replay records no section events.
# Anything slower than BUDGET_S is reported as too slow rather than as up.
#
#   overpass-ready.sh            # check every mirror the app uses
#   overpass-ready.sh --quiet    # exit status only, for use as a gate
#
# Exit 0 if at least one mirror returns JSON inside the budget, 1 otherwise.
set -euo pipefail

BUDGET_S=12
QUIET=0
[ "${1:-}" = "--quiet" ] && QUIET=1

# The two endpoints RoadRoulette.ENDPOINTS actually uses. Kept in sync by hand;
# if that list changes, this one is stale and the check is measuring the wrong
# servers.
MIRRORS=(
  "https://overpass-api.de/api/interpreter"
  "https://overpass.kumi.systems/api/interpreter"
)

# Cheapest possible real query: a count, in a box a few hundred metres across.
# `out count` still exercises the parser and the database, unlike a bare status
# page, so a mirror that is up but refusing queries fails here as it should.
QUERY='[out:json][timeout:10];node["highway"="speed_camera"](50.86,4.60,50.87,4.61);out count;'

ok=1
for m in "${MIRRORS[@]}"; do
  start=$(date +%s%N)
  body=$(curl -s -m "$BUDGET_S" -X POST -d "$QUERY" "$m" 2>/dev/null || true)
  elapsed=$(( ( $(date +%s%N) - start ) / 1000000 ))

  if [ -z "$body" ]; then
    verdict="DOWN        no body (refused, reset, or slower than ${BUDGET_S}s)"
  elif ! printf '%s' "$body" | head -c 1 | grep -q '{'; then
    # The trap this script exists for.
    verdict="NOT JSON    answered in ${elapsed}ms with a non-JSON body — almost"
    verdict="$verdict certainly an HTML error page behind a 200"
  elif [ "$elapsed" -gt $(( BUDGET_S * 1000 )) ]; then
    verdict="TOO SLOW    JSON in ${elapsed}ms, past the app's own budget"
  else
    verdict="READY       JSON in ${elapsed}ms"
    ok=0
  fi
  [ "$QUIET" -eq 1 ] || printf '  %-46s %s\n' "$(printf '%s' "$m" | cut -d/ -f3)" "$verdict"
done

if [ "$QUIET" -eq 0 ]; then
  if [ "$ok" -eq 0 ]; then
    echo
    echo "At least one mirror is usable. Section, ambient-limit and camera-warn"
    echo "behaviour can be recorded on this run."
  else
    echo
    echo "No usable mirror. A replay still exercises trip auto-detection, trace"
    echo "decimation, camera follow/park and the speed HUD — but speedSections"
    echo "and speedLimitWays will both be empty, so the average-speed chip, the"
    echo "posted-limit sign and the camera chime cannot be observed. Do not"
    echo "record a section baseline now; it will look like a clean run with the"
    echo "one quantity you wanted missing, which has already happened twice."
  fi
fi
exit $ok
