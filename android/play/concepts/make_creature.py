"""The five-eyed creature, drawn as a smooth illustration in the style of a
classic monster card: clean ink outlines, flat warm colours and hatched fur
strokes on a round, fluffy body with big furry arms and little claws. The
face follows the original pencil drawing: a long pale face under an arch of
hair, a third eye in the forehead, two pairs of eyes, a long nose with a
round bulb and a wide mouth (here a friendly smile). All eyes are natural
colours. Writes creature.png. Run: python3 make_creature.py"""
import math, random
from PIL import Image, ImageDraw

K = 3                          # supersampling
WD, HD = 700, 860              # design size
INK = (58, 34, 34)
FUR, FUR_L, FUR_D = (164, 92, 72), (196, 128, 100), (112, 56, 44)
SKIN, SKIN_S = (246, 228, 206), (226, 198, 172)
CLAW = (236, 150, 176)
img = Image.new('RGBA', (WD * K, HD * K), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
rnd = random.Random(4)
def P(pts): return [(x * K, y * K) for x, y in pts]
def shape(pts, fill, w=5, outline=INK):
    d.polygon(P(pts), fill=fill)
    if w: d.line(P(pts + pts[:1]), fill=outline, width=int(w * K), joint='curve')
def oval(cx, cy, rx, ry, rot=0.0, n=72, tufts=0.0, seed=0):
    r = random.Random(seed); pts = []
    for i in range(n):
        a = 2 * math.pi * i / n
        k = 1 + (tufts * (1 if i % 2 else -0.4) + r.uniform(-tufts, tufts) * 0.5 if tufts else 0)
        x, y = rx * math.cos(a) * k, ry * math.sin(a) * k
        pts.append((cx + x * math.cos(rot) - y * math.sin(rot), cy + x * math.sin(rot) + y * math.cos(rot)))
    return pts
def fluff(cx, cy, rx, ry, rot=0.0, seed=0, clump=24, fill=None):
    """A fluffy shape like the card's fur: an ellipse ringed by soft round clumps
    (outlined as one silhouette), with a few loose hairs sticking out."""
    r = random.Random(seed); fill = fill or FUR
    ring = []
    n = max(10, int(2 * math.pi * max(rx, ry) / (clump * 1.1)))
    for i in range(n):
        a = 2 * math.pi * (i + r.uniform(-0.25, 0.25)) / n
        x, y = rx * math.cos(a), ry * math.sin(a)
        ring.append((cx + x * math.cos(rot) - y * math.sin(rot), cy + x * math.sin(rot) + y * math.cos(rot), clump * r.uniform(0.75, 1.15)))
    body = oval(cx, cy, rx, ry, rot)
    w = 5
    for (x, y, rr) in ring: d.ellipse(P([(x - rr - w, y - rr - w), (x + rr + w, y + rr + w)]), fill=INK)
    d.polygon(P(oval(cx, cy, rx + w, ry + w, rot)), fill=INK)
    for (x, y, rr) in ring: d.ellipse(P([(x - rr, y - rr), (x + rr, y + rr)]), fill=fill)
    d.polygon(P(body), fill=fill)
    for (x, y, rr) in ring[::3]:   # loose hairs
        a = math.atan2(y - cy, x - cx) + r.uniform(-0.4, 0.4)
        d.line(P([(x + math.cos(a) * rr * 0.6, y + math.sin(a) * rr * 0.6), (x + math.cos(a) * (rr + 12), y + math.sin(a) * (rr + 12))]), fill=INK, width=int(2.5 * K))
    return oval(cx, cy, rx + clump * 0.6, ry + clump * 0.6, rot)

def inside(pts, x, y):
    c = False; j = len(pts) - 1
    for i in range(len(pts)):
        xi, yi = pts[i]; xj, yj = pts[j]
        if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi) + xi: c = not c
        j = i
    return c
def fur_strokes(region, n, cx, cy, avoid=()):
    """Short hatched fur strokes, combed outward and down from (cx, cy)."""
    for _ in range(n):
        x = rnd.uniform(min(p[0] for p in region), max(p[0] for p in region)); y = rnd.uniform(min(p[1] for p in region), max(p[1] for p in region))
        if not inside(region, x, y) or any(inside(a, x, y) for a in avoid): continue
        a = math.atan2(y - cy, x - cx) * 0.5 + math.pi / 2 * 0.5 + rnd.uniform(-0.35, 0.35)
        L = rnd.uniform(14, 30)
        pts = [(x + math.cos(a) * L * t + math.sin(t * 3) * 3, y + math.sin(a) * L * t) for t in (0, 0.5, 1)]
        col = FUR_D if rnd.random() < 0.7 else FUR_L
        d.line(P(pts), fill=col, width=int(rnd.uniform(2, 3.5) * K))
def claw(x, y, ang, size=16):
    a = math.radians(ang)
    tip = (x + math.cos(a) * size * 1.6, y + math.sin(a) * size * 1.6)
    b1 = (x + math.cos(a + 1.6) * size * 0.45, y + math.sin(a + 1.6) * size * 0.45)
    b2 = (x + math.cos(a - 1.6) * size * 0.45, y + math.sin(a - 1.6) * size * 0.45)
    shape([b1, tip, b2], CLAW, 3)

# ---- ears: small and rounded, the same fur
for ex in (138, 562):
    fluff(ex, 236, 44, 40, seed=ex, clump=16); shape(oval(ex, 240, 26, 23), FUR_D, 0)
# ---- stubby legs with claws
for lx in (236, 464):
    fluff(lx, 770, 96, 58, seed=lx, clump=20)
    for k, ang in enumerate((100, 90, 80)): claw(lx - 44 + k * 44, 822, ang)
# ---- the round, fluffy body
BODY = fluff(350, 468, 262, 270, seed=1, clump=30)
# ---- the face, long and pale (from the drawing)
FACE = []
for i in range(80):
    t = 2 * math.pi * i / 80
    s = math.sin(t); hw = 112 * (1 - 0.32 * max(0, s) ** 1.6)
    FACE.append((350 + hw * math.cos(t), 370 + (178 if s > 0 else 160) * s))
fur_strokes(BODY, 900, 350, 300, avoid=(FACE,))
shape(FACE, SKIN, 5)
# soft shading down one side of the face and nose
shape([(262, 330), (300, 300), (318, 420), (326, 500), (300, 520), (268, 460)], SKIN_S, 0)
# ---- hair arching over the forehead, framing the third eye
def bez(p0, p1, p2, n=16): return [((1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t * t * p2[0], (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t * t * p2[1]) for t in (i / n for i in range(n + 1))]
# a soft arch of hair: rounded outer edge, a scalloped inner fringe around the third eye
HAIR = bez((230, 336), (226, 186), (350, 178)) + bez((350, 178), (474, 186), (470, 336))[1:]
fringe = []
for i in range(9):
    t = i / 8; x = 466 - 232 * t; y = 336 - 110 * math.sin(math.pi * t) ** 0.8
    fringe += [(x, y), (x - 13, y - 8 + 4 * math.cos(math.pi * t))]
HAIR += fringe
shape(HAIR, FUR, 5)
for _ in range(60):
    x = rnd.uniform(240, 460); y = rnd.uniform(190, 320)
    if inside(HAIR, x, y): d.line(P([(x, y), (x + (x - 350) * 0.08, y + 18)]), fill=FUR_D, width=int(2.5 * K))
# little hair wisps over the brow, like the drawing's lashes of hair
for (x0, y0, x1, y1) in ((250, 300, 236, 336), (262, 304, 254, 344), (450, 300, 464, 336), (438, 304, 446, 344)):
    d.line(P([(x0, y0), (x1, y1)]), fill=INK, width=int(3 * K))

def eye(cx, cy, w, h, iris, iris_r, lid=0.0, look=(0, 0), swirl=False):
    """An almond eye; `lid` (0..1) lowers a relaxed upper lid."""
    pts = [(cx - w / 2 + w * i / 30, cy - h / 2 * math.sin(math.pi * i / 30)) for i in range(31)] + \
          [(cx + w / 2 - w * i / 30, cy + h / 2 * math.sin(math.pi * i / 30)) for i in range(31)]
    d.polygon(P(pts), fill=(255, 255, 255))
    ix, iy = cx + look[0], cy + look[1]
    d.ellipse(P([(ix - iris_r, iy - iris_r), (ix + iris_r, iy + iris_r)]), fill=iris)
    if swirl:   # the drawing's swirling iris, in natural tones
        for k in range(36):
            a = k * 0.5; r = 2 + k * iris_r / 40
            d.ellipse(P([(ix + math.cos(a) * r - 1.4, iy + math.sin(a) * r - 1.4), (ix + math.cos(a) * r + 1.4, iy + math.sin(a) * r + 1.4)]), fill=tuple(max(0, c - 40) for c in iris))
    pr = iris_r * 0.42
    d.ellipse(P([(ix - pr, iy - pr), (ix + pr, iy + pr)]), fill=(24, 16, 16))
    g = iris_r * 0.28
    d.ellipse(P([(ix - iris_r * 0.45 - g, iy - iris_r * 0.45 - g), (ix - iris_r * 0.45 + g, iy - iris_r * 0.45 + g)]), fill=(255, 255, 255))
    if lid > 0:
        d.polygon(P([(cx - w / 2 - 2, cy - h / 2 - 4), (cx + w / 2 + 2, cy - h / 2 - 4), (cx + w / 2 + 2, cy - h / 2 + h * lid), (cx - w / 2 - 2, cy - h / 2 + h * lid)]), fill=SKIN)
        d.line(P([(cx - w / 2, cy - h / 2 + h * lid), (cx + w / 2, cy - h / 2 + h * lid)]), fill=INK, width=int(3.5 * K))
    d.line(P(pts + pts[:1]), fill=INK, width=int(3.5 * K))

# soft rounded sockets, gentle rather than gloomy
for (cx, cy, rx, ry) in ((306, 330, 34, 26), (394, 330, 34, 26), (268, 380, 34, 24), (432, 380, 34, 24)):
    d.ellipse(P([(cx - rx, cy - ry), (cx + rx, cy + ry)]), fill=(232, 204, 184))
# (1) the third eye in the forehead: calm, wide, a swirling grey-green iris
eye(350, 262, 96, 50, (110, 140, 130), 20, swirl=True)
for k in range(7):  # lashes
    x = 312 + k * 12.5; d.line(P([(x, 241 - 3 * math.sin(k / 6 * math.pi)), (x + (x - 350) * 0.12, 228 - 5 * math.sin(k / 6 * math.pi))]), fill=INK, width=int(3 * K))
# (2) the upper pair: bright and curious, warm brown, brows raised
for cx in (306, 394):
    eye(cx, 330, 50, 36, (122, 78, 42), 12, look=(0, -1))
d.arc(P([(276, 292), (336, 318)]), 200, 340, fill=INK, width=int(4 * K)); d.arc(P([(364, 292), (424, 318)]), 200, 340, fill=INK, width=int(4 * K))
# (3) the lower pair: content and sleepy, hazel, heavy relaxed lids
for cx in (268, 432):
    eye(cx, 382, 48, 30, (126, 116, 58), 10, lid=0.42, look=(0, 3))
# rosy cheeks
for cx in (262, 438):
    d.ellipse(P([(cx - 26, 430), (cx + 26, 450)]), fill=(236, 168, 160))
# ---- the long nose with a round bulb
d.line(P([(340, 300), (336, 360), (333, 418)]), fill=INK, width=int(3.5 * K))
d.line(P([(362, 330), (365, 380), (367, 418)]), fill=(196, 160, 140), width=int(2.5 * K))
shape(oval(350, 432, 22, 20), SKIN, 4)
d.arc(P([(322, 428), (340, 448)]), 90, 270, fill=INK, width=int(3 * K)); d.arc(P([(360, 428), (378, 448)]), 270, 90, fill=INK, width=int(3 * K))
d.ellipse(P([(343, 420), (351, 426)]), fill=(255, 250, 240))
# ---- the wide mouth, as a friendly smile with a row of teeth (the drawing's mouth, cheered up)
MOUTH = [(296, 486)] + [(296 + 108 * i / 20, 486 + 30 * math.sin(math.pi * i / 20)) for i in range(1, 20)] + [(404, 486)] + [(404 - 108 * i / 20, 486 + 8 * math.sin(math.pi * i / 20)) for i in range(1, 20)]
shape(MOUTH, (190, 84, 96), 4)
d.polygon(P([(306, 491), (394, 491), (390, 500), (310, 500)]), fill=(250, 244, 230))
for x in range(318, 390, 12): d.line(P([(x, 491), (x, 500)]), fill=(200, 180, 170), width=int(1.5 * K))
d.line(P([(290, 482), (298, 488)]), fill=INK, width=int(3.5 * K)); d.line(P([(410, 482), (402, 488)]), fill=INK, width=int(3.5 * K))
# ---- big furry arms in front: one hangs down, one bends up to rest its claws on the chest
RARM = fluff(500, 640, 74, 132, rot=math.radians(-8), seed=7, clump=22)
fur_strokes(RARM, 140, 500, 520)
for k, ang in enumerate((105, 90, 75)): claw(470 + k * 30, 772, ang)
LARM = fluff(178, 560, 70, 120, rot=math.radians(12), seed=8, clump=22)
fur_strokes(LARM, 120, 178, 450)
FORE = fluff(268, 652, 98, 56, rot=math.radians(-24), seed=9, clump=20)
fur_strokes(FORE, 110, 220, 640)
for k, ang in enumerate((-40, -20, 0)): claw(352 + k * 4, 598 + k * 20, ang)

out = img.resize((WD, HD), Image.LANCZOS)
out = out.crop(out.getbbox())
out.save('creature.png')
print('ok')
