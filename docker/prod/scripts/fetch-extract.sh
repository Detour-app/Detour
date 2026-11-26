#!/bin/sh
# Resolve the OSM extract URL, download it to <OSM_DIR>/region.osm.pbf, and
# verify it against Geofabrik's published .md5.
#
# Runs at every `up`, but downloads only when there is something to import:
# GraphHopper reads the extract once, when it builds the graph, and loads the
# built graph from its own volume after that. Geofabrik publishes a new extract
# (and a new .md5) about daily, so comparing against the .md5 at every start
# fetched the whole file again (~700 MB for Belgium) for nothing (#1077). An
# extract already here from the same URL is kept; a refresh is the graph
# rebuild's job (scripts/rebuild-graph.sh sets OSM_REFRESH=1).
#
# Env:
#   OSM_EXTRACT_URL   any Geofabrik .pbf URL; wins over OSM_REGION when set
#   OSM_REGION        a key in the regions file
#   OSM_REFRESH       1: fetch the current extract unless the file here already
#                     matches its .md5
#   REGIONS_FILE      path to the region=url map        (default /regions.env)
#   OSM_DIR           where region.osm.pbf is written   (default /osm)
set -eu

REGIONS_FILE="${REGIONS_FILE:-/regions.env}"
OSM_DIR="${OSM_DIR:-/osm}"
DEST="$OSM_DIR/region.osm.pbf"
# The URL the extract here came from, so a changed OSM_REGION still downloads.
SRC="$DEST.src"

URL="${OSM_EXTRACT_URL:-}"
if [ -z "$URL" ]; then
    [ -n "${OSM_REGION:-}" ] || { echo "set OSM_REGION or OSM_EXTRACT_URL"; exit 1; }
    URL="$(sed -n "s|^${OSM_REGION}=||p" "$REGIONS_FILE" | head -n1)"
    [ -n "$URL" ] || { echo "OSM_REGION='$OSM_REGION' is not in $REGIONS_FILE"; exit 1; }
fi

# No .src: an extract downloaded before #1077, which only ever came from this
# install's region. Changing the region since without a rebuild was already a
# broken graph, so that case is not worth a 700 MB guess.
if [ "${OSM_REFRESH:-}" != 1 ] && [ -f "$DEST" ] \
    && { [ ! -f "$SRC" ] || [ "$(cat "$SRC")" = "$URL" ]; }; then
    echo "extract present, not re-downloaded (scripts/rebuild-graph.sh refreshes it)"
    exit 0
fi

WANT="$(curl -fsSL "$URL.md5" | cut -d' ' -f1)"
[ -n "$WANT" ] || { echo "could not fetch $URL.md5"; exit 1; }

if [ -f "$DEST" ] && [ "$(md5sum "$DEST" | cut -d' ' -f1)" = "$WANT" ]; then
    printf '%s\n' "$URL" > "$SRC"
    echo "extract up to date ($WANT)"
    exit 0
fi

echo "downloading $URL"
curl -fSL --retry 3 -o "$DEST.part" "$URL"
GOT="$(md5sum "$DEST.part" | cut -d' ' -f1)"
if [ "$GOT" != "$WANT" ]; then
    echo "md5 mismatch: got $GOT, want $WANT"
    rm -f "$DEST.part"
    exit 1
fi
mv "$DEST.part" "$DEST"
printf '%s\n' "$URL" > "$SRC"
echo "extract ready ($GOT)"
