"""The duel's creature: the night-meadow pixel art re-posed into a fighting stance with a battle axe.
Cut out of its background (no moon), arms re-posed at the shoulders (axe raised in one hand, the other
reaching to grab), knees bent a little, an angry brow, and the axe painted in the same pixel style."""
import math, sys
from PIL import Image, ImageDraw
nat = Image.open(sys.argv[1]).convert("RGBA")       # 256x224 native pixels
W, H = nat.size
px = nat.load()

def is_fur(c):
    r, g, b, _ = c
    return r > g >= b - 6 and r - b > 22 and r > 48
mask = Image.new("L", (W, H), 0); m = mask.load()
for y in range(H):
    for x in range(W):
        if is_fur(px[x, y]): m[x, y] = 255
# keep the biggest piece (him), then fill the holes in it (eyes, fireflies in front of him)
seen = set(); best = []
for y in range(H):
    for x in range(W):
        if m[x, y] and (x, y) not in seen:
            comp = []; st = [(x, y)]; seen.add((x, y))
            while st:
                cx, cy = st.pop(); comp.append((cx, cy))
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, ny = cx + dx, cy + dy
                    if 0 <= nx < W and 0 <= ny < H and m[nx, ny] and (nx, ny) not in seen: seen.add((nx, ny)); st.append((nx, ny))
            if len(comp) > len(best): best = comp
body = Image.new("L", (W, H), 0); bm = body.load()
for (x, y) in best: bm[x, y] = 255
outside = set(); st = [(0, 0)]; outside.add((0, 0))
while st:
    cx, cy = st.pop()
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        nx, ny = cx + dx, cy + dy
        if 0 <= nx < W and 0 <= ny < H and not bm[nx, ny] and (nx, ny) not in outside: outside.add((nx, ny)); st.append((nx, ny))
for y in range(H):
    for x in range(W):
        if not bm[x, y] and (x, y) not in outside: bm[x, y] = 255
# repaint stray firefly / grass pixels inside him with the fur beside them
src = nat.copy(); sp = src.load()
for y in range(H):
    for x in range(W):
        if bm[x, y] and not is_fur(px[x, y]):
            r, g, b, _ = px[x, y]
            white = r > 200 and g > 190 and b > 170   # the whites of his eyes stay
            dark = r < 60 and g < 50                  # outlines stay
            if not white and not dark and (g > r - 6 or b > r):
                for k in range(1, 7):
                    hit = next(((x + dx, y + dy) for dx, dy in ((-k, 0), (k, 0), (0, -k), (0, k)) if 0 <= x + dx < W and 0 <= y + dy < H and is_fur(px[x + dx, y + dy])), None)
                    if hit: sp[x, y] = px[hit]; break
cut = Image.new("RGBA", (W, H), (0, 0, 0, 0)); cut.paste(src, (0, 0), body)

def poly_mask(pts):
    mm = Image.new("L", (W, H), 0); ImageDraw.Draw(mm).polygon(pts, fill=255)
    out = Image.new("L", (W, H), 0); out.paste(body, (0, 0), mm); return out
L_ARM = [(20, 62), (97, 48), (93, 70), (89, 99), (78, 126), (62, 152), (20, 152)]
R_ARM = [(250, 62), (165, 48), (171, 70), (173, 99), (184, 126), (200, 152), (250, 152)]
la_m = poly_mask(L_ARM); ra_m = poly_mask(R_ARM)
def take(m_):
    o = Image.new("RGBA", (W, H), (0, 0, 0, 0)); o.paste(cut, (0, 0), m_); return o
la = take(la_m); ra = take(ra_m)
torso_m = body.copy()
tm = torso_m.load(); lm = la_m.load(); rm = ra_m.load()
for y in range(H):
    for x in range(W):
        if lm[x, y] or rm[x, y]: tm[x, y] = 0
legs_m = torso_m.copy(); ImageDraw.Draw(legs_m).rectangle((0, 0, W, 149), fill=0)
up_m = torso_m.copy(); ImageDraw.Draw(up_m).rectangle((0, 150, W, H), fill=0)
torso = take(up_m); legs = take(legs_m)

PAD_T, PAD_L, PAD_R = 60, 40, 70
CW, CH = W + PAD_L + PAD_R, H + PAD_T
GROUND = PAD_T + 214
canvas = Image.new("RGBA", (CW, CH), (0, 0, 0, 0))
DROP = 0

# ---- legs as drawn
legs_l = Image.new("RGBA", (CW, CH), (0, 0, 0, 0)); legs_l.alpha_composite(legs, (PAD_L, PAD_T))

# ---- arms
def arm_layer(img, pivot, ang):
    lay = Image.new("RGBA", (CW, CH), (0, 0, 0, 0)); lay.alpha_composite(img, (PAD_L, PAD_T + DROP))
    return lay.rotate(ang, resample=Image.NEAREST, center=(pivot[0] + PAD_L, pivot[1] + PAD_T + DROP))
def moved(pivot, pt, ang):
    a = math.radians(ang); dx, dy = pt[0] - pivot[0], pt[1] - pivot[1]
    return (pivot[0] + math.cos(a) * dx + math.sin(a) * dy + PAD_L, pivot[1] - math.sin(a) * dx + math.cos(a) * dy + PAD_T + DROP)
LP, RP = (98, 60), (172, 60)
LANG, RANG = -30, 96          # left: swung up and out to grab; right: raised high with the axe
arm_l = arm_layer(la, LP, LANG); arm_r = arm_layer(ra, RP, RANG)
# the raised hand: the arm's pixel farthest from the shoulder
ar = arm_r.load(); pvx, pvy = RP[0] + PAD_L, RP[1] + PAD_T
far = max(((x, y) for y in range(CH) for x in range(CW) if ar[x, y][3] > 0), key=lambda q: (q[0] - pvx) ** 2 + (q[1] - pvy) ** 2)
# grip a little back down the arm from the fingertips
gl = math.hypot(far[0] - pvx, far[1] - pvy)
hand_r = (far[0] - (far[0] - pvx) / gl * 9, far[1] - (far[1] - pvy) / gl * 9)

# ---- the axe: haft through the raised fist, a big bearded double head above, painted in pixels
axe = Image.new("RGBA", (CW, CH), (0, 0, 0, 0)); ad = ImageDraw.Draw(axe)
OUT = (34, 24, 30, 255); W1 = (84, 52, 30, 255); W2 = (118, 76, 42, 255); W3 = (150, 102, 58, 255); WRAP = (62, 36, 26, 255)
ST1 = (64, 70, 84, 255); ST2 = (104, 112, 128, 255); ST3 = (156, 164, 178, 255); ST4 = (214, 220, 230, 255); EDGE = (244, 248, 252, 255)
hx, hy = hand_r
bx_, by_ = hx + 14, hy + 24          # butt
tx_, ty_ = hx - 12, hy - 58          # top
def lerp(a, b, t): return a + (b - a) * t
n = int(math.hypot(tx_ - bx_, ty_ - by_))
for i in range(n + 1):
    t = i / n; x = round(lerp(bx_, tx_, t)); y = round(lerp(by_, ty_, t))
    ad.point((x - 2, y), OUT); ad.point((x + 2, y), OUT)
    ad.point((x - 1, y), W1); ad.point((x, y), W2 if (i // 4) % 2 else W3); ad.point((x + 1, y), W2)
for t in (0.06, 0.1, 0.14):
    x = round(lerp(bx_, tx_, t)); y = round(lerp(by_, ty_, t)); ad.line((x - 2, y, x + 2, y), fill=WRAP)
ad.rectangle((bx_ - 3, by_ - 1, bx_ + 3, by_ + 3), fill=OUT); ad.rectangle((bx_ - 2, by_, bx_ + 2, by_ + 2), fill=ST3)
cx, cy = round(lerp(bx_, tx_, 0.88)), round(lerp(by_, ty_, 0.88))
def blade(side):
    pts = []
    for i in range(0, 25):
        a = math.radians(-80 + i * 6.6)
        pts.append((cx + side * (8 + 22 * math.cos(a)), cy + 4 + 24 * math.sin(a)))
    pts += [(cx + side * 7, cy + 10), (cx + side * 4, cy + 6), (cx + side * 4, cy - 6), (cx + side * 7, cy - 9)]
    return pts
for s_ in (-1, 1):
    ad.polygon(blade(s_), fill=OUT)
    ad.polygon([(cx + (x - cx) * 0.88, cy + (y - cy) * 0.88) for x, y in blade(s_)], fill=ST2)
    ad.polygon([(cx + (x - cx) * 0.62 + s_ * 3, cy + (y - cy) * 0.62) for x, y in blade(s_)][:13] + [(cx + s_ * 8, cy)], fill=ST3)
    edge = [(cx + s_ * (8 + 21 * math.cos(math.radians(-80 + i * 6.6))), cy + 4 + 23 * math.sin(math.radians(-80 + i * 6.6))) for i in range(25)]
    ad.line(edge, fill=EDGE, width=1)
    for i in range(0, 25, 2):
        x, y = edge[i]; ad.point((x - s_, y), ST4)
    ad.line([(cx + s_ * 9, cy + 12), (cx + s_ * 16, cy + 18)], fill=ST1)
ad.rectangle((cx - 5, cy - 10, cx + 5, cy + 12), fill=OUT); ad.rectangle((cx - 4, cy - 9, cx + 4, cy + 11), fill=ST1)
ad.line([(cx - 3, cy - 8), (cx - 3, cy + 10)], fill=ST2)
ad.line([(cx - 1, cy - 4), (cx + 2, cy - 1), (cx - 1, cy + 2), (cx + 2, cy + 5)], fill=(232, 120, 52, 255))
ad.polygon([(cx - 3, cy - 10), (cx + 3, cy - 10), (cx, cy - 20)], fill=OUT); ad.polygon([(cx - 1, cy - 11), (cx + 1, cy - 11), (cx, cy - 17)], fill=ST3)

# ---- torso and head, dropped into the crouch, with an angry brow
tl = Image.new("RGBA", (CW, CH), (0, 0, 0, 0)); tl.alpha_composite(torso, (PAD_L, PAD_T + DROP))
fd = ImageDraw.Draw(tl)
BROW = (46, 26, 18, 255)
for (x0, y0, x1, y1) in ((107, 43, 118, 46), (143, 43, 132, 46)):
    fd.line((x0 + PAD_L, y0 + PAD_T + DROP, x1 + PAD_L, y1 + PAD_T + DROP), fill=BROW, width=2)

out = Image.new("RGBA", (CW, CH), (0, 0, 0, 0))
out = Image.alpha_composite(out, arm_l)
out = Image.alpha_composite(out, legs_l)
out = Image.alpha_composite(out, arm_r)
out = Image.alpha_composite(out, tl)
# round fur caps over the shoulder joints
patch = cut.crop((120, 100, 140, 120))
for (pv, sgn) in ((LP, -1), (RP, 1)):
    mm = Image.new("L", patch.size, 0); ImageDraw.Draw(mm).ellipse((0, 0, 19, 19), fill=255)
    cap = Image.new("RGBA", patch.size, (0, 0, 0, 0)); cap.paste(patch, (0, 0), mm)
    out.alpha_composite(cap, (int(pv[0] + PAD_L - 10 + sgn * 2), int(pv[1] + PAD_T + DROP - 8)))
out = Image.alpha_composite(out, axe)
# fingers curled round the haft, in front of it
od = ImageDraw.Draw(out)
SK = (190, 132, 92, 255); SKD = (140, 90, 60, 255)
for k_ in range(4):
    yy = hy - 6 + k_ * 3; xx = round(lerp(bx_, tx_, (by_ - yy) / (by_ - ty_)))
    od.rectangle((xx - 4, yy, xx + 3, yy + 2), fill=OUT); od.rectangle((xx - 3, yy, xx + 2, yy + 1), fill=SK); od.point((xx + 2, yy + 1), SKD)
# a clean outline where he meets the air
o = out.load(); Wc, Hc = out.size
edge = [(x, y) for y in range(Hc) for x in range(Wc) if o[x, y][3] == 0 and any(0 <= x + a < Wc and 0 <= y + b < Hc and o[x + a, y + b][3] > 128 for a, b in ((1, 0), (-1, 0), (0, 1), (0, -1)))]
for (x, y) in edge: o[x, y] = (40, 22, 16, 255)
for y in range(Hc):
    for x in range(Wc):
        if o[x, y][3] and sum(1 for a in (-1, 0, 1) for b in (-1, 0, 1) if (a or b) and 0 <= x + a < Wc and 0 <= y + b < Hc and o[x + a, y + b][3]) <= 1: o[x, y] = (0, 0, 0, 0)
# any green or yellow speck left on the fur (a firefly, a blade of grass) takes the fur beside it
for _ in range(2):
    for y in range(Hc):
        for x in range(Wc):
            r, g, b_, al = o[x, y]
            if al and g > r - 20 and g > b_ + 25:
                for k in range(1, 6):
                    hit = next(((x + dx, y + dy) for dx, dy in ((-k, 0), (k, 0), (0, -k), (0, k)) if 0 <= x + dx < Wc and 0 <= y + dy < Hc and o[x + dx, y + dy][3] and o[x + dx, y + dy][0] > o[x + dx, y + dy][1] + 8), None)
                    if hit: o[x, y] = o[hit]; break
out = out.crop(out.getbbox())
out.save(sys.argv[2]); print(out.size)
