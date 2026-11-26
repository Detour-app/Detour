#!/bin/sh
# Rebuild the routing graph from a fresh OSM extract and swap it in (#1077).
#
#   ./scripts/rebuild-graph.sh               # routing stays up during the build
#   ./scripts/rebuild-graph.sh --stop-first  # stop GraphHopper first, to free its RAM
#
# GraphHopper reads the extract only when it builds a graph; after that it loads
# graphhopper-data and never looks at OSM again. Without this, a road made
# one-way after the first boot keeps routing the old way while the basemap
# already draws the new arrows. Run it on a schedule — docker/prod/README.md,
# "Keeping the routing graph current" — and after any change that needs a new
# graph (graph.encoded_values, the profile set, GRAPHHOPPER_ELEVATION).
#
# Steps, each one stopping the script on failure with the live graph untouched
# until step 4:
#   1. osm-extract with OSM_REFRESH=1: download the current extract if it changed.
#   2. A one-off GraphHopper container builds the graph into /graph/.next on the
#      same volume, next to the one being served. It publishes no port and takes
#      no network alias, so nothing routes to it. It runs with the current .env
#      and config, so a config change is built in too.
#   3. Check the build left a graph (.next/properties).
#   4. Stop GraphHopper, move the served graph to /graph/.old and .next into its
#      place (renames on one volume: instant), start GraphHopper.
#   5. Wait for /health. Up: delete .old. Not up within REBUILD_LOAD_TIMEOUT:
#      move .old back, start again, exit 1.
# Routing is down only between 4 and 5, the time GraphHopper takes to load a
# graph — unless --stop-first, which takes it down for the whole build too.
#
# Needs, besides the stack's own: room on the graphhopper-data volume for a
# second graph, and GRAPHHOPPER_HEAP of free RAM for the build while the running
# server keeps its own. A box without that RAM uses --stop-first.
#
# Env:
#   REBUILD_LOAD_TIMEOUT  seconds to wait for /health after the swap (default 900)
set -eu

# Compose reads .env and COMPOSE_FILE relative to docker/prod.
cd "$(dirname "$0")/.."

STOP_FIRST=''
case "${1:-}" in
    '') ;;
    --stop-first) STOP_FIRST=1 ;;
    -h | --help) sed -n '2,6p' "$0"; exit 0 ;;
    *) echo "rebuild-graph: unknown argument: $1" >&2; exit 2 ;;
esac
LOAD_TIMEOUT="${REBUILD_LOAD_TIMEOUT:-900}"

log() { echo "rebuild-graph: $(date '+%F %T') $*"; }
die() { log "$*" >&2; exit 1; }

docker compose config --services | grep -qx graphhopper \
    || die "graphhopper is not in this stack; add docker-compose.routing.yml to COMPOSE_FILE"

# Two rebuilds at once would build into the same .next.
LOCK=.rebuild-graph.lock
mkdir "$LOCK" 2>/dev/null \
    || die "another rebuild is running (or one was killed: remove docker/prod/$LOCK)"
# A shell in a one-off GraphHopper container: same image and volumes as the
# server, no ports, no deps.
in_graph_volume() {
    docker compose run --rm --no-deps -T --entrypoint sh graphhopper -c "$1"
}

# Set while a failure must not leave things as they are: a half-built .next takes
# a graph's worth of disk, and --stop-first stopped the server it now has to restart.
BUILDING=''
STOPPED=''
cleanup() {
    status=$?
    if [ "$status" -ne 0 ] && [ -n "$BUILDING" ]; then
        log "build failed (exit $status); removing /graph/.next" >&2
        in_graph_volume 'rm -rf /graph/.next' || true
    fi
    if [ "$status" -ne 0 ] && [ -n "$STOPPED" ]; then
        log "starting graphhopper again on the graph it had" >&2
        docker compose up -d --no-deps graphhopper || true
    fi
    rmdir "$LOCK"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

log "1/5 refreshing the OSM extract"
docker compose run --rm --no-deps -T -e OSM_REFRESH=1 osm-extract

if [ -n "$STOP_FIRST" ]; then
    log "stopping graphhopper for the build (--stop-first): routing is down until step 5"
    STOPPED=1
    docker compose stop graphhopper
fi

log "2/5 building the graph into /graph/.next (25-65 min, see the README's Regions table)"
BUILDING=1
in_graph_volume 'rm -rf /graph/.next'
# Appended to the service's entrypoint: graphhopper.sh takes the last -o.
docker compose run --rm --no-deps -T graphhopper --import -o /graph/.next

log "3/5 checking the build"
in_graph_volume 'test -s /graph/.next/properties' \
    || die "the build left no graph in /graph/.next; the served graph is unchanged"
BUILDING=''

log "4/5 swapping it in: routing is down until GraphHopper has loaded it"
STOPPED=''
docker compose stop graphhopper
# Everything at the top of /graph is the graph, except the elevation tiles
# (GRAPHHOPPER_ELEVATION's cache, reused by the next build) and our two dirs.
in_graph_volume '
    set -e
    cd /graph
    rm -rf .old
    mkdir .old
    for f in * .[!.]*; do
        case "$f" in .next|.old|elevation-cache) continue ;; esac
        [ -e "$f" ] && mv "$f" .old/
    done
    for f in .next/* .next/.[!.]*; do
        [ -e "$f" ] && mv "$f" .
    done
    rmdir .next
'
docker compose up -d --no-deps graphhopper

log "5/5 waiting up to ${LOAD_TIMEOUT}s for /health"
waited=0
until docker compose exec -T graphhopper curl -fsS http://localhost:8989/health >/dev/null 2>&1; do
    if [ "$waited" -ge "$LOAD_TIMEOUT" ]; then
        log "the new graph did not come up; restoring the previous one" >&2
        docker compose logs --tail 40 graphhopper >&2 || true
        docker compose stop graphhopper
        in_graph_volume '
            set -e
            cd /graph
            for f in * .[!.]*; do
                case "$f" in .old|elevation-cache) continue ;; esac
                [ -e "$f" ] && rm -rf "$f"
            done
            for f in .old/* .old/.[!.]*; do
                [ -e "$f" ] && mv "$f" .
            done
            rmdir .old
        '
        docker compose up -d --no-deps graphhopper
        die "restored the previous graph; GraphHopper is loading it"
    fi
    sleep 10
    waited=$((waited + 10))
done

in_graph_volume 'rm -rf /graph/.old'
# data_date is the extract's OSM timestamp: what a rider's routes now reflect.
date_line="$(docker compose exec -T graphhopper curl -fsS http://localhost:8989/info \
    | tr ',' '\n' | grep -E '"(data|import)_date"' | tr '\n' ' ' || true)"
log "done: ${date_line:-graph swapped in}"
