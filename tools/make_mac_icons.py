#!/usr/bin/env python3
"""Build every macOS app-icon size from logo-wide.png.

Run from the repo root:  python3 tools/make_mac_icons.py

  input:  logo-wide.png  (transparent Gujarati wordmark, 1536x1024)
  output: mac/Aavija/Assets.xcassets/AppIcon.appiconset/*.png

The wordmark is blue, so it goes on a dark squircle (rounded square) —
same look as your original artwork. Corners are transparent because
macOS shows the icon over whatever background it wants.
"""
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "logo-wide.png"
OUT = ROOT / "mac" / "Aavija" / "Assets.xcassets" / "AppIcon.appiconset"

BASE = 1024
RADIUS = 230  # Big Sur-style squircle corner radius at 1024px
TOP = (18, 26, 34)      # dark blue-grey (top of gradient)
BOTTOM = (8, 10, 12)    # near black (bottom of gradient)

# Every size macOS wants: (filename, pixels)
SIZES = [
    ("icon_16x16.png", 16),
    ("icon_16x16@2x.png", 32),
    ("icon_32x32.png", 32),
    ("icon_32x32@2x.png", 64),
    ("icon_128x128.png", 128),
    ("icon_128x128@2x.png", 256),
    ("icon_256x256.png", 256),
    ("icon_256x256@2x.png", 512),
    ("icon_512x512.png", 512),
    ("icon_512x512@2x.png", 1024),
]


def build_base() -> Image.Image:
    # 1. vertical gradient background
    bg = Image.new("RGB", (BASE, BASE))
    px = bg.load()
    for y in range(BASE):
        t = y / (BASE - 1)
        row = tuple(round(TOP[i] + (BOTTOM[i] - TOP[i]) * t) for i in range(3))
        for x in range(BASE):
            px[x, y] = row

    # 2. round the corners off (alpha = 0 outside the squircle)
    mask = Image.new("L", (BASE, BASE), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, BASE - 1, BASE - 1], radius=RADIUS, fill=255
    )
    icon = bg.convert("RGBA")
    icon.putalpha(mask)

    # 3. paste the wordmark, scaled to ~72% of the width, centred
    logo = Image.open(SRC).convert("RGBA")
    content = logo.split()[3].point(lambda a: 255 if a > 10 else 0).getbbox()
    if content:
        logo = logo.crop(content)  # trim empty margins
    w = round(BASE * 0.72)
    h = round(logo.height * w / logo.width)
    logo = logo.resize((w, h), Image.LANCZOS)
    icon.alpha_composite(logo, ((BASE - w) // 2, (BASE - h) // 2))
    return icon


def main() -> None:
    base = build_base()
    OUT.mkdir(parents=True, exist_ok=True)
    for name, size in SIZES:
        img = base if size == BASE else base.resize((size, size), Image.LANCZOS)
        img.save(OUT / name)
    print(f"wrote {len(SIZES)} sizes to {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
