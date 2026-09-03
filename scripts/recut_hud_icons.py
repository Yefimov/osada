#!/usr/bin/env python3
"""
Re-cut hud_icons_grid_raw.png -> hud_icons_grid.png so `.osada-ico` (osada-theme.css) can sample it
on a plain uniform 4x3 / 362px grid.

The raw sheet is the GOOD art (the earlier recut flood-keyed the backdrop and damaged some glyphs),
but its 12 badges sit on a ~322x311 pitch anchored to the top-left, leaving empty margins on the
right and bottom of the sheet -- so uniform 362px sampling crops every icon toward the top-left.

The raw sheet is already RGBA with a clean alpha channel, so no keying is needed. We locate the 4
columns and 3 rows of badges by alpha projection profiles (the gaps between badges are empty), take
each badge's tight bounding box, and paste it -- untouched raw pixels -- centered into its 362px
cell. No color/alpha op touches a glyph, so nothing is damaged.

Usage: python3 scripts/recut_hud_icons.py [--apply]
Without --apply, prints the detected bands / per-badge boxes and writes nothing.
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image

REPO = Path(__file__).resolve().parents[1]
RES = REPO / "src/jsMain/resources/resources"
RAW = RES / "hud_icons_grid_raw.png"
OUT = RES / "hud_icons_grid.png"

COLS, ROWS = 4, 3
ALPHA_THRESHOLD = 40
MIN_BAND = 40  # ignore stray runs narrower than this (px)


def bands(profile: np.ndarray, want: int) -> list:
    """Contiguous runs where profile>0, as (start,end) pairs, widest `want` kept and re-sorted."""
    runs, start = [], None
    for i, v in enumerate(profile):
        if v > 0 and start is None:
            start = i
        elif v == 0 and start is not None:
            if i - start >= MIN_BAND:
                runs.append((start, i))
            start = None
    if start is not None and len(profile) - start >= MIN_BAND:
        runs.append((start, len(profile)))
    runs.sort(key=lambda r: r[1] - r[0], reverse=True)
    return sorted(runs[:want])


def main(apply: bool) -> None:
    raw = Image.open(RAW).convert("RGBA")
    w, h = raw.size
    cw, ch = w // COLS, h // ROWS
    alpha = np.asarray(raw.getchannel("A"))
    mask = alpha >= ALPHA_THRESHOLD

    col_bands = bands(mask.sum(axis=0), COLS)
    row_bands = bands(mask.sum(axis=1), ROWS)
    print(f"column bands (x): {col_bands}")
    print(f"row bands   (y): {row_bands}")
    if len(col_bands) != COLS or len(row_bands) != ROWS:
        print("!! did not find a clean 4x3 -- aborting"); return

    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    for ri, (ry0, ry1) in enumerate(row_bands):
        for ci, (cx0, cx1) in enumerate(col_bands):
            sub = mask[ry0:ry1, cx0:cx1]
            ys, xs = np.where(sub)
            bx0, bx1 = cx0 + xs.min(), cx0 + xs.max() + 1
            by0, by1 = ry0 + ys.min(), ry0 + ys.max() + 1
            gw, gh = bx1 - bx0, by1 - by0
            px = ci * cw + (cw - gw) // 2
            py = ri * ch + (ch - gh) // 2
            print(f"  badge ({ri},{ci}): src=({bx0},{by0},{bx1},{by1}) {gw}x{gh} -> ({px},{py})")
            if apply:
                glyph = raw.crop((bx0, by0, bx1, by1))
                out.paste(glyph, (px, py), glyph)

    if apply:
        out.save(OUT)
        print(f"  wrote {OUT.relative_to(REPO)}")
    else:
        print("  dry run only -- pass --apply to write")


if __name__ == "__main__":
    main("--apply" in sys.argv)
