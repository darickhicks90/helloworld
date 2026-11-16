"""Clean up the photo of the pencil drawing like a scan, and finish the hand
on the hanging arm in matching pencil strokes."""
import math, random
import numpy as np
from PIL import Image, ImageOps, ImageFilter, ImageDraw

im = ImageOps.exif_transpose(Image.open('/root/.claude/uploads/44fd42f6-4e4d-5864-a6fa-293c9604188d/1080e872-image.jpg')).convert('L')
im = im.crop((0, 0, 3000, 3110))                      # the paper (the table is below)
g = np.asarray(im).astype(np.float32)
# flatten the lighting: divide by a heavily blurred copy (the paper's own shading)
bg = np.asarray(im.filter(ImageFilter.GaussianBlur(60))).astype(np.float32)
norm = np.clip(g / np.maximum(bg, 1) * 255.0, 0, 255)
# paper to white, pencil to strong dark grey, keeping the pencil's texture
lo, hi = 110.0, 228.0
v = np.clip((norm - lo) / (hi - lo), 0, 1) ** 1.35
out = Image.fromarray((v * 255).astype(np.uint8))

# ---- finish the hand on the hanging arm, in the same bold, loose pencil as the hand on the chest
# (coordinates in the 1500-wide view, doubled here)
S = 2.0
rnd = random.Random(9)
layer = Image.new('L', out.size, 255)
d = ImageDraw.Draw(layer)
def bez(p0, p1, p2, p3, n=24):
    return [((1 - t) ** 3 * p0[0] + 3 * (1 - t) ** 2 * t * p1[0] + 3 * (1 - t) * t * t * p2[0] + t ** 3 * p3[0],
             (1 - t) ** 3 * p0[1] + 3 * (1 - t) ** 2 * t * p1[1] + 3 * (1 - t) * t * t * p2[1] + t ** 3 * p3[1]) for t in (i / n for i in range(n + 1))]
SC, AX, AY, UP = 1.38, 1258.0, 1120.0, 46.0
def T(x, y): return (AX + (x - AX) * SC, AY + (y - AY) * SC - UP)
def oval(cx, cy, rx, ry, w):
    x, y = T(cx, cy)
    d.ellipse([(x - rx * SC) * S, (y - ry * SC) * S, (x + rx * SC) * S, (y + ry * SC) * S], outline=24, width=int(w * SC * S))
def stroke(pts, w0=3.2, w1=1.0, dark=28, wobble=0.0):
    pts = [T(x, y) for x, y in pts]; w0 *= 1.45; w1 *= 1.3
    """A continuous tapered pencil stroke: stamped densely, heavy at the start and thinning out."""
    seg = [math.hypot(pts[i + 1][0] - pts[i][0], pts[i + 1][1] - pts[i][1]) for i in range(len(pts) - 1)]
    total = sum(seg) or 1; acc = 0.0
    for i in range(len(pts) - 1):
        steps = max(1, int(seg[i] * S * 2))
        for k in range(steps):
            f = k / steps
            x = (pts[i][0] + (pts[i + 1][0] - pts[i][0]) * f) * S; y = (pts[i][1] + (pts[i + 1][1] - pts[i][1]) * f) * S
            t = (acc + seg[i] * f) / total
            r = max(0.6, (w0 + (w1 - w0) * t) * S / 2)
            d.ellipse([x - r, y - r, x + r, y + r], fill=dark + int(25 * t))
        acc += seg[i]

# hair from the arm flowing down over the wrist (long tapered strands, like the rest of the arm)
for k in range(7):
    x = 1218 + k * 13 + rnd.uniform(-5, 5); y = 1096 + rnd.uniform(-24, 14)
    sw = rnd.uniform(-14, 14)
    stroke(bez((x, y), (x + sw * 0.2, y + 26), (x + sw, y + 52), (x + sw * 0.6 + rnd.uniform(-6, 6), y + 70 + rnd.uniform(0, 24))), rnd.uniform(2.2, 3.4), 0.5)
# the wrist and the back of the hand (loose, overlapping outline)
stroke(bez((1218, 1150), (1212, 1176), (1210, 1196), (1214, 1214)), 3.0, 1.6)
stroke(bez((1304, 1148), (1310, 1172), (1316, 1192), (1312, 1214)), 3.0, 1.6)
stroke(bez((1222, 1206), (1240, 1200), (1262, 1204), (1280, 1200)), 1.6, 0.6, 70)      # a crease across the back
# four long fingers hanging and tapering, tips curling in, each with an outlined nail
fingers = [  # base x, base y, length, spread, curl
    (1226, 1214, 74, -10, 6), (1248, 1218, 88, -4, 5), (1272, 1218, 84, 3, 3), (1296, 1212, 66, 9, -2)]
for (bx, by, L, spread, curl) in fingers:
    tx, ty = bx + spread, by + L
    wb, wt = 9.5, 5.5
    left = bez((bx - wb, by), (bx - wb + spread * 0.3, by + L * 0.4), (tx - wt - curl * 0.4, ty - L * 0.25), (tx - wt * 0.6 + curl, ty))
    right = bez((bx + wb, by), (bx + wb + spread * 0.3, by + L * 0.4), (tx + wt - curl * 0.2, ty - L * 0.25), (tx + wt * 0.6 + curl, ty))
    stroke(left, 3.0, 1.4); stroke(right, 2.6, 1.2)
    stroke(bez((tx - wt * 0.6 + curl, ty), (tx - 2 + curl, ty + 7), (tx + 2 + curl, ty + 7), (tx + wt * 0.6 + curl, ty)), 1.8, 1.4)   # rounded tip
    # knobbly knuckle and a joint crease
    jy = by + L * 0.45
    stroke(bez((bx - wb * 0.7 + spread * 0.35, jy), (bx + spread * 0.4, jy + 4), (bx + spread * 0.45, jy + 4), (bx + wb * 0.7 + spread * 0.45, jy)), 1.4, 0.7, 60)
    # the nail: an outlined oval near the tip, pale inside, a dark mark at its base
    nx, ny = tx + curl * 0.8, ty - 8
    oval(nx, ny - 1.5, 5.2, 7.5, 1.9)
    stroke([(nx - 3, ny - 6), (nx + 2, ny - 7)], 1.8, 1.0, 30)
# the thumb, on the inner side, angling down and in
stroke(bez((1212, 1196), (1198, 1210), (1192, 1232), (1194, 1252)), 3.0, 1.4)
stroke(bez((1222, 1216), (1210, 1226), (1206, 1242), (1206, 1256)), 2.6, 1.2)
stroke(bez((1194, 1252), (1196, 1262), (1204, 1263), (1206, 1256)), 1.8, 1.4)
oval(1200, 1251, 4.2, 7, 1.8)
# a little hatching for shadow down the outer side of the hand
for k in range(8):
    x = 1294 + rnd.uniform(-3, 6); y = 1170 + k * 5
    stroke([(x, y), (x + 10, y + 16)], 1.0, 0.5, 120)

out = Image.fromarray(np.minimum(np.asarray(out), np.asarray(layer)))
out.save('digitized.png', optimize=True)
out.resize((out.width // 2, out.height // 2), Image.LANCZOS).save('digitized-preview.png')
out.crop((2200, 2100, 2800, 2700)).save('hand.png')
print('ok', out.size)
