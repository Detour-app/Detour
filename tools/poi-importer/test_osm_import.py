#!/usr/bin/env python3
"""Self-check for osm_import.py: builds a tiny synthetic .pbf (osmium has no easy way to fake
one from a fixture file) and asserts on the parsed output.
Run directly: python3 test_osm_import.py"""
import tempfile, os
import osmium
from osm_import import import_region


def _write_fixture(path):
    w = osmium.SimpleWriter(path)

    # A viewpoint node with a name — kept as-is, no centroid needed.
    w.add_node(osmium.osm.mutable.Node(
        id=1, location=(6.100, 49.600), tags={"tourism": "viewpoint", "name": "Belvedere"},
    ))

    # A cafe node with no name tag — kept, name comes back blank.
    w.add_node(osmium.osm.mutable.Node(
        id=2, location=(6.200, 49.700), tags={"amenity": "cafe"},
    ))

    # Not a recognised kind (a bakery) — excluded.
    w.add_node(osmium.osm.mutable.Node(
        id=3, location=(6.300, 49.800), tags={"shop": "bakery"},
    ))

    # A castle way — point is the average of its own nodes' locations.
    w.add_node(osmium.osm.mutable.Node(id=10, location=(5.000, 50.000)))
    w.add_node(osmium.osm.mutable.Node(id=11, location=(5.010, 50.010)))
    w.add_way(osmium.osm.mutable.Way(
        id=100, nodes=[10, 11], tags={"historic": "castle", "name": "Old Castle"},
    ))

    w.close()


def main():
    with tempfile.TemporaryDirectory() as d:
        pbf = os.path.join(d, "fixture.osm.pbf")
        _write_fixture(pbf)
        result = import_region(pbf, region="fixture")

    pois = {p["sourceId"]: p for p in result["pois"]}
    assert set(pois) == {"n1", "n2", "w100"}, f"expected the three matching elements, got {set(pois)}"

    viewpoint = pois["n1"]
    assert viewpoint["kind"] == "viewpoint"
    assert viewpoint["name"] == "Belvedere"
    assert viewpoint["lat"] == 49.600 and viewpoint["lon"] == 6.100

    cafe = pois["n2"]
    assert cafe["kind"] == "food"
    assert cafe["name"] == "", "an element with no name tag must come back with a blank name"

    castle = pois["w100"]
    assert castle["kind"] == "sight"
    assert castle["name"] == "Old Castle"
    assert abs(castle["lat"] - 50.005) < 1e-9, "way point should be the average of its own nodes"
    assert abs(castle["lon"] - 5.005) < 1e-9

    print(f"OK: {len(result['pois'])} pois, non-matching elements excluded")


if __name__ == "__main__":
    main()
