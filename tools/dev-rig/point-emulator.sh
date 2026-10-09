#!/usr/bin/env bash
# Point the debug build on an Android emulator at the local rig (#612).
#
#   tools/dev-rig/point-emulator.sh [adb-serial]
#
# Writes the same keys Settings → Servers & sync saves (RoutingServer.save):
#   url, api_url  -> http://10.0.2.2:7500   the rig's API (roads, POIs, speed limits)
#   routing_url   -> http://10.0.2.2:7510   the rig's GraphHopper
#   geocoder_url  -> https://photon.komoot.io, only if blank (see below)
# and leaves every other key in routing_server.xml as it was. 10.0.2.2 is the
# emulator's alias for the host's loopback, where the rig's ports are bound.
#
# Only the .debug package, only through run-as, and only shared_prefs/
# routing_server.xml. It force-stops the app first (so its in-memory prefs
# cannot overwrite the file on the next commit) — that is the whole of what it
# does to the app. No pm clear, no uninstall, nothing under files/.
set -euo pipefail

PKG=io.github.maxke24.detour.debug
SERIAL="${1:-${ANDROID_SERIAL:-}}"
API="${RIG_API_URL:-http://10.0.2.2:7500}"
ROUTING="${RIG_ROUTING_URL:-http://10.0.2.2:7510}"
GEOCODER="${RIG_GEOCODER_URL:-https://photon.komoot.io}"
PREFS=shared_prefs/routing_server.xml

die() { printf 'point-emulator.sh: %s\n' "$*" >&2; exit 1; }
command -v adb >/dev/null || die "adb is not on PATH"

# No guessing, even with one device attached: this force-stops the app, and
# the one emulator that happens to be up may be someone else's test run.
if [ -z "$SERIAL" ]; then
    attached="$(adb devices | awk 'NR>1 && $2=="device" {printf "%s ", $1}')"
    die "name the device: $0 <serial> (attached: ${attached:-none})"
fi
a() { adb -s "$SERIAL" "$@"; }

# 10.0.2.2 only means "the host" on an emulator. A physical phone would need
# `adb reverse` and localhost instead; refuse rather than write a dead address.
[ "$(a shell getprop ro.kernel.qemu | tr -d '\r')" = 1 ] \
    || [ -n "${RIG_API_URL:-}" ] \
    || die "$SERIAL is not an emulator; set RIG_API_URL/RIG_ROUTING_URL (e.g. after adb reverse) to use it anyway"

a shell pm path "$PKG" >/dev/null 2>&1 \
    || die "$PKG is not installed on $SERIAL — install the debug APK first (./gradlew :app:assembleDebug)"
a shell run-as "$PKG" true >/dev/null 2>&1 \
    || die "run-as refused on $PKG — is it a debuggable build?"

a shell am force-stop "$PKG"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
a exec-out run-as "$PKG" cat "$PREFS" > "$tmp/old.xml" 2>/dev/null || : > "$tmp/old.xml"
# run-as prints its error on stdout when the file is missing.
grep -q '<map' "$tmp/old.xml" || : > "$tmp/old.xml"

python3 -I - "$tmp/old.xml" "$tmp/new.xml" "$API" "$ROUTING" "$GEOCODER" <<'PY'
import sys
import xml.etree.ElementTree as ET

src, dst, api, routing, geocoder = sys.argv[1:6]
try:
    root = ET.parse(src).getroot()
except (ET.ParseError, FileNotFoundError):
    root = ET.Element("map")

def find(name):
    return next((e for e in root if e.get("name") == name), None)

def put_string(name, value):
    e = find(name)
    if e is not None:
        root.remove(e)
    e = ET.SubElement(root, "string", name=name)
    e.text = value

old_api = (find("api_url").text or "") if find("api_url") is not None else ""
old_url = (find("url").text or "") if find("url") is not None else ""

# RoutingServer.save clears what the previous server announced when the API
# base changes; writing the file directly bypasses save(), so do the same here.
if (old_api or old_url) != api:
    stale = {"idp_issuer_discovered", "server_features"}
    for svc in ("routing", "geocoder", "cameras", "speedlimits", "roads", "municipality", "pois"):
        stale |= {f"{svc}_discovered_base", f"{svc}_pending_base", f"{svc}_declined_base"}
    for e in [e for e in root if e.get("name") in stale]:
        root.remove(e)

saved = find("saved")
if saved is not None:
    root.remove(saved)
ET.SubElement(root, "boolean", name="saved", value="true")
put_string("url", api)
put_string("api_url", api)
put_string("routing_url", routing)
# Search: a blank geocoder_url falls back to the general url above — the API,
# which does not serve Photon's /api/?q=, so search and spin-pick names would
# fail. Name the public Photon explicitly (what a fresh install with no server
# uses anyway), unless a geocoder was already typed in.
geo = find("geocoder_url")
if geo is None or not (geo.text or "").strip():
    put_string("geocoder_url", geocoder)
if find("idp_issuer") is None:
    put_string("idp_issuer", "")

ET.indent(root)
with open(dst, "w", encoding="utf-8") as f:
    f.write("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n")
    f.write(ET.tostring(root, encoding="unicode"))
    f.write("\n")
PY

a shell "run-as $PKG sh -c 'mkdir -p shared_prefs && cat > $PREFS'" < "$tmp/new.xml"

# Read back what landed, not what was meant to.
back="$(a exec-out run-as "$PKG" cat "$PREFS")"
for want in "name=\"routing_url\">$ROUTING<" "name=\"url\">$API<" 'name="saved" value="true"'; do
    grep -qF "$want" <<<"$back" || die "routing_server.xml on $SERIAL does not contain $want after writing"
done
echo "$SERIAL: $PKG now uses API $API and routing $ROUTING (app force-stopped; reopen it)"
