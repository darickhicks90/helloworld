"""A digitized version of the five-eyed creature pencil drawing, coloured in
the style of a classic monster card: dark ink outlines, flat warm colours and
hatched fur. It follows the drawing's pose and features: a hunched figure
under a peaked hood of hair, a third eye in the forehead, two pairs of eyes
(one wide and startled, one heavy and weary), a long nose, gritted teeth, a
double chin, a hand on its chest, a long hanging arm, a round belly and
hairy legs. Filled in where the drawing stops: colour, the hanging hand and
the lower legs and feet. Eyes are natural colours.
Writes creature.png. Run: python3 make_creature.py"""
import math, random
from PIL import Image, ImageDraw

K = 3
WD, HD = 720, 1240
INK = (52, 30, 28)
FUR, FUR_L, FUR_D = (150, 84, 62), (186, 118, 90), (98, 50, 38)
SKIN, SKIN_S, SKIN_D = (240, 214, 190), (218, 184, 158), (190, 150, 124)
img = Image.new('RGBA', (WD * K, HD * K), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
rnd = random.Random(2)

def P(pts): return [(x * K, y * K) for x, y in pts]
def bez(*ps, n=18):
    """A cubic (or quadratic) Bezier through control points, as a point list."""
    out = []
    for i in range(n + 1):
        t = i / n
        if len(ps) == 3:
            p0, p1, p2 = ps
            out.append(((1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t * t * p2[0], (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t * t * p2[1]))
        else:
            p0, p1, p2, p3 = ps
            a, b, c, e = (1 - t) ** 3, 3 * (1 - t) ** 2 * t, 3 * (1 - t) * t * t, t ** 3
            out.append((a * p0[0] + b * p1[0] + c * p2[0] + e * p3[0], a * p0[1] + b * p1[1] + c * p2[1] + e * p3[1]))
    return out
def path(*segs):
    pts = []
    for sgm in segs: pts += sgm if not pts else sgm[1:]
    return pts
def shape(pts, fill, w=4.5):
    d.polygon(P(pts), fill=fill)
    if w: d.line(P(pts + pts[:1]), fill=INK, width=int(w * K), joint='curve')
def line(pts, w=3.0, col=INK): d.line(P(pts), fill=col, width=max(1, int(w * K)), joint='curve')
def inside(pts, x, y):
    c = False; j = len(pts) - 1
    for i in range(len(pts)):
        xi, yi = pts[i]; xj, yj = pts[j]
        if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi) + xi: c = not c
        j = i
    return c
def hairs(region, n, flow, avoid=(), length=(26, 60)):
    """Long hatched hair strands, combed by `flow(x, y)` -> angle."""
    xs = [p[0] for p in region]; ys = [p[1] for p in region]
    for _ in range(n):
        x = rnd.uniform(min(xs), max(xs)); y = rnd.uniform(min(ys), max(ys))
        if not inside(region, x, y) or any(inside(a, x, y) for a in avoid): continue
        a = flow(x, y) + rnd.uniform(-0.18, 0.18); L = rnd.uniform(*length); bend = rnd.uniform(-0.25, 0.25)
        pts = [(x + math.cos(a + bend * t) * L * t, y + math.sin(a + bend * t) * L * t) for t in (0, 0.33, 0.66, 1)]
        if all(inside(region, px, py) for px, py in pts):
            line(pts, rnd.uniform(1.6, 3.0), FUR_D if rnd.random() < 0.72 else FUR_L)
def eye(cx, cy, w, h, iris, ir, pupil=0.4, lid=0.0, look=(0, 0), swirl=False):
    top = bez((cx - w / 2, cy), (cx - w / 4, cy - h * 0.62), (cx + w / 4, cy - h * 0.62), (cx + w / 2, cy))
    bot = bez((cx + w / 2, cy), (cx + w / 4, cy + h * 0.62), (cx - w / 4, cy + h * 0.62), (cx - w / 2, cy))
    pts = top + bot[1:]
    d.polygon(P(pts), fill=(252, 250, 246))
    ix, iy = cx + look[0], cy + look[1]
    d.ellipse(P([(ix - ir, iy - ir), (ix + ir, iy + ir)]), fill=iris, outline=INK, width=int(2 * K))
    if swirl:
        sp = [(ix + math.cos(a) * (ir * 0.15 + a * ir * 0.07), iy + math.sin(a) * (ir * 0.15 + a * ir * 0.07)) for a in [i * 0.25 for i in range(46)]]
        line(sp, 1.8, tuple(max(0, c - 55) for c in iris))
    pr = ir * pupil
    d.ellipse(P([(ix - pr, iy - pr), (ix + pr, iy + pr)]), fill=(20, 14, 14))
    g = max(1.6, ir * 0.22)
    d.ellipse(P([(ix - ir * 0.45 - g, iy - ir * 0.45 - g), (ix - ir * 0.45 + g, iy - ir * 0.45 + g)]), fill=(255, 255, 255))
    if lid > 0:   # a heavy upper lid
        lp = bez((cx - w / 2 - 3, cy), (cx - w / 4, cy - h * 0.62 + h * lid), (cx + w / 4, cy - h * 0.62 + h * lid), (cx + w / 2 + 3, cy))
        d.polygon(P(top + lp[::-1]), fill=SKIN_S)
        line(lp, 3)
    line(pts + pts[:1], 3.2)

# ================= the long hanging arm (viewer's right), with its hand (filled in)
RARM = path(bez((520, 330), (640, 360), (690, 520), (676, 700)), bez((676, 700), (668, 820), (650, 900), (628, 950)),
            bez((628, 950), (600, 960), (580, 940), (572, 900)), bez((572, 900), (566, 760), (560, 560), (500, 420)))
shape(RARM, FUR, 5)
hairs(RARM, 520, lambda x, y: math.pi / 2 + (x - 600) * 0.002)
# the hand at its end (the drawing stops before it): a broad palm and long fingers with pale nails
HAND_R = path(bez((572, 920), (580, 960), (630, 968), (646, 930)), bez((646, 930), (652, 980), (600, 1000), (572, 920)))
shape(HAND_R, SKIN, 4)
for k, (x0, y0, x1, y1) in enumerate(((582, 962, 576, 1022), (598, 970, 598, 1034), (614, 970, 620, 1030), (630, 960, 642, 1012))):
    line([(x0, y0), (x1, y1)], 10); line([(x0, y0), (x1, y1)], 6.4, SKIN)
    d.ellipse(P([(x1 - 5, y1 - 4), (x1 + 5, y1 + 6)]), fill=(234, 222, 208), outline=INK, width=int(1.5 * K))
line([(646, 932), (664, 968)], 10); line([(646, 932), (664, 968)], 6.4, SKIN)   # thumb

# ================= legs and feet (filled in below where the drawing ends): long hair over the thighs
for side in (-1, 1):
    cx = 350 + side * 82
    LEG = path(bez((cx - 78, 940), (cx - 86, 1040), (cx - 70, 1120), (cx - 62, 1170)), bez((cx - 62, 1170), (cx - 20, 1182), (cx + 20, 1182), (cx + 62, 1170)),
               bez((cx + 62, 1170), (cx + 70, 1100), (cx + 76, 1020), (cx + 70, 940)))
    shape(LEG, FUR, 5)
    hairs(LEG, 260, lambda x, y: math.pi / 2, length=(30, 70))
    FOOT = path(bez((cx - 74, 1176), (cx - 80, 1212), (cx + 80, 1212), (cx + 74, 1176)), bez((cx + 74, 1176), (cx, 1160), (cx - 74, 1176)))
    shape(FOOT, SKIN_S, 4.5)
    for k in range(4): line([(cx - 48 + k * 32, 1196), (cx - 48 + k * 32, 1206)], 2.5)

# ================= the hood of hair: a peak on top, falling over the shoulders
HOOD = path(bez((350, 70), (420, 72), (520, 150), (560, 290)), bez((560, 290), (600, 360), (610, 440), (600, 520)),
            bez((600, 520), (520, 720), (180, 720), (100, 520)), bez((100, 520), (92, 440), (100, 360), (140, 290)),
            bez((140, 290), (180, 150), (280, 72), (350, 70)))
shape(HOOD, FUR, 5)

# ================= the torso: chest, the round belly (pale, as in the drawing)
TORSO = path(bez((190, 520), (250, 548), (450, 548), (510, 520)), bez((510, 520), (560, 640), (600, 800), (560, 920)),
             bez((560, 920), (520, 990), (180, 990), (140, 920)), bez((140, 920), (100, 800), (140, 640), (190, 520)))
shape(TORSO, SKIN, 5)
# hair along the flanks
for side in (-1, 1):
    FLANK = [(350 + side * 200, 560), (350 + side * 240, 700), (350 + side * 236, 900), (350 + side * 205, 960), (350 + side * 188, 860), (350 + side * 190, 640)]
    hairs(FLANK, 90, lambda x, y: math.pi / 2, length=(30, 60))
# the belly: a big round curve, its shade, the navel
line(bez((150, 760), (170, 990), (530, 990), (552, 760)), 3.2)
d.polygon(P(bez((420, 980), (520, 960), (556, 860), (548, 760))), fill=SKIN_S)
line(bez((300, 720), (350, 700), (420, 700), (470, 730)), 2.4, SKIN_D)
line(bez((228, 640), (280, 680), (330, 690), (360, 680)), 2.6, SKIN_D); line(bez((380, 680), (420, 690), (460, 672), (486, 640)), 2.6, SKIN_D)  # chest
line(bez((290, 760), (340, 772), (400, 772), (450, 760)), 2.2, SKIN_D)
d.ellipse(P([(342, 862), (372, 878)]), fill=SKIN_D, outline=INK, width=int(2.5 * K))
line(bez((330, 890), (350, 900), (380, 900), (392, 888)), 2.0, SKIN_D)
# hair across the hips, where the legs begin
HIPS = path(bez((150, 930), (240, 990), (460, 990), (550, 930)), bez((550, 930), (560, 990), (520, 1010), (350, 1000)), bez((350, 1000), (180, 1010), (140, 990), (150, 930)))
shape(HIPS, FUR, 4.5); hairs(HIPS, 120, lambda x, y: math.pi / 2, length=(16, 36))

# hair over the hood, combed down from the peak
FACE = path(bez((350, 160), (420, 160), (470, 230), (468, 330)), bez((468, 330), (470, 440), (420, 530), (350, 540)),
            bez((350, 540), (280, 530), (230, 440), (232, 330)), bez((232, 330), (230, 230), (280, 160), (350, 160)))
hairs(HOOD, 900, lambda x, y: math.atan2(y - 60, x - 350) * 0.9 + (math.pi / 2) * 0.1, avoid=(FACE, TORSO), length=(30, 80))

# ================= the face: long and pale under the hair
shape(FACE, SKIN, 5)
# shading down the left side and under the brow
d.polygon(P(bez((236, 300), (250, 420), (290, 500), (340, 532)) + bez((340, 532), (300, 470), (282, 380), (290, 300))[1:]), fill=SKIN_S)
# the hair's edge sweeps across the top of the forehead (around the third eye)
FRINGE = path(bez((226, 300), (232, 200), (290, 150), (350, 150)), bez((350, 150), (410, 150), (468, 200), (474, 300)),
              bez((474, 300), (452, 250), (432, 238), (420, 240)), bez((420, 240), (400, 200), (300, 200), (280, 240)), bez((280, 240), (268, 238), (248, 250), (226, 300)))
shape(FRINGE, FUR, 4.5)
for k in range(10):
    x = 262 + k * 20; line(bez((x, 168 + abs(x - 350) * 0.25), (x + (x - 350) * 0.05, 200), (x + (x - 350) * 0.12, 226 + abs(x - 350) * 0.1)), 1.8, FUR_D)
# long wisps of hair curling down past the temples, as in the drawing
for side in (-1, 1):
    for k in range(3):
        x0 = 350 + side * (130 + k * 12)
        line(bez((x0, 250 + k * 8), (x0 + side * 22, 280 + k * 6), (x0 + side * 10, 320 + k * 4), (x0 + side * 36, 330 + k * 10)), 2.6)

# (1) the third eye, under the hair's edge: wide, steady, a swirling iris
eye(350, 214, 104, 46, (104, 132, 120), 19, pupil=0.32, swirl=True)
for k in range(8):
    x = 306 + k * 12.5; line([(x, 200 - 4 * math.sin(k / 7 * math.pi)), (x + (x - 350) * 0.15, 186 - 6 * math.sin(k / 7 * math.pi))], 2.4)

# dark, sunken sockets around the four lower eyes
for (cx, cy, rx, ry) in ((298, 292, 44, 34), (402, 292, 44, 34), (264, 352, 40, 32), (436, 352, 40, 32)):
    d.ellipse(P([(cx - rx, cy - ry), (cx + rx, cy + ry)]), fill=SKIN_D)
    d.ellipse(P([(cx - rx * 0.8, cy - ry * 0.75), (cx + rx * 0.8, cy + ry * 0.9)]), fill=(206, 168, 142))
# (2) the upper pair: wide open and startled, small pupils
for cx in (298, 402):
    eye(cx, 292, 56, 42, (118, 84, 50), 13, pupil=0.3)
    for k in range(5):  # lashes
        x = cx - 22 + k * 11; line([(x, 276 - 3 * math.sin(k / 4 * math.pi)), (x + (x - cx) * 0.25, 266 - 4 * math.sin(k / 4 * math.pi))], 2.2)
# (3) the lower pair: heavy-lidded and weary, with bags beneath
for cx in (264, 436):
    eye(cx, 354, 52, 34, (124, 108, 60), 11, pupil=0.4, lid=0.45, look=(0, 3))
    line(bez((cx - 22, 376), (cx - 8, 386), (cx + 8, 386), (cx + 22, 376)), 2.2, SKIN_D)
    line(bez((cx - 18, 386), (cx - 6, 394), (cx + 6, 394), (cx + 18, 386)), 1.8, SKIN_D)

# ================= the long nose
line(bez((338, 270), (334, 330), (330, 380), (326, 420)), 3.0)
line(bez((362, 270), (366, 330), (370, 380), (374, 420)), 2.0, SKIN_D)
NOSE = path(bez((322, 410), (300, 430), (310, 456), (334, 452)), bez((334, 452), (342, 462), (358, 462), (366, 452)), bez((366, 452), (390, 456), (400, 430), (378, 410)))
d.polygon(P(NOSE), fill=SKIN); line(NOSE, 3.2)
for nx in (334, 366): d.ellipse(P([(nx - 7, 444), (nx + 7, 452)]), fill=INK)

# ================= the mouth: gritted teeth between thick lips
LIPS = path(bez((296, 486), (320, 462), (380, 462), (404, 486)), bez((404, 486), (380, 512), (320, 512), (296, 486)))
shape(LIPS, (196, 112, 106), 3.5)
TEETH = path(bez((308, 486), (330, 474), (370, 474), (392, 486)), bez((392, 486), (370, 498), (330, 498), (308, 486)))
shape(TEETH, (244, 236, 214), 2.5)
line([(310, 486), (390, 486)], 2.2)
for x in range(322, 390, 12): line([(x, 476), (x, 496)], 1.6)
# the double chin and a crease below
line(bez((300, 528), (330, 548), (370, 548), (400, 528)), 2.6, SKIN_D)
line(bez((280, 552), (320, 580), (380, 580), (420, 552)), 3.0)

# ================= the bent arm (viewer's left): hairy, hand on the chest with long fingers
LARM = path(bez((150, 420), (70, 450), (40, 580), (60, 700)), bez((60, 700), (80, 780), (140, 800), (220, 760)),
            bez((220, 760), (280, 730), (320, 700), (330, 660)), bez((330, 660), (300, 640), (250, 660), (190, 690)),
            bez((190, 690), (170, 620), (180, 540), (214, 500)), bez((214, 500), (206, 440), (176, 412), (150, 420)))
shape(LARM, FUR, 5)
hairs(LARM, 520, lambda x, y: math.pi / 2 if x < 150 else math.atan2(700 - y, 330 - x) * -0.3 + 0.2, length=(26, 64))
HAND = path(bez((300, 650), (330, 620), (360, 616), (382, 626)), bez((382, 626), (378, 660), (350, 690), (316, 690)), bez((316, 690), (296, 680), (292, 664), (300, 650)))
shape(HAND, SKIN, 4)
for k, (x0, y0, x1, y1) in enumerate(((360, 626, 424, 612), (370, 642, 434, 640), (366, 658, 426, 668), (352, 674, 406, 694))):
    line([(x0, y0), (x1, y1)], 9.5); line([(x0, y0), (x1, y1)], 6.0, SKIN)
    d.ellipse(P([(x1 - 6, y1 - 4), (x1 + 4, y1 + 4)]), fill=(234, 222, 208), outline=INK, width=int(1.5 * K))
line(bez((322, 646), (334, 640), (344, 640), (352, 646)), 1.8, SKIN_D)

out = img.resize((WD, HD), Image.LANCZOS)
out = out.crop(out.getbbox())
out.save('creature.png')
print('ok')
