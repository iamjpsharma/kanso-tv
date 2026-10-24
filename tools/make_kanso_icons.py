#!/usr/bin/env python3
"""Draw the Kanso TV launcher icon and TV banner.

The mark is an ensō (an incomplete circle) with a play triangle at its centre:
simplicity, and a TV you actually watch. Everything is rendered at 4x and
downsampled so the curves stay clean at 48 px.

    python3 tools/make_kanso_icons.py

Requires Pillow. Outputs mipmap PNGs for the launcher, a vector adaptive icon
foreground (checked in separately), and 320/480/640 px TV banners.
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "launcher/app/src/main/res"
FONT = ROOT / "fonts/Outfit.ttf"

BG = (11, 11, 15)
CREAM = (242, 237, 227)
ACCENT = (232, 168, 124)
MUTED = (139, 134, 128)

SS = 4  # supersampling factor


def radial_glow(size, centre, radius, colour, peak=70):
    """A soft warm glow, returned as an RGBA layer."""
    layer = Image.new("RGBA", size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    steps = 18
    for i in range(steps, 0, -1):
        r = radius * i / steps
        a = int(peak * (1 - i / steps) ** 2)
        d.ellipse(
            [centre[0] - r, centre[1] - r, centre[0] + r, centre[1] + r],
            fill=colour + (a,),
        )
    return layer.filter(ImageFilter.GaussianBlur(radius * 0.12))


def enso(draw, cx, cy, r, stroke, colour, gap_degrees=48, gap_center=-90):
    """An open circle with rounded ends. The gap sits at gap_center (0 = right)."""
    start = gap_center + gap_degrees / 2
    end = start + 360 - gap_degrees
    box = [cx - r, cy - r, cx + r, cy + r]
    draw.arc(box, start=start, end=end, fill=colour, width=int(stroke))
    # Pillow doesn't round arc caps, so put a dot at each end.
    import math

    for angle in (start, end):
        a = math.radians(angle)
        x = cx + r * math.cos(a)
        y = cy + r * math.sin(a)
        draw.ellipse(
            [x - stroke / 2, y - stroke / 2, x + stroke / 2, y + stroke / 2],
            fill=colour,
        )


def play_triangle(draw, cx, cy, size, colour):
    h = size
    w = size * 0.86
    draw.polygon(
        [(cx - w / 2, cy - h / 2), (cx - w / 2, cy + h / 2), (cx + w / 2, cy)],
        fill=colour,
    )


def icon(size):
    """A rounded-square legacy icon."""
    s = size * SS
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    radius = int(s * 0.23)
    draw.rounded_rectangle([0, 0, s - 1, s - 1], radius=radius, fill=BG)
    img.alpha_composite(radial_glow((s, s), (s * 0.46, s * 0.44), s * 0.52, ACCENT, 55))
    draw = ImageDraw.Draw(img)
    enso(draw, s * 0.50, s * 0.50, s * 0.27, s * 0.042, CREAM, gap_degrees=48, gap_center=40)
    play_triangle(draw, s * 0.525, s * 0.50, s * 0.19, ACCENT)
    return img.resize((size, size), Image.LANCZOS)


def measure_tracked(draw, text, font, tracking):
    return sum(draw.textlength(c, font=font) for c in text) + tracking * max(0, len(text) - 1)


def banner(w, h):
    """320x180 dp banner: mark on the left, wordmark on the right."""
    img = Image.new("RGBA", (w, h), BG)
    img.alpha_composite(radial_glow((w, h), (w * 0.14, h * 0.50), w * 0.36, ACCENT, 80))
    draw = ImageDraw.Draw(img)

    # mark
    r = h * 0.23
    cx = r + w * 0.04
    cy = h * 0.50
    enso(draw, cx, cy, r, h * 0.042, CREAM, gap_center=40)
    play_triangle(draw, cx + r * 0.09, cy, r * 0.72, ACCENT)

    # wordmark: shrink until "KANSO TV" fits the space right of the mark
    x0 = cx + r + w * 0.035
    available = w * 0.96 - x0
    title_size = int(h * 0.25)
    tracking_ratio = 0.045
    while True:
        font = ImageFont.truetype(str(FONT), title_size)
        font.set_variation_by_name("Medium")
        tracking = title_size * tracking_ratio
        total = measure_tracked(draw, "KANSO", font, tracking) + title_size * 0.30 + measure_tracked(draw, "TV", font, tracking)
        if total <= available or title_size <= 10:
            break
        title_size -= 1

    y = h * 0.46
    x = x0
    for ch in "KANSO":
        draw.text((x, y), ch, font=font, fill=CREAM, anchor="lm")
        x += draw.textlength(ch, font=font) + tracking
    x += title_size * 0.30 - tracking
    for ch in "TV":
        draw.text((x, y), ch, font=font, fill=ACCENT, anchor="lm")
        x += draw.textlength(ch, font=font) + tracking

    # tagline
    tag_size = max(9, int(h * 0.055))
    tag_font = ImageFont.truetype(str(FONT), tag_size)
    tag_font.set_variation_by_name("Regular")
    tag = "NOTHING EXTRA"
    tag_tracking = tag_size * 0.30
    tag_y = h * 0.70
    tx = x0
    for ch in tag:
        draw.text((tx, tag_y), ch, font=tag_font, fill=MUTED, anchor="lm")
        tx += draw.textlength(ch, font=tag_font) + tag_tracking

    return img.convert("RGB")


def main():
    sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for bucket, px in sizes.items():
        out = RES / f"mipmap-{bucket}"
        out.mkdir(parents=True, exist_ok=True)
        im = icon(px)
        im.save(out / "ic_launcher.png")
        im.save(out / "ic_launcher_round.png")

    # A large copy for the README / store listing
    (ROOT / "docs/images").mkdir(parents=True, exist_ok=True)
    icon(512).save(ROOT / "docs/images/kanso-icon.png")

    for bucket, w, h in (("xhdpi", 320, 180), ("xxhdpi", 480, 270), ("xxxhdpi", 640, 360)):
        out = RES / f"drawable-{bucket}"
        out.mkdir(parents=True, exist_ok=True)
        banner(w, h).save(out / "tv_banner.png")

    print("wrote launcher icons and TV banners")


if __name__ == "__main__":
    main()
