# Nether logo (2026-11-14): pixel fireball. Source sprite = fireball-pixel.json (17x24 rows of "#RRGGBB" or null,
# extracted from fireball-pixel-src.webp). Writes nether-logo.svg (square, crisp pixels) and prints the Java sprite.
#   python design/icon/fireball-pixel.py
import json, os
d = os.path.dirname(os.path.abspath(__file__))
rows = json.load(open(os.path.join(d, "fireball-pixel.json")))
h, w = len(rows), len(rows[0])
n = max(w, h) + 2                      # one empty pixel of margin round the sprite
ox, oy = (n - w) / 2, (n - h) / 2
px = 512 / n
rects = []
for j, r in enumerate(rows):
    i = 0
    while i < w:                       # merge horizontal runs of one colour
        c = r[i]
        k = i
        while k + 1 < w and r[k + 1] == c: k += 1
        if c: rects.append(f'<rect x="{(ox + i) * px:.3f}" y="{(oy + j) * px:.3f}" width="{(k - i + 1) * px + 0.02:.3f}" height="{px + 0.02:.3f}" fill="{c}"/>')
        i = k + 1
svg = f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" width="512" height="512" shape-rendering="crispEdges">\n<title>Nether Client</title>\n' + "\n".join(rects) + "\n</svg>\n"
for p in (os.path.join(d, "nether-logo.svg"), os.path.join(d, "..", "..", "crates", "app", "ui", "assets", "nether-logo.svg")):
    open(p, "w", newline="\n").write(svg)
# Java: palette + rows of palette letters ('.' = empty)
pal = []
for r in rows:
    for c in r:
        if c and c not in pal: pal.append(c)
keys = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
assert len(pal) <= len(keys), len(pal)
print("PAL = {" + ", ".join("0xFF" + c[1:] for c in pal) + "}")
print("ROWS = {" + ", ".join('"' + "".join("." if c is None else keys[pal.index(c)] for c in r) + '"' for r in rows) + "}")
