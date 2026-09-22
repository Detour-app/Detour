#!/usr/bin/env python3
"""OSM speed-camera + enforcement-section importer, from a Geofabrik .pbf extract.

Same node/relation-folding rules as tools/belgium-enforcement/build_dataset.py (see that file's
README for the "why all three OSM encodings" background) — ported from Overpass JSON onto
pyosmium's streaming pbf reader, which needs two passes: node locations first (ways/relations
reference node ids, not inline coordinates), then ways/relations resolved against them.

Usage: python3 osm_import.py <region.osm.pbf> --region <name> --out <out.json>
       python3 osm_import.py --demo   # self-check against the Luxembourg extract
"""
import argparse, json, math, datetime, sys
import osmium

SAME_CAM_M = 12.0
END_CLUSTER_M = 120.0
MIN_SPAN_M = 200.0


def hav(a, b):
    R = 6371000.0
    la1, lo1, la2, lo2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    dla, dlo = la2 - la1, lo2 - lo1
    h = math.sin(dla / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin(dlo / 2) ** 2
    return 2 * R * math.asin(math.sqrt(h))


def parse_maxspeed(v):
    if not v:
        return None
    v = str(v).strip().lower()
    if v.endswith("mph"):
        try:
            return round(float(v[:-3].strip()) * 1.609344)
        except ValueError:
            return None
    try:
        return int(float(v))
    except ValueError:
        return None


class NodeLocationPass(osmium.SimpleHandler):
    """Pass 1: every speed_camera node, plus every node id referenced by an enforcement
    relation (device/from/to members) — those are looked up by id in pass 2, and pyosmium's
    relation members carry only ids, not coordinates."""

    def __init__(self):
        super().__init__()
        self.cameras = {}  # node id -> camera dict
        # node id -> Location, for every node any relation might need. A country-scale extract
        # (Belgium alone: ~19M nodes) OOMs a plain Python dict of (lat, lon) tuples — each entry
        # costs several times a raw float pair once CPython's per-object overhead is counted.
        # osmium's own sparse_mmap_array index stores the same data compactly off-heap, which is
        # what NodeLocationsForWays is built on; used directly here since ways aren't consumed
        # by this pass, only plain node lookups by id are.
        self.locations = osmium.index.create_map("sparse_mmap_array")

    def node(self, n):
        if n.tags.get("highway") == "speed_camera":
            self.cameras[n.id] = {
                "sourceId": f"n{n.id}",
                "lat": n.location.lat,
                "lon": n.location.lon,
                "maxSpeedKmh": parse_maxspeed(n.tags.get("maxspeed")),
                "kind": "FixedSpeed",
                "roadRef": None,
            }
        # Locations are needed for *any* node a relation might reference as a device/from/to
        # member or a way endpoint; recording all of them is simpler and cheap (in exchange for
        # off-heap storage — see the field comment above) than pre-scanning relations to know
        # which ids matter before this pass has read them.
        self.locations.set(n.id, osmium.osm.Location(n.location.lon, n.location.lat))


class RelationPass(osmium.SimpleHandler):
    def __init__(self, locations, cameras):
        super().__init__()
        self.locations = locations
        self.cameras = cameras
        self.sections = []

    def _member_points(self, relation, role=None):
        pts = []
        for m in relation.members:
            if m.type != "n":
                continue
            if role is not None and m.role != role:
                continue
            try:
                loc = self.locations.get(m.ref)
            except KeyError:
                continue
            pts.append((loc.lat, loc.lon))
        return pts

    def relation(self, r):
        enforcement = r.tags.get("enforcement", "")
        if enforcement == "average_speed":
            self._add_section(r)
        elif enforcement in ("maxspeed", "traffic_signals"):
            self._add_point_relation(r, enforcement)

    def _add_point_relation(self, r, enforcement):
        kind = "RedLight" if enforcement == "traffic_signals" else "FixedSpeed"
        ms = parse_maxspeed(r.tags.get("maxspeed"))
        road = r.tags.get("ref") or r.tags.get("name")
        devices = self._member_points(r, "device") or self._member_points(r, "from") or self._member_points(r, "to")
        for lat, lon in devices:
            hit = None
            for cam in self.cameras.values():
                if hav((lat, lon), (cam["lat"], cam["lon"])) <= SAME_CAM_M:
                    hit = cam
                    break
            if hit:
                if hit["kind"] == "FixedSpeed" and kind == "RedLight":
                    hit["kind"] = "SpeedAndRedLight"
                if hit["maxSpeedKmh"] is None and ms is not None:
                    hit["maxSpeedKmh"] = ms
                if road and hit["roadRef"] is None:
                    hit["roadRef"] = road
            else:
                new_id = f"r{r.id}"
                self.cameras[new_id] = {
                    "sourceId": new_id, "lat": lat, "lon": lon,
                    "maxSpeedKmh": ms, "kind": kind, "roadRef": road,
                }

    def _add_section(self, r):
        pts = self._member_points(r)
        if len(pts) < 2:
            return
        a, b, span = pts[0], pts[1], 0.0
        for i in range(len(pts)):
            for j in range(i + 1, len(pts)):
                d = hav(pts[i], pts[j])
                if d > span:
                    span, a, b = d, pts[i], pts[j]
        if span < MIN_SPAN_M:
            return
        # a and b are themselves members of pts (distance to self is 0, always <= the cluster
        # radius), so this filter already includes them — drop them from the middle before
        # wrapping, or they'd appear twice (a zero-length segment at each end).
        mid = [p for p in pts
               if p is not a and p is not b
               and (hav(p, a) <= END_CLUSTER_M or hav(p, b) <= END_CLUSTER_M)]
        ms = parse_maxspeed(r.tags.get("maxspeed"))
        self.sections.append({
            "sourceId": f"r{r.id}",
            "kind": "Section",
            "lat": None, "lon": None,
            "polyline": [[p[0], p[1]] for p in ([a] + mid + [b])],
            "maxSpeedKmh": ms,
            "roadRef": r.tags.get("ref") or r.tags.get("name"),
        })


def import_region(pbf_path, region):
    node_pass = NodeLocationPass()
    # No `locations=True`: that flag has pyosmium build its own internal node-location cache
    # for consumers like NodeLocationsForWays, which nothing here uses — every node already
    # carries its own location inline (only ways/relations need external lookup), and this
    # handler indexes locations itself into `self.locations`. Passing it anyway would build
    # the same data twice — real memory on a country-scale extract, not just wasted cycles.
    node_pass.apply_file(pbf_path)

    rel_pass = RelationPass(node_pass.locations, node_pass.cameras)
    rel_pass.apply_file(pbf_path)

    cameras = [
        {**c, "polyline": None} for c in node_pass.cameras.values()
    ] + rel_pass.sections

    return {
        "source": "osm",
        "region": region,
        "generatedAt": datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat(),
        "cameras": cameras,
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
    print(f"wrote {args.out}: {len(result['cameras'])} cameras/sections")


def _demo():
    """Self-check: import Luxembourg (smallest Geofabrik extract) and sanity-check counts.
    Run directly: python3 osm_import.py --demo"""
    import tempfile, urllib.request
    url = "https://download.geofabrik.de/europe/luxembourg-latest.osm.pbf"
    with tempfile.NamedTemporaryFile(suffix=".pbf") as f:
        print(f"downloading {url} ...")
        urllib.request.urlretrieve(url, f.name)
        result = import_region(f.name, region="luxembourg")
    assert len(result["cameras"]) > 5, f"expected >5 Luxembourg cameras, got {len(result['cameras'])}"
    kinds = {c["kind"] for c in result["cameras"]}
    assert kinds <= {"FixedSpeed", "RedLight", "SpeedAndRedLight", "Section"}, f"unexpected kind in {kinds}"
    print(f"OK: {len(result['cameras'])} cameras, kinds={kinds}")


if __name__ == "__main__":
    if "--demo" in sys.argv:
        _demo()
    else:
        main()
