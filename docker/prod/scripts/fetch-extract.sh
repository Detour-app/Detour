#!/bin/sh
# Resolve the OSM extract URL, download it to <OSM_DIR>/region.osm.pbf once, and
# verify it against Geofabrik's published .md5. Idempotent: a file already there
# whose md5 still matches is left alone — a restart costs one small .md5 fetch.
#
# Env:
#   OSM_EXTRACT_URL   any Geofabrik .pbf URL; wins over OSM_REGION when set
#   OSM_REGION        a key in the regions file
#   REGIONS_FILE      path to the region=url map        (default /regions.env)
#   OSM_DIR           where region.osm.pbf is written   (default /osm)
set -eu

REGIONS_FILE="${REGIONS_FILE:-/regions.env}"
OSM_DIR="${OSM_DIR:-/osm}"
DEST="$OSM_DIR/region.osm.pbf"

URL="${OSM_EXTRACT_URL:-}"
if [ -z "$URL" ]; then
    [ -n "${OSM_REGION:-}" ] || { echo "set OSM_REGION or OSM_EXTRACT_URL"; exit 1; }
    URL="$(sed -n "s|^${OSM_REGION}=||p" "$REGIONS_FILE" | head -n1)"
    [ -n "$URL" ] || { echo "OSM_REGION='$OSM_REGION' is not in $REGIONS_FILE"; exit 1; }
fi

WANT="$(curl -fsSL "$URL.md5" | cut -d' ' -f1)"
[ -n "$WANT" ] || { echo "could not fetch $URL.md5"; exit 1; }

if [ -f "$DEST" ] && [ "$(md5sum "$DEST" | cut -d' ' -f1)" = "$WANT" ]; then
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
echo "extract ready ($GOT)"
