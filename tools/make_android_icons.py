#!/usr/bin/env python3
"""Generate Android launcher icons from logo.png.

Android adaptive icons are two layers:
  - foreground (the logo), scaled into a circular "safe zone"
  - background (a solid color)
The visible area is a circle ~66dp across a 108dp canvas, so we shrink the
logo so it isn't clipped by the mask. We also emit legacy square PNGs for the
mipmap density buckets so nothing breaks in tooling.
"""
from PIL import Image
import os

REPO = "/Users/sandipkumarpatel/Developer/Aavija"
RES = os.path.join(REPO, "android/app/src/main/res")
SRC = os.path.join(REPO, "logo.png")

# Dark background matching the Mac icon's squircle.
BG = (27, 42, 56, 255)  # #1B2A38

logo = Image.open(SRC).convert("RGBA")

# Trim transparent margins so the logo is tightly bounded.
bbox = logo.getbbox()
if bbox:
    logo = logo.crop(bbox)

def make_foreground(size):
    """Center the logo inside a square canvas, scaled to the safe zone."""
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    # Safe zone ≈ 66/108 of the canvas; leave generous padding.
    target = int(size * 0.60)
    w, h = logo.size
    scale = min(target / w, target / h)
    nw, nh = int(w * scale), int(h * scale)
    scaled = logo.resize((nw, nh), Image.LANCZOS)
    x = (size - nw) // 2
    y = (size - nh) // 2
    canvas.alpha_composite(scaled, (x, y))
    return canvas

def make_legacy(size):
    """Solid-background square icon (for pre-26 mipmap fallbacks)."""
    icon = Image.new("RGBA", (size, size), BG)
    fg = make_foreground(size)
    icon.alpha_composite(fg)
    return icon

# --- Adaptive icon (API 26+): foreground drawable + background color ---
drawable_dir = os.path.join(RES, "drawable")
os.makedirs(drawable_dir, exist_ok=True)
make_foreground(432).save(os.path.join(drawable_dir, "ic_launcher_foreground.png"))

# Background color resource
values_dir = os.path.join(RES, "values")
os.makedirs(values_dir, exist_ok=True)
with open(os.path.join(values_dir, "ic_launcher_background.xml"), "w") as f:
    f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
            '    <color name="ic_launcher_background">#1B2A38</color>\n'
            '</resources>\n')

# Adaptive icon XML in mipmap-anydpi-v26
anydpi = os.path.join(RES, "mipmap-anydpi-v26")
os.makedirs(anydpi, exist_ok=True)
with open(os.path.join(anydpi, "ic_launcher.xml"), "w") as f:
    f.write('<?xml version="1.0" encoding="utf-8"?>\n'
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@color/ic_launcher_background"/>\n'
            '    <foreground android:drawable="@drawable/ic_launcher_foreground"/>\n'
            '</adaptive-icon>\n')

# Legacy square PNGs per density (base launcher = 48dp)
densities = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
for name, px in densities.items():
    d = os.path.join(RES, f"mipmap-{name}")
    os.makedirs(d, exist_ok=True)
    make_legacy(px).save(os.path.join(d, "ic_launcher.png"))

print("Generated adaptive + legacy launcher icons under", RES)
