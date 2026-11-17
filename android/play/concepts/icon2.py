"""App icon v2: the blue yeti line drawing without its pointy ears, redrawn in
pale mint lines on a dark green night with stars (the look picked from the
themed-icon preview). Writes Android resources and the Play icon."""
import sys, os, random
from PIL import Image, ImageDraw, ImageFilter
import numpy as np

fig = Image.open('yeti-cut.png')            # cut-out from icon.py (white body, blue lines)
W, H = fig.size
a = np.asarray(fig).astype(int)
mask = a[..., 3] > 128
# remove the pointy ears (outside the headband and ear cups), both sides
ear = Image.new('L', (W, H), 0); d = ImageDraw.Draw(ear)
left = [(0, 0), (160, 0), (140, 28), (119, 52), (101, 78), (86, 106), (70, 140), (60, 172), (54, 228), (36, 262), (0, 268)]
d.polygon(left, fill=255); d.polygon([(W - 1 - x, y) for x, y in left], fill=255)
ear = np.asarray(ear) > 0
# above the ear cups, cut along the headband's outer curve so no ear tufts are left on it
yy, xx = np.mgrid[0:H, 0:W]
ear |= (np.hypot(xx - W / 2, yy - 262) > 244) & (yy < 190)
mask &= ~ear
# lines: how dark/blue each pixel is (0 paper .. 1 ink)
ink = np.clip((200 - a[..., 0]) / 140.0, 0, 1) * mask
# outline the cut edges so the silhouette stays closed
m8 = Image.fromarray(np.uint8(mask) * 255)
edge = (np.asarray(m8) > 0) & ~(np.asarray(m8.filter(ImageFilter.MinFilter(9))) > 0)
ink = np.maximum(ink, edge * 1.0)

MINT = np.array([222, 242, 212]); FILL = np.array([84, 112, 82])
def figure(size_px):
    out = np.zeros((H, W, 4), np.uint8)
    col = FILL[None, None, :] * (1 - ink[..., None]) + MINT[None, None, :] * ink[..., None]
    out[..., :3] = col.astype(np.uint8)
    out[..., 3] = np.where(mask, 255, 0).astype(np.uint8)
    im = Image.fromarray(out)
    im.putalpha(im.getchannel('A').filter(ImageFilter.GaussianBlur(1.0)))
    return im.resize((size_px, int(size_px * H / W)), Image.LANCZOS)

TOP, BOT = (44, 64, 46), (66, 92, 64)
def background(S, seed=4):
    im = Image.new('RGBA', (S, S)); d = ImageDraw.Draw(im)
    for y in range(S):
        t = y / S
        d.line([(0, y), (S, y)], fill=tuple(int(TOP[i] * (1 - t) + BOT[i] * t) for i in range(3)) + (255,))
    rnd = random.Random(seed)
    for _ in range(30):
        x, y, s = rnd.random() * S, rnd.random() * S * 0.75, S * rnd.choice((0.006, 0.009, 0.012))
        d.rectangle([x, y, x + s, y + s], fill=(222, 242, 212, 210))
    return im

def compose(size, frac, with_bg=True):
    S = size * 4
    im = background(S) if with_bg else Image.new('RGBA', (S, S), (0, 0, 0, 0))
    f = figure(int(S * frac))
    ox, oy = (S - f.width) // 2, (S - f.height) // 2 + int(S * 0.01)
    # soft shadow under the figure
    sh = Image.new('RGBA', (S, S), (0, 0, 0, 0)); m = Image.new('L', (S, S), 0); m.paste(f.getchannel('A'), (ox + int(S * 0.01), oy + int(S * 0.018)))
    sh.putalpha(m.filter(ImageFilter.GaussianBlur(S * 0.012)).point(lambda v: int(v * 0.4)))
    im.alpha_composite(sh); im.alpha_composite(f, (ox, oy))
    return im.resize((size, size), Image.LANCZOS)

RES, PLAY = sys.argv[1], sys.argv[2]
FRAC = 0.50   # inside the adaptive icon's safe circle now that the ears are gone
for dn, k in {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}.items():
    size = int(108 * k); dd = os.path.join(RES, f'drawable-{dn}'); os.makedirs(dd, exist_ok=True)
    compose(size, FRAC, with_bg=False).save(os.path.join(dd, 'ic_launcher_foreground.png'))
    background(size * 4).resize((size, size), Image.LANCZOS).save(os.path.join(dd, 'ic_launcher_background.png'))
    S = size * 4; f = figure(int(S * FRAC)); fa = np.asarray(f).astype(float)
    line = np.clip((fa[..., 0] - 90) / 130.0, 0, 1) * (fa[..., 3] / 255)
    mono = Image.new('LA', (S, S), (255, 0))
    mono.paste(Image.merge('LA', (Image.new('L', f.size, 255), Image.fromarray(np.uint8(line * 255)))), ((S - f.width) // 2, (S - f.height) // 2 + int(S * 0.01)))
    mono.resize((size, size), Image.LANCZOS).save(os.path.join(dd, 'ic_launcher_monochrome.png'))
compose(512, 0.80).convert('RGB').save(PLAY)
# preview: round, rounded square
fg = Image.open(os.path.join(RES, 'drawable-xxxhdpi', 'ic_launcher_foreground.png')); bg = Image.open(os.path.join(RES, 'drawable-xxxhdpi', 'ic_launcher_background.png')).convert('RGBA')
bg.alpha_composite(fg); c = bg.crop((72, 72, 360, 360)).resize((240, 240))
out = Image.new('RGB', (520, 260), (30, 30, 30))
m = Image.new('L', (240, 240), 0); ImageDraw.Draw(m).ellipse([0, 0, 239, 239], fill=255); out.paste(c, (10, 10), m)
m2 = Image.new('L', (240, 240), 0); ImageDraw.Draw(m2).rounded_rectangle([0, 0, 239, 239], 60, fill=255); out.paste(c, (270, 10), m2)
out.save('launcher-sim2.png'); print('ok')
