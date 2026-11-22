"""Title screen for 5 Eye Yeti: the little guy from the app icon standing in a field under a sky
full of stars, mountains behind him, no moon. Clean mint lines of varied weight on night greens,
in the same engraved look as the icon.

Writes two layers for the title screen (the scene, and the yeti on his own so he can breathe):
  python3 title_scene.py <assets/images dir> [preview.png]
"""
import sys, os, math, random
from PIL import Image, ImageDraw, ImageFilter
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
W, H = 2400, 1080          # output size (about 20:9; the screen crops from the left/top)
SS = 3                     # supersampling
S = lambda v: v * SS
MINT = (222, 242, 212)
SKY_TOP, SKY_LOW = (20, 31, 25), (50, 72, 54)
FILL = (84, 112, 82)       # the yeti's body, as on the icon

def rgba(c, a=1.0): return (c[0], c[1], c[2], int(255 * a))
def mix(a, b, t): return tuple(int(a[i] * (1 - t) + b[i] * t) for i in range(3))

# ---------------------------------------------------------------- tapered strokes
def stroke(d, pts, w0, w1=None, wmid=None, col=MINT, a=1.0):
    """A line of varying weight along pts (in output pixels): w0 at the start, w1 at the end,
    swelling to wmid in the middle (brush-like)."""
    if w1 is None: w1 = w0
    if wmid is None: wmid = (w0 + w1) / 2
    pts = [(S(x), S(y)) for x, y in pts]
    n = len(pts)
    if n < 2: return
    L = [0.0]
    for i in range(1, n): L.append(L[-1] + math.dist(pts[i - 1], pts[i]))
    tot = L[-1] or 1
    left, right = [], []
    for i in range(n):
        t = L[i] / tot
        w = (w0 * (1 - t) + w1 * t) + (wmid - (w0 + w1) / 2) * math.sin(math.pi * t)
        w = S(max(w, 0.15)) / 2
        a0 = pts[max(i - 1, 0)]; a1 = pts[min(i + 1, n - 1)]
        dx, dy = a1[0] - a0[0], a1[1] - a0[1]; l = math.hypot(dx, dy) or 1
        nx, ny = -dy / l, dx / l
        left.append((pts[i][0] + nx * w, pts[i][1] + ny * w)); right.append((pts[i][0] - nx * w, pts[i][1] - ny * w))
    d.polygon(left + right[::-1], fill=rgba(col, a))
    # round the ends a touch
    for i, ww in ((0, w0), (n - 1, w1)):
        r = S(ww) / 2
        if r > S(0.6): d.ellipse([pts[i][0] - r, pts[i][1] - r, pts[i][0] + r, pts[i][1] + r], fill=rgba(col, a))

def curve(p0, p1, p2, p3=None, n=24):
    """Quadratic (3 points) or cubic (4 points) Bezier as a polyline."""
    out = []
    for i in range(n + 1):
        t = i / n
        if p3 is None:
            x = (1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t * t * p2[0]
            y = (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t * t * p2[1]
        else:
            x = (1 - t) ** 3 * p0[0] + 3 * (1 - t) ** 2 * t * p1[0] + 3 * (1 - t) * t * t * p2[0] + t ** 3 * p3[0]
            y = (1 - t) ** 3 * p0[1] + 3 * (1 - t) ** 2 * t * p1[1] + 3 * (1 - t) * t * t * p2[1] + t ** 3 * p3[1]
        out.append((x, y))
    return out

def smooth(pts, n=6):
    """Catmull-Rom through pts."""
    out = []
    P = [pts[0]] + pts + [pts[-1]]
    for i in range(1, len(P) - 2):
        p0, p1, p2, p3 = P[i - 1], P[i], P[i + 1], P[i + 2]
        for k in range(n):
            t = k / n; t2 = t * t; t3 = t2 * t
            out.append(tuple(0.5 * (2 * p1[j] + (-p0[j] + p2[j]) * t + (2 * p0[j] - 5 * p1[j] + 4 * p2[j] - p3[j]) * t2 + (-p0[j] + 3 * p1[j] - 3 * p2[j] + p3[j]) * t3) for j in range(2)))
    out.append(pts[-1])
    return out

# ---------------------------------------------------------------- the sky
def sky():
    im = Image.new('RGBA', (S(W), S(H)))
    a = np.zeros((S(H), S(W), 4), np.uint8)
    t = (np.arange(S(H)) / S(H))[:, None]
    t = np.clip(t / 0.72, 0, 1) ** 1.4
    for i in range(3): a[..., i] = (SKY_TOP[i] * (1 - t) + SKY_LOW[i] * t).astype(np.uint8)
    a[..., 3] = 255
    return Image.fromarray(a)

def sparkle(d, x, y, r, a=1.0, thin=False):
    """A four-pointed star of tapered strokes."""
    w = max(0.9, r * (0.16 if thin else 0.22))
    stroke(d, [(x, y - r), (x, y), (x, y + r)], 0.2, 0.2, w, a=a)
    stroke(d, [(x - r * 0.8, y), (x, y), (x + r * 0.8, y)], 0.2, 0.2, w * 0.9, a=a)
    if r > 9:
        q = r * 0.32
        stroke(d, [(x - q, y - q), (x + q, y + q)], 0.2, 0.2, w * 0.5, a=a * 0.7)
        stroke(d, [(x - q, y + q), (x + q, y - q)], 0.2, 0.2, w * 0.5, a=a * 0.7)

def stars(d, horizon):
    rnd = random.Random(11)
    # a faint band of tiny stars arcing across (no moon)
    for _ in range(900):
        u = rnd.random()
        x = u * W
        cy = 120 + 260 * (u - 0.5) ** 2 * 2.2 + 40 * math.sin(u * 5)
        y = cy + rnd.gauss(0, 55)
        if y > horizon(x) - 30 or y < 0: continue
        r = rnd.choice((0.9, 1.1, 1.3))
        d.ellipse([S(x - r), S(y - r), S(x + r), S(y + r)], fill=rgba(MINT, 0.18 + 0.3 * rnd.random()))
    # scattered dots
    for _ in range(420):
        x, y = rnd.random() * W, rnd.random() * H * 0.78
        if y > horizon(x) - 24: continue
        r = rnd.choice((1.2, 1.4, 1.8, 2.2, 2.6))
        d.ellipse([S(x - r), S(y - r), S(x + r), S(y + r)], fill=rgba(MINT, 0.35 + 0.6 * rnd.random()))
    # bigger sparkles
    out = []
    for _ in range(60):
        x, y = rnd.random() * W, 30 + rnd.random() * H * 0.62
        if y > horizon(x) - 60: continue
        r = rnd.choice((7, 9, 11, 14, 18)) * (1.25 if rnd.random() < 0.15 else 1)
        sparkle(d, x, y, r, a=0.75 + 0.25 * rnd.random())
        out.append((x, y, r))
    return out

# ---------------------------------------------------------------- mountains
def ridge(rnd, x0, x1, base, peaks):
    """A jagged ridge: list of (x, y) from x0 to x1 through the given peaks [(x, y)]."""
    pts = [(x0, base)]
    for (px, py) in peaks:
        pts.append((px, py))
    pts.append((x1, base))
    # add shoulders between peaks
    out = [pts[0]]
    for a, b in zip(pts, pts[1:]):
        for k in range(1, 4):
            t = k / 4
            x = a[0] + (b[0] - a[0]) * t
            y = a[1] + (b[1] - a[1]) * t + (rnd.random() - 0.4) * 26 * math.sin(math.pi * t)
            out.append((x, y))
        out.append(b)
    return out

def range_layer(d, rnd, pts, fill, line_w, line_a, hatch_a, peaks):
    base = max(p[1] for p in pts) + 400
    poly = [(S(x), S(y)) for x, y in pts] + [(S(pts[-1][0]), S(base)), (S(pts[0][0]), S(base))]
    d.polygon(poly, fill=fill)
    # outline: heavier where it climbs (lit side), lighter coming down
    for a, b in zip(pts, pts[1:]):
        up = b[1] < a[1]
        stroke(d, [a, b], line_w * (1.2 if up else 0.75), line_w * (1.2 if up else 0.75), a=line_a)
    for (px, py, h, snow) in peaks:
        # ridge spurs running down from the peak: tapering lines, thick at the top
        for side, n in ((-1, 1), (1, 2)):
            for k in range(n):
                ang = (0.12 + 0.16 * k + 0.06 * rnd.random()) * side
                ln = h * (1.4 - 0.25 * k) * (0.8 + 0.3 * rnd.random())
                x1 = px + math.sin(ang) * ln; y1 = py + math.cos(ang) * ln
                mx = (px + x1) / 2 + side * 8 * rnd.random(); my = (py + y1) / 2
                stroke(d, curve((px + side * 2, py + 8 + 6 * k), (mx, my), (x1, y1)), line_w * (0.9 if k == 0 else 0.6), 0.2, a=hatch_a + 0.15 * (k == 0))
        # shadow side: short engraved strokes between the spurs, thinning out downhill
        for j in range(int(h / 7)):
            t = j / (h / 7)
            x = px + 10 + t * h * 0.75 + rnd.random() * 10; y = py + 14 + t * h * 0.9 + rnd.random() * 8
            ln = 14 + 20 * (1 - t)
            stroke(d, [(x, y), (x - ln * 0.25, y + ln)], line_w * 0.4 * (1 - t * 0.6), 0.2, a=hatch_a)
        if snow:
            sl = 0.95
            cap = [(px - h * 0.34 * sl, py + h * 0.36), (px - h * 0.2, py + h * 0.27), (px - h * 0.1, py + h * 0.42), (px + h * 0.04, py + h * 0.3), (px + h * 0.16, py + h * 0.46), (px + h * 0.3, py + h * 0.33)]
            stroke(d, cap, line_w * 0.25, line_w * 0.2, line_w * 0.8, a=line_a * 0.9)

def peaks_ridge(rnd, x0, x1, base, peaks):
    """Sharp peaks joined by saddles that dip between them."""
    pts = [(x0, base)]
    pk = sorted(peaks)
    prev = (x0, base)
    for i, (px, py, h, sn) in enumerate(pk):
        if i > 0:
            ax, ay = pk[i - 1][0], pk[i - 1][1]
            sx = (ax + px) / 2 + (rnd.random() - 0.5) * 60
            sy = max(ay, py) + (min(abs(px - ax) * 0.35, 150)) * (0.6 + 0.4 * rnd.random())
            sy = min(sy, base - 10)
            for t in (0.5,):
                pts.append((ax + (sx - ax) * t + 6, ay + (sy - ay) * t - 8 + rnd.random() * 10))
            pts.append((sx, sy))
            pts.append((sx + (px - sx) * 0.5 - 5, sy + (py - sy) * 0.5 + 6 * rnd.random()))
        else:
            pts.append(((x0 + px) / 2, (base + py) / 2 + 30))
        pts.append((px, py))
    pts.append(((pk[-1][0] + x1) / 2, (base + pk[-1][1]) / 2 + 20))
    pts.append((x1, base))
    return pts

def field(d, rnd, y0, yeti_x):
    """The field: a gentle rise under the yeti, grass in tapered strokes."""
    hill = smooth([(-40, y0 + 40), (500, y0 + 10), (1100, y0 + 28), (1650, y0 - 18), (yeti_x, y0 - 34), (2500, y0 - 6)], 20)
    poly = [(S(x), S(y)) for x, y in hill] + [(S(2500), S(H + 10)), (S(-40), S(H + 10))]
    d.polygon(poly, fill=(30, 46, 34, 255))
    stroke(d, hill, 3.2, 3.2, 4.2, a=0.95)
    # a second, nearer swell
    near = smooth([(-40, y0 + 170), (600, y0 + 140), (1200, y0 + 175), (1800, y0 + 150), (2500, y0 + 180)], 20)
    poly = [(S(x), S(y)) for x, y in near] + [(S(2500), S(H + 10)), (S(-40), S(H + 10))]
    d.polygon(poly, fill=(24, 38, 28, 255))
    stroke(d, near, 2.0, 2.0, 3.6, a=0.75)
    def yof(curve_pts, x):
        for a, b in zip(curve_pts, curve_pts[1:]):
            if a[0] <= x <= b[0]: return a[1] + (b[1] - a[1]) * (x - a[0]) / max(b[0] - a[0], 1e-6)
        return curve_pts[-1][1]
    # grass tufts along both swells: blades lean and taper; nearer ones heavier
    for line, scale, count, alpha in ((hill, 1.0, 170, 0.85), (near, 1.5, 120, 0.9)):
        clumps = [rnd.random() * W for _ in range(count // 6)]
        for _ in range(count):
            x = rnd.choice(clumps) + rnd.gauss(0, 50) if rnd.random() < 0.7 else rnd.random() * W
            if line is hill and abs(x - yeti_x) < 120: continue
            y = yof(line, x) + rnd.random() * 8 * scale
            for b in range(rnd.randint(1, 5)):
                lean = (rnd.random() - 0.5) * 22 * scale
                h = (6 + rnd.random() * 26) * scale
                bx = x + (b - 2) * 4 * scale
                stroke(d, curve((bx, y), (bx + lean * 0.3, y - h * 0.6), (bx + lean, y - h)), 1.9 * scale, 0.2, a=alpha)
    # a few little five-petal flowers in the near grass
    for _ in range(24):
        x = rnd.random() * W; y = yof(near, x) + 26 + rnd.random() * 120
        if y > H - 10: continue
        r = 4 + rnd.random() * 3
        for k in range(5):
            ang = k * 2 * math.pi / 5 + rnd.random()
            stroke(d, [(x, y), (x + math.cos(ang) * r, y + math.sin(ang) * r)], 1.4, 2.4, a=0.7)
    # scattered tufts in the deep foreground
    for _ in range(90):
        x = rnd.random() * W; y = yof(near, x) + 40 + rnd.random() * (H - yof(near, x))
        for b in range(rnd.randint(2, 4)):
            lean = (rnd.random() - 0.5) * 22; h = 12 + rnd.random() * 20
            stroke(d, curve((x + b * 5, y), (x + b * 5 + lean * 0.3, y - h * 0.6), (x + b * 5 + lean, y - h)), 2.2, 0.2, a=0.55)
    return hill

# ---------------------------------------------------------------- the yeti (from the icon art)
def yeti(height):
    fig = Image.open(os.path.join(HERE, 'yeti-cut.png'))
    Wf, Hf = fig.size
    a = np.asarray(fig).astype(int)
    mask = a[..., 3] > 128
    ear = Image.new('L', (Wf, Hf), 0); dd = ImageDraw.Draw(ear)
    left = [(0, 0), (160, 0), (140, 28), (119, 52), (101, 78), (86, 106), (70, 140), (60, 172), (54, 228), (36, 262), (0, 268)]
    dd.polygon(left, fill=255); dd.polygon([(Wf - 1 - x, y) for x, y in left], fill=255)
    ear = np.asarray(ear) > 0
    yy, xx = np.mgrid[0:Hf, 0:Wf]
    ear |= (np.hypot(xx - Wf / 2, yy - 262) > 244) & (yy < 190)
    mask &= ~ear
    ink = np.clip((200 - a[..., 0]) / 140.0, 0, 1) * mask
    m8 = Image.fromarray(np.uint8(mask) * 255)
    edge = (np.asarray(m8) > 0) & ~(np.asarray(m8.filter(ImageFilter.MinFilter(9))) > 0)
    ink = np.maximum(ink, edge * 1.0)
    out = np.zeros((Hf, Wf, 4), np.uint8)
    col = np.array(FILL)[None, None, :] * (1 - ink[..., None]) + np.array(MINT)[None, None, :] * ink[..., None]
    out[..., :3] = col.astype(np.uint8)
    out[..., 3] = np.where(mask, 255, 0).astype(np.uint8)
    im = Image.fromarray(out)
    im.putalpha(im.getchannel('A').filter(ImageFilter.GaussianBlur(1.0)))
    wpx = int(height * Wf / Hf)
    return im.resize((wpx, height), Image.LANCZOS)

def main():
    outdir = sys.argv[1]
    rnd = random.Random(7)
    im = sky(); d = ImageDraw.Draw(im)
    HOR = 640
    # far range (pale), near range (darker, heavier lines)
    FARP = [(120, 450, 120, False), (430, 330, 170, True), (760, 420, 120, False), (1010, 250, 220, True), (1330, 370, 150, False), (1560, 300, 180, True), (1840, 210, 240, True), (2180, 340, 160, True), (2420, 400, 120, False)]
    NEARP = [(180, 600, 40, False), (560, 545, 70, False), (820, 610, 30, False), (1240, 530, 80, False), (1500, 600, 35, False), (2060, 560, 60, False), (2390, 610, 30, False)]
    far = peaks_ridge(rnd, -40, W + 40, HOR + 10, FARP)
    near = smooth(peaks_ridge(rnd, -40, W + 40, HOR + 70, NEARP), 8)
    def horizon(x):
        best = H
        for line in (far,):
            for a, b in zip(line, line[1:]):
                if a[0] <= x <= b[0]: best = min(best, a[1] + (b[1] - a[1]) * (x - a[0]) / max(b[0] - a[0], 1e-6))
        return best
    sp = stars(d, horizon)
    range_layer(d, rnd, far, (36, 54, 42, 255), 2.4, 0.6, 0.3, FARP)
    range_layer(d, rnd, near, (28, 43, 33, 255), 3.0, 0.8, 0.38, NEARP)
    YX = 1880
    field(d, rnd, 800, YX)
    bg = im.resize((W, H), Image.LANCZOS).convert('RGB')
    # the yeti on his own layer, with his shadow baked into the scene
    yh = 560
    y = yeti(yh)
    foot = 800 - 34 + 8
    sh = Image.new('L', (W, H), 0); ImageDraw.Draw(sh).ellipse([YX - y.width * 0.42, foot - 16, YX + y.width * 0.42, foot + 16], fill=110)
    bg.paste((12, 20, 15), (0, 0), sh.filter(ImageFilter.GaussianBlur(9)))
    os.makedirs(outdir, exist_ok=True)
    bg.save(os.path.join(outdir, 'title_scene.webp'), quality=88, method=6)
    y.save(os.path.join(outdir, 'title_yeti.png'), optimize=True)
    # where the yeti stands, in scene pixels: centre x, feet y, and where the twinkles go
    import json
    tw = sorted(sp, key=lambda s: -s[2])[:14]
    with open(os.path.join(outdir, 'title_scene.json'), 'w') as f:
        json.dump({'w': W, 'h': H, 'yetiX': YX, 'yetiFeet': foot, 'twinkles': [[round(x), round(y_), r] for x, y_, r in tw]}, f)
    if len(sys.argv) > 2:
        prev = bg.convert('RGBA'); prev.alpha_composite(y, (int(YX - y.width / 2), int(foot - y.height)))
        prev.convert('RGB').save(sys.argv[2])
    print('ok', y.size)

if __name__ == "__main__": main()
