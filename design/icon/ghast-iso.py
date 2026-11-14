# Generates ghast-iso.svg (mark) and ghast-iso-badge.svg (app tile): flat isometric ghast, negative-space face.
import os
OUT = os.path.dirname(os.path.abspath(__file__))

# isometric cube, viewBox 128
T, L, R, C = (64, 8), (28, 29), (100, 29), (64, 50)
H = 40
LB, CB, RB = (L[0], L[1] + H), (C[0], C[1] + H), (R[0], R[1] + H)

def P(u, v):  # left (face) side
    return (L[0] + (C[0] - L[0]) * u, L[1] + (C[1] - L[1]) * u + H * v)

def Q(u, v):  # right side
    return (C[0] + (R[0] - C[0]) * u, C[1] + (R[1] - C[1]) * u + H * v)

def S(a, b):  # top: a along T->R, b along T->L
    return (T[0] + (R[0] - T[0]) * a + (L[0] - T[0]) * b, T[1] + (R[1] - T[1]) * a + (L[1] - T[1]) * b)

def f(p): return f"{p[0]:.2f} {p[1]:.2f}"
def poly(pts): return "M" + " L".join(f(p) for p in pts) + "Z"
def quad(fn, u0, u1, v0, v1): return poly([fn(u0, v0), fn(u1, v0), fn(u1, v1), fn(u0, v1)])

cube = poly([T, R, RB, CB, LB, L])

# cut-outs (negative space)
cuts = [
    quad(P, .10, .40, .34, .43), quad(P, .56, .88, .34, .43),   # closed eyes
    quad(P, .30, .35, .48, .60), quad(P, .78, .83, .48, .57),   # tear streaks (detached, under the inner ends)
    quad(P, .34, .64, .70, .78),                                 # mouth
    quad(Q, .24, .38, .24, .32), quad(Q, .58, .74, .58, .66),  # pixel marks
]
edges = [(L, C), (C, R), (C, CB), (LB, CB), (CB, RB)]

def ybot(x):  # y of the cube's bottom edge at x
    return LB[1] + (x - LB[0]) * (CB[1] - LB[1]) / (CB[0] - LB[0]) if x <= CB[0] else CB[1] + (x - CB[0]) * (RB[1] - CB[1]) / (RB[0] - CB[0])

tent = [(39, 17), (53, 27), (76, 24), (90, 14)]  # x, length (slant down-right)

def mark(fill, gap="3"):
    m = [f'<mask id="g" maskUnits="userSpaceOnUse" x="0" y="0" width="128" height="128"><rect width="128" height="128" fill="#fff"/>']
    m += [f'<path d="{d}" fill="#000"/>' for d in cuts]
    m += [f'<path d="M{f(a)} L{f(b)}" stroke="#000" stroke-width="{gap}" stroke-linecap="round"/>' for a, b in edges]
    m.append("</mask>")
    shapes = [f'<path d="{cube}"/>']
    for x, ln in tent:
        y = ybot(x) - 3
        dx = ln * 0.32
        shapes.append(f'<path d="{poly([(x - 3.5, y), (x + 3.5, y), (x + 3.5 + dx, y + ln), (x - 3.5 + dx, y + ln)])}"/>')
    return "".join(m), f'<g mask="url(#g)" fill="{fill}" stroke="{fill}" stroke-width="2" stroke-linejoin="round">' + "".join(shapes) + "</g>"

defs, body = mark("#FFFFFF")
open(os.path.join(OUT, "ghast-iso.svg"), "w", newline="\n").write(
    f'<svg xmlns="http://www.w3.org/2000/svg" width="128" height="128" viewBox="0 0 128 128">\n<defs>{defs}</defs>\n{body}\n</svg>\n')

# badge: dark tile, open hexagon frame (open at the bottom where the tentacles hang), the mark inside
defs, body = mark("#ECEEF1")
cx, cy, k = 64, 49, 1.32
hexa = [T, R, RB, LB, L]
fr = [(cx + (p[0] - cx) * k, cy + (p[1] - cy) * k) for p in [RB, R, T, L, LB]]
frame = "M" + " L".join(f(p) for p in fr)
open(os.path.join(OUT, "ghast-iso-badge.svg"), "w", newline="\n").write(
    f'<svg xmlns="http://www.w3.org/2000/svg" width="128" height="128" viewBox="0 0 128 128">\n'
    f'<defs>{defs}</defs>\n<rect width="128" height="128" rx="26" fill="#0A0A0B"/>\n'
    f'<g transform="translate(64 66) scale(0.72) translate(-64 -62)">'
    f'<path d="{frame}" fill="none" stroke="#9A9EA6" stroke-width="3.2" stroke-linecap="round" stroke-linejoin="round"/>'
    f'{body}</g>\n</svg>\n')

print("ok")
