"""Generate the per-band REST sensors and the map card's geojson layers.

The band tables live in sync_server.py; the dashboard has to name every band's
colour, and the package has to name every band's attribute. Writing either by
hand at 27 bands is how they drift, so both come out of here.

    python3 server/ha/gen_bands.py sensors          # package rest: sensors
    python3 server/ha/gen_bands.py layers speed    # dashboard geojson: layers
"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'sync'))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sync_server as s  # noqa: E402

SETS = {
    'speed': (s.SPEED_BANDS, 'speed'),
    'lean': (s.LEAN_BANDS, 'lean'),
}


def keys(bands):
    """Band keys low to high, with no-data first so it paints underneath."""
    return [(s.NO_DATA_KEY, s.NO_DATA_COLOR, s.NO_DATA_LABEL)] + [
        (s._band_key(i), b[2], b[1]) for i, b in enumerate(bands)]


def sensors():
    out = []
    for name, (bands, prefix) in SETS.items():
        for key, _color, label in keys(bands):
            attr = '%s_%s' % (prefix, key)
            out.append(
                "      # %s\n"
                "      - name: Map Roulette %s %s\n"
                "        unique_id: maproulette_%s\n"
                "        value_template: \"{{ (value_json.%s.geometry.coordinates | count)"
                " if value_json.%s is defined else 0 }}\"\n"
                "        unit_of_measurement: runs\n"
                "        json_attributes:\n"
                "          - %s\n"
                % (label, prefix, key, attr, attr, attr, attr))
    return ''.join(out)


def layers(which, indent=14):
    bands, prefix = SETS[which]
    pad = ' ' * indent
    out = []
    for key, color, label in keys(bands):
        # Thicker at the top of the scale: the fast/hard end is the part worth
        # spotting from across the room, and it is also the rarest.
        idx = 0 if key == s.NO_DATA_KEY else int(key[1:]) + 1
        weight = 4 + round(2.0 * idx / (len(bands) + 1))
        out.append(
            "%s# %s\n"
            "%s- entity: sensor.map_roulette_%s_%s\n"
            "%s  attribute: %s_%s\n"
            "%s  color: \"%s\"\n"
            "%s  width: %d\n"
            "%s  opacity: %s\n"
            % (pad, label, pad, prefix, key, pad, prefix, key,
               pad, color, pad, weight, pad, '0.55' if key == s.NO_DATA_KEY else '0.95'))
    return ''.join(out)


if __name__ == '__main__':
    what = sys.argv[1]
    if what == 'sensors':
        print(sensors(), end='')
    else:
        print(layers(sys.argv[2]), end='')
