#!/usr/bin/env python3
"""Render the NERT wordmark into site assets.

Geometry mirrors app/src/main/res/drawable/ic_launcher_foreground.xml
(viewport 1024, letters inside the adaptive-icon safe circle), so the website
icon and the installed app icon are the same mark.
"""
import os

from PIL import Image, ImageDraw

INK = (11, 16, 23, 255)
ACCENT = (47, 109, 246, 255)
WHITE = (255, 255, 255, 255)
DISC = (251, 253, 255, 255)
STROKE = 38
BOX = (224, 428, 800, 596)  # 407 重排后的字母包围盒

# (x1, y1, x2, y2) straight strokes, in the 1024 viewport.
STROKES = [
    (224, 596, 224, 428),
    (224, 428, 320, 596),
    (320, 596, 320, 428),
    (384, 428, 384, 596),
    (384, 428, 480, 428),
    (384, 512, 462, 512),
    (384, 596, 480, 596),
    (544, 428, 544, 596),
    (544, 428, 640, 428),
    (634, 512, 648, 596),
    (704, 428, 800, 428),
    (752, 428, 752, 596),
]
R_BOWL = (598, 470, 42, -90, 90)   # cx, cy, r, a0, a1
SPARK = (832, 564, 11)              # cx, cy, r


def render(size: int, path: str, transparent: bool = False, margin_scale: float = 1.0) -> None:
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0) if transparent else WHITE)
    d = ImageDraw.Draw(img)
    if not transparent:
        d.ellipse([0, 0, size - 1, size - 1], fill=DISC)

    bw, bh = BOX[2] - BOX[0], BOX[3] - BOX[1]
    pad = size * 0.12
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
