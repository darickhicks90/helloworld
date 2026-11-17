"""Renders every pose in the two styles the user picked: the SNES moonlit-clearing scene and
the Game Boy Color 4-colour sprite. Usage: python3 render.py"""
import math, random, json
from PIL import Image, ImageFilter
import numpy as np

def hx(s): return tuple(int(s[i:i + 2], 16) for i in (1, 3, 5))
FUR = [hx(c) for c in ('#2a1620', '#43222a', '#623530', '#86503c', '#a86c4c', '#c48a62', '#d9a67c', '#ebc398', '#f7deb6')]
IRIS = [hx(c) for c in ('#3a1408', '#7a2e0c', '#b4561a', '#e08a2c', '#f6b850')]
SCLERA = [hx(c) for c in ('#b8a49c', '#e2d6cc', '#fbf6ee')]
OUT = hx('#1c0e14')
B4 = np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]]) / 16 - 0.5
FRONT_H = 897            # the front screenshot's full body height (head to feet)
def _ref():
    a = np.asarray(Image.open('front.png')).astype(float); m = a[..., 3] > 110
    return ((0.3 * a[..., 0] + 0.55 * a[..., 1] + 0.15 * a[..., 2]) / 255)[m].mean()
REF_LUM = _ref()

# ======================= SNES
def snes_creature(src, TH):
    cw = round(src.width * TH / src.height)
    small = src.resize((cw, TH), Image.LANCZOS)
    a = np.asarray(small).astype(float); rgb, al = a[..., :3], a[..., 3]
    mask = al > 110
    yy, xx = np.mgrid[0:TH, 0:cw]
    m8 = Image.fromarray((mask * 255).astype(np.uint8))
    blur = np.asarray(m8.filter(ImageFilter.GaussianBlur(5))).astype(float) / 255
    shift = np.asarray(m8.transform((cw, TH), Image.AFFINE, (1, 0, 3, 0, 1, 3)).filter(ImageFilter.GaussianBlur(3))).astype(float) / 255
    rim = np.clip(blur - shift, 0, 1); dark = np.clip(1 - blur, 0, 1)
    lum = (0.3 * rgb[..., 0] + 0.55 * rgb[..., 1] + 0.15 * rgb[..., 2]) / 255
    lum = lum + (REF_LUM - lum[mask].mean())     # match every view's brightness to the front view
    lum = (lum - 0.33) / 0.5
    lum = lum + 0.10 - 0.18 * (xx / cw) - 0.10 * (yy / TH) + 0.25 * rim - 0.35 * dark
    detail = lum - np.asarray(Image.fromarray(np.uint8(np.clip(lum, 0, 1) * 255)).filter(ImageFilter.GaussianBlur(2))).astype(float) / 255
    lum = lum + detail * 0.9
    r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]
    sat = (rgb.max(-1) - rgb.min(-1)) / np.maximum(rgb.max(-1), 1)
    out = np.zeros((TH, cw, 4), np.uint8)
    for y in range(TH):
        for x in range(cw):
            if not mask[y, x]: continue
            R, G, Bc = r[y, x], g[y, x], b[y, x]
            L = lum[y, x] + B4[y % 4, x % 4] * 0.07
            if R > 150 and sat[y, x] < 0.22 and (R + G + Bc) / 3 > 170: c = SCLERA[int(np.clip((R - 150) / 105 * 3, 0, 2))]
            elif sat[y, x] > 0.5 and R > 120 and G < 120: c = IRIS[int(np.clip((R + G) / 2 / 255 * 6 - 0.6, 0, 4))]
            elif (R + G + Bc) / 3 < 55 and y < TH * 0.4: c = OUT
            else: c = FUR[int(np.clip(L * 8 + 0.5, 0, 8))]
            out[y, x] = (*c, 255)
    o = out.copy()
    for y in range(TH):
        for x in range(cw):
            if mask[y, x]: continue
            if any(0 <= y + dy < TH and 0 <= x + dx < cw and mask[y + dy, x + dx] for dy, dx in ((0, 1), (1, 0), (0, -1), (-1, 0))):
                o[y, x] = (*(FUR[1] if (x < cw * 0.35 and y < TH * 0.6) else OUT), 255)
    return Image.fromarray(o)

def snes_scene(creature, seed=4, feet_off=0):
    W, H = 256, 224; rnd = random.Random(seed)
    sc = np.zeros((H, W, 3), np.uint8)
    SKY = [hx(c) for c in ('#0c0a24', '#141236', '#1c1c4a', '#26305e', '#2f4670', '#3b5f7c', '#4c7a86')]
    for y in range(H):
        t = y / 150 * (len(SKY) - 1)
        for x in range(W): sc[y, x] = SKY[int(np.clip(t + B4[y % 4, x % 4], 0, len(SKY) - 1))]
    for _ in range(70):
        x, y = rnd.randrange(W), rnd.randrange(110)
        sc[y, x] = hx('#c8d0f0') if rnd.random() < 0.7 else hx('#fff4c0')
    for (x, y) in ((30, 20), (214, 34), (120, 12)):
        for dx, dy in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)): sc[y + dy, x + dx] = hx('#e8ecff')
    MOON = [hx(c) for c in ('#9aa6b8', '#c4ccd6', '#e4e6dc', '#f8f6e6')]
    mcx, mcy, mr = 196, 52, 24
    for y in range(mcy - mr - 6, mcy + mr + 7):
        for x in range(mcx - mr - 6, mcx + mr + 7):
            d = math.hypot(x - mcx, y - mcy)
            if mr < d < mr + 6 and (d - mr) / 6 < 0.5 + B4[y % 4, x % 4] * 0.9:
                sc[y, x] = (np.array(sc[y, x], int) * 0.6 + np.array(SKY[4]) * 0.4).astype(np.uint8)
            if d <= mr:
                k = 3 - (x - mcx + y - mcy + mr * 1.1) / (mr * 2.2) * 2.4 + B4[y % 4, x % 4] * 0.6
                sc[y, x] = MOON[int(np.clip(k, 0, 3))]
    for cx, cy, cr in ((188, 46, 5), (204, 60, 4), (200, 40, 2.5), (186, 62, 3)):
        for y in range(int(cy - cr), int(cy + cr) + 1):
            for x in range(int(cx - cr), int(cx + cr) + 1):
                if math.hypot(x - cx, y - cy) <= cr: sc[y, x] = MOON[1] if (x - cx) + (y - cy) > 0 else MOON[0]
    def ridge(base, amp, seed_, col, top_col=None, jag=0):
        rr = random.Random(seed_); ph = [rr.uniform(0, 6) for _ in range(4)]
        for x in range(W):
            h = base - amp * (0.5 * math.sin(x * 0.025 + ph[0]) + 0.3 * math.sin(x * 0.07 + ph[1]) + 0.2 * math.sin(x * 0.19 + ph[2]))
            if jag: h -= jag * abs(math.sin(x * 0.9 + ph[3]) * math.sin(x * 0.37))
            for y in range(int(h), H): sc[y, x] = col
            if top_col is not None and 0 <= int(h) < H: sc[int(h), x] = top_col
    ridge(128, 12, 1, hx('#22305a'), hx('#34507a'))
    ridge(140, 10, 2, hx('#18213e'), hx('#2a3c62'), jag=9)
    ridge(150, 6, 3, hx('#101830'), None, jag=14)
    GRASS = [hx(c) for c in ('#0e2418', '#16341e', '#1f4624', '#2c5a2a', '#3c6e30', '#4f8436')]
    for y in range(156, H):
        t = (y - 156) / (H - 156)
        for x in range(W):
            k = 4.6 - t * 3.2 + B4[y % 4, x % 4] * 1.2 + 0.6 * math.sin(x * 0.05 + y * 0.3) * (1 - t)
            sc[y, x] = GRASS[int(np.clip(k, 0, 5))]
    img = Image.fromarray(sc); px = img.load()
    cw = creature.width
    cx0 = (W - cw) // 2 + feet_off
    feet_y = 210
    foot_w = min(cw, 120)
    for y in range(feet_y - 8, feet_y + 6):
        for x in range(max(0, W // 2 - foot_w // 2 - 20), min(W, W // 2 + foot_w // 2 + 20)):
            if ((x - W / 2) / (foot_w * 0.62)) ** 2 + ((y - feet_y + 1) / 6) ** 2 < 1 + B4[y % 4, x % 4] * 0.4: px[x, y] = GRASS[0]
    img.paste(creature, (cx0, feet_y - creature.height + 2), creature)
    cm = np.zeros((H, W), bool)
    ca = np.asarray(creature.getchannel('A')) > 0
    y0 = feet_y - creature.height + 2
    for y in range(creature.height):
        for x in range(cw):
            if ca[y, x] and 0 <= y0 + y < H and 0 <= cx0 + x < W: cm[y0 + y, cx0 + x] = True
    for _ in range(150):
        x = rnd.randrange(W); y = rnd.randrange(160, H)
        h = 2 + int((y - 156) / 14) + rnd.randrange(3)
        col = GRASS[min(5, 3 + rnd.randrange(3))] if rnd.random() < 0.7 else GRASS[2]
        lean = rnd.choice((-1, 0, 1))
        for k in range(h):
            xx = x + (lean if k > h // 2 else 0)
            if 0 <= xx < W and 0 <= y - k < H: px[xx, y - k] = col
    for _ in range(14):
        x = rnd.randrange(8, W - 8); y = rnd.randrange(170, H - 4)
        if cm[max(0, y - 2):y + 3, x - 2:x + 3].any(): continue
        pc = rnd.choice((hx('#e86a8a'), hx('#f2d65a'), hx('#c8b8ff'), hx('#ffffff')))
        px[x, y + 1] = GRASS[4]; px[x, y + 2] = GRASS[3]
        for dx, dy in ((0, -1), (-1, 0), (1, 0), (0, 1)): px[x + dx, y + dy] = pc
        px[x, y] = hx('#ffd84a') if pc != hx('#f2d65a') else hx('#b4561a')
    for _ in range(9):
        x, y = rnd.randrange(10, W - 10), rnd.randrange(100, 190)
        if cm[y - 1:y + 2, x - 1:x + 2].any(): continue
        px[x, y] = hx('#f6ff9a')
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            c = px[x + dx, y + dy]; px[x + dx, y + dy] = tuple(min(255, int(c[i] * 0.5 + (90, 110, 40)[i])) for i in range(3))
    return img

# ======================= Game Boy Color
WHITE, LIGHT, DARK, BLACK = (248, 248, 248), (240, 184, 128), (176, 96, 48), (40, 16, 16)
def gbc(src, k, face_origin=None, S=56):
    """4-colour sprite; `k` = sprite pixels per screenshot pixel; `face_origin` = where the front
    view's (0, 0) sits in `src`, to hand-pixel the face (front-facing poses only)."""
    TH = round(src.height * k); cw = round(src.width * k)
    sm = src.resize((cw, TH), Image.LANCZOS)
    a = np.asarray(sm).astype(float); al = a[..., 3]; rgb = a[..., :3]
    mask = al > 100
    lum = (0.3 * rgb[..., 0] + 0.55 * rgb[..., 1] + 0.15 * rgb[..., 2]) / 255
    lum = lum + (REF_LUM - lum[mask].mean())
    blur = np.asarray(Image.fromarray(np.uint8(np.clip(lum, 0, 1) * 255)).filter(ImageFilter.GaussianBlur(1.2))).astype(float) / 255
    yy, xx = np.mgrid[0:TH, 0:cw]
    L = lum + (lum - blur) * 1.2 - 0.12 * (xx / cw) + 0.05
    B2 = np.array([[0, 2], [3, 1]]) / 4 - 0.375
    spr = np.zeros((S, S), np.uint8)
    ox, oy = (S - cw) // 2, S - TH - 1
    for y in range(TH):
        for x in range(cw):
            if not mask[y, x] or not (0 <= oy + y < S and 0 <= ox + x < S): continue
            v = L[y, x] + B2[y % 2, x % 2] * 0.12
            spr[oy + y, ox + x] = 1 if v > 0.56 else 2 if v > 0.30 else 3
    m = spr > 0
    for y in range(S):
        for x in range(S):
            if m[y, x] and any(not (0 <= y + dy < S and 0 <= x + dx < S) or not m[y + dy, x + dx] for dy, dx in ((0, 1), (1, 0), (0, -1), (-1, 0))):
                spr[y, x] = 3
    if face_origin is not None:
        fx, fy = face_origin
        def at(px_, py_): return ox + int(round((px_ + fx) * k)), oy + int(round((py_ + fy) * k))
        def put(x, y, c):
            if 0 <= x < S and 0 <= y < S: spr[y, x] = c
        cx, cy = at(426, 58)
        for x in range(cx - 3, cx + 4):
            for y in range(cy - 1, cy + 2):
                if abs(x - cx) == 3 and y != cy: continue
                put(x, y, 0)
        for x in range(cx - 3, cx + 4): put(x, cy - 2, 3)
        for x in range(cx - 2, cx + 3): put(x, cy + 2, 2)
        put(cx - 4, cy, 3); put(cx + 4, cy, 3)
        for dx, dy in ((-1, -1), (0, -1), (1, -1), (-1, 0), (1, 0), (-1, 1), (0, 1), (1, 1)): put(cx + dx, cy + dy, 2)
        put(cx, cy, 3)
        for ex, ey in ((372, 114), (480, 114), (381, 143), (473, 143)):
            x, y = at(ex, ey)
            put(x - 1, y, 0); put(x, y, 2); put(x + 1, y, 0)
            for d in (-2, -1, 0, 1, 2): put(x + d, y - 1, 3)
            put(x - 2, y, 3); put(x + 2, y, 3)
            for d in (-1, 0, 1): put(x + d, y + 1, 2)
        nx, ny = at(427, 205)
        put(nx - 1, ny, 3); put(nx + 1, ny, 3); put(nx, ny + 1, 2)
        mx, my = at(427, 238)
        for d in range(-3, 4): put(mx + d, my, 3)
        put(mx - 1, my + 1, 0); put(mx, my + 1, 0)
        for d in (-2, -1, 0, 1, 2): put(mx + d, my + 2, 2)
        for side in (-1, 1):
            bx, by = at(426 + side * 110, 86)
            for i in range(4): put(bx + side * i, by - (i // 2), 3)
    pal = [WHITE, LIGHT, DARK, BLACK]
    img = Image.new('RGB', (S, S)); img.putdata([pal[v] for v in spr.flatten()])
    return img

if __name__ == '__main__':
    offs = json.load(open('pose-offsets.json'))
    POSES = [('front', Image.open('front.png'), (0, 0), FRONT_H)] + \
            [(n, Image.open(f'pose-{n}.png'), tuple(offs[n]), FRONT_H) for n in ('arms-down', 'wave', 'cheer', 'belly', 'shrug')] + \
            [(n, Image.open(f'{n}.png'), None, None) for n in ('side', 'back34', 'back')]
    KS = 188 / FRONT_H
    snes, gb = [], []
    for i, (name, im, off, bodyh) in enumerate(POSES):
        # keep one scale for all front poses (body height = 188 px); the other views are fitted to the same height
        k = KS if bodyh else 188 / im.height
        if im.width * k > 250: k = 250 / im.width
        if im.height * k > 206: k = 206 / im.height
        cr = snes_creature(im, round(im.height * k))
        sc = snes_scene(cr, seed=4 + i)
        sc.resize((1024, 896), Image.NEAREST).save(f'snes-{name}.png'); snes.append(sc)
        kg = 54 / FRONT_H if bodyh else 54 / im.height
        kg = min(kg, 55 / im.width, 54 / im.height)
        g = gbc(im, kg, off if bodyh else None)
        g.resize((448, 448), Image.NEAREST).save(f'gbc-{name}.png'); gb.append(g)
        print(name)
    # contact sheets
    cols = 3
    rows = (len(snes) + cols - 1) // cols
    sheet = Image.new('RGB', (cols * 256 * 2 + (cols + 1) * 16, rows * 224 * 2 + (rows + 1) * 16), (20, 18, 30))
    for i, s in enumerate(snes):
        sheet.paste(s.resize((512, 448), Image.NEAREST), (16 + (i % cols) * 528, 16 + (i // cols) * 464))
    sheet.save('sheet-snes.png')
    gs = Image.new('RGB', (len(gb) * (56 * 4 + 16) + 16, 56 * 4 + 32), (248, 248, 248))
    for i, g in enumerate(gb): gs.paste(g.resize((224, 224), Image.NEAREST), (16 + i * 240, 16))
    gs.save('sheet-gbc.png')
