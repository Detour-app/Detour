#!/usr/bin/env python3
"""OSM drivable-way speed-limit importer, from a Geofabrik .pbf extract.

One pass, unlike tools/camera-importer/osm_import.py's four: a way carries its own tags and,
with pyosmium's `locations=True`, its own node coordinates inline as the file streams past —
there are no relations to resolve device nodes against, so no want-list pass is needed.

Usage: python3 osm_import.py <region.osm.pbf> --region <name> --out <out.json>
       python3 osm_import.py --demo   # self-check against the Luxembourg extract
"""
import argparse, json, datetime, sys
import osmium

# Mirrors RoadRoulette.DRIVABLE_HIGHWAYS in shared/ — the classes a car/moto can legally be
# on, excluding the footways, cycleways, service roads and tracks that would otherwise pollute
# the ambient speed-limit sign.
DRIVABLE_HIGHWAYS = {
    "motorway", "trunk", "primary", "secondary", "tertiary",
    "unclassified", "residential", "living_street",
    "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link",
}


def parse_maxspeed(v):
    """Mirrors RoadRoulette.parseMaxSpeed's number/mph/km/h handling. Deliberately narrower:
    zone/urban/living_street implicit defaults are a client-side judgement call
    (RoadRoulette.kt's own comment on that), not something this importer should bake into
    the dataset."""
    if not v:
        return None
    v = str(v).strip().lower()
    if v.endswith("mph"):
        try:
            return round(float(v[:-3].strip()) * 1.609344)
        except ValueError:
            return None
    if v.endswith("km/h"):
        v = v[:-4].strip()
    elif v.endswith("kmh"):
        v = v[:-3].strip()
    try:
        return int(float(v))
    except ValueError:
        return None


class WayPass(osmium.SimpleHandler):
    """Every drivable way with a parseable `maxspeed`, geometry inline via pyosmium's own
    node-location cache (`locations=True` on `apply_file` below)."""

    def __init__(self):
        super().__init__()
        self.ways = []

    def way(self, w):
        if w.tags.get("highway") not in DRIVABLE_HIGHWAYS:
            return
        kmh = parse_maxspeed(w.tags.get("maxspeed"))
        if kmh is None:
            return
        pts = [(nd.location.lat, nd.location.lon) for nd in w.nodes if nd.location.valid()]
        if len(pts) < 2:
            return
        self.ways.append({
            "sourceId": f"w{w.id}",
            "maxSpeedKmh": kmh,
            "polyline": [[p[0], p[1]] for p in pts],
        })


def import_region(pbf_path, region):
    handler = WayPass()
    handler.apply_file(pbf_path, locations=True)
    return {
        "source": "osm",
        "region": region,
        "generatedAt": datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat(),
        "ways": handler.ways,
    }


def main():
    p = argparse.ArgumentParser()
    p.add_argument("pbf")
    p.add_argument("--region", required=True)
    p.add_argument("--out", required=True)
    args = p.parse_args()

    result = import_region(args.pbf, args.region)
    with open(args.out, "w") as f:
        json.dump(result, f, indent=1)
    print(f"wrote {args.out}: {len(result['ways'])} ways")


def _demo():
    """Self-check: import Luxembourg (smallest Geofabrik extract) and sanity-check counts.
    Run directly: python3 osm_import.py --demo"""
    import tempfile, urllib.request
    url = "https://download.geofabrik.de/europe/luxembourg-latest.osm.pbf"
    with tempfile.NamedTemporaryFile(suffix=".pbf") as f:
        print(f"downloading {url} ...")
        urllib.request.urlretrieve(url, f.name)
        result = import_region(f.name, region="luxembourg")
    assert len(result["ways"]) > 1000, f"expected >1000 tagged drivable ways, got {len(result['ways'])}"
    print(f"OK: {len(result['ways'])} ways")


if __name__ == "__main__":
    if "--demo" in sys.argv:
        _demo()
    else:
        main()
