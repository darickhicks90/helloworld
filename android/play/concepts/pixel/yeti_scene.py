"""5 Eye Yeti title scene: the yeti in headphones, playing a pad sampler on a stump,
in the moonlit clearing (SNES-style, 480x240). Writes yeti-scene.png and a 3x preview."""
import sys, math, random, json
sys.path.insert(0, '.')
from PIL import Image, ImageDraw, ImageFilter
import numpy as np
import render as R

W, H = 480, 240
rnd = random.Random(9)
B4 = R.B4
hx = R.hx

# ---------------- hi-res pose with headphones
pose = Image.open('pose-play.png')
OFF = tuple(json.load(open('pose-play.json')))
import json
OX, OY = OFF          # front.png coords -> pose coords (from rig)
TOP = 70
hi = Image.new('RGBA', (pose.width, pose.height + TOP), (0, 0, 0, 0))
hi.paste(pose, (0, TOP))
d = ImageDraw.Draw(hi)
def P(x, y): return (x + OX, y + OY + TOP)
BLUE_D, BLUE, BLUE_L, CUSH = (24, 52, 112, 255), (52, 112, 208, 255), (150, 190, 245, 255), (30, 34, 52, 255)
# headband: a thick arc over the crown
cx, cy, rx, ry = 430, 120, 190, 175
for wdt, col in ((40, BLUE_D), (28, BLUE), (8, BLUE_L)):
    box = [P(cx - rx, cy - ry), P(cx + rx, cy + ry)]
    d.arc([box[0], box[1]], 196, 344, fill=col, width=wdt)
# ear cups on the sides of the head
for sx in (262, 598):
    l, t, r, b = sx - 42, 110, sx + 42, 250
    d.rounded_rectangle([P(l - 6, t - 6), P(r + 6, b + 6)], radius=34, fill=BLUE_D)
    d.rounded_rectangle([P(l, t), P(r, b)], radius=30, fill=BLUE)
    d.rounded_rectangle([P(l + 10, t + 12), P(l + 26, b - 30)], radius=8, fill=BLUE_L)
    cushion = (sx + (30 if sx < 430 else -30))
    d.rounded_rectangle([P(cushion - 14, t + 6), P(cushion + 14, b - 6)], radius=12, fill=CUSH)

# ---------------- pixelize (the liked v1 look), with a blue ramp for the headphones
BLUES = [hx(c) for c in ('#10183a', '#1c3070', '#2c50a8', '#4a7ad8', '#8ab4f0', '#d4e6ff')]
def creature_px(src, TH):
    cw = round(src.width * TH / src.height)
    small = src.resize((cw, TH), Image.LANCZOS)
    a = np.asarray(small).astype(float); rgb = a[..., :3]
    base = R.snes_creature(src, TH)          # the fur/eye pipeline
    o = np.asarray(base).copy()
    r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]
    blue = (b > r + 25) & (a[..., 3] > 110)
    lum = (0.3 * r + 0.55 * g + 0.15 * b) / 255
    for y, x in zip(*np.nonzero(blue)):
        k = int(np.clip(lum[y, x] * 7 - 0.2 + B4[y % 4, x % 4] * 0.6, 0, 5))
        o[y, x] = (*BLUES[k], 255)
    return Image.fromarray(o)

TH = 205
cr = creature_px(hi, TH)

# ---------------- the wide clearing
sc = np.zeros((H, W, 3), np.uint8)
SKY = [hx(c) for c in ('#0c0a24', '#141236', '#1c1c4a', '#26305e', '#2f4670', '#3b5f7c', '#4c7a86')]
for y in range(H):
    t = y / 165 * (len(SKY) - 1)
    for x in range(W): sc[y, x] = SKY[int(np.clip(t + B4[y % 4, x % 4], 0, len(SKY) - 1))]
for _ in range(130):
    x, y = rnd.randrange(W), rnd.randrange(120)
    sc[y, x] = hx('#c8d0f0') if rnd.random() < 0.7 else hx('#fff4c0')
for (x, y) in ((40, 70), (210, 24), (450, 30), (150, 96)):
    for dx, dy in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)): sc[y + dy, x + dx] = hx('#e8ecff')
MOON = [hx(c) for c in ('#9aa6b8', '#c4ccd6', '#e4e6dc', '#f8f6e6')]
mcx, mcy, mr = 262, 50, 26
for y in range(mcy - mr - 7, mcy + mr + 8):
    for x in range(mcx - mr - 7, mcx + mr + 8):
        dd = math.hypot(x - mcx, y - mcy)
        if mr < dd < mr + 7 and (dd - mr) / 7 < 0.5 + B4[y % 4, x % 4] * 0.9:
            sc[y, x] = (np.array(sc[y, x], int) * 0.6 + np.array(SKY[4]) * 0.4).astype(np.uint8)
        if dd <= mr:
            k = 3 - (x - mcx + y - mcy + mr * 1.1) / (mr * 2.2) * 2.4 + B4[y % 4, x % 4] * 0.6
            sc[y, x] = MOON[int(np.clip(k, 0, 3))]
for ccx, ccy, ccr in ((254, 44, 5), (271, 58, 4), (266, 38, 2.5), (252, 60, 3)):
    for y in range(int(ccy - ccr), int(ccy + ccr) + 1):
        for x in range(int(ccx - ccr), int(ccx + ccr) + 1):
            if math.hypot(x - ccx, y - ccy) <= ccr: sc[y, x] = MOON[1] if (x - ccx) + (y - ccy) > 0 else MOON[0]
def ridge(base, amp, seed_, col, top_col=None, jag=0):
    rr = random.Random(seed_); ph = [rr.uniform(0, 6) for _ in range(4)]
    for x in range(W):
        h = base - amp * (0.5 * math.sin(x * 0.02 + ph[0]) + 0.3 * math.sin(x * 0.06 + ph[1]) + 0.2 * math.sin(x * 0.17 + ph[2]))
        if jag: h -= jag * abs(math.sin(x * 0.9 + ph[3]) * math.sin(x * 0.37))
        for y in range(int(h), H): sc[y, x] = col
        if top_col is not None and 0 <= int(h) < H: sc[int(h), x] = top_col
ridge(140, 14, 1, hx('#22305a'), hx('#34507a'))
ridge(152, 10, 2, hx('#18213e'), hx('#2a3c62'), jag=9)
ridge(162, 6, 3, hx('#101830'), None, jag=14)
GRASS = [hx(c) for c in ('#0e2418', '#16341e', '#1f4624', '#2c5a2a', '#3c6e30', '#4f8436')]
for y in range(168, H):
    t = (y - 168) / (H - 168)
    for x in range(W):
        k = 4.6 - t * 3.2 + B4[y % 4, x % 4] * 1.2 + 0.6 * math.sin(x * 0.05 + y * 0.3) * (1 - t)
        sc[y, x] = GRASS[int(np.clip(k, 0, 5))]
img = Image.fromarray(sc); px = img.load()

# ---------------- the yeti, right of centre
cx0 = 382 - cr.width // 2
feet = 233
# ground shadow
for y in range(feet - 6, feet + 6):
    for x in range(cx0 - 10, cx0 + cr.width + 10):
        if 0 <= x < W and 0 <= y < H and ((x - 382) / 62) ** 2 + ((y - feet) / 5) ** 2 < 1 + B4[y % 4, x % 4] * 0.4: px[x, y] = GRASS[0]
img.paste(cr, (cx0, feet - cr.height + 1), cr)

# ---------------- music notes drifting up from the pads
NOTE = ["..##", "..#.#", "..#..", "..#..", "###..", "###..", ".#..."]
NOTE2 = [".####", ".#..#", ".#..#", ".#..#", "##.##", "##.##"]
def note(x, y, shape, col):
    for j, row in enumerate(shape):
        for i, ch in enumerate(row):
            if ch == '#':
                px[x + i, y + j] = col
                if px[x + i + 1, y + j + 1] != col: px[x + i + 1, y + j + 1] = hx('#0c0a24') if y < 150 else px[x + i + 1, y + j + 1]
for (x, y, s, col) in ((318, 96, NOTE, hx('#f6c26b')), (304, 70, NOTE2, hx('#8fd3f4')), (322, 46, NOTE, hx('#f7efdc')), (452, 124, NOTE2, hx('#f6c26b')), (462, 150, NOTE, hx('#8fd3f4'))):
    note(x, y, s, col)
# fireflies and flowers in the grass
for _ in range(10):
    x, y = rnd.randrange(10, W - 10), rnd.randrange(110, 200)
    if abs(x - 382) < 70: continue
    px[x, y] = hx('#f6ff9a')
for _ in range(26):
    x = rnd.randrange(8, W - 8); y = rnd.randrange(182, H - 4)
    if abs(x - 382) < 70: continue
    pc = rnd.choice((hx('#e86a8a'), hx('#f2d65a'), hx('#c8b8ff'), hx('#ffffff')))
    px[x, y + 1] = GRASS[4]; px[x, y + 2] = GRASS[3]
    for dx, dy in ((0, -1), (-1, 0), (1, 0), (0, 1)): px[x + dx, y + dy] = pc
    px[x, y] = hx('#ffd84a') if pc != hx('#f2d65a') else hx('#b4561a')
for _ in range(220):
    x = rnd.randrange(W); y = rnd.randrange(172, H)
    if abs(x - 382) < 40 and y < feet - 8: continue
    h = 2 + int((y - 168) / 16) + rnd.randrange(3)
    col = GRASS[min(5, 3 + rnd.randrange(3))] if rnd.random() < 0.7 else GRASS[2]
    for k3 in range(h):
        if 0 <= y - k3 < H: px[x, y - k3] = col

img.save('yeti-scene.png')
img.resize((W * 3, H * 3), Image.NEAREST).save('yeti-scene-x3.png')
print('ok', cr.size)
