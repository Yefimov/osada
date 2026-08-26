#!/usr/bin/env python3
"""
Draw the shell-crater overlay OSADA paints on a cratered hex (`rules/Barrage`, craters rule).

Kept as a script rather than a hand-made asset for the reason `recut_hud_icons.py` is: the sprite is
tiny, it has to sit on a 30px hex top, and anyone who wants it darker or wants four holes instead of
three should be able to change a number and re-run rather than open an image editor.

Deliberately transparent and unlit: the map is a pre-rendered image, so the overlay has to read on
green fields, on snow and on sand without repainting the terrain under it. Dark rims and a lighter
lip are what make a hole read as a hole at this size.

Usage:  python scripts/make_crater_icon.py
Writes: src/jsMain/resources/resources/ui/indicators/hex-craters.png
"""
from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "src/jsMain/resources/resources/ui/indicators/hex-craters.png"

# Supersampled, then reduced: circles this small alias badly if drawn at final size.
SCALE = 8
SIZE = (30, 20)

# (centre x, centre y, radius) in final pixels. Three holes, none of them centred, because a tidy
# row reads as a decoration and a scatter reads as damage.
CRATERS = [
    (9.0, 8.5, 4.6),
    (19.5, 6.5, 3.4),
    (15.0, 14.0, 3.9),
]

PIT = (38, 30, 24, 205)
RIM = (150, 132, 108, 170)
LIP = (196, 182, 158, 120)


def draw() -> Image.Image:
    big = Image.new("RGBA", (SIZE[0] * SCALE, SIZE[1] * SCALE), (0, 0, 0, 0))
    pen = ImageDraw.Draw(big)
    for cx, cy, r in CRATERS:
        x, y, rr = cx * SCALE, cy * SCALE, r * SCALE
        # Thrown-up earth first, so the pit is drawn over its own rim rather than under it.
        pen.ellipse((x - rr, y - rr * 0.82, x + rr, y + rr * 0.82), fill=LIP)
        pen.ellipse(
            (x - rr * 0.86, y - rr * 0.70, x + rr * 0.86, y + rr * 0.70),
            fill=RIM,
        )
        pen.ellipse(
            (x - rr * 0.62, y - rr * 0.50, x + rr * 0.62, y + rr * 0.50),
            fill=PIT,
        )
    small = big.resize(SIZE, Image.LANCZOS)
    return small.filter(ImageFilter.SMOOTH)


def main() -> int:
    TARGET.parent.mkdir(parents=True, exist_ok=True)
    draw().save(TARGET)
    print(f"wrote {TARGET.relative_to(ROOT)} {SIZE[0]}x{SIZE[1]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
