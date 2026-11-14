# Renders the Nether fireball mark: a white-hot ball with a flame trail swirling round it, glowing on obsidian.
#   python design/icon/fireball.py [size] [--clear]   ->  design/icon/fireball-mark.png (--clear: transparent background)
import sys, math, os
import numpy as np
from PIL import Image, ImageFilter

S = int(next((a for a in sys.argv[1:] if a.isdigit()), 1024))
CLEAR = "--clear" in sys.argv
rng = np.random.default_rng(7)
yy, xx = np.mgrid[0:S, 0:S].astype(np.float32) + 0.5

O = np.array([0.56, 0.55]) * S          # swirl centre
R0, RB = 0.2 * S, 0.118 * S          # ball distance from centre, ball radius
TH0, SWEEP = math.radians(135), math.radians(240)  # ball bottom-left; trail runs up the left, over the top


def trail_point(t):
    th = TH0 + t * SWEEP
    r = R0 * (1 + 0.38 * t)
    return O + r * np.array([math.cos(th), math.sin(th)])


# ---- shape: max of soft stamps along the trail; ttag = trail position of the strongest stamp (0 at the ball)
F = np.zeros((S, S), np.float32)
T = np.ones((S, S), np.float32)
for t in np.linspace(0, 1, 900):
    w = RB * (1.0 * (1 - t) ** 1.05 + 0.015)
    b = (1 - t) ** 0.55
    c = trail_point(t)
    x0, x1 = int(max(0, c[0] - w - 2)), int(min(S, c[0] + w + 2))
    y0, y1 = int(max(0, c[1] - w - 2)), int(min(S, c[1] + w + 2))
    if x0 >= x1 or y0 >= y1:
        continue
    d = np.hypot(xx[y0:y1, x0:x1] - c[0], yy[y0:y1, x0:x1] - c[1])
    g = np.clip(1 - d / w, 0, 1) ** 1.4 * b
    sub = F[y0:y1, x0:x1]
    m = g > sub
    sub[m] = g[m]
    T[y0:y1, x0:x1][m] = t


# ---- flame noise in polar coordinates round the swirl: long streaks along the trail, fine across it
def value_noise(h, w, cells_y, cells_x, seed):
    g = np.random.default_rng(seed).random((cells_y + 3, cells_x + 3)).astype(np.float32)
    im = Image.fromarray((g * 255).astype(np.uint8)).resize((w + int(3 * w / cells_x), h + int(3 * h / cells_y)), Image.BICUBIC)
    return np.asarray(im, np.float32)[:h, :w] / 255


ang = (np.arctan2(yy - O[1], xx - O[0]) - TH0 + math.radians(60)) % (2 * math.pi) / (2 * math.pi)   # 0..1, seam in the trail's gap
rad = np.hypot(xx - O[0], yy - O[1]) / S
PA, PR = 2048, 1024
pol = sum(value_noise(PR, PA, int(36 * k), int(7 * k), 11 + k) / k for k in (1, 2, 4, 8)) / (1 + 1 / 2 + 1 / 4 + 1 / 8)
warp = value_noise(PR, PA, 9, 5, 3) - 0.5                                   # bends the streaks so they curl
ai = (ang * (PA - 1)).astype(np.int32)
ri = np.clip((rad / 0.75 + warp[np.clip((rad / 0.75 * (PR - 1)).astype(np.int32), 0, PR - 1), ai] * 0.08) * (PR - 1), 0, PR - 1).astype(np.int32)
N = pol[ri, ai]
N = (N - N.min()) / (N.max() - N.min())

# flame: noise eats the edges and the thin end of the trail, leaving tongues; the ball stays solid
erode = 0.35 + 0.65 * np.clip(T * 1.8, 0, 1)
Fl = np.clip((F * (1 + erode * 1.6 * (N - 0.5)) - 0.05) / 0.8, 0, 1) * (0.75 + 0.5 * N * erode)  # noise only inside the shape
Fl = np.clip(Fl, 0, 1)

# ball: a hot core with a soft limb
db = np.hypot(xx - trail_point(0)[0], yy - trail_point(0)[1]) / RB
core = np.clip(1 - db, 0, 1)
surf = value_noise(S, S, 18, 18, 5)                                         # churn on the ball's face
Fl = np.maximum(Fl, np.clip(1.08 - db + (N - 0.5) * 0.14 * np.clip(db, 0, 1), 0, 1) ** 0.85)
heat = np.clip(Fl * (0.5 + 0.5 * (1 - T) ** 1.4) * (0.85 + 0.3 * surf) + 0.6 * core ** 2.2, 0, 1.25)

# embers flung off the outside of the trail
E = np.zeros((S, S), np.float32)
for _ in range(34):
    t = rng.random() ** 1.2 * 0.85
    c = trail_point(t)
    out = (c - O) / np.linalg.norm(c - O)
    p = c + out * RB * (0.75 + rng.random() * 0.9) * (1 - t * 0.6) + rng.normal(0, RB * 0.12, 2)
    r = S * (0.0012 + rng.random() * 0.0022)
    E += np.exp(-((xx - p[0]) ** 2 + (yy - p[1]) ** 2) / (2 * r * r)) * (0.5 + 0.5 * rng.random()) * (1 - t)
heat = np.maximum(heat, np.clip(E, 0, 1) * 0.8)

# ---- colour: deep ember -> racing red -> orange -> pale gold -> white
stops = [(0.00, (0, 0, 0)), (0.12, (70, 4, 6)), (0.30, (200, 22, 30)), (0.50, (238, 64, 36)),
         (0.68, (255, 138, 52)), (0.84, (255, 214, 150)), (1.00, (255, 250, 240))]
hs = np.array([s[0] for s in stops]); cs = np.array([s[1] for s in stops], np.float32) / 255
h = np.clip(heat, 0, 1)
rgb = np.stack([np.interp(h, hs, cs[:, i]) for i in range(3)], -1)
rgb *= np.clip(heat / 0.12, 0, 1)[..., None]

# bloom: a few blurred copies of the bright parts added back
def blur(a, r):
    im = Image.fromarray(np.clip(a * 255, 0, 255).astype(np.uint8))
    return np.asarray(im.filter(ImageFilter.GaussianBlur(r)), np.float32) / 255

bright = rgb * np.clip(heat - 0.25, 0, 1)[..., None] * 1.6
glow = 0.55 * blur(bright, S * 0.008) + 0.45 * blur(bright, S * 0.025) + 0.5 * blur(rgb, S * 0.07)
red_haze = blur(rgb * np.array([1.0, 0.22, 0.18]), S * 0.16) * 0.6
light = rgb + glow + red_haze

if CLEAR:
    a = np.clip(light.max(-1) * 1.4, 0, 1)
    col = np.clip(light / np.maximum(a[..., None], 1e-4), 0, 1)
    out = np.concatenate([col, a[..., None]], -1)
    img = Image.fromarray((out * 255).astype(np.uint8), "RGBA")
else:
    # obsidian: near-black with a faint cool centre and a vignette
    d = np.hypot(xx - S / 2, yy - S / 2) / (S * 0.72)
    bg = np.stack([np.full((S, S), v, np.float32) for v in (0.035, 0.038, 0.048)], -1) * (1.15 - 0.6 * d ** 2)[..., None]
    final = 1 - np.exp(-(bg + light) * 1.25)          # soft tone map: highlights roll off instead of clipping
    final += (rng.random((S, S, 1)) - 0.5) / 255 * 1.5  # dither against banding in the glow
    img = Image.fromarray((np.clip(final, 0, 1) * 255).astype(np.uint8), "RGB")

out_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fireball-mark" + ("-clear" if CLEAR else "") + ".png")
img.save(out_path)
print(out_path)
