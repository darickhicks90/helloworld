"""Cuts the blue yeti line drawing out of its white page and builds the app icon."""
import sys
from PIL import Image, ImageDraw, ImageFilter, ImageChops
import numpy as np
from collections import deque
src = Image.open(sys.argv[1]).convert('RGB')
a = np.asarray(src).astype(int)
# ink = blue lines; paper = near white
r, g, b = a[..., 0], a[..., 1], a[..., 2]
ink = (b - r > 40) & (r < 200)
ys, xs = np.nonzero(ink[:, 300:1100]); xs = xs + 300
y0, y1, x0, x1 = ys.min(), ys.max(), xs.min(), xs.max()
pad = 12
crop = src.crop((x0 - pad, y0 - pad, x1 + pad, y1 + pad))
ca = np.asarray(crop).astype(int)
ink = (ca[..., 2] - ca[..., 0] > 40) & (ca[..., 0] < 200)
# close small gaps in the outline, then flood the outside paper from the border
inkimg = Image.fromarray(np.uint8(ink) * 255).filter(ImageFilter.MaxFilter(5))
wall = np.asarray(inkimg) > 0
H, W = wall.shape
outside = np.zeros_like(wall)
q = deque([(0, x) for x in range(W)] + [(H - 1, x) for x in range(W)] + [(y, 0) for y in range(H)] + [(y, W - 1) for y in range(H)])
while q:
    y, x = q.popleft()
    if y < 0 or y >= H or x < 0 or x >= W or outside[y, x] or wall[y, x]: continue
    outside[y, x] = True
    q.extend(((y + 1, x), (y - 1, x), (y, x + 1), (y, x - 1)))
body = ~outside
# deepen the line colour a little and make the paper inside pure white
out = np.zeros((H, W, 4), np.uint8)
inkf = np.clip((ca[..., 0] - 90) / 140.0, 0, 1)[..., None]   # 0 = full ink, 1 = paper
lineCol = np.array([38, 120, 214]); paper = np.array([255, 255, 255])
rgb = lineCol * (1 - inkf) + paper * inkf
out[..., :3] = rgb.astype(np.uint8); out[..., 3] = np.where(body, 255, 0)
fig = Image.fromarray(out)
# soften the cut edge and add a white sticker border plus a soft shadow
alpha = fig.getchannel('A').filter(ImageFilter.GaussianBlur(1.2))
fig.putalpha(alpha)
border = alpha.filter(ImageFilter.MaxFilter(25)).filter(ImageFilter.GaussianBlur(2))
fig.save('yeti-cut.png')

def icon(size, bg, figure_frac, stars=True, shadow=True):
    S = size * 4
    im = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    if bg is not None:
        top, bot = bg
        for y in range(S):
            t = y / S
            d.line([(0, y), (S, y)], fill=tuple(int(top[i] * (1 - t) + bot[i] * t) for i in range(3)) + (255,))
        if stars:
            import random; rnd = random.Random(4)
            for _ in range(26):
                x, y, s = rnd.random() * S, rnd.random() * S * 0.7, S * rnd.choice((0.006, 0.009, 0.012))
                d.rectangle([x, y, x + s, y + s], fill=(230, 236, 255, 200))
    if figure_frac <= 0: return im.resize((size, size), Image.LANCZOS)
    fw = int(S * figure_frac); fh = int(fw * fig.height / fig.width)
    f = fig.resize((fw, fh), Image.LANCZOS)
    b = border.resize((fw, fh), Image.LANCZOS)
    ox, oy = (S - fw) // 2, (S - fh) // 2 + int(S * 0.01)
    if shadow:
        sh = Image.new('RGBA', (S, S), (0, 0, 0, 0)); shm = Image.new('L', (S, S), 0); shm.paste(b, (ox + int(S * 0.012), oy + int(S * 0.02)))
        sh.putalpha(shm.filter(ImageFilter.GaussianBlur(S * 0.012)).point(lambda v: int(v * 0.45)))
        im.alpha_composite(sh)
    white = Image.new('RGBA', (S, S), (255, 255, 255, 0)); wm = Image.new('L', (S, S), 0); wm.paste(b, (ox, oy)); white.putalpha(wm)
    im.alpha_composite(white)
    im.alpha_composite(f, (ox, oy))
    return im.resize((size, size), Image.LANCZOS)

NIGHT = ((26, 34, 82), (44, 76, 140))
SUN = ((255, 214, 120), (240, 128, 60))
AQUA = ((120, 214, 222), (46, 150, 160))
for name, bg in (('night', NIGHT), ('sun', SUN), ('aqua', AQUA)):
    icon(512, bg, 0.80, stars=(name == 'night')).save(f'icon-{name}.png')
sheet = Image.new('RGB', (3 * 260, 260), (40, 40, 40))
for i, name in enumerate(('night', 'sun', 'aqua')):
    ic = Image.open(f'icon-{name}.png').resize((240, 240), Image.LANCZOS)
    m = Image.new('L', (240, 240), 0); ImageDraw.Draw(m).ellipse([0, 0, 239, 239], fill=255)
    sheet.paste(ic, (10 + i * 260, 10), m)
sheet.save('icon-options.png')
print(fig.size)

# ---------------- Android resources
import os
RES = sys.argv[2]
DENS = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}
FRAC = 0.47   # the figure fits inside the adaptive icon's 72 dp safe circle
for dn, k in DENS.items():
    size = int(108 * k)
    d = os.path.join(RES, f'drawable-{dn}'); os.makedirs(d, exist_ok=True)
    icon(size, None, FRAC, stars=False).save(os.path.join(d, 'ic_launcher_foreground.png'))
    icon(size, NIGHT, 0.0, stars=True, shadow=False).save(os.path.join(d, 'ic_launcher_background.png'))
    # themed (monochrome) icon: just the drawing's lines, as a silhouette
    S = size * 4; fw = int(S * FRAC); fh = int(fw * fig.height / fig.width)
    lines = np.asarray(fig.resize((fw, fh), Image.LANCZOS)).astype(int)
    a_ = np.clip((200 - lines[..., 0]) / 140.0, 0, 1) * (lines[..., 3] / 255.0)
    mono = Image.new('LA', (S, S), (255, 0))
    m = Image.fromarray(np.uint8(a_ * 255))
    mono.paste(Image.merge('LA', (Image.new('L', (fw, fh), 255), m)), ((S - fw) // 2, (S - fh) // 2 + int(S * 0.01)))
    mono.resize((size, size), Image.LANCZOS).save(os.path.join(d, 'ic_launcher_monochrome.png'))
icon(512, NIGHT, 0.80, stars=True).convert('RGB').save(sys.argv[3])
print('resources written')
