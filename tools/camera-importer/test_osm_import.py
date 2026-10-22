#!/usr/bin/env python3
"""Self-check for #367's way-geometry/maxspeed-fallback fix and #772's way chaining: builds a tiny synthetic .pbf
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

    # #772's shape (relation 18614352, Oudenburg): member ways out of order, some reversed, and a
    # split carriageway listed as two one-way ways between the same two nodes. The polyline must
    # run 60 -> 64 along one carriageway, not zigzag in member order.
    for nid, lon in ((60, 3.000), (61, 3.005), (62, 3.010), (63, 3.015), (64, 3.020)):
        w.add_node(osmium.osm.mutable.Node(id=nid, location=(lon, 52.000)))
    w.add_node(osmium.osm.mutable.Node(id=65, location=(3.0125, 52.0003)))
    w.add_way(osmium.osm.mutable.Way(id=10, nodes=[61, 60], tags={"highway": "tertiary"}))
    w.add_way(osmium.osm.mutable.Way(id=11, nodes=[61, 62], tags={"highway": "tertiary"}))
    w.add_way(osmium.osm.mutable.Way(id=12, nodes=[62, 65, 63], tags={"highway": "tertiary", "oneway": "yes"}))
    w.add_way(osmium.osm.mutable.Way(id=13, nodes=[63, 62], tags={"highway": "tertiary", "oneway": "yes"}))
    w.add_way(osmium.osm.mutable.Way(id=14, nodes=[64, 63], tags={"highway": "tertiary"}))
    w.add_relation(osmium.osm.mutable.Relation(
        id=400,
        tags={"enforcement": "average_speed", "maxspeed": "50"},
        members=[("w", 13, "section"), ("w", 10, "section"), ("w", 14, "section"),
                 ("w", 11, "section"), ("w", 12, "section")],
    ))

    # Member ways that share no node: the longest connected stretch wins, rather than a
    # straight jump between the two.
    w.add_node(osmium.osm.mutable.Node(id=70, location=(6.000, 52.000)))
    w.add_node(osmium.osm.mutable.Node(id=71, location=(6.005, 52.000)))
    w.add_node(osmium.osm.mutable.Node(id=72, location=(6.010, 52.000)))
    w.add_node(osmium.osm.mutable.Node(id=73, location=(6.100, 52.000)))
    w.add_node(osmium.osm.mutable.Node(id=74, location=(6.104, 52.000)))
    w.add_way(osmium.osm.mutable.Way(id=20, nodes=[70, 71, 72], tags={"highway": "primary"}))
    w.add_way(osmium.osm.mutable.Way(id=21, nodes=[73, 74], tags={"highway": "primary"}))
    w.add_relation(osmium.osm.mutable.Relation(
        id=500,
        tags={"enforcement": "average_speed", "maxspeed": "70"},
        members=[("w", 21, "section"), ("w", 20, "section")],
    ))

    w.close()


def main():
    with tempfile.TemporaryDirectory() as d:
        pbf = os.path.join(d, "fixture.osm.pbf")
        _write_fixture(pbf)
        result = import_region(pbf, region="fixture")

    sections = [c for c in result["cameras"] if c["kind"] == "Section"]
    assert len(sections) == 4, f"expected 4 sections, got {len(sections)}"

    way_section = next(s for s in sections if s["sourceId"] == "r100")
    assert way_section["polyline"] == [[50.000, 4.000], [50.005, 4.010], [50.010, 4.020]], (
        f"section polyline should follow the way's 3 points, got {way_section['polyline']}"
    )

    fallback_section = next(s for s in sections if s["sourceId"] == "r300")
    assert fallback_section["polyline"] == [[51.000, 5.000], [51.010, 5.010]], (
        f"way-less section should still use the node-pair straight line, got {fallback_section['polyline']}"
    )

    chained = next(s for s in sections if s["sourceId"] == "r400")["polyline"]
    along = [[52.000, 3.000], [52.000, 3.005], [52.000, 3.010], [52.000, 3.015], [52.000, 3.020]]
    assert chained in (along, along[::-1]), (
        f"out-of-order ways should chain end to end along one carriageway, got {chained}"
    )

    unchained = next(s for s in sections if s["sourceId"] == "r500")["polyline"]
    longest = [[52.000, 6.000], [52.000, 6.005], [52.000, 6.010]]
    assert unchained in (longest, longest[::-1]), (
        f"ways that don't connect should keep the longest stretch, got {unchained}"
    )

    fallback_cams = [c for c in result["cameras"] if c["sourceId"] == "r200"]
    assert len(fallback_cams) == 1, f"expected 1 camera from the tagless relation, got {len(fallback_cams)}"
    assert fallback_cams[0]["maxSpeedKmh"] == 90, (
        f"expected maxspeed fallback from the device node's own tag, got {fallback_cams[0]['maxSpeedKmh']}"
    )

    print(f"OK: section polyline chains way geometry, maxspeed fallback works ({len(result['cameras'])} cameras/sections)")


if __name__ == "__main__":
    main()
