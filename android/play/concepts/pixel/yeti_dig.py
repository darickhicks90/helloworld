"""5 Eye Yeti title animation: a starlit clearing (no moon) where the yeti kneels,
turned away to the side, digging through the grass, busy and paying the viewer no mind.
Pixel-art frames the project screen plays back. Writes title_bg.png, title_fg.png,
yeti_frames.png and yeti_anim.json into OUT."""
import sys, math, random, json, os
sys.path.insert(0, '.')
from PIL import Image
import numpy as np
import siderig
import render as R

OUT = sys.argv[1]
W, H = 480, 240
rnd = random.Random(9)
B4, hx = R.B4, R.hx

# ---------------- background
sc = np.zeros((H, W, 3), np.uint8)
SKY = [hx(c) for c in ('#0c0a24', '#141236', '#1c1c4a', '#26305e', '#2f4670', '#3b5f7c', '#4c7a86')]
for y in range(H):
    t = y / 165 * (len(SKY) - 1)
    for x in range(W): sc[y, x] = SKY[int(np.clip(t + B4[y % 4, x % 4], 0, len(SKY) - 1))]
for _ in range(150):
    x, y = rnd.randrange(W), rnd.randrange(125)
    sc[y, x] = hx('#c8d0f0') if rnd.random() < 0.7 else hx('#fff4c0')
for (x, y) in ((40, 70), (210, 24), (450, 30), (150, 96), (300, 52), (262, 18)):
    for dx, dy in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)): sc[y + dy, x + dx] = hx('#e8ecff')
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
bg = Image.fromarray(sc); px = bg.load()
FEET, BODYX = 228, 380
for y in range(FEET - 6, FEET + 6):
    for x in range(BODYX - 80, BODYX + 80):
        if ((x - BODYX) / 66) ** 2 + ((y - FEET) / 5) ** 2 < 1 + B4[y % 4, x % 4] * 0.4: px[x, y] = GRASS[0]
for _ in range(26):
    x = rnd.randrange(8, W - 8); y = rnd.randrange(182, H - 4)
    if abs(x - BODYX) < 60: continue
    pc = rnd.choice((hx('#e86a8a'), hx('#f2d65a'), hx('#c8b8ff'), hx('#ffffff')))
    px[x, y + 1] = GRASS[4]; px[x, y + 2] = GRASS[3]
    for dx, dy in ((0, -1), (-1, 0), (1, 0), (0, 1)): px[x + dx, y + dy] = pc
    px[x, y] = hx('#ffd84a') if pc != hx('#f2d65a') else hx('#b4561a')
for _ in range(240):
    x = rnd.randrange(W); y = rnd.randrange(172, H)
    if abs(x - BODYX) < 34 and y < FEET - 6: continue
    h = 2 + int((y - 168) / 16) + rnd.randrange(3)
    col = GRASS[min(5, 3 + rnd.randrange(3))] if rnd.random() < 0.7 else GRASS[2]
    for k3 in range(h):
        if 0 <= y - k3 < H: px[x, y - k3] = col
DIRT = [hx(c) for c in ('#2a1a10', '#3e2816', '#5a3a20', '#7a5230')]
DIGX = BODYX - 62
for y in range(FEET - 7, FEET + 5):
    for x in range(DIGX - 26, DIGX + 26):
        e = ((x - DIGX) / 25) ** 2 + ((y - FEET + 1) / 5.5) ** 2
        if e < 1 + B4[y % 4, x % 4] * 0.5:
            px[x, y] = DIRT[int(np.clip((1 - e) * 3 + B4[y % 4, x % 4], 0, 3))]
for _ in range(30):   # loose clods around the hole
    x = DIGX + rnd.randrange(-34, 34); y = FEET + rnd.randrange(-6, 6)
    px[x, y] = DIRT[rnd.randrange(1, 4)]
bg.save(os.path.join(OUT, 'title_bg.png'))


# ---------------- frames: bent over, scooping with the near arm, a slight bob of the back
K = 270 / 1112
N = 6
frames = []
for i in range(N):
    t = i / (N - 1)
    e = 0.5 - 0.5 * math.cos(math.pi * t)
    im, off = siderig.pose(34 + 4 * e, -5 - 42 * e, -75, 165)
    TH = round(im.height * K)
    spr = R.snes_creature(im, TH)
    k = spr.width / im.width
    frames.append((spr, off, k))
# line the frames up on the hip (side.png coords 235, 610) and put the lowest point on the ground
HIPX, HIPY = BODYX + 6, None
cells = []
for spr, off, k in frames:
    hx_ = (235 + off[0]) * k; hy_ = (610 + off[1]) * k
    cells.append((spr, hx_, hy_))
ground_off = cells[0][0].height - cells[0][2]   # the body's lowest point (first frame) sits on the ground
HIPY = FEET + 4 - ground_off
placed = [(spr, int(round(BODYX - hx_)), int(round(HIPY - hy_))) for spr, hx_, hy_ in cells]
fx0 = min(p[1] for p in placed); fy0 = min(p[2] for p in placed)
fx1 = max(p[1] + p[0].width for p in placed); fy1 = max(p[2] + p[0].height for p in placed)
FW, FH = fx1 - fx0, fy1 - fy0
sheet = Image.new('RGBA', (FW * N, FH), (0, 0, 0, 0))
hands = []
for i, (spr, x, y) in enumerate(placed):
    cell = Image.new('RGBA', (FW, FH), (0, 0, 0, 0)); cell.paste(spr, (x - fx0, y - fy0), spr)
    ca = np.asarray(cell).copy(); gy = FEET + 3 - fy0
    if gy < FH: ca[gy:, :, 3] = 0
    cell = Image.fromarray(ca)
    sheet.paste(cell, (i * FW, 0))
    a = np.asarray(cell)[..., 3] > 0
    # the digging hand: lowest point in the front (left) half
    ys, xs = np.nonzero(a); j = int(np.argmax(ys * 4 - xs * 0.2))
    hands.append([int(xs[j] + fx0), int(ys[j] + fy0)])
sheet.save(os.path.join(OUT, 'yeti_frames.png'))

# ---------------- foreground: tall grass in front of his knees and the near edge of the hole
fg = Image.new('RGBA', (W, H), (0, 0, 0, 0)); fp = fg.load()
for _ in range(170):
    x = BODYX + rnd.randrange(-100, 80); base = FEET + rnd.randrange(1, 9)
    h = rnd.randrange(6, 20) if x > BODYX - 40 else rnd.randrange(4, 11)
    lean = rnd.choice((-1, 0, 0, 1))
    col = GRASS[rnd.choice((3, 4, 5, 5))]
    for k3 in range(h):
        xx = x + (lean * (k3 // 4)); yy = base - k3
        if 0 <= xx < W and 0 <= yy < H: fp[xx, yy] = (*col, 255)
fg.save(os.path.join(OUT, 'title_fg.png'))
json.dump({'frames': N, 'w': FW, 'h': FH, 'x': fx0, 'y': fy0, 'hands': hands, 'dig': [DIGX, FEET - 2]}, open(os.path.join(OUT, 'yeti_anim.json'), 'w'))
prev = bg.convert('RGBA')
for f in (0, N - 1):
    b = bg.convert('RGBA'); c = sheet.crop((f * FW, 0, (f + 1) * FW, FH)); b.alpha_composite(c, (fx0, fy0)); b.alpha_composite(fg)
    b.crop((240, 40, 480, 240)).resize((720, 600), Image.NEAREST).save(f'dig-{f}.png')
print('ok', N, FW, FH, fx0, fy0, hands)
