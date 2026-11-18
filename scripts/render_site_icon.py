#!/usr/bin/env python3
"""Render the NERT wordmark into site assets.

Geometry mirrors app/src/main/res/drawable/ic_launcher_foreground.xml
(viewport 1024, letters inside the adaptive-icon safe circle), so the website
icon and the installed app icon are the same mark.
"""
import os

from PIL import Image, ImageDraw

INK = (16, 22, 28, 255)
ACCENT = (47, 109, 246, 255)
WHITE = (255, 255, 255, 255)
DISC = (250, 251, 253, 255)
STROKE = 62
BOX = (170, 380, 830, 640)  # letters bounding box, including stroke caps

# (x1, y1, x2, y2) straight strokes, in the 1024 viewport.
STROKES = [
    (212, 600, 212, 424), (212, 424, 300, 600), (300, 600, 300, 424),          # N
    (368, 424, 368, 600), (368, 424, 456, 424), (368, 512, 446, 512), (368, 600, 456, 600),  # E
    (520, 600, 520, 424), (520, 424, 578, 424), (520, 512, 578, 512), (552, 512, 604, 600),  # R
    (656, 424, 784, 424), (720, 424, 720, 600),                                # T
]
R_BOWL = (549, 468, 29, -90, 90)   # cx, cy, r, a0, a1
SPARK = (612, 556, 17)              # cx, cy, r


def render(size: int, path: str, transparent: bool = False, margin_scale: float = 1.0) -> None:
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0) if transparent else WHITE)
    d = ImageDraw.Draw(img)
    if not transparent:
        d.ellipse([0, 0, size - 1, size - 1], fill=DISC)

    bw, bh = BOX[2] - BOX[0], BOX[3] - BOX[1]
    pad = size * 0.14
    k = min((size - 2 * pad) / bw, (size - 2 * pad) / bh) * margin_scale
    ox = (size - bw * k) / 2.0 - BOX[0] * k
    oy = (size - bh * k) / 2.0 - BOX[1] * k

    def X(v):
        return v * k + ox

    def Y(v):
        return v * k + oy

    w = max(2, int(STROKE * k))
    r = STROKE * k / 2.0
    for (x1, y1, x2, y2) in STROKES:
        d.line([(X(x1), Y(y1)), (X(x2), Y(y2))], fill=INK, width=w, joint="curve")
        for (cx, cy) in ((x1, y1), (x2, y2)):
            d.ellipse([X(cx) - r, Y(cy) - r, X(cx) + r, Y(cy) + r], fill=INK)

    cx, cy, rr, a0, a1 = R_BOWL
    d.arc([X(cx - rr), Y(cy - rr), X(cx + rr), Y(cy + rr)], a0, a1, fill=INK, width=w)

    sx, sy, sr = SPARK
    d.ellipse([X(sx) - sr * k, Y(sy) - sr * k, X(sx) + sr * k, Y(sy) + sr * k], fill=ACCENT)

    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, "PNG")
    print("wrote", path, f"{size}x{size}")


def main() -> None:
    site = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "site", "assets")
    render(512, os.path.join(site, "icon.png"))
    render(192, os.path.join(site, "icon-192.png"))
    render(192, os.path.join(site, "icon-maskable-192.png"), margin_scale=0.72)
    render(180, os.path.join(site, "apple-touch-icon.png"))
    render(48, os.path.join(site, "favicon.png"), transparent=True)


if __name__ == "__main__":
    main()
