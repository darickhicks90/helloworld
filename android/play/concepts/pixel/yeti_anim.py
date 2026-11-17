"""5 Eye Yeti title animation: a starlit clearing (no moon) and the yeti reaching up
to catch fireflies, as pixel-art frames the project screen plays back.
Writes title_bg.png, yeti_frames.png, yeti_blink.png and yeti_anim.json into OUT."""
import sys, math, random, json, os
sys.path.insert(0, '.')
from PIL import Image
import numpy as np
import rig
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
FEET, BODYX = 233, 352
for y in range(FEET - 6, FEET + 6):
    for x in range(BODYX - 70, BODYX + 70):
        if ((x - BODYX) / 58) ** 2 + ((y - FEET) / 5) ** 2 < 1 + B4[y % 4, x % 4] * 0.4: px[x, y] = GRASS[0]
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
bg.save(os.path.join(OUT, 'title_bg.png'))

# ---------------- yeti frames: the right arm reaches up for a firefly and comes back down
K = 190 / 897                         # front body height -> 190 px
OXs, OYs = BODYX - 431 * K, FEET - 897 * K
N = 8
frames = []
for i in range(N):
    t = i / (N - 1)
    e = 0.5 - 0.5 * math.cos(math.pi * t)          # ease in/out
    im, off = rig.pose(('L', 8 + 6 * e, 4 * e), ('R', 4 + 112 * e, 6 + 48 * e))
    TH = round(im.height * K)
    spr = R.snes_creature(im, TH)
    x0 = OXs - off[0] * spr.width / im.width; y0 = OYs - off[1] * TH / im.height
    frames.append((spr, int(round(x0)), int(round(y0))))
fx0 = min(f[1] for f in frames); fy0 = min(f[2] for f in frames)
fx1 = max(f[1] + f[0].width for f in frames); fy1 = max(f[2] + f[0].height for f in frames)
FW, FH = fx1 - fx0, fy1 - fy0
sheet = Image.new('RGBA', (FW * N, FH), (0, 0, 0, 0))
hands = []
headTop = int(OYs - fy0 + 4)
for i, (spr, x, y) in enumerate(frames):
    cell = Image.new('RGBA', (FW, FH), (0, 0, 0, 0)); cell.paste(spr, (x - fx0, y - fy0), spr)
    sheet.paste(cell, (i * FW, 0))
    a = np.asarray(cell)[..., 3] > 0
    cx = int(BODYX - fx0)
    # the hand: the highest point of the reaching arm (above the head once it's raised)
    ys, xs = np.nonzero(a); j = int(np.argmin(ys))
    if not (xs[j] > cx + 10 and ys[j] < headTop - 2):
        ys, xs = np.nonzero(a[:, cx + 44:]); j = int(np.argmin(ys)); xs = xs + cx + 44
    hands.append([int(xs[j] + fx0), int(ys[j] + fy0)])
sheet.save(os.path.join(OUT, 'yeti_frames.png'))

# ---------------- blink overlay: all five eyes shut (the head doesn't move between frames)
cell = np.asarray(sheet.crop((0, 0, FW, FH))).copy()
EYE = set(R.SCLERA + R.IRIS)
blink = np.zeros_like(cell)
headH = int(FH * 0.4)
eye = np.zeros(cell.shape[:2], bool)
for y in range(headH):
    for x in range(FW):
        if cell[y, x, 3] and tuple(cell[y, x, :3]) in EYE: eye[y, x] = True
# pupils: outline-coloured pixels surrounded by eye colour
for y in range(1, headH - 1):
    for x in range(1, FW - 1):
        if cell[y, x, 3] and tuple(cell[y, x, :3]) == R.OUT and eye[y - 1:y + 2, x - 1:x + 2].sum() >= 3: eye[y, x] = True
for y in range(headH):
    for x in range(FW):
        if eye[y, x]:
            low = y + 1 < FH and not eye[y + 1, x]
            blink[y, x] = (*(R.FUR[2] if low else R.FUR[6]), 255)
Image.fromarray(blink).save(os.path.join(OUT, 'yeti_blink.png'))
json.dump({'frames': N, 'w': FW, 'h': FH, 'x': fx0, 'y': fy0, 'hands': hands}, open(os.path.join(OUT, 'yeti_anim.json'), 'w'))

# preview: frames over the background
prev = Image.new('RGB', (W * 2, H * 2))
for i, f in enumerate((0, N - 1)):
    b = bg.copy(); c = sheet.crop((f * FW, 0, (f + 1) * FW, FH)); b.paste(c, (fx0, fy0), c)
    if f == N - 1:
        bl = Image.fromarray(blink); b.paste(bl, (fx0, fy0), bl)
    hxp, hyp = hands[f]; b.putpixel((hxp, max(0, hyp - 4)), (246, 255, 154))
    prev.paste(b.resize((W, H)), (0, 0) if i == 0 else (W, 0))
prev.save('anim-preview.png')
bg2 = bg.copy(); c = sheet.crop(((N // 2) * FW, 0, (N // 2 + 1) * FW, FH)); bg2.paste(c, (fx0, fy0), c); bg2.save('anim-mid.png')
print('ok', N, FW, FH, fx0, fy0, hands)
