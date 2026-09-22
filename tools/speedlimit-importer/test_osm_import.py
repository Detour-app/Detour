#!/usr/bin/env python3
"""Self-check for osm_import.py: builds a tiny synthetic .pbf (osmium has no easy way to fake
one from a fixture file) and asserts on the parsed output.
Run directly: python3 test_osm_import.py"""
import tempfile, os
import osmium
from osm_import import import_region


def _write_fixture(path):
    w = osmium.SimpleWriter(path)

    # A tagged, drivable way — the ordinary case.
    w.add_node(osmium.osm.mutable.Node(id=1, location=(4.000, 50.000)))
    w.add_node(osmium.osm.mutable.Node(id=2, location=(4.010, 50.005)))
    w.add_node(osmium.osm.mutable.Node(id=3, location=(4.020, 50.010)))
    w.add_way(osmium.osm.mutable.Way(
        id=10, nodes=[1, 2, 3], tags={"highway": "primary", "maxspeed": "70"},
    ))

    # Not drivable (footway) — excluded even though it carries a maxspeed tag.
    w.add_node(osmium.osm.mutable.Node(id=4, location=(5.000, 51.000)))
    w.add_node(osmium.osm.mutable.Node(id=5, location=(5.010, 51.010)))
    w.add_way(osmium.osm.mutable.Way(
        id=20, nodes=[4, 5], tags={"highway": "footway", "maxspeed": "5"},
    ))

    # Drivable but untagged — excluded, no limit to snap against.
    w.add_node(osmium.osm.mutable.Node(id=6, location=(6.000, 52.000)))
    w.add_node(osmium.osm.mutable.Node(id=7, location=(6.010, 52.010)))
    w.add_way(osmium.osm.mutable.Way(id=30, nodes=[6, 7], tags={"highway": "residential"}))

    # mph, converted.
    w.add_node(osmium.osm.mutable.Node(id=8, location=(7.000, 53.000)))
    w.add_node(osmium.osm.mutable.Node(id=9, location=(7.010, 53.010)))
    w.add_way(osmium.osm.mutable.Way(
        id=40, nodes=[8, 9], tags={"highway": "trunk", "maxspeed": "30 mph"},
    ))

    w.close()


def main():
    with tempfile.TemporaryDirectory() as d:
        pbf = os.path.join(d, "fixture.osm.pbf")
        _write_fixture(pbf)
        result = import_region(pbf, region="fixture")

    ways = {w["sourceId"]: w for w in result["ways"]}
    assert set(ways) == {"w10", "w40"}, f"expected only the tagged drivable ways, got {set(ways)}"

    tagged = ways["w10"]
    assert tagged["maxSpeedKmh"] == 70
    assert tagged["polyline"] == [[50.000, 4.000], [50.005, 4.010], [50.010, 4.020]], (
        f"polyline should follow the way's node order, got {tagged['polyline']}"
    )

    mph = ways["w40"]
    assert mph["maxSpeedKmh"] == round(30 * 1.609344), f"expected mph converted, got {mph['maxSpeedKmh']}"

    print(f"OK: {len(result['ways'])} ways, non-drivable and untagged ways excluded")


if __name__ == "__main__":
    main()
