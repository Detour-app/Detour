#!/usr/bin/env python3
"""OSM admin_level=8 municipality-boundary importer, from a Geofabrik .pbf extract.

Serves issue #381 (phase 4 of #302): moves MunicipalityStore.fetch's live Overpass relation
query off the public API onto Detour's own backend, same OSM-extract-off-Overpass approach as
tools/speedlimit-importer/ and tools/roads-importer/.

Only relation-based boundaries are kept, matching MunicipalityStore.fetch's own Overpass query
(`relation(pivot.a)[boundary=administrative][admin_level=8]`) exactly: a plain closed way
carrying the same tags is a shape Overpass never returned here, and keeping it would need a
second id namespace to avoid colliding with a relation's numeric id in MunicipalityBoundaryDto.

Usage: python3 osm_import.py <region.osm.pbf> --region <name> --out <out.json>
       python3 osm_import.py --demo   # self-check against the Luxembourg extract
"""
import argparse, json, datetime, sys
import osmium

ADMIN_LEVEL = "8"


def import_region(pbf_path, region):
    boundaries = []
    fp = osmium.FileProcessor(pbf_path).with_areas()
    for area in fp:
        if not area.is_area() or area.from_way():
            continue
        tags = area.tags
        if tags.get("boundary") != "administrative" or tags.get("admin_level") != ADMIN_LEVEL:
            continue
        name = tags.get("name")
        if not name:
            continue

        rings = []
        for outer in area.outer_rings():
            ring = _ring_points(outer)
            if len(ring) >= 3:
                rings.append(ring)
            for inner in area.inner_rings(outer):
                iring = _ring_points(inner)
                if len(iring) >= 3:
                    rings.append(iring)
        if not rings:
            continue

        boundaries.append({
            "sourceId": f"r{area.orig_id()}",
            "name": name,
            "rings": rings,
        })

    return {
        "source": "osm",
        "region": region,
        "generatedAt": datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat(),
        "boundaries": boundaries,
    }


def _ring_points(ring):
    """A ring's own points, closing segment dropped: OSM repeats the first node as the last to
    close a way; MunicipalityBoundary.Create (backend) and Municipality.rings (client, via
    MunicipalityStore.assembleRings) both expect it implied instead, not repeated."""
    pts = [(n.lat, n.lon) for n in ring]
    if len(pts) > 1 and pts[0] == pts[-1]:
        pts = pts[:-1]
    return pts


def main():
    p = argparse.ArgumentParser()
    p.add_argument("pbf")
    p.add_argument("--region", required=True)
    p.add_argument("--out", required=True)
    args = p.parse_args()

    result = import_region(args.pbf, args.region)
    with open(args.out, "w") as f:
        json.dump(result, f, indent=1)
    print(f"wrote {args.out}: {len(result['boundaries'])} boundaries")


def _demo():
    """Self-check: import Luxembourg (smallest Geofabrik extract) and sanity-check counts.
    Run directly: python3 osm_import.py --demo"""
    import tempfile, urllib.request
    url = "https://download.geofabrik.de/europe/luxembourg-latest.osm.pbf"
    with tempfile.NamedTemporaryFile(suffix=".pbf") as f:
        print(f"downloading {url} ...")
        urllib.request.urlretrieve(url, f.name)
        result = import_region(f.name, region="luxembourg")
    assert len(result["boundaries"]) > 50, f"expected >50 communes, got {len(result['boundaries'])}"
    print(f"OK: {len(result['boundaries'])} boundaries")


if __name__ == "__main__":
    if "--demo" in sys.argv:
        _demo()
    else:
        main()
