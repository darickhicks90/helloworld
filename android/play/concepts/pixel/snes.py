"""SNES-style detailed pixel scene of the creature (from the user's 3D model screenshot)."""
import math, random, colorsys
from PIL import Image, ImageFilter
import numpy as np
rnd = random.Random(4)
W, H = 256, 224

def hx(s): return tuple(int(s[i:i + 2], 16) for i in (1, 3, 5))
# hue-shifted ramps: shadows go cool/purple, lights go warm/yellow
FUR = [hx(c) for c in ('#2a1620', '#43222a', '#623530', '#86503c', '#a86c4c', '#c48a62', '#d9a67c', '#ebc398', '#f7deb6')]
IRIS = [hx(c) for c in ('#3a1408', '#7a2e0c', '#b4561a', '#e08a2c', '#f6b850')]
SCLERA = [hx(c) for c in ('#b8a49c', '#e2d6cc', '#fbf6ee')]
OUT = hx('#1c0e14')

# ---------- the creature
src = Image.open('front.png')
TH = 188
cw = round(src.width * TH / src.height)
small = src.resize((cw, TH), Image.LANCZOS)
a = np.asarray(small).astype(float)
rgb, al = a[..., :3], a[..., 3]
mask = al > 110
# soft directional light (upper left): darken towards the lower right of the silhouette
yy, xx = np.mgrid[0:TH, 0:cw]
blur = np.asarray(Image.fromarray((mask * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(5))).astype(float) / 255
shift = np.asarray(Image.fromarray((mask * 255).astype(np.uint8)).transform((cw, TH), Image.AFFINE, (1, 0, 3, 0, 1, 3)).filter(ImageFilter.GaussianBlur(3))).astype(float) / 255
rim = np.clip(blur - shift, 0, 1)   # bright where the up-left neighbour is outside (lit edge)
dark = np.clip(shift * 0 + (1 - blur), 0, 1)
lum = (0.3 * rgb[..., 0] + 0.55 * rgb[..., 1] + 0.15 * rgb[..., 2]) / 255
lum = (lum - 0.33) / 0.5
lum = lum + 0.17 - 0.18 * (xx / cw) - 0.10 * (yy / TH) + 0.25 * rim - 0.35 * dark
# local contrast so fur strands and folds read
detail = lum - np.asarray(Image.fromarray(np.uint8(np.clip(lum, 0, 1) * 255)).filter(ImageFilter.GaussianBlur(2))).astype(float) / 255
lum = lum + detail * 0.9
r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]
mx, mn = rgb.max(-1), rgb.min(-1)
sat = (mx - mn) / np.maximum(mx, 1)
B4 = np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]]) / 16 - 0.5
out = np.zeros((TH, cw, 4), np.uint8)
for y in range(TH):
    for x in range(cw):
        if not mask[y, x]: continue
        R, G, Bc = r[y, x], g[y, x], b[y, x]
        L = lum[y, x] + B4[y % 4, x % 4] * 0.07
        if R > 150 and sat[y, x] < 0.22 and (R + G + Bc) / 3 > 170:          # whites of the eyes
            c = SCLERA[int(np.clip((R - 150) / 105 * 3, 0, 2))]
        elif sat[y, x] > 0.5 and R > 120 and G < 120:                            # amber irises
            c = IRIS[int(np.clip((R + G) / 2 / 255 * 6 - 0.6, 0, 4))]
        elif (R + G + Bc) / 3 < 55 and y < TH * 0.4:                              # pupils
            c = OUT
        else:
            c = FUR[int(np.clip(L * 8 + 0.5, 0, 8))]
        out[y, x] = (*c, 255)
# 1-px outline, lighter where the light hits (selective outline)
o = out.copy()
for y in range(TH):
    for x in range(cw):
        if mask[y, x]: continue
        nb = [(y + dy, x + dx) for dy, dx in ((0, 1), (1, 0), (0, -1), (-1, 0)) if 0 <= y + dy < TH and 0 <= x + dx < cw and mask[y + dy, x + dx]]
        if nb:
            o[y, x] = (*(FUR[1] if (x < cw * 0.35 and y < TH * 0.6) else OUT), 255)
# ---- hand-pixelled eyes, placed from the model's face
k = TH / 897
def put(x, y, c):
    x, y = int(round(x)), int(round(y))
    if 0 <= x < cw and 0 <= y < TH: o[y, x] = (*c, 255)
def eye(cx, cy, w, h, ir, lid):
    cx, cy = cx * k, cy * k
    for y in range(int(cy - h), int(cy + h) + 1):
        for x in range(int(cx - w), int(cx + w) + 1):
            u, v = (x - cx) / w, (y - cy) / h
            if abs(v) <= (1 - u * u) * 1.05:
                put(x, y, SCLERA[2] if v < 0.3 else SCLERA[1])
    for y in range(int(cy - ir) - 1, int(cy + ir) + 2):
        for x in range(int(cx - ir) - 1, int(cx + ir) + 2):
            dd = math.hypot(x - cx, y - cy + 0.3)
            if dd <= ir and abs((y - cy) / h) <= (1 - ((x - cx) / w) ** 2) * 1.05:
                put(x, y, IRIS[1] if dd > ir - 0.8 else IRIS[3] if (y - cy) > 0 else IRIS[2])
    pr = max(0.6, ir * 0.42)
    for y in range(int(cy - pr) - 1, int(cy + pr) + 2):
        for x in range(int(cx - pr) - 1, int(cx + pr) + 2):
            if math.hypot(x - cx, y - cy) <= pr: put(x, y, OUT)
    if ir >= 2: put(cx - ir * 0.5, cy - ir * 0.5, (255, 255, 250))
    for x in range(int(cx - w), int(cx + w) + 1):   # upper lid line(s)
        u = (x - cx) / w
        yy0 = cy - h * (1 - u * u) * 1.05 - 0.5
        for t in range(lid): put(x, yy0 - t, OUT if t == 0 else FUR[2])
        put(x, cy + h * (1 - u * u) * 0.9 + 0.8, FUR[3])
eye(426, 58, 8.5, 3.6, 3.4, 2)
for ex, ey in ((372, 114), (480, 114), (381, 143), (473, 143)):
    eye(ex, ey, 3.6, 1.6, 1.5, 2)
creature = Image.fromarray(o)

# ---------- the scene: moonlit clearing
sc = np.zeros((H, W, 3), np.uint8)
SKY = [hx(c) for c in ('#0c0a24', '#141236', '#1c1c4a', '#26305e', '#2f4670', '#3b5f7c', '#4c7a86')]
for y in range(H):
    t = y / 150 * (len(SKY) - 1)
    for x in range(W):
        sc[y, x] = SKY[int(np.clip(t + (B4[y % 4, x % 4]) * 1.0, 0, len(SKY) - 1))]
# stars
for _ in range(70):
    x, y = rnd.randrange(W), rnd.randrange(110)
    sc[y, x] = hx('#c8d0f0') if rnd.random() < 0.7 else hx('#fff4c0')
for (x, y) in ((30, 20), (214, 34), (120, 12)):
    for dx, dy in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)): sc[y + dy, x + dx] = hx('#e8ecff')
# the moon with craters
MOON = [hx(c) for c in ('#9aa6b8', '#c4ccd6', '#e4e6dc', '#f8f6e6')]
mcx, mcy, mr = 196, 52, 24
for y in range(mcy - mr - 6, mcy + mr + 7):
    for x in range(mcx - mr - 6, mcx + mr + 7):
        d = math.hypot(x - mcx, y - mcy)
        if mr < d < mr + 6 and (d - mr) / 6 < 0.5 + B4[y % 4, x % 4] * 0.9:
            sc[y, x] = (np.array(sc[y, x], int) * 0.6 + np.array(SKY[4]) * 0.4).astype(np.uint8)   # glow
        if d <= mr:
            k = 3 - (x - mcx + y - mcy + mr * 1.1) / (mr * 2.2) * 2.4 + B4[y % 4, x % 4] * 0.6
            sc[y, x] = MOON[int(np.clip(k, 0, 3))]
for cx, cy, cr in ((188, 46, 5), (204, 60, 4), (200, 40, 2.5), (186, 62, 3)):
    for y in range(int(cy - cr), int(cy + cr) + 1):
        for x in range(int(cx - cr), int(cx + cr) + 1):
            if math.hypot(x - cx, y - cy) <= cr: sc[y, x] = MOON[1] if (x - cx) + (y - cy) > 0 else MOON[0]
# far hills and the tree line (two layers)
def ridge(base, amp, seed, col, top_col=None, jag=0):
    rr = random.Random(seed); ph = [rr.uniform(0, 6) for _ in range(4)]
    for x in range(W):
        h = base - amp * (0.5 * math.sin(x * 0.025 + ph[0]) + 0.3 * math.sin(x * 0.07 + ph[1]) + 0.2 * math.sin(x * 0.19 + ph[2]))
        if jag: h -= jag * abs(math.sin(x * 0.9 + ph[3]) * math.sin(x * 0.37))
        for y in range(int(h), H):
            sc[y, x] = col
        if top_col is not None and 0 <= int(h) < H: sc[int(h), x] = top_col
ridge(128, 12, 1, hx('#22305a'), hx('#34507a'))
ridge(140, 10, 2, hx('#18213e'), hx('#2a3c62'), jag=9)
ridge(150, 6, 3, hx('#101830'), None, jag=14)
# grass field: bands of green, darker towards the front, dithered
GRASS = [hx(c) for c in ('#0e2418', '#16341e', '#1f4624', '#2c5a2a', '#3c6e30', '#4f8436')]
for y in range(156, H):
    t = (y - 156) / (H - 156)
    for x in range(W):
        k = 4.6 - t * 3.2 + B4[y % 4, x % 4] * 1.2 + 0.6 * math.sin(x * 0.05 + y * 0.3) * (1 - t)
        sc[y, x] = GRASS[int(np.clip(k, 0, 5))]
img = Image.fromarray(sc)

# ground shadow under the creature
px = img.load()
cx0 = (W - cw) // 2
feet_y = 156 + 54
for y in range(feet_y - 8, feet_y + 6):
    for x in range(cx0 + 10, cx0 + cw - 10):
        if ((x - W / 2) / (cw * 0.36)) ** 2 + ((y - feet_y + 1) / 6) ** 2 < 1 + B4[y % 4, x % 4] * 0.4:
            px[x, y] = GRASS[0]
img.paste(creature, (cx0, feet_y - TH + 2), creature)

# grass blades and little flowers in front (some overlap the feet)
for _ in range(150):
    x = rnd.randrange(W); y = rnd.randrange(160, H)
    if abs(x - W / 2) < cw / 2 and y < feet_y + 2: continue
    h = 2 + int((y - 156) / 14) + rnd.randrange(3)
    col = GRASS[min(5, 3 + rnd.randrange(3))] if rnd.random() < 0.7 else GRASS[2]
    lean = rnd.choice((-1, 0, 1))
    for k in range(h):
        xx = x + (lean if k > h // 2 else 0)
        if 0 <= xx < W and 0 <= y - k < H: px[xx, y - k] = col
for _ in range(14):
    x = rnd.randrange(8, W - 8); y = rnd.randrange(170, H - 4)
    if abs(x - W / 2) < cw * 0.25 and y < feet_y + 2: continue
    pc = rnd.choice((hx('#e86a8a'), hx('#f2d65a'), hx('#c8b8ff'), hx('#ffffff')))
    px[x, y + 1] = GRASS[4]; px[x, y + 2] = GRASS[3]
    for dx, dy in ((0, -1), (-1, 0), (1, 0), (0, 1)): px[x + dx, y + dy] = pc
    px[x, y] = hx('#ffd84a') if pc != hx('#f2d65a') else hx('#b4561a')
# fireflies
for _ in range(9):
    x, y = rnd.randrange(10, W - 10), rnd.randrange(100, 190)
    if abs(x - W / 2) < cw / 2: continue
    px[x, y] = hx('#f6ff9a')
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        c = px[x + dx, y + dy]; px[x + dx, y + dy] = tuple(int(c[i] * 0.5 + (90, 110, 40)[i]) for i in range(3))

img.save('snes-native.png')
img.resize((W * 4, H * 4), Image.NEAREST).save('snes-x4.png')
print('colors', len(img.getcolors(10000)), 'creature', cw, TH)
