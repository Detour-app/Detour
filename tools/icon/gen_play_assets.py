#!/usr/bin/env python3
"""Generate the Play Console graphics from the launcher icon's geometry.

The launcher icon ships as vector XML only, and Play wants rasters. Rather
than trace them by hand, this reuses the road/dash geometry from
`gen_icon.py`, so a change to the mark there flows into the store assets on a
re-run instead of drifting away from what installs on the phone.

Writes into docs/play/:

    icon-512.png          512x512, the Play Store listing icon
    feature-graphic.png   1024x500, the listing header

Run from anywhere:

    python3 tools/icon/gen_play_assets.py
"""

import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen_icon  # noqa: E402  (path set up above)

# The icon is drawn in a 108-unit square, the same viewport as the vector.
VIEWPORT = 108.0

# 3x then downsample: PIL's polygon fill has no antialiasing of its own, and
# the road's long diagonal edge is exactly where a hard edge would show.
SS = 3

CREAM = (247, 227, 182)
MUTED = (154, 163, 134)

FONT_DIR = "/usr/share/fonts/opentype/inter"
TITLE_FONT = os.path.join(FONT_DIR, "InterDisplay-Bold.otf")
BODY_FONT = os.path.join(FONT_DIR, "Inter-Regular.otf")


def rgb(hex_colour):
    h = hex_colour.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def linear_gradient(size, p0, p1, c0, c1):
    """Axis-aligned-agnostic linear gradient, p0/p1 in pixel coordinates."""
    w, h = size
    dx, dy = p1[0] - p0[0], p1[1] - p0[1]
    denom = dx * dx + dy * dy or 1.0
    xs = np.arange(w, dtype=np.float32)[None, :]
    ys = np.arange(h, dtype=np.float32)[:, None]
    t = ((xs - p0[0]) * dx + (ys - p0[1]) * dy) / denom
    t = np.clip(t, 0.0, 1.0)[:, :, None]
    a = np.array(rgb(c0), dtype=np.float32)
    b = np.array(rgb(c1), dtype=np.float32)
    return Image.fromarray((a + (b - a) * t).astype(np.uint8))


def mark_mask(size, scale, offset):
    """Alpha mask of the road, dashes punched out as holes.

    `scale` is pixels per viewport unit; `offset` shifts the 108-unit square's
    top-left corner, in pixels.
    """
    def to_px(p):
        return (p[0] * scale + offset[0], p[1] * scale + offset[1])

    mask = Image.new("L", size, 0)
    draw = ImageDraw.Draw(mask)
    draw.polygon([to_px(p) for p in gen_icon.road()], fill=255)
    for dash in gen_icon.dashes():
        draw.polygon([to_px(p) for p in dash], fill=0)
    return mask


def paint_mark(canvas, scale, offset, opacity=1.0):
    """Composite the amber road onto `canvas` (RGB, already at SS scale)."""
    size = canvas.size
    amber = linear_gradient(
        size,
        (24 * scale + offset[0], 96 * scale + offset[1]),
        (84 * scale + offset[0], 18 * scale + offset[1]),
        gen_icon.AMBER_NEAR, gen_icon.AMBER_FAR)
    mask = mark_mask(size, scale, offset)
    if opacity < 1.0:
        mask = mask.point(lambda v: int(v * opacity))
    canvas.paste(amber, (0, 0), mask)


def background(size, scale, offset):
    """The icon's background gradient, in the same 108-unit frame as the mark."""
    return linear_gradient(
        size,
        (0 * scale + offset[0], 0 * scale + offset[1]),
        (37.8 * scale + offset[0], 108 * scale + offset[1]),
        gen_icon.BG_TOP, gen_icon.BG_BOTTOM)


def store_icon(px=512):
    """Full-bleed square: the whole 108 viewport, uncropped.

    A launcher crops this to its mask and shows the road larger; Play only
    rounds the corners. Matching the launcher's framing would mean cropping to
    the safe zone, but the mark was drawn to run off every edge, so the
    uncropped frame is the one that reads as a road rather than a stripe.
    """
    size = (px * SS, px * SS)
    scale = px * SS / VIEWPORT
    canvas = background(size, scale, (0, 0))
    paint_mark(canvas, scale, (0, 0))
    return canvas.resize((px, px), Image.LANCZOS)


def rounded_badge(px, radius_ratio=0.225):
    """The icon as a rounded square, for dropping into the feature graphic."""
    icon = store_icon(px).convert("RGBA")
    mask = Image.new("L", (px * SS, px * SS), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, px * SS - 1, px * SS - 1),
        radius=int(px * SS * radius_ratio), fill=255)
    icon.putalpha(mask.resize((px, px), Image.LANCZOS))
    return icon


def feature_graphic(w=1024, h=500):
    size = (w * SS, h * SS)
    # Same two greens as the icon background, run across the wider frame so
    # the graphic and the icon read as one surface.
    canvas = linear_gradient(size, (0, 0), (w * SS * 0.35, h * SS),
                             gen_icon.BG_TOP, gen_icon.BG_BOTTOM)

    # The badge carries the mark on its own. An oversized road behind the text
    # was tried and read as a smudge at this size, so the field stays plain.
    badge_px, margin = 300, 64
    badge = rounded_badge(badge_px)
    canvas = canvas.convert("RGBA")
    canvas.alpha_composite(
        badge.resize((badge_px * SS, badge_px * SS), Image.LANCZOS),
        ((w - badge_px - margin) * SS, (h - badge_px) // 2 * SS))

    # Text stops well short of the badge: nothing runs past x=620.
    draw = ImageDraw.Draw(canvas)
    title = ImageFont.truetype(TITLE_FONT, 108 * SS)
    body = ImageFont.truetype(BODY_FONT, 36 * SS)
    draw.text((80 * SS, 230 * SS), "Detour", font=title, fill=CREAM, anchor="ls")
    draw.text((84 * SS, 292 * SS), "A random road, every time.",
              font=body, fill=MUTED, anchor="ls")
    draw.text((84 * SS, 348 * SS), "Spin a destination. Ride it.",
              font=body, fill=MUTED, anchor="ls")

    return canvas.convert("RGB").resize((w, h), Image.LANCZOS)


def main():
    root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    out = os.path.join(root, "docs", "play")
    os.makedirs(out, exist_ok=True)

    store_icon().convert("RGBA").save(os.path.join(out, "icon-512.png"))
    feature_graphic().save(os.path.join(out, "feature-graphic.png"))
    print("wrote docs/play/icon-512.png and docs/play/feature-graphic.png")


if __name__ == "__main__":
    main()
