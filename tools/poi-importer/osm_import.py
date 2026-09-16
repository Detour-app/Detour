#!/usr/bin/env python3
"""OSM point-of-interest importer, from a Geofabrik .pbf extract.

One pass, mirrors tools/roads-importer/osm_import.py's shape, adapted for points: a node's own
tags are checked directly; a way's tags are checked the same way and its point is the plain
average of its own nodes' locations (a centroid, not a true polygon centroid — good enough for
"where do I point the map", which is the only use `PoiRoulette.randomPoi` (shared/) puts it to).
No relations — `historic`/`tourism` multipolygons exist but are a small minority of matches, and
skipping them keeps this importer a single pyosmium pass like every other one in tools/.

Serves issue #383 (phase 5 of #302, spin's random-POI feature) — same OSM-extract-off-Overpass
approach as tools/roads-importer/ (#380/#382) and tools/speedlimit-importer/ (#379).

Usage: python3 osm_import.py <region.osm.pbf> --region <name> --out <out.json>
       python3 osm_import.py --demo   # self-check against the Luxembourg extract
"""
import argparse, json, datetime, sys
import osmium

# Mirrors PoiKind.selectors in shared/'s PoiRoulette.kt — kind names are lowercase to match
# PoiKind.name.lowercase(), the wire value both sides agree on.
KIND_TAGS = {
    "viewpoint": lambda tags: tags.get("tourism") == "viewpoint",
    "food": lambda tags: tags.get("amenity") in {"cafe", "restaurant", "pub", "bar", "ice_cream"},
    "sight": lambda tags: (
        tags.get("historic") in {"castle", "ruins", "monument", "fort", "memorial"}
        or tags.get("tourism") == "attraction"
    ),
}


def _kind_of(tags):
    for kind, matches in KIND_TAGS.items():
        if matches(tags):
            return kind
    return None


class PoiPass(osmium.SimpleHandler):
    """Every matching node and way, geometry inline via pyosmium's own node-location cache
    (`locations=True` on `apply_file` below)."""

    def __init__(self):
        super().__init__()
        self.pois = []

    def node(self, n):
        kind = _kind_of(n.tags)
        if kind is None or not n.location.valid():
            return
        self.pois.append({
            "sourceId": f"n{n.id}",
            "kind": kind,
            "name": n.tags.get("name", ""),
            "lat": n.location.lat,
            "lon": n.location.lon,
        })

    def way(self, w):
        kind = _kind_of(w.tags)
        if kind is None:
            return
        locations = [nd.location for nd in w.nodes if nd.location.valid()]
        if not locations:
            return
        self.pois.append({
            "sourceId": f"w{w.id}",
            "kind": kind,
            "name": w.tags.get("name", ""),
            "lat": sum(loc.lat for loc in locations) / len(locations),
            "lon": sum(loc.lon for loc in locations) / len(locations),
        })


def import_region(pbf_path, region):
    handler = PoiPass()
    handler.apply_file(pbf_path, locations=True)
    return {
        "source": "osm",
        "region": region,
        "generatedAt": datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat(),
        "pois": handler.pois,
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
    print(f"wrote {args.out}: {len(result['pois'])} pois")


def _demo():
    """Self-check: import Luxembourg (smallest Geofabrik extract) and sanity-check counts.
    Run directly: python3 osm_import.py --demo"""
    import tempfile, urllib.request
    url = "https://download.geofabrik.de/europe/luxembourg-latest.osm.pbf"
    with tempfile.NamedTemporaryFile(suffix=".pbf") as f:
        print(f"downloading {url} ...")
        urllib.request.urlretrieve(url, f.name)
        result = import_region(f.name, region="luxembourg")
    assert len(result["pois"]) > 100, f"expected >100 POIs, got {len(result['pois'])}"
    print(f"OK: {len(result['pois'])} pois")


if __name__ == "__main__":
    if "--demo" in sys.argv:
        _demo()
    else:
        main()
