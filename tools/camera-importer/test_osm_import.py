#!/usr/bin/env python3
"""Self-check for #367's way-geometry/maxspeed-fallback fix: builds a tiny synthetic .pbf
(osmium has no easy way to fake one from a fixture file) and asserts on the parsed output.
Run directly: python3 test_osm_import.py"""
import tempfile, os
import osmium
from osm_import import import_region


def _write_fixture(path):
    w = osmium.SimpleWriter(path)

    # A 3-point way (a real road segment) that an average_speed relation references by way
    # member — the section polyline should follow these points, not the from/to device pair.
    w.add_node(osmium.osm.mutable.Node(id=20, location=(4.000, 50.000)))
    w.add_node(osmium.osm.mutable.Node(id=21, location=(4.010, 50.005)))
    w.add_node(osmium.osm.mutable.Node(id=22, location=(4.020, 50.010)))
    w.add_way(osmium.osm.mutable.Way(id=5, nodes=[20, 21, 22], tags={"highway": "primary"}))

    # from/to device markers, off the way itself (as real average_speed relations have).
    w.add_node(osmium.osm.mutable.Node(id=30, location=(4.000, 50.000)))
    w.add_node(osmium.osm.mutable.Node(id=31, location=(4.020, 50.010)))

    w.add_relation(osmium.osm.mutable.Relation(
        id=100,
        tags={"enforcement": "average_speed", "maxspeed": "70", "ref": "N1"},
        members=[("w", 5, ""), ("n", 30, "from"), ("n", 31, "to")],
    ))

    # A maxspeed-enforcement relation with no maxspeed tag of its own — the device node
    # carries it instead, and the fallback should pick it up.
    w.add_node(osmium.osm.mutable.Node(id=40, location=(4.5, 50.5), tags={"maxspeed": "90"}))
    w.add_relation(osmium.osm.mutable.Relation(
        id=200,
        tags={"enforcement": "maxspeed"},
        members=[("n", 40, "device")],
    ))

    # A section with no way member at all (older mapping style) — should still fall back to
    # the pre-existing furthest-node-pair straight line, unchanged.
    w.add_node(osmium.osm.mutable.Node(id=50, location=(5.000, 51.000)))
    w.add_node(osmium.osm.mutable.Node(id=51, location=(5.010, 51.010)))
    w.add_relation(osmium.osm.mutable.Relation(
        id=300,
        tags={"enforcement": "average_speed", "maxspeed": "90"},
        members=[("n", 50, "from"), ("n", 51, "to")],
    ))

    w.close()


def main():
    with tempfile.TemporaryDirectory() as d:
        pbf = os.path.join(d, "fixture.osm.pbf")
        _write_fixture(pbf)
        result = import_region(pbf, region="fixture")

    sections = [c for c in result["cameras"] if c["kind"] == "Section"]
    assert len(sections) == 2, f"expected 2 sections, got {len(sections)}"

    way_section = next(s for s in sections if s["sourceId"] == "r100")
    assert way_section["polyline"] == [[50.000, 4.000], [50.005, 4.010], [50.010, 4.020]], (
        f"section polyline should follow the way's 3 points, got {way_section['polyline']}"
    )

    fallback_section = next(s for s in sections if s["sourceId"] == "r300")
    assert fallback_section["polyline"] == [[51.000, 5.000], [51.010, 5.010]], (
        f"way-less section should still use the node-pair straight line, got {fallback_section['polyline']}"
    )

    fallback_cams = [c for c in result["cameras"] if c["sourceId"] == "r200"]
    assert len(fallback_cams) == 1, f"expected 1 camera from the tagless relation, got {len(fallback_cams)}"
    assert fallback_cams[0]["maxSpeedKmh"] == 90, (
        f"expected maxspeed fallback from the device node's own tag, got {fallback_cams[0]['maxSpeedKmh']}"
    )

    print(f"OK: section polyline uses way geometry, maxspeed fallback works ({len(result['cameras'])} cameras/sections)")


if __name__ == "__main__":
    main()
