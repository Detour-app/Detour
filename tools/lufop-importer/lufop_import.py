#!/usr/bin/env python3
"""LUFOP speed-camera importer, from lufop.net's OsmAnd GPX export.

Despite ending in .osm, LUFOP's "OsmAnd" download is plain GPX 1.1
(<gpx><wpt lat lon><name>...) -- not OSM format, so this doesn't use
pyosmium (see tools/camera-importer/osm_import.py for that one). Camera type
and speed limit are encoded entirely in the free-text French <name> string;
there is no structured tag for either. Vocabulary confirmed across
Belgium/Netherlands/Luxembourg/France/Germany's files (2026-09-15):

    Radar Fixe <CC> <speed>        -> FixedSpeed, maxspeed = <speed>
    Radar Fixe <CC> Passage Niveau -> FixedSpeed, no maxspeed (level crossing)
    Radar Fixe <CC> Covoiturage    -> FixedSpeed, no maxspeed (carpool lane)
    Radar Poid lourd <CC>          -> FixedSpeed, no maxspeed (HGV-specific)
    Radar Tunnel <CC>              -> FixedSpeed, no maxspeed
    Radar Feu Rouge <CC>           -> RedLight
    Radar Chantier <CC>            -> MobileHotspot (roadworks/temporary)
    Radar Troncon Debut/Fin <CC>   -> skipped: no id links a Debut to its Fin,
                                       and they aren't even adjacent in the
                                       file (every Debut, then every Fin) --
                                       pairing would need an unverifiable
                                       distance/road-matching heuristic. OSM's
                                       relation-based sections already cover
                                       trajectcontrole zones with real
                                       geometry; LUFOP's value here is
                                       point-camera density, not sections.

Usage: python3 lufop_import.py <country.osm> --region <name> --out <out.json>
       python3 lufop_import.py --demo   # self-check against an inline fixture
"""
import argparse, json, re, datetime, sys
import xml.etree.ElementTree as ET

GPX_NS = "{http://www.topografix.com/GPX/1/1}"

# Order matters: more specific patterns before the general "<speed>" one, so e.g.
# "Radar Fixe FR Passage Niveau" doesn't fall through to being parsed as a speed.
NAME_PATTERNS = [
    (re.compile(r"^Radar Feu Rouge \w+$"), "RedLight", False),
    (re.compile(r"^Radar Chantier \w+\s*$"), "MobileHotspot", False),
    (re.compile(r"^Radar Fixe \w+ Passage Niveau$"), "FixedSpeed", False),
    (re.compile(r"^Radar Fixe \w+ Covoiturage$"), "FixedSpeed", False),
    (re.compile(r"^Radar Poid lourd \w+$"), "FixedSpeed", False),
    (re.compile(r"^Radar Tunnel \w+$"), "FixedSpeed", False),
    (re.compile(r"^Radar Fixe \w+ (\d+)$"), "FixedSpeed", True),
]
SKIP_PATTERN = re.compile(r"^Radar Troncon (Debut|Fin) \w+$")


def classify(name):
    """(kind, maxSpeedKmh) for a real camera waypoint, or None to skip it."""
    name = name.strip()
    if not name or name == "Base Radars" or SKIP_PATTERN.match(name):
        return None
    for pattern, kind, has_speed in NAME_PATTERNS:
        m = pattern.match(name)
        if m:
            return kind, (int(m.group(1)) if has_speed else None)
    return None  # unrecognised -- skipped, not guessed at


def import_country(gpx_path, region):
    tree = ET.parse(gpx_path)
    root = tree.getroot()
    cameras = []
    unrecognised = set()

    for wpt in root.iter(f"{GPX_NS}wpt"):
        name_el = wpt.find(f"{GPX_NS}name")
        name = (name_el.text or "").strip() if name_el is not None else ""
        classified = classify(name)
        if classified is None:
            if name and name != "Base Radars" and not SKIP_PATTERN.match(name):
                unrecognised.add(name)
            continue
        kind, max_speed = classified
        lat = float(wpt.get("lat"))
        lon = float(wpt.get("lon"))
        cameras.append({
            "sourceId": f"{lat:.6f}_{lon:.6f}",
            "lat": lat,
            "lon": lon,
            "polyline": None,
            "maxSpeedKmh": max_speed,
            "kind": kind,
            "roadRef": None,
        })

    if unrecognised:
        print(f"warning: {len(unrecognised)} unrecognised waypoint name(s), skipped:", file=sys.stderr)
        for n in sorted(unrecognised):
            print(f"  {n!r}", file=sys.stderr)

    return {
        "source": "lufop",
        "region": region,
        "generatedAt": datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat(),
        "cameras": cameras,
    }


def main():
    p = argparse.ArgumentParser()
    p.add_argument("gpx")
    p.add_argument("--region", required=True)
    p.add_argument("--out", required=True)
    args = p.parse_args()

    result = import_country(args.gpx, args.region)
    with open(args.out, "w") as f:
        json.dump(result, f, indent=1)
    print(f"wrote {args.out}: {len(result['cameras'])} cameras")


def _demo():
    """Self-check against an inline fixture -- no network round-trip, unlike
    osm_import.py's --demo (LUFOP's site sits behind a Cloudflare bot-check that
    blocks automated fetching, so there's no reliable small file to download here)."""
    import tempfile
    fixture = '''<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="Lufop https://lufop.net" xmlns="http://www.topografix.com/GPX/1/1">
  <metadata><name>Base Radars</name></metadata>
  <wpt lat="49.599791" lon="6.119708"><name>Radar Feu Rouge LU</name></wpt>
  <wpt lat="49.7297258" lon="5.983834"><name>Radar Fixe LU 50</name></wpt>
  <wpt lat="49.5829" lon="6.308223"><name>Radar Chantier LU</name></wpt>
  <wpt lat="49.1" lon="6.2"><name>Radar Fixe FR Passage Niveau</name></wpt>
  <wpt lat="49.2" lon="6.3"><name>Radar Troncon Debut LU</name></wpt>
  <wpt lat="49.3" lon="6.4"><name>Radar Troncon Fin LU</name></wpt>
  <wpt lat="49.4" lon="6.5"><name>Something Unexpected</name></wpt>
</gpx>'''
    with tempfile.NamedTemporaryFile(suffix=".osm", mode="w", delete=False) as f:
        f.write(fixture)
        path = f.name
    result = import_country(path, region="demo")
    assert len(result["cameras"]) == 4, f"expected 4 real cameras, got {len(result['cameras'])}"
    kinds = {c["kind"] for c in result["cameras"]}
    assert kinds == {"RedLight", "FixedSpeed", "MobileHotspot"}, f"unexpected kinds: {kinds}"
    fixed = next(c for c in result["cameras"] if c["lat"] == 49.7297258)
    assert fixed["maxSpeedKmh"] == 50, f"expected maxSpeedKmh 50, got {fixed['maxSpeedKmh']}"
    print(f"OK: {len(result['cameras'])} cameras, kinds={kinds}")


if __name__ == "__main__":
    if "--demo" in sys.argv:
        _demo()
    else:
        main()
