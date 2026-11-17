"""Game Boy Color battle-style sprite (56x56, one 4-colour palette) of the five-eyed
creature from the user's 3D model: chibi proportions, built from shapes and then
hand-placed pixels for the face. Writes gbc-native.png and gbc-x8.png."""
import math, random
from PIL import Image, ImageDraw
import numpy as np
S, K = 56, 8
WHITE, LIGHT, DARK, BLACK = (248, 248, 248), (240, 184, 128), (176, 96, 48), (40, 16, 16)
rnd = random.Random(3)

def shape(draw_fn):
    """Rasterise shapes drawn at 8x into a 56x56 boolean mask."""
    im = Image.new('L', (S * K, S * K), 0); d = ImageDraw.Draw(im); draw_fn(d)
    return np.asarray(im.resize((S, S), Image.BOX)) > 120
def E(d, cx, cy, rx, ry): d.ellipse([(cx - rx) * K, (cy - ry) * K, (cx + rx) * K, (cy + ry) * K], fill=255)
def limb(d, pts, w0, w1):
    n = len(pts) - 1
    for i in range(n * 8 + 1):
        t = i / (n * 8); j = min(int(t * n), n - 1); u = t * n - j
        x = pts[j][0] + (pts[j + 1][0] - pts[j][0]) * u; y = pts[j][1] + (pts[j + 1][1] - pts[j][1]) * u
        E(d, x, y, (w0 + (w1 - w0) * t) / 2, (w0 + (w1 - w0) * t) / 2)

def body(d):
    E(d, 28, 15, 12.5, 12)          # the domed head, merging into
    E(d, 28, 33, 15.5, 13)          # the big round belly
    E(d, 28, 24, 15, 9)             # broad shoulders
    for s in (-1, 1):               # hanging arms and stubby legs
        limb(d, [(28 + s * 12, 22), (28 + s * 18, 32), (28 + s * 22, 41)], 7, 5)
        limb(d, [(28 + s * 6, 42), (28 + s * 6.5, 50)], 8.5, 7)
def hands(d):
    for s in (-1, 1): E(d, 28 + s * 23, 43, 3, 2.6)
def feet(d):
    for s in (-1, 1): E(d, 28 + s * 7, 52.5, 5, 2.4)
def face(d): E(d, 28, 17, 8, 9.5)
def shade(d):                       # the side away from the light (upper left), and under the belly
    E(d, 40, 34, 6.5, 11); E(d, 28, 45, 12, 2.5); E(d, 39, 16, 3, 9)
    for s in (-1, 1): limb(d, [(28 + s * 6 + 2, 43), (28 + s * 6.5 + 2, 50)], 4, 3)
    limb(d, [(42, 24), (48, 33), (51, 41)], 3.4, 2.4)

B = body(None) if False else None
M = shape(body); Hm = shape(hands); Fm = shape(feet); FACE = shape(face); SH = shape(shade)
spr = np.zeros((S, S), np.uint8)
spr[M | Hm | Fm] = 1
spr[SH & (M | Fm) & ~FACE] = 2
# dithered border between light and shade
for y in range(1, S - 1):
    for x in range(1, S - 1):
        if spr[y, x] == 1 and not FACE[y, x] and ((spr[y, x + 1] == 2) or (spr[y + 1, x] == 2)) and (x + y) % 2 == 0: spr[y, x] = 2
# fur: short dark hatch strokes over the body (not the face, hands or feet), denser in the shade
for _ in range(150):
    x, y = rnd.randrange(S), rnd.randrange(S)
    if not M[y, x] or FACE[y, x] or Hm[y, x]: continue
    if 28 <= y <= 40 and abs(x - 28) < 7 and rnd.random() < 0.7: continue   # keep the belly smoother
    c = 3 if spr[y, x] == 2 else 2
    dx = -1 if x < 28 else 1
    for k in range(2):
        xx, yy = x + dx * k, y + k
        if 0 <= xx < S and 0 <= yy < S and M[yy, xx] and not FACE[yy, xx]: spr[yy, xx] = c
spr[Hm] = 1; spr[Fm & ~SH] = 1
# spiky fur along the silhouette
sil = spr > 0
for y in range(1, S - 1):
    for x in range(1, S - 1):
        if sil[y, x] or y > 46: continue
        nb = sil[y, x + 1] + sil[y, x - 1] + sil[y + 1, x] + sil[y - 1, x]
        if nb == 1 and rnd.random() < 0.28: spr[y, x] = 2
# black outline
m = spr > 0
o = spr.copy()
for y in range(S):
    for x in range(S):
        if not m[y, x]:
            if any(0 <= y + dy < S and 0 <= x + dx < S and m[y + dy, x + dx] for dy, dx in ((0, 1), (1, 0), (0, -1), (-1, 0))): o[y, x] = 3
spr = o
def put(x, y, c): spr[y, x] = c
# separating lines: arms from body, legs apart, hands' fingers, toes
for s in (-1, 1):   # the inner edge of each arm against the body
    for i in range(12):
        x = round(28 + s * (12.5 + i * 0.42)); y = 27 + i
        if spr[y, x]: put(x, y, 3)
for y in range(44, 52): put(28, y, 3)
for s in (-1, 1):
    hx = 28 + s * 23
    for fx in (-2, 0, 2): put(hx + fx, 45, 3); put(hx + fx, 46, 1 if fx else 1) if False else None
    put(hx - 1, 45, 3); put(hx + 1, 45, 3)
    for tx in (-3, -1, 1, 3): put(28 + s * 7 + tx, 54, 3)
    put(28 + s * 7 - 2, 53, 1); put(28 + s * 7 + 2, 53, 1)
# ---- the face
# the big top eye, high on the dome: white almond, amber iris, black pupil, a glint
cx, cy = 28, 8
for x in range(cx - 6, cx + 7):
    ax = abs(x - cx)
    rows = (7, 8) if ax >= 4 else (6, 7, 8, 9)
    if ax == 6: rows = (8,)
    for y in rows: put(x, y, 0)
    put(x, min(rows) - 1, 3)
    if ax <= 5: put(x, max(rows) + 1, 2)
for y in (6, 7, 8, 9):
    for x in (cx - 1, cx, cx + 1): put(x, y, 2)
put(cx, 7, 3); put(cx, 8, 3); put(cx - 1, 6, 0)
# brow tufts sweeping up and out
for s in (-1, 1):
    for i in range(5): put(28 + s * (8 + i), 12 - (i + 1) // 2, 3)
# four sleepy lower eyes: heavy black lid, white slit, amber dot
for ex, ey in ((23, 14), (33, 14), (24, 18), (32, 18)):
    for dx in (-2, -1, 0, 1, 2): put(ex + dx, ey - 1, 3)
    put(ex - 1, ey, 0); put(ex, ey, 2); put(ex + 1, ey, 0)
    put(ex - 2, ey, 3); put(ex + 2, ey, 3)
    put(ex - 1, ey + 1, 2); put(ex, ey + 1, 2); put(ex + 1, ey + 1, 2)
# long nose: shadow down its right side, a round tip and nostrils
for y in range(15, 21): put(30, y, 2)
put(29, 21, 2); put(30, 21, 2); put(31, 21, 2); put(26, 22, 2)
put(27, 22, 3); put(29, 22, 3); put(28, 23, 2)
# mouth with two little front teeth, lips below, a beard of hatching
for x in range(24, 33): put(x, 25, 3)
put(27, 26, 0); put(28, 26, 0); put(23, 24, 2); put(33, 24, 2)
for x in range(25, 32): put(x, 27, 2)
for x in (22, 25, 28, 31, 34):
    put(x, 29, 2); put(x + (1 if x > 28 else -1 if x < 28 else 0), 30, 2)
# navel
put(28, 37, 2); put(29, 37, 3); put(28, 38, 2)

pal = [WHITE, LIGHT, DARK, BLACK]
img = Image.new('RGB', (S, S)); img.putdata([pal[v] for v in spr.flatten()])
img.save('gbc-native.png'); img.resize((S * 8, S * 8), Image.NEAREST).save('gbc-x8.png')
print('ok')
