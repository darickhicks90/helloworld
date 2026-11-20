"""The duel's creature, from the night-meadow pixel art (native 256x224), in the two-handed axe stance of
the reference pose: feet set wide, one hand forward on the haft near the head, the other low at the hip
near the butt, the haft across the body. His torso and head stay as drawn; the legs swing out at the
hips; the arms are grown anew along the pose with his own arm fur mapped onto them (so there are no
seams at the joints); the fists close on the haft; the axe is painted at the same pixel scale."""
import math, random, sys
from PIL import Image, ImageDraw
random.seed(5)
nat = Image.open(sys.argv[1]).convert("RGBA"); W, H = nat.size; npx = nat.load()
MID_X = 126                                  # he's symmetric about here
def mx(x): return 2 * MID_X - x

def is_fur(c):
    r, g, b, _ = c
    return r > g >= b - 6 and r - b > 22 and r > 48
# ---- his silhouette (biggest fur region, holes filled), and stray fireflies / grass painted over
fur = [[is_fur(npx[x, y]) for x in range(W)] for y in range(H)]
seen = [[False] * W for _ in range(H)]; best = []
for y in range(H):
    for x in range(W):
        if fur[y][x] and not seen[y][x]:
            comp = []; st = [(x, y)]; seen[y][x] = True
            while st:
                cx, cy = st.pop(); comp.append((cx, cy))
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx_, ny_ = cx + dx, cy + dy
                    if 0 <= nx_ < W and 0 <= ny_ < H and fur[ny_][nx_] and not seen[ny_][nx_]: seen[ny_][nx_] = True; st.append((nx_, ny_))
            if len(comp) > len(best): best = comp
body = [[False] * W for _ in range(H)]
for (x, y) in best: body[y][x] = True
out_ = [[False] * W for _ in range(H)]; st = [(0, 0)]; out_[0][0] = True
while st:
    cx, cy = st.pop()
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        nx_, ny_ = cx + dx, cy + dy
        if 0 <= nx_ < W and 0 <= ny_ < H and not body[ny_][nx_] and not out_[ny_][nx_]: out_[ny_][nx_] = True; st.append((nx_, ny_))
for y in range(H):
    for x in range(W):
        if not body[y][x] and not out_[y][x]: body[y][x] = True
src = nat.copy(); sp = src.load()
for y in range(H):
    for x in range(W):
        if body[y][x] and not is_fur(npx[x, y]):
            r, g, b, _ = npx[x, y]
            if r > 200 and g > 190 and b > 170: continue        # the whites of his eyes
            if r < 70 and g < 50: continue                     # outlines
            for k in range(1, 7):
                hit = next(((x + dx, y + dy) for dx, dy in ((-k, 0), (k, 0), (0, -k), (0, k)) if 0 <= x + dx < W and 0 <= y + dy < H and is_fur(npx[x + dx, y + dy])), None)
                if hit: sp[x, y] = npx[hit]; break
def in_poly(x, y, poly):
    c = False; j = len(poly) - 1
    for i in range(len(poly)):
        xi, yi = poly[i]; xj, yj = poly[j]
        if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi) + xi: c = not c
        j = i
    return c
L_ARM = [(0, 30), (86, 44), (90, 62), (92, 100), (80, 150), (0, 150)]
R_ARM = [(mx(x), y) for (x, y) in L_ARM]
def layer(): return Image.new("RGBA", (CW, CH), (0, 0, 0, 0))

PAD_L, PAD_T, PAD_R, PAD_B = 70, 30, 40, 6
CW, CH = W + PAD_L + PAD_R, H + PAD_T + PAD_B
def C(p): return (p[0] + PAD_L, p[1] + PAD_T)

# ---- torso and head as drawn (arms and legs taken off)
torso = layer(); tp = torso.load()
for y in range(H):
    for x in range(W):
        if body[y][x] and y < 158 and not in_poly(x, y, L_ARM) and not in_poly(x, y, R_ARM):
            tp[x + PAD_L, y + PAD_T] = sp[x, y]
# an angry brow over the four small eyes
td = ImageDraw.Draw(torso)
for (x0, y0, x1, y1) in ((107, 43, 118, 46), (143, 43, 132, 46)):
    td.line((x0 + PAD_L, y0 + PAD_T, x1 + PAD_L, y1 + PAD_T), fill=(46, 26, 18, 255), width=2)

# ---- legs: each swung out at the hip into a wide stance
def leg(x0, x1, hip, ang):
    lay = layer(); lp = lay.load()
    for y in range(146, H):
        for x in range(x0, x1):
            if body[y][x] and not in_poly(x, y, L_ARM) and not in_poly(x, y, R_ARM): lp[x + PAD_L, y + PAD_T] = sp[x, y]
    return lay.rotate(ang, resample=Image.NEAREST, center=C(hip))
legs = layer()
legs = Image.alpha_composite(legs, leg(60, MID_X + 1, (108, 150), -15))
legs = Image.alpha_composite(legs, leg(MID_X + 1, 200, (144, 150), 15))

# ---- arms: his own arm fur mapped along new bent arms
def arm_strip(S, E):
    """Half-width of the source arm along S→E (scanning out from the axis until the fur ends)."""
    dx, dy = E[0] - S[0], E[1] - S[1]; L = math.hypot(dx, dy); ux, uy = dx / L, dy / L; nx_, ny_ = -uy, ux
    widths = []
    for i in range(21):
        t = i / 20; ax, ay = S[0] + dx * t, S[1] + dy * t
        def run(sg):
            k = 0
            while k < 20:
                x = round(ax + nx_ * sg * (k + 1)); y = round(ay + ny_ * sg * (k + 1))
                if not (0 <= x < W and 0 <= y < H) or not body[y][x]: break
                k += 1
            return k
        widths.append((run(1), run(-1)))
    return widths
def grow(S, E, path, flip=False):
    """Paint an arm along `path` (shoulder, elbow, wrist), sampling the source arm S→E."""
    lay = layer(); lp = lay.load()
    dx, dy = E[0] - S[0], E[1] - S[1]; Ls = math.hypot(dx, dy); sux, suy = dx / Ls, dy / Ls; snx, sny = -suy, sux
    widths = arm_strip(S, E)
    segs = []; total = 0
    for a, b in zip(path, path[1:]):
        l = math.hypot(b[0] - a[0], b[1] - a[1]); segs.append((a, b, l, total)); total += l
    xs = [p[0] for p in path]; ys = [p[1] for p in path]
    for y in range(int(min(ys)) - 14, int(max(ys)) + 15):
        for x in range(int(min(xs)) - 14, int(max(xs)) + 15):
            best_ = None
            for (a, b, l, off) in segs:
                ux, uy = (b[0] - a[0]) / l, (b[1] - a[1]) / l
                t = max(0, min(l, (x - a[0]) * ux + (y - a[1]) * uy))
                px_, py_ = a[0] + ux * t, a[1] + uy * t
                dd = math.hypot(x - px_, y - py_)
                if best_ is None or dd < best_[0]:
                    sd = (x - px_) * (-uy) + (y - py_) * ux
                    if t <= 0 or t >= l: sd = math.copysign(dd, sd if sd != 0 else 1)   # rounded at the joints
                    best_ = (dd, (off + t) / total, sd)
            _, u, sd = best_
            if flip: sd = -sd
            i = min(20, max(0, round(u * 20))); wp, wn = widths[i]
            # the fur keeps its own width; a little fuller at the elbow
            if not (-wn - 0.5 <= sd <= wp + 0.5): continue
            sx = S[0] + dx * u + snx * sd; sy = S[1] + dy * u + sny * sd
            sx, sy = round(sx), round(sy)
            if 0 <= sx < W and 0 <= sy < H and body[sy][sx]:
                lp[x + PAD_L, y + PAD_T] = sp[sx, sy]
    return lay
# source arms (shoulder → wrist), and the new paths
SL, WL = (86, 64), (57, 112)
SR, WR = (mx(86), 64), (mx(57), 112)
NEW_L = [(88, 60), (66, 94), (42, 88)]            # forward hand: elbow down, forearm out to the haft
NEW_R = [(162, 60), (186, 98), (178, 127)]        # back hand: elbow out, forearm in to the hip
arm_l = grow(SL, WL, NEW_L)
arm_r = grow(SR, WR, NEW_R)

# ---- the axe, painted at his pixel scale
HL, HR = (40, 87), (180, 133)                      # the two fists on the haft
ux, uy = HR[0] - HL[0], HR[1] - HL[1]; Lh = math.hypot(ux, uy); ux, uy = ux / Lh, uy / Lh
nx_, ny_ = -uy, ux
butt = (HR[0] + ux * 16, HR[1] + uy * 16)
head = (HL[0] - ux * 30, HL[1] - uy * 30)          # the socket's middle (the blade reaches on past it)
axe = layer(); ap = axe.load()
OUT = (30, 22, 26, 255)
WOOD = [(70, 40, 24), (96, 58, 32), (124, 80, 44), (152, 104, 60)]
LEATHER = [(52, 30, 24), (78, 46, 32)]
IRON = [(44, 46, 56), (66, 70, 84), (92, 98, 114), (124, 132, 148), (164, 172, 186), (206, 212, 222), (240, 244, 250)]
def put(x, y, c):
    x, y = round(x) + PAD_L, round(y) + PAD_T
    if 0 <= x < CW and 0 <= y < CH: ap[x, y] = c if len(c) == 4 else c + (255,)
# haft: 5 px across, grain running along it, leather wraps at the grips, an iron cap at the butt
L_haft = math.hypot(butt[0] - head[0], butt[1] - head[1])
for i in range(int(L_haft * 2) + 1):
    t = i / 2
    cx, cy = head[0] + ux * t, head[1] + uy * t
    for k in (-3, 3): put(cx + nx_ * k, cy + ny_ * k, OUT)
    for k in (-2, -1, 0, 1, 2):
        grain = (int(t * 0.7 + k * 5) % 9 == 0)
        shade = {-2: 3, -1: 2, 0: 2, 1: 1, 2: 0}[k]
        if grain: shade = max(0, shade - 1)
        put(cx + nx_ * k, cy + ny_ * k, WOOD[shade])
for g in (HL, HR):
    for t in range(-7, 8):
        cx, cy = g[0] + ux * t, g[1] + uy * t
        for k in (-2, -1, 0, 1, 2): put(cx + nx_ * k, cy + ny_ * k, LEATHER[1] if (t + k) % 3 else LEATHER[0])
for t in range(0, 6):
    cx, cy = butt[0] - ux * t, butt[1] - uy * t
    for k in (-3, -2, -1, 0, 1, 2, 3): put(cx + nx_ * k, cy + ny_ * k, OUT if abs(k) == 3 or t in (0, 5) else IRON[2 + (1 if k < 0 else 0)])
# head: in its own frame (a along the haft toward the head, b across), a bearded crescent blade
REACH = 36
def edge_at(b): return REACH - 0.006 * b * b                        # the long, gently curved cutting edge
def inside(a, b):
    if -7 <= a <= 7 and -9 <= b <= 9: return "socket"
    if -13 <= a < -7 and -5 <= b <= 5: return "poll"
    if a > 7:
        f = (a - 7) / (REACH - 7)                                   # 0 at the neck, 1 at the edge
        up = 8 + 24 * f ** 1.6                                       # flaring to the horns,
        down = 8 + 27 * f ** 1.6                                     # a touch more beard below
        if a <= edge_at(b) and -up <= b <= down: return "blade"
    return None
nicks = {round(random.uniform(-20, 20)) for _ in range(4)}
for y in range(-40, 50):
    for x in range(-50, 40):
        X, Y = head[0] + x, head[1] + y
        a = -(x * ux + y * uy); b = x * nx_ + y * ny_                # a grows toward the blade (beyond the head end)
        part = inside(a, b)
        if not part: continue
        edge_out = any(not inside(a + da, b + db) for da, db in ((1, 0), (-1, 0), (0, 1), (0, -1)))
        if edge_out: put(X, Y, OUT); continue
        if part == "socket":
            c = IRON[2] if b < 0 else IRON[1]
            if abs(a) == 5 or (abs(b) == 5 and abs(a) < 4): c = IRON[0]
            if (a, b) in ((-3, -4), (3, -4), (-3, 4), (3, 4)): c = IRON[5]       # rivets
            put(X, Y, c); continue
        if part == "poll": put(X, Y, IRON[3] if b < 0 else IRON[2]); continue
        to_edge = edge_at(b) - a
        if round(b) in nicks and to_edge < 1.5: continue                   # nicks in the edge
        f = (a - 7) / (REACH - 7)
        if to_edge < 1.5: c = IRON[6]
        elif to_edge < 4.5: c = IRON[5] if (round(a) + round(b)) % 2 else IRON[4]   # the honed bevel, dithered
        elif to_edge < 6: c = IRON[3]
        else:
            # forged face: darker by the neck, lit from above, with hammer marks and pitting
            k = 1 + (1 if b < 0 else 0) + (1 if f > 0.45 else 0)
            if (round(a * 0.7) + round(b * 0.5)) % 7 == 0: k += 1
            if random.random() < 0.07: k -= 1
            c = IRON[max(0, min(4, k))]
        # a dark fuller line following the edge
        if 7.5 <= to_edge < 8.5 and abs(b) < 20: c = IRON[1]
        put(X, Y, c)
# a worn rune on the cheek of the blade
for (a, b) in ((16, -4), (17, -3), (18, -2), (17, -1), (16, 0), (17, 1), (18, 2), (17, 3), (16, 4)):
    put(head[0] - ux * a + nx_ * b, head[1] - uy * a + ny_ * b, (210, 116, 52))

# ---- fists closed round the haft
SKIN = [(98, 53, 48), (134, 80, 60), (168, 108, 76), (196, 138, 98), (217, 166, 124)]
def fist(c, flipped):
    lay = layer(); fp = lay.load(); fd = ImageDraw.Draw(lay)
    cx, cy = c[0] + PAD_L, c[1] + PAD_T
    # a rounded fist across the haft, knuckles toward us
    for y in range(-8, 9):
        for x in range(-8, 9):
            if (x / 8.2) ** 2 + (y / 8.6) ** 2 > 1: continue
            light = -x * 0.25 - y * 0.35 + (1 if flipped else 0) * x * 0.2
            k = 2 + (1 if light > 0.6 else 0) + (1 if light > 1.6 else 0) - (1 if light < -1.2 else 0)
            fp[cx + x, cy + y] = SKIN[max(0, min(4, k))] + (255,)
    for i in range(4):                                   # four fingers curled round, creases between
        yy = cy - 5 + i * 3
        fd.line((cx - 6 if not flipped else cx + 1, yy, cx - 1 if not flipped else cx + 6, yy), fill=SKIN[0] + (255,))
        fd.point((cx - 6 if not flipped else cx + 6, yy + 1), fill=SKIN[1] + (255,))
    fd.line((cx + (2 if not flipped else -6), cy - 7, cx + (6 if not flipped else -2), cy - 3), fill=SKIN[4] + (255,))   # the thumb over the top
    fd.line((cx + (2 if not flipped else -6), cy - 6, cx + (6 if not flipped else -2), cy - 2), fill=SKIN[3] + (255,))
    return lay

figure = layer()
for part in (legs, torso, arm_r, axe, arm_l, fist(HL, False), fist(HR, True)):
    figure = Image.alpha_composite(figure, part)
# one clean outline round the whole figure, then tidy specks
fp = figure.load()
edge = [(x, y) for y in range(CH) for x in range(CW) if fp[x, y][3] == 0 and any(0 <= x + a < CW and 0 <= y + b < CH and fp[x + a, y + b][3] > 128 for a, b in ((1, 0), (-1, 0), (0, 1), (0, -1)))]
for (x, y) in edge: fp[x, y] = (40, 22, 16, 255)
for y in range(CH):
    for x in range(CW):
        if fp[x, y][3] and sum(1 for a in (-1, 0, 1) for b in (-1, 0, 1) if (a or b) and 0 <= x + a < CW and 0 <= y + b < CH and fp[x + a, y + b][3]) <= 1: fp[x, y] = (0, 0, 0, 0)
for _ in range(2):
    for y in range(CH):
        for x in range(CW):
            r, g, b_, al = fp[x, y]
            if al and g > r - 20 and g > b_ + 25:
                for k in range(1, 6):
                    hit = next(((x + dx, y + dy) for dx, dy in ((-k, 0), (k, 0), (0, -k), (0, k)) if 0 <= x + dx < CW and 0 <= y + dy < CH and fp[x + dx, y + dy][3] and fp[x + dx, y + dy][0] > fp[x + dx, y + dy][1] + 8), None)
                    if hit: fp[x, y] = fp[hit]; break
figure = figure.crop(figure.getbbox())
figure.save(sys.argv[2]); print(figure.size)
