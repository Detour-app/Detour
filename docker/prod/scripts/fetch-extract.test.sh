#!/bin/sh
# POSIX-sh test for fetch-extract.sh. Serves a fixture .pbf + .md5 over a local
# HTTP server (python3 -m http.server) and drives the script through its paths.
set -eu

HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$HERE/fetch-extract.sh"
WORK="$(mktemp -d)"
trap 'kill "$SRV_PID" 2>/dev/null || true; rm -rf "$WORK"' EXIT

# Publishes a "pbf" of the given content as <name>-latest.osm.pbf plus its real
# .md5, the way Geofabrik does.
publish() {
    printf '%s' "$2" > "$WORK/www/$1-latest.osm.pbf"
    printf '%s  %s-latest.osm.pbf\n' "$(md5_of "$WORK/www/$1-latest.osm.pbf")" "$1" \
        > "$WORK/www/$1-latest.osm.pbf.md5"
}
md5_of() { md5sum "$1" | cut -d' ' -f1; }

mkdir -p "$WORK/www" "$WORK/osm"
publish benelux 'fake-pbf-bytes'
MD5="$(md5_of "$WORK/www/benelux-latest.osm.pbf")"

( cd "$WORK/www" && python3 -m http.server 8199 >/dev/null 2>&1 ) &
SRV_PID=$!
sleep 1

cat > "$WORK/regions.env" <<EOF
benelux=http://localhost:8199/benelux-latest.osm.pbf
luxembourg=http://localhost:8199/luxembourg-latest.osm.pbf
EOF

run() {
    env OSM_REGION="${1:-}" OSM_EXTRACT_URL="${2:-}" OSM_REFRESH="${3:-}" \
        REGIONS_FILE="$WORK/regions.env" OSM_DIR="$WORK/osm" \
        sh "$SCRIPT"
}

fail() { echo "FAIL: $1"; exit 1; }

# 1. Region resolved from regions.env, downloaded, verified.
run benelux "" || fail "clean download"
[ -f "$WORK/osm/region.osm.pbf" ] || fail "no output file"
[ "$(md5_of "$WORK/osm/region.osm.pbf")" = "$MD5" ] || fail "bad content"

# 2. A restart keeps the extract it has, even once Geofabrik publishes a newer one
#    (#1077): nothing would import it before the graph is rebuilt.
publish benelux 'newer-pbf-bytes'
NEW_MD5="$(md5_of "$WORK/www/benelux-latest.osm.pbf")"
OUT="$(run benelux "")"
echo "$OUT" | grep -qi 'not re-downloaded' || fail "expected a kept extract, got: $OUT"
[ "$(md5_of "$WORK/osm/region.osm.pbf")" = "$MD5" ] || fail "restart replaced the extract"

# 3. An extract from before #1077 has no .src; it is kept too.
rm -f "$WORK/osm/region.osm.pbf.src"
OUT="$(run benelux "")"
echo "$OUT" | grep -qi 'not re-downloaded' || fail "expected a kept pre-#1077 extract, got: $OUT"
[ "$(cat "$WORK/osm/region.osm.pbf.src")" = "http://localhost:8199/benelux-latest.osm.pbf" ] \
    || fail "kept pre-#1077 extract did not record its .src"

# 4. OSM_REFRESH=1 (the graph rebuild) fetches the newer extract.
run benelux "" 1 || fail "refresh"
[ "$(md5_of "$WORK/osm/region.osm.pbf")" = "$NEW_MD5" ] || fail "refresh kept the old extract"

# 5. OSM_REFRESH=1 when the file already matches the .md5 downloads nothing.
OUT="$(run benelux "" 1)"
echo "$OUT" | grep -qi 'up to date' || fail "expected up-to-date skip, got: $OUT"

# 6. A different region downloads even without OSM_REFRESH.
publish luxembourg 'lux-pbf-bytes'
run luxembourg "" || fail "region change"
[ "$(md5_of "$WORK/osm/region.osm.pbf")" = "$(md5_of "$WORK/www/luxembourg-latest.osm.pbf")" ] \
    || fail "region change kept the old extract"

# 6b. A pre-#1077 extract kept once still sees a later region change: the keep
#     recorded its .src, so switching back to benelux downloads.
rm -f "$WORK/osm/region.osm.pbf.src"
run luxembourg "" >/dev/null || fail "pre-#1077 keep"
run benelux "" || fail "region change after a pre-#1077 keep"
[ "$(md5_of "$WORK/osm/region.osm.pbf")" = "$NEW_MD5" ] \
    || fail "region change after a pre-#1077 keep kept the old extract"

# 7. OSM_EXTRACT_URL overrides the region.
rm -f "$WORK/osm/region.osm.pbf"
run "" "http://localhost:8199/benelux-latest.osm.pbf" || fail "url override"
[ "$(md5_of "$WORK/osm/region.osm.pbf")" = "$NEW_MD5" ] || fail "url override bad content"

# 8. Unknown region aborts non-zero.
if run nowhere ""; then fail "unknown region should abort"; fi

# 9. md5 mismatch aborts non-zero and leaves no file.
rm -f "$WORK/osm/region.osm.pbf"
printf 'deadbeef  benelux-latest.osm.pbf\n' > "$WORK/www/benelux-latest.osm.pbf.md5"
if run benelux ""; then fail "md5 mismatch should abort"; fi
[ ! -f "$WORK/osm/region.osm.pbf" ] || fail "mismatch left a file behind"

# 10. Neither env set aborts non-zero.
if run "" ""; then fail "no region and no url should abort"; fi

echo "PASS"
