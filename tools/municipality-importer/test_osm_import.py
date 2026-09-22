#!/usr/bin/env python3
"""Self-check for osm_import.py: builds a tiny synthetic .pbf and asserts on the parsed output.
Run directly: python3 test_osm_import.py"""
import tempfile, os
import osmium
import osmium.osm.mutable as mut
from osm_import import import_region


def _write_fixture(path):
    w = osmium.SimpleWriter(path)

    # A relation-based admin_level=8 boundary with a hole (an enclave) — kept, both rings.
    w.add_node(mut.Node(id=1, location=(4.000, 50.000), version=1))
    w.add_node(mut.Node(id=2, location=(4.020, 50.000), version=1))
    w.add_node(mut.Node(id=3, location=(4.020, 50.020), version=1))
    w.add_node(mut.Node(id=4, location=(4.000, 50.020), version=1))
    w.add_way(mut.Way(id=100, nodes=[1, 2, 3, 4, 1], tags={}, version=1))
    w.add_node(mut.Node(id=11, location=(4.008, 50.008), version=1))
    w.add_node(mut.Node(id=12, location=(4.012, 50.008), version=1))
    w.add_node(mut.Node(id=13, location=(4.012, 50.012), version=1))
    w.add_node(mut.Node(id=14, location=(4.008, 50.012), version=1))
    w.add_way(mut.Way(id=101, nodes=[11, 12, 13, 14, 11], tags={}, version=1))
    w.add_relation(mut.Relation(
        id=1000,
        members=[('w', 100, 'outer'), ('w', 101, 'inner')],
        tags={"type": "boundary", "boundary": "administrative", "admin_level": "8", "name": "Testville"},
        version=1,
    ))

    # A plain closed way carrying the same tags directly — excluded, matches
    # MunicipalityStore.fetch's relation-only Overpass query.
    w.add_node(mut.Node(id=21, location=(5.000, 51.000), version=1))
    w.add_node(mut.Node(id=22, location=(5.010, 51.000), version=1))
    w.add_node(mut.Node(id=23, location=(5.010, 51.010), version=1))
    w.add_way(mut.Way(id=200, nodes=[21, 22, 23, 21], tags={
        "boundary": "administrative", "admin_level": "8", "name": "WayVille",
    }, version=1))

    # admin_level=9 (a lower administrative tier) — excluded.
    w.add_node(mut.Node(id=31, location=(6.000, 52.000), version=1))
    w.add_node(mut.Node(id=32, location=(6.010, 52.000), version=1))
    w.add_node(mut.Node(id=33, location=(6.010, 52.010), version=1))
    w.add_way(mut.Way(id=300, nodes=[31, 32, 33, 31], tags={}, version=1))
    w.add_relation(mut.Relation(
        id=2000,
        members=[('w', 300, 'outer')],
        tags={"type": "boundary", "boundary": "administrative", "admin_level": "9", "name": "SubVille"},
        version=1,
    ))

    w.close()


def main():
    with tempfile.TemporaryDirectory() as d:
        pbf = os.path.join(d, "fixture.osm.pbf")
        _write_fixture(pbf)
        result = import_region(pbf, region="fixture")

    boundaries = {b["sourceId"]: b for b in result["boundaries"]}
    assert set(boundaries) == {"r1000"}, f"expected only the relation-based admin_level=8 boundary, got {set(boundaries)}"

    b = boundaries["r1000"]
    assert b["name"] == "Testville"
    assert len(b["rings"]) == 2, f"expected outer + inner ring, got {len(b['rings'])}"
    outer, inner = b["rings"]
    assert outer[0] == (50.000, 4.000), f"ring points should be (lat, lon), got {outer[0]}"
    assert outer[-1] != outer[0], "closing segment must not be repeated"
    assert len(outer) == 4 and len(inner) == 4

    print(f"OK: {len(result['boundaries'])} boundaries, way-based and admin_level=9 excluded")


if __name__ == "__main__":
    main()
