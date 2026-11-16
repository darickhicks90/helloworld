"""The five-eyed creature from the pencil drawing, coloured and posed sitting:
leaning back on one elbow, a hand resting on its belly, one knee up with that
foot flat on the ground and the other leg stretched out. Ink-and-colour,
with the drawing's sketchy outlines and hatched fur. Writes creature-sitting.png."""
import math, random
from PIL import Image, ImageDraw, ImageFilter, ImageChops

K = 2
W, H = 1360, 1080
PAPER = (246, 238, 222)
INK = (46, 30, 26)
FUR, FUR_L, FUR_D = (150, 84, 62), (196, 128, 94), (92, 48, 36)
SKIN, SKIN_S, SKIN_D = (240, 212, 186), (214, 174, 148), (186, 142, 116)
NAIL = (236, 224, 210)
rnd = random.Random(11)
canvas = Image.new('RGB', (W * K, H * K), PAPER)
d = ImageDraw.Draw(canvas)
SW = 1.0
xf = lambda x, y: (x, y)

def P(pts): return [(xf(x, y)[0] * K, xf(x, y)[1] * K) for x, y in pts]
def bez(*ps, n=20):
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
    for s in segs: pts += s if not pts else s[1:]
    return pts
def smooth(*pts, n=14):
    """A closed Catmull-Rom curve through the given points."""
    out = []; m = len(pts)
    for i in range(m):
        p0, p1, p2, p3 = pts[i - 1], pts[i], pts[(i + 1) % m], pts[(i + 2) % m]
        for k in range(n):
            t = k / n; t2, t3 = t * t, t * t * t
            out.append(tuple(0.5 * (2 * p1[j] + (-p0[j] + p2[j]) * t + (2 * p0[j] - 5 * p1[j] + 4 * p2[j] - p3[j]) * t2 + (-p0[j] + 3 * p1[j] - 3 * p2[j] + p3[j]) * t3) for j in (0, 1)))
    return out
def ellipse(cx, cy, rx, ry, n=40): return [(cx + rx * math.cos(i / n * 6.2832), cy + ry * math.sin(i / n * 6.2832)) for i in range(n)]

def ink(pts, w=3.6, col=INK, closed=False, passes=2):
    """A sketchy pencil-ink line: a main stroke whose weight swells and thins, and a looser second pass."""
    if closed: pts = pts + pts[:2]
    ph = rnd.uniform(0, 6)
    for p in range(passes):
        j = 0.9 * p
        q = [(x + rnd.gauss(0, j), y + rnd.gauss(0, j)) for x, y in pts]
        for i in range(0, len(q) - 1, 2):
            seg = q[i:i + 3]
            if len(seg) < 2: break
            ww = w * SW * (0.75 + 0.35 * math.sin(i * 0.21 + ph)) * (1 if p == 0 else 0.4)
            d.line(P(seg), fill=col, width=max(1, int(ww * K)), joint='curve')

def part(pts, base, shade, light, flow=None, nhair=0, hlen=(24, 60), outline=4.2, dark_hair=FUR_D, light_hair=FUR_L, sdir=(1, 1)):
    """Fill a shape with its colour, shade it away from the light (top left), comb hair into it and ink its edge."""
    mask = Image.new('L', canvas.size, 0); ImageDraw.Draw(mask).polygon(P(pts), fill=255)
    lay = Image.new('RGB', canvas.size, base)
    off = int(30 * K * SW)
    sh = ImageChops.subtract(mask, ImageChops.offset(mask, -off * sdir[0], -off * sdir[1])).filter(ImageFilter.GaussianBlur(16 * K * SW))
    lay = Image.composite(Image.new('RGB', canvas.size, shade), lay, sh)
    hl = ImageChops.subtract(mask, ImageChops.offset(mask, off, off)).filter(ImageFilter.GaussianBlur(14 * K * SW)).point(lambda v: int(v * 0.55))
    lay = Image.composite(Image.new('RGB', canvas.size, light), lay, hl)
    if nhair:
        ld = ImageDraw.Draw(lay)
        xs = [xf(*p)[0] for p in pts]; ys = [xf(*p)[1] for p in pts]
        # hair strands are planned in local coordinates; sample until enough land inside
        lx = [p[0] for p in pts]; ly = [p[1] for p in pts]
        made = 0; tries = 0
        while made < nhair and tries < nhair * 6:
            tries += 1
            x = rnd.uniform(min(lx), max(lx)); y = rnd.uniform(min(ly), max(ly))
            sx, sy = xf(x, y)
            if not (0 <= sx * K < mask.width and 0 <= sy * K < mask.height) or mask.getpixel((int(sx * K), int(sy * K))) == 0: continue
            made += 1
            a = flow(x, y) + rnd.uniform(-0.2, 0.2); L = rnd.uniform(*hlen) * SW; bend = rnd.uniform(-0.3, 0.3)
            st = [(x + math.cos(a + bend * t) * L * t, y + math.sin(a + bend * t) * L * t) for t in (0, 0.25, 0.5, 0.75, 1)]
            r = rnd.random()
            col = dark_hair if r < 0.62 else light_hair if r < 0.92 else INK
            ld.line(P(st), fill=col, width=max(1, int(rnd.uniform(1.4, 2.8) * SW * K)), joint='curve')
    canvas.paste(lay, mask=mask)
    if outline: ink(pts, outline, closed=True)
    return mask

def fingers(base, tips, w=10.5, bend=0.25):
    """Long knobbly fingers from base points to tip points, each with a pale oval nail."""
    for (x0, y0), (x1, y1) in zip(base, tips):
        mx, my = (x0 + x1) / 2, (y0 + y1) / 2
        nx, ny = -(y1 - y0) * bend, (x1 - x0) * bend
        f = bez((x0, y0), (mx + nx * 0.5, my + ny * 0.5), (x1, y1), n=12)
        d.line(P(f), fill=INK, width=int(w * SW * K), joint='curve')
        d.line(P(f), fill=SKIN, width=int((w - 4.2) * SW * K), joint='curve')
        for t in (0.42, 0.72):   # knuckle creases
            px, py = f[int(t * 12)]
            ang = math.atan2(y1 - y0, x1 - x0) + math.pi / 2
            ink([(px - math.cos(ang) * 3, py - math.sin(ang) * 3), (px + math.cos(ang) * 3, py + math.sin(ang) * 3)], 1.4, SKIN_D, passes=1)
        ang = math.atan2(f[-1][1] - f[-3][1], f[-1][0] - f[-3][0])
        nx2, ny2 = x1 - math.cos(ang) * 4, y1 - math.sin(ang) * 4
        nail = [(nx2 + math.cos(ang) * 5 * math.cos(t) - math.sin(ang) * 3.4 * math.sin(t), ny2 + math.sin(ang) * 5 * math.cos(t) + math.cos(ang) * 3.4 * math.sin(t)) for t in [i / 16 * 6.2832 for i in range(16)]]
        d.polygon(P(nail), fill=NAIL); ink(nail, 1.4, closed=True, passes=1)

down = lambda x, y: math.pi / 2

# ================= ground shadow
sh = Image.new('L', canvas.size, 0)
ImageDraw.Draw(sh).ellipse([150 * K, 930 * K, 1320 * K, 1035 * K], fill=110)
sh = sh.filter(ImageFilter.GaussianBlur(26 * K))
canvas.paste(Image.new('RGB', canvas.size, (196, 178, 150)), mask=sh)
for k in range(9):  # a few pencil marks for the ground
    x = rnd.uniform(180, 1300); y = rnd.uniform(990, 1040)
    ink([(x, y), (x + rnd.uniform(30, 80), y + rnd.uniform(-2, 2))], 1.6, (150, 128, 104), passes=1)

# ================= the support arm (viewer's right), behind: leaning back on its elbow
ARM_B = smooth((930, 470), (1010, 540), (1066, 700), (1092, 850), (1118, 902), (1200, 912), (1214, 952), (1110, 964), (1056, 956), (1032, 900), (996, 740), (944, 630), (900, 560))
part(ARM_B, FUR, FUR_D, FUR_L, lambda x, y: math.atan2(900 - 470, 1070 - 930) if y < 890 else 0.05, 900, (22, 56))
ink(bez((1050, 930), (1060, 950), (1076, 958), (1092, 958)), 2.2, FUR_D)      # the elbow on the ground
HAND_B = smooth((1196, 914), (1236, 908), (1258, 922), (1262, 952), (1232, 964), (1200, 958))
part(HAND_B, SKIN, SKIN_D, SKIN, outline=3.6)
fingers([(1248, 916), (1256, 928), (1258, 942), (1252, 955)], [(1300, 924), (1310, 942), (1306, 962), (1290, 974)], w=10, bend=0.12)
fingers([(1222, 908)], [(1262, 898)], w=10, bend=-0.15)   # thumb

# ================= the hood of hair (back), drawn in the head's frame, tilted with the lean
BA, BX, BY = 0.15, 690, 930
def body_xf(x, y):
    x -= BX; y -= BY
    c, s = math.cos(BA), math.sin(BA)
    return (BX + c * x - s * y, BY + s * x + c * y)
HX, HY, HS, HA = 688, 300, 0.78, -0.03
def head_xf(x, y):
    x -= 350; y -= 330
    c, s = math.cos(HA), math.sin(HA)
    return body_xf(HX + HS * (c * x - s * y), HY + HS * (s * x + c * y))
xf, SW = head_xf, HS
HOOD = path(bez((350, 70), (420, 72), (520, 150), (560, 290)), bez((560, 290), (600, 360), (616, 440), (606, 520)),
            bez((606, 520), (590, 720), (110, 720), (94, 520)), bez((94, 520), (84, 440), (100, 360), (140, 290)),
            bez((140, 290), (180, 150), (280, 72), (350, 70)))
part(HOOD, FUR, FUR_D, FUR_L, lambda x, y: math.atan2(y - 60, x - 350) * 0.9 + 0.15, 1500, (30, 80))
xf, SW = (lambda x, y: (x, y)), 1.0

# ================= the hips and seat: hair under the belly
HIPS = smooth((470, 800), (690, 780), (880, 820), (900, 930), (780, 978), (580, 984), (450, 950), (440, 870))
part(HIPS, FUR, FUR_D, FUR_L, down, 500, (20, 46))

# ================= the stretched-out leg (viewer's left), its sole to us
LEG_L = smooth((560, 846), (450, 884), (330, 912), (258, 920), (250, 992), (380, 1004), (510, 994), (620, 960), (640, 900))
part(LEG_L, FUR, FUR_D, FUR_L, lambda x, y: math.pi * 0.94, 700, (24, 60))
FOOT_L = smooth((222, 884), (252, 874), (272, 900), (276, 960), (262, 1006), (228, 1012), (204, 986), (198, 930))
part(FOOT_L, SKIN_S, SKIN_D, SKIN, outline=4.0)
for k in range(5):   # toes along the top, and the creases of the sole
    tx = 210 + k * 13; ty = 884 - 4 * math.sin(k / 4 * math.pi) + (6 if k in (0, 4) else 0)
    toe = ellipse(tx, ty, 7.5, 9, 16); d.polygon(P(toe), fill=SKIN_S); ink(toe, 2.0, closed=True, passes=1)
ink(bez((214, 930), (232, 938), (252, 936), (266, 928)), 1.8, SKIN_D, passes=1)
ink(bez((216, 980), (232, 988), (252, 986), (264, 976)), 1.8, SKIN_D, passes=1)

# ================= the torso: chest and the big round belly, pale; leaning back to the right
xf = body_xf
TORSO = smooth((530, 470), (690, 476), (846, 490), (890, 600), (920, 760), (874, 890), (700, 946), (530, 912), (448, 800), (466, 610))
part(TORSO, SKIN, SKIN_D, (250, 230, 208), outline=4.6)
# hair along the flanks and a little on the chest
FL = smooth((462, 640), (505, 580), (512, 700), (500, 840), (466, 810))
part(FL, SKIN, SKIN, SKIN, down, 70, (20, 44), outline=0, dark_hair=FUR, light_hair=FUR_D)
FR = smooth((850, 530), (890, 620), (912, 780), (866, 870), (862, 700))
part(FR, SKIN, SKIN_S, SKIN, down, 70, (20, 44), outline=0, dark_hair=FUR, light_hair=FUR_D)
ink(bez((462, 700), (500, 930), (850, 950), (912, 700)), 3.4)                  # the belly's round
ink(bez((560, 616), (612, 646), (660, 650), (690, 640)), 2.2, SKIN_D)          # chest
ink(bez((712, 646), (760, 654), (800, 640), (830, 614)), 2.2, SKIN_D)
ink(bez((610, 712), (660, 724), (730, 726), (790, 712)), 1.8, SKIN_D, passes=1)  # the soft fold above the belly
NAV = ellipse(706, 818, 15, 8, 20); d.polygon(P(NAV), fill=SKIN_D); ink(NAV, 2.4, closed=True, passes=1)
ink(bez((690, 840), (704, 848), (722, 848), (734, 838)), 1.8, SKIN_D, passes=1)
xf = lambda x, y: (x, y)

# ================= the raised knee (viewer's right): thigh up to the knee, shin down, foot flat
THIGH = smooth((700, 866), (790, 736), (880, 630), (950, 606), (990, 660), (950, 780), (900, 890), (830, 966), (730, 966))
part(THIGH, FUR, FUR_D, FUR_L, lambda x, y: -0.9, 900, (24, 60))
SHIN = smooth((912, 650), (958, 616), (1000, 650), (1010, 780), (1006, 926), (940, 934), (930, 800), (914, 712))
part(SHIN, FUR, FUR_D, FUR_L, down, 520, (24, 56))
ink(bez((924, 656), (944, 640), (968, 640), (986, 656)), 2.2, FUR_D)           # the knee
FOOT_R = smooth((936, 916), (1008, 914), (1030, 950), (1034, 986), (990, 996), (920, 996), (898, 978), (910, 944))
part(FOOT_R, SKIN_S, SKIN_D, SKIN, outline=4.0)
for k in range(5):
    tx = 912 + k * 26; ty = 990
    toe = ellipse(tx, ty, 12, 8, 16); d.polygon(P(toe), fill=SKIN_S); ink(toe, 2.0, closed=True, passes=1)
    d.polygon(P(ellipse(tx, ty + 2, 5, 3, 12)), fill=NAIL)
ink(bez((936, 940), (960, 950), (990, 950), (1006, 944)), 1.6, SKIN_D, passes=1)

# ================= the face, in the head's tilted frame
xf, SW = head_xf, HS
part(HOOD, FUR, FUR_D, FUR_L, lambda x, y: math.atan2(y - 60, x - 350) * 0.9 + 0.15, 1500, (30, 80))
FACE = path(bez((350, 160), (420, 160), (470, 230), (468, 330)), bez((468, 330), (470, 440), (420, 530), (350, 540)),
            bez((350, 540), (280, 530), (230, 440), (232, 330)), bez((232, 330), (230, 230), (280, 160), (350, 160)))
part(FACE, SKIN, SKIN_S, (250, 230, 208), outline=4.6)
FRINGE = path(bez((226, 300), (232, 200), (290, 150), (350, 150)), bez((350, 150), (410, 150), (468, 200), (474, 300)),
              bez((474, 300), (452, 250), (432, 238), (420, 240)), bez((420, 240), (400, 200), (300, 200), (280, 240)), bez((280, 240), (268, 238), (248, 250), (226, 300)))
part(FRINGE, FUR, FUR_D, FUR_L, lambda x, y: math.pi / 2 + (x - 350) * 0.004, 160, (14, 30), outline=4.0)
for side in (-1, 1):   # wisps curling past the temples
    for k in range(3):
        x0 = 350 + side * (128 + k * 12)
        ink(bez((x0, 250 + k * 8), (x0 + side * 22, 280 + k * 6), (x0 + side * 10, 320 + k * 4), (x0 + side * 36, 330 + k * 10)), 2.6, passes=1)

def eye(cx, cy, w, h, iris, ir, pupil=0.4, lid=0.0, look=(0, 0), swirl=False):
    top = bez((cx - w / 2, cy), (cx - w / 4, cy - h * 0.62), (cx + w / 4, cy - h * 0.62), (cx + w / 2, cy))
    bot = bez((cx + w / 2, cy), (cx + w / 4, cy + h * 0.62), (cx - w / 4, cy + h * 0.62), (cx - w / 2, cy))
    pts = top + bot[1:]
    d.polygon(P(pts), fill=(252, 250, 246))
    ix, iy = cx + look[0], cy + look[1]
    d.polygon(P(ellipse(ix, iy, ir, ir)), fill=iris)
    d.polygon(P(ellipse(ix + ir * 0.15, iy + ir * 0.2, ir * 0.6, ir * 0.6)), fill=tuple(min(255, c + 30) for c in iris))
    ink(ellipse(ix, iy, ir, ir), 1.8, closed=True, passes=1)
    if swirl:
        ink([(ix + math.cos(a) * (ir * 0.15 + a * ir * 0.07), iy + math.sin(a) * (ir * 0.15 + a * ir * 0.07)) for a in [i * 0.25 for i in range(46)]], 1.6, tuple(max(0, c - 60) for c in iris), passes=1)
    d.polygon(P(ellipse(ix, iy, ir * pupil, ir * pupil)), fill=(20, 14, 14))
    g = max(1.6, ir * 0.22)
    d.polygon(P(ellipse(ix - ir * 0.42, iy - ir * 0.42, g, g, 12)), fill=(255, 255, 255))
    if lid > 0:
        lp = bez((cx - w / 2 - 3, cy), (cx - w / 4, cy - h * 0.62 + h * lid), (cx + w / 4, cy - h * 0.62 + h * lid), (cx + w / 2 + 3, cy))
        d.polygon(P(top + lp[::-1]), fill=SKIN_S); ink(lp, 3)
    ink(pts, 3.2, closed=True)

# (1) the third eye under the hair's edge: wide, steady, a swirling green iris
eye(350, 214, 104, 46, (96, 132, 116), 19, pupil=0.32, swirl=True)
for k in range(8):
    x = 306 + k * 12.5; ink([(x, 200 - 4 * math.sin(k / 7 * math.pi)), (x + (x - 350) * 0.15, 186 - 6 * math.sin(k / 7 * math.pi))], 2.2, passes=1)
# sunken sockets
for (cx, cy, rx, ry) in ((298, 292, 44, 34), (402, 292, 44, 34), (264, 352, 40, 32), (436, 352, 40, 32)):
    d.polygon(P(ellipse(cx, cy, rx, ry)), fill=SKIN_D)
    d.polygon(P(ellipse(cx, cy + 2, rx * 0.8, ry * 0.8)), fill=(204, 164, 138))
# (2) the upper pair: wide open, startled
for cx in (298, 402):
    eye(cx, 292, 56, 42, (118, 82, 48), 13, pupil=0.3)
    for k in range(5):
        x = cx - 22 + k * 11; ink([(x, 276 - 3 * math.sin(k / 4 * math.pi)), (x + (x - cx) * 0.25, 266 - 4 * math.sin(k / 4 * math.pi))], 2.0, passes=1)
# (3) the lower pair: heavy-lidded, weary, with bags
for cx in (264, 436):
    eye(cx, 354, 52, 34, (122, 106, 58), 11, pupil=0.4, lid=0.45, look=(0, 3))
    ink(bez((cx - 22, 376), (cx - 8, 386), (cx + 8, 386), (cx + 22, 376)), 2.0, SKIN_D, passes=1)
    ink(bez((cx - 18, 386), (cx - 6, 394), (cx + 6, 394), (cx + 18, 386)), 1.6, SKIN_D, passes=1)
# the long nose
ink(bez((338, 270), (334, 330), (330, 380), (326, 420)), 3.0)
ink(bez((362, 270), (366, 330), (370, 380), (374, 420)), 2.0, SKIN_D, passes=1)
NOSE = path(bez((322, 410), (300, 430), (310, 456), (334, 452)), bez((334, 452), (342, 462), (358, 462), (366, 452)), bez((366, 452), (390, 456), (400, 430), (378, 410)))
d.polygon(P(NOSE), fill=SKIN); ink(NOSE, 3.2)
for nx in (334, 366): d.polygon(P(ellipse(nx, 448, 7, 4.5, 14)), fill=INK)
# gritted teeth between thick lips
LIPS = path(bez((296, 486), (320, 462), (380, 462), (404, 486)), bez((404, 486), (380, 512), (320, 512), (296, 486)))
d.polygon(P(LIPS), fill=(192, 108, 100)); ink(LIPS, 3.4, closed=True)
TEETH = path(bez((308, 486), (330, 474), (370, 474), (392, 486)), bez((392, 486), (370, 498), (330, 498), (308, 486)))
d.polygon(P(TEETH), fill=(244, 236, 214)); ink(TEETH, 2.4, closed=True, passes=1)
ink([(310, 486), (390, 486)], 2.0, passes=1)
for x in range(322, 390, 12): ink([(x, 477), (x, 495)], 1.5, passes=1)
# double chin
ink(bez((300, 528), (330, 548), (370, 548), (400, 528)), 2.6, SKIN_D)
xf, SW = (lambda x, y: (x, y)), 1.0

# ================= the near arm (viewer's left): down the side, hand resting on the belly
xf = body_xf
ARM_F = smooth((506, 432), (440, 470), (398, 580), (396, 700), (420, 780), (500, 808), (606, 812), (612, 762), (520, 748), (470, 712), (476, 600), (512, 512))
part(ARM_F, FUR, FUR_D, FUR_L, lambda x, y: math.pi / 2 + 0.1 if y < 720 else 0.05, 1000, (22, 58))
HAND_F = smooth((596, 760), (640, 752), (690, 762), (700, 800), (660, 818), (600, 814))
part(HAND_F, SKIN, SKIN_S, (250, 230, 208), outline=3.8)
fingers([(690, 766), (697, 780), (699, 794), (692, 808)], [(742, 774), (752, 798), (748, 820), (730, 838)], w=10.5, bend=0.14)
fingers([(640, 754)], [(684, 736)], w=10.5, bend=-0.2)   # thumb
ink(bez((616, 784), (640, 778), (662, 780), (680, 788)), 1.6, SKIN_D, passes=1)
for k in range(10):  # hair over the wrist
    x = 588 + rnd.uniform(-6, 14); y = 764 + k * 5
    ink(bez((x - 14, y), (x, y - 4), (x + 16, y + 3)), 1.6, FUR_D, passes=1)

out = canvas.resize((W, H), Image.LANCZOS)
out.save('creature-sitting.png')
print('ok')
