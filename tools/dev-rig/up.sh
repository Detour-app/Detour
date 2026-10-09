#!/usr/bin/env bash
# Bring up the local routing + road-data rig (#612): a small OSM extract,
# GraphHopper built from it with the repo's own config, the API and its
# database, the road/POI/speed-limit tables loaded from the same extract, and
# — if you name an emulator — the debug build pointed at all of it.
#
#   tools/dev-rig/up.sh [adb-serial]     (or ANDROID_SERIAL; no serial = no device touched)
#
# Idempotent: every step checks before it acts, so a re-run on a warm rig costs
# seconds (one compose up, three skipped imports, one prefs write). See
# tools/dev-rig/README.md for what each step does and what it costs cold.
#
# Env:
#   RIG_EXTRACT_URL   Geofabrik .pbf to use      (default: Luxembourg, ~45 MB)
#   RIG_REGION        region name for importers  (default: derived from the URL)
#   RIG_GH_HEAP       GraphHopper -Xmx           (default: 2g)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(git -C "$HERE" rev-parse --show-toplevel)"
DATA="$REPO/docker/dev/data/rig"   # docker/dev/data/ is gitignored
SERIAL="${1:-${ANDROID_SERIAL:-}}"

RIG_EXTRACT_URL="${RIG_EXTRACT_URL:-https://download.geofabrik.de/europe/luxembourg-latest.osm.pbf}"
RIG_REGION="${RIG_REGION:-$(basename "$RIG_EXTRACT_URL" | sed 's/-latest\.osm\.pbf$//; s/\.osm\.pbf$//')}"
# Luxembourg's graph with both CH profiles peaked at ~1.3 GB of heap and built
# in ~22 s (2026-10-09). A bigger extract needs more; see README.
export RIG_GH_HEAP="${RIG_GH_HEAP:-2g}"
export RIG_REPO="$REPO" RIG_DATA="$DATA"

API_PORT=7500
GH_PORT=7510
PROJECT=detour-rig

log() { printf '\n== %s\n' "$*"; }
die() { printf 'up.sh: %s\n' "$*" >&2; exit 1; }

compose() {
    docker compose -p "$PROJECT" \
        -f "$REPO/docker/dev/docker-compose.yml" \
        -f "$HERE/compose.rig.yml" "$@"
}

for tool in docker curl md5sum python3 git; do
    command -v "$tool" >/dev/null || die "needs $tool on PATH"
done

mkdir -p "$DATA/osm" "$DATA/import"

# --- 0. Port check -----------------------------------------------------------
# The rig uses the canonical dev ports (docker/dev/README.md), so it cannot run
# next to a detour-dev stack or an API started from an IDE. Say so rather than
# letting compose fail with "address already in use" halfway through.
for port in $API_PORT $GH_PORT 7532; do
    holder="$(docker ps --filter "publish=$port" --format '{{.Names}}' | grep -v "^${PROJECT}-" || true)"
    [ -z "$holder" ] || die "port $port is held by container '$holder' — stop it first (docker/dev stack?)"
    if [ -z "$(docker ps --filter "publish=$port" --format '{{.Names}}')" ] \
        && (exec 3<>/dev/tcp/127.0.0.1/$port) 2>/dev/null; then
        die "port $port is in use by a non-docker process (an API run from the IDE?) — stop it first"
    fi
done

# --- 1. The extract ----------------------------------------------------------
# Downloaded once and md5-verified against Geofabrik's published sum. Not
# refreshed on later runs: Geofabrik republishes daily, and a new extract under
# an existing graph means a rebuild nobody asked for. Delete the file (or
# change RIG_EXTRACT_URL) to refresh.
PBF="$DATA/osm/region.osm.pbf"
if [ -f "$PBF" ] && [ "$(cat "$DATA/osm/source-url" 2>/dev/null)" = "$RIG_EXTRACT_URL" ]; then
    log "extract: already have $RIG_REGION ($(du -h "$PBF" | cut -f1))"
else
    log "extract: downloading $RIG_EXTRACT_URL"
    want="$(curl -fsSL "$RIG_EXTRACT_URL.md5" | cut -d' ' -f1)"
    [ -n "$want" ] || die "could not fetch $RIG_EXTRACT_URL.md5"
    curl -fSL --retry 3 -o "$PBF.part" "$RIG_EXTRACT_URL"
    got="$(md5sum "$PBF.part" | cut -d' ' -f1)"
    [ "$got" = "$want" ] || { rm -f "$PBF.part"; die "md5 mismatch: got $got, want $want"; }
    mv "$PBF.part" "$PBF"
    printf '%s\n' "$RIG_EXTRACT_URL" > "$DATA/osm/source-url"
fi
PBF_MD5="$(md5sum "$PBF" | cut -d' ' -f1)"

# --- 2. GraphHopper config ---------------------------------------------------
# The repo's dev config with only datareader.file rewritten, regenerated every
# run so it can never drift from docker/dev/config/graphhopper/config.yml.
GH_CFG="$DATA/graphhopper-config.yml"
sed 's|^\(  datareader\.file:\).*|\1 /osm/region.osm.pbf|' \
    "$REPO/docker/dev/config/graphhopper/config.yml" > "$GH_CFG"
grep -q '^  datareader.file: /osm/region.osm.pbf$' "$GH_CFG" \
    || die "could not rewrite datareader.file in the dev GraphHopper config — has its layout changed?"
# The app's avoid rules read these three (#586); a graph without them answers
# a request naming one with HTTP 400. Fail here rather than in the app.
for ev in toll surface road_environment; do
    tr ',' '\n' < "$GH_CFG" | grep -qw "$ev" \
        || die "dev GraphHopper config lacks encoded value '$ev'"
done

# A graph is keyed to the extract and the config. When either changed since it
# was built, drop the volume so GraphHopper rebuilds instead of serving a stale
# graph (it loads whatever is in /graph and never re-reads the extract).
GRAPH_KEY="$PBF_MD5 $(md5sum "$GH_CFG" "$REPO/docker/dev/config/graphhopper/moto.json" | cut -d' ' -f1 | tr '\n' ' ')"
if [ -f "$DATA/graph-key" ] && [ "$(cat "$DATA/graph-key")" != "$GRAPH_KEY" ]; then
    log "graphhopper: extract or config changed — dropping the old graph"
    compose rm -sf graphhopper >/dev/null
    docker volume rm "${PROJECT}_graphhopper-data" >/dev/null 2>&1 || true
fi
printf '%s\n' "$GRAPH_KEY" > "$DATA/graph-key"

# --- 3. The stack ------------------------------------------------------------
# db + api first, and waited on: the API applies migrations at startup, and the
# import-* commands do not, so importing into a fresh database before the API
# has booted once fails on a missing table. GraphHopper starts alongside and
# builds its graph while the importers run.
log "stack: building the API image and starting db, api, graphhopper"
compose up -d --build --wait --wait-timeout 600 db api
compose up -d graphhopper

# --- 4. Importers ------------------------------------------------------------
# Run in a throwaway python container so the host needs no pyosmium. The
# full image, not -slim: pyosmium's wheel links libexpat, which -slim lacks. Each
# output is regenerated only when it is missing or older than the extract.
run_importer() {  # <tool-dir> <out-name>
    local tool="$1" out="$DATA/import/$2.json"
    if [ -s "$out" ] && [ "$out" -nt "$PBF" ]; then
        echo "  $2.json: up to date"
        return
    fi
    echo "  $2.json: running tools/$tool"
    docker run --rm \
        -v "$REPO/tools/$tool:/tool:ro" \
        -v "$DATA/osm:/osm:ro" \
        -v "$DATA/import:/out" \
        -v "${PROJECT}_pip-cache:/root/.cache/pip" \
        -e PIP_DISABLE_PIP_VERSION_CHECK=1 \
        --user 0:0 \
        python:3.12-bookworm sh -c "
            pip install -q --root-user-action=ignore -r /tool/requirements.txt &&
            python3 -I /tool/osm_import.py /osm/region.osm.pbf --region '$RIG_REGION' --out /out/$2.json.part &&
            chown $(id -u):$(id -g) /out/$2.json.part" \
        >/dev/null
    mv "$out.part" "$out"
}
log "importers: extracting roads, POIs and speed limits from $RIG_REGION"
run_importer roads-importer roads
run_importer poi-importer pois
run_importer speedlimit-importer speedlimits

# --- 5. Load into the LOCAL API ---------------------------------------------
# `compose exec` into this project's own api container — never a remote host.
# The import-* commands upsert, so a re-run is safe; it is skipped anyway when
# the same file was already loaded into the same database volume, because a
# full upsert of Luxembourg's ways takes a minute for no change.
DB_ID="$(docker volume inspect -f '{{.CreatedAt}}' "${PROJECT}_postgres-data")"
load() {  # <command> <name>
    local file="$DATA/import/$2.json" stamp="$DATA/import/.$2.loaded"
    local key; key="$DB_ID $(md5sum "$file" | cut -d' ' -f1)"
    if [ "$(cat "$stamp" 2>/dev/null)" = "$key" ]; then
        echo "  $2: already loaded"
        return
    fi
    # Npgsql probes for Kerberos on every connect and the aspnet image has no
    # libgssapi; the two lines it prints are noise, not a failure.
    compose exec -T api dotnet Detour.Api.dll "$1" "/import/$2.json" 2>&1 \
        | grep -v 'libgssapi_krb5' | sed 's/^/  /'
    printf '%s\n' "$key" > "$stamp"
}
log "imports: loading into the local API's database"
load import-roads roads
load import-pois pois
load import-speedlimits speedlimits

# --- 6. Wait for the graph, then prove both halves answer --------------------
log "graphhopper: waiting for the graph (Luxembourg builds in under a minute; seconds once cached)"
deadline=$(( $(date +%s) + 1800 ))
until curl -fsS "http://127.0.0.1:$GH_PORT/health" >/dev/null 2>&1; do
    [ "$(date +%s)" -lt "$deadline" ] || die "graphhopper not healthy after 30 min — compose -p $PROJECT logs --tail 50 graphhopper"
    state="$(docker inspect -f '{{.State.Status}}' "${PROJECT}-graphhopper-1" 2>/dev/null || echo missing)"
    [ "$state" = running ] || die "graphhopper container is $state — see: docker logs --tail 50 ${PROJECT}-graphhopper-1"
    sleep 10
done

# A probe point and a short hop inside the extract's bounds: the centre of its
# bbox, read from GraphHopper's own /info so it works for any region.
read -r LON0 LAT0 < <(curl -fsS "http://127.0.0.1:$GH_PORT/info" | python3 -I -c '
import json, sys
b = json.load(sys.stdin)["bbox"]          # [minLon, minLat, maxLon, maxLat]
print(f"{(b[0] + b[2]) / 2:.5f} {(b[1] + b[3]) / 2:.5f}")')
LAT1="$(python3 -I -c "print(round($LAT0 + 0.02, 5))")"
LON1="$(python3 -I -c "print(round($LON0 + 0.02, 5))")"

log "smoke tests"
for profile in car moto; do
    dist="$(curl -fsS "http://127.0.0.1:$GH_PORT/route?point=$LAT0,$LON0&point=$LAT1,$LON1&profile=$profile&points_encoded=false" \
        | python3 -I -c 'import json,sys; print(round(json.load(sys.stdin)["paths"][0]["distance"]))')" \
        || die "GraphHopper did not route profile=$profile near $LAT0,$LON0"
    echo "  route $profile: ${dist} m"
done
# The avoid-tolls rule the app sends (#586) must not 400 on this graph.
curl -fsS -X POST "http://127.0.0.1:$GH_PORT/route" -H 'Content-Type: application/json' -d "{
    \"points\": [[$LON0,$LAT0],[$LON1,$LAT1]], \"profile\": \"car\", \"ch.disable\": true,
    \"custom_model\": {\"priority\": [{\"if\": \"toll != NO\", \"multiply_by\": \"0\"}]}}" >/dev/null \
    || die "GraphHopper refused a custom model reading 'toll' — encoded values missing from the graph?"
echo "  custom model reading toll: ok"
bbox="minLat=$(python3 -I -c "print(round($LAT0-0.02, 5))")&minLon=$(python3 -I -c "print(round($LON0-0.02, 5))")&maxLat=$LAT1&maxLon=$LON1"
ways="$(curl -fsS "http://127.0.0.1:$API_PORT/api/roads?$bbox" | python3 -I -c 'import json,sys; print(len(json.load(sys.stdin)["ways"]))')"
[ "$ways" -gt 0 ] || die "/api/roads answered no ways near $LAT0,$LON0 — the import did not land (#535)"
echo "  /api/roads around the centre: $ways ways"

# --- 7. Point the emulator at it ---------------------------------------------
cat <<EOF

Rig is up ($RIG_REGION, centre $LAT0,$LON0).

  On this machine          API http://localhost:$API_PORT    routing http://localhost:$GH_PORT
  From an Android emulator API http://10.0.2.2:$API_PORT     routing http://10.0.2.2:$GH_PORT

Routing only answers inside the extract: put the emulator there first, e.g.
  adb -s <serial> emu geo fix $LON0 $LAT0
EOF

if [ -z "$SERIAL" ]; then
    echo
    echo "No device named, so none was touched. Point an emulator's debug build at the rig with:"
    echo "  tools/dev-rig/point-emulator.sh <serial>"
else
    "$HERE/point-emulator.sh" "$SERIAL" || echo "(device not configured — see the message above)"
fi
