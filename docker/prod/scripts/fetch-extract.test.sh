#!/bin/sh
# POSIX-sh test for fetch-extract.sh. Serves a fixture .pbf + .md5 over a local
# HTTP server (python3 -m http.server) and drives the script through its paths.
set -eu

HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$HERE/fetch-extract.sh"
WORK="$(mktemp -d)"
trap 'kill "$SRV_PID" 2>/dev/null || true; rm -rf "$WORK"' EXIT

# Fixture: a "pbf" of known content, and its real md5, served as region files.
mkdir -p "$WORK/www" "$WORK/osm"
printf 'fake-pbf-bytes' > "$WORK/www/benelux-latest.osm.pbf"
MD5="$(md5sum "$WORK/www/benelux-latest.osm.pbf" | cut -d' ' -f1)"
printf '%s  benelux-latest.osm.pbf\n' "$MD5" > "$WORK/www/benelux-latest.osm.pbf.md5"

( cd "$WORK/www" && python3 -m http.server 8199 >/dev/null 2>&1 ) &
SRV_PID=$!
sleep 1

cat > "$WORK/regions.env" <<EOF
benelux=http://localhost:8199/benelux-latest.osm.pbf
EOF

run() {
    env OSM_REGION="${1:-}" OSM_EXTRACT_URL="${2:-}" \
        REGIONS_FILE="$WORK/regions.env" OSM_DIR="$WORK/osm" \
        sh "$SCRIPT"
}

fail() { echo "FAIL: $1"; exit 1; }

# 1. Region resolved from regions.env, downloaded, verified.
run benelux "" || fail "clean download"
[ -f "$WORK/osm/region.osm.pbf" ] || fail "no output file"
[ "$(md5sum "$WORK/osm/region.osm.pbf" | cut -d' ' -f1)" = "$MD5" ] || fail "bad content"

# 2. Second run is a no-op (already current).
OUT="$(run benelux "")"
echo "$OUT" | grep -qi 'up to date' || fail "expected up-to-date skip, got: $OUT"

# 3. OSM_EXTRACT_URL overrides the region.
rm -f "$WORK/osm/region.osm.pbf"
run "" "http://localhost:8199/benelux-latest.osm.pbf" || fail "url override"
[ -f "$WORK/osm/region.osm.pbf" ] || fail "url override produced no file"

# 4. Unknown region aborts non-zero.
if run nowhere ""; then fail "unknown region should abort"; fi

# 5. md5 mismatch aborts non-zero and leaves no file.
rm -f "$WORK/osm/region.osm.pbf"
printf 'deadbeef  benelux-latest.osm.pbf\n' > "$WORK/www/benelux-latest.osm.pbf.md5"
if run benelux ""; then fail "md5 mismatch should abort"; fi
[ ! -f "$WORK/osm/region.osm.pbf" ] || fail "mismatch left a file behind"

# 6. Neither env set aborts non-zero.
if run "" ""; then fail "no region and no url should abort"; fi

echo "PASS"
