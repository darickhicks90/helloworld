"""Launcher icons for the two apps split off from 5 Eye Yeti:
  - the game (Wildwood Hall): the pixel yeti with his axe raised, under stars in the glasshouse night
  - 5IY Pocket (radio + notebook): a little portable radio and a memo pad, mint lines on night green
python3 new_apps.py <android dir>"""
import os, sys, math, random
from PIL import Image, ImageDraw
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'title'))

ROOT = sys.argv[1]
DENS = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}
XML = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
'''

def night(S, top, bot, seed, star):
    im = Image.new('RGBA', (S, S)); d = ImageDraw.Draw(im)
    for y in range(S):
        t = y / S; d.line([(0, y), (S, y)], fill=tuple(int(top[i] * (1 - t) + bot[i] * t) for i in range(3)) + (255,))
    rnd = random.Random(seed)
    for _ in range(26):
        x, y, s = rnd.random() * S, rnd.random() * S * 0.7, S * rnd.choice((0.006, 0.009, 0.012))
        d.rectangle([x, y, x + s, y + s], fill=star)
    return im

def write(module, fg_fn, bg_fn, mono_fn):
    res = os.path.join(ROOT, module, 'src/main/res')
    for dn, k in DENS.items():
        size = int(108 * k); dd = os.path.join(res, f'drawable-{dn}'); os.makedirs(dd, exist_ok=True)
        fg_fn(size).save(os.path.join(dd, 'ic_launcher_foreground.png'))
        bg_fn(size).save(os.path.join(dd, 'ic_launcher_background.png'))
        mono_fn(size).save(os.path.join(dd, 'ic_launcher_monochrome.png'))
    md = os.path.join(res, 'mipmap-anydpi-v26'); os.makedirs(md, exist_ok=True)
    for n in ('ic_launcher.xml', 'ic_launcher_round.xml'): open(os.path.join(md, n), 'w').write(XML)
    # a preview with background + foreground
    p = bg_fn(432).copy(); p.alpha_composite(fg_fn(432)); p.save(os.path.join(ROOT, 'art', 'icons', module + '_preview.png'))

# ---- the game
sheet = Image.open(os.path.join(ROOT, 'game/src/main/assets/images/hall_yeti_axe.png')).convert('RGBA')
frame = sheet.crop((72 * 2, 0, 72 * 3, 72)); frame = frame.crop(frame.getbbox())
def game_fg(size):
    S = size; im = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    k = max(1, int(S * 0.52 / frame.height))
    f = frame.resize((frame.width * k, frame.height * k), Image.NEAREST)
    # a soft glow behind him
    gl = Image.new('RGBA', (S, S), (0, 0, 0, 0)); gd = ImageDraw.Draw(gl)
    for r in range(int(S * 0.3), 0, -max(1, S // 60)):
        a = int(70 * (1 - r / (S * 0.3)))
        gd.ellipse([S / 2 - r, S / 2 - r, S / 2 + r, S / 2 + r], fill=(255, 214, 140, a))
    im.alpha_composite(gl)
    im.alpha_composite(f, ((S - f.width) // 2, (S - f.height) // 2 + int(S * 0.02)))
    return im
def game_bg(size): return night(size * 4, (26, 20, 40), (56, 40, 60), 9, (255, 236, 200, 220)).resize((size, size), Image.LANCZOS)
def game_mono(size):
    f = game_fg(size); a = f.getchannel('A').point(lambda v: 255 if v > 200 else 0)
    return Image.merge('LA', (Image.new('L', f.size, 255), a))
write('game', game_fg, game_bg, game_mono)

# ---- 5IY Pocket: radio + notebook in mint lines
from title_scene import stroke, curve  # noqa: E402  (tapered strokes, same hand as the title screen)
import title_scene as ts
MINT = (222, 242, 212)
def pocket_draw(S, col, a=1.0):
    ts.SS = 1
    ts.S = lambda v: v
    im = Image.new('RGBA', (S, S), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    u = S / 108
    # the radio: a rounded box, speaker grille, dial, handle and aerial
    x0, y0, x1, y1 = 30 * u, 44 * u, 72 * u, 74 * u
    d.rounded_rectangle([x0, y0, x1, y1], radius=5 * u, outline=col + (int(255 * a),), width=max(1, int(2.6 * u)))
    for r in (3, 6, 9):
        d.ellipse([41 * u - r * u, 60 * u - r * u, 41 * u + r * u, 60 * u + r * u], outline=col + (int(255 * a),), width=max(1, int(1.2 * u)))
    stroke(d, [(55 * u, 52 * u), (67 * u, 52 * u)], 1.6 * u, 1.6 * u, a=a, col=col)
    stroke(d, [(61 * u, 50 * u), (61 * u, 54 * u)], 1.2 * u, 1.2 * u, a=a, col=col)
    d.ellipse([58 * u, 60 * u, 66 * u, 68 * u], outline=col + (int(255 * a),), width=max(1, int(1.6 * u)))
    stroke(d, curve((36 * u, y0), (36 * u, 36 * u), (51 * u, 36 * u)), 2.4 * u, 1.6 * u, a=a, col=col)
    stroke(d, [(66 * u, y0), (78 * u, 26 * u)], 1.8 * u, 0.6 * u, a=a, col=col)
    d.ellipse([77 * u, 24 * u, 80.5 * u, 27.5 * u], fill=col + (int(255 * a),))
    # the memo pad, tucked in front at an angle
    pad = Image.new('RGBA', (S, S), (0, 0, 0, 0)); pd = ImageDraw.Draw(pad)
    px0, py0, px1, py1 = 58 * u, 60 * u, 82 * u, 88 * u
    pd.rounded_rectangle([px0, py0, px1, py1], radius=2 * u, fill=(40, 58, 44, 255), outline=col + (int(255 * a),), width=max(1, int(2 * u)))
    pd.rectangle([px0, py0, px1, py0 + 4 * u], fill=col + (int(255 * a),))
    for k in range(4):
        y = py0 + (10 + k * 4.6) * u
        stroke(pd, [(px0 + 4 * u, y), (px1 - (4 + 6 * (k == 3)) * u, y)], 1.0 * u, 0.6 * u, a=a * 0.85, col=col)
    pad = pad.rotate(-12, center=((px0 + px1) / 2, (py0 + py1) / 2), resample=Image.BICUBIC)
    im.alpha_composite(pad)
    return im
def pocket_fg(size): return pocket_draw(size * 4, MINT).resize((size, size), Image.LANCZOS)
def pocket_bg(size): return night(size * 4, (44, 64, 46), (66, 92, 64), 4, (222, 242, 212, 210)).resize((size, size), Image.LANCZOS)
def pocket_mono(size):
    f = pocket_draw(size * 4, (255, 255, 255)).resize((size, size), Image.LANCZOS)
    return Image.merge('LA', (Image.new('L', f.size, 255), f.getchannel('A')))
write('pocket', pocket_fg, pocket_bg, pocket_mono)
print('ok')

# ---- Little Eye Yeti (kids): the icon yeti, a size smaller, on a warm sunny background with a star
def kids_fig(size):
    f = ts.yeti(int(size * 4 * 0.55))
    im = Image.new('RGBA', (size * 4, size * 4), (0, 0, 0, 0))
    im.alpha_composite(f, ((size * 4 - f.width) // 2, (size * 4 - f.height) // 2 + size // 10))
    return im.resize((size, size), Image.LANCZOS)
def kids_bg(size):
    S = size * 4
    im = Image.new('RGBA', (S, S)); d = ImageDraw.Draw(im)
    for y in range(S):
        t = y / S; top, bot = (246, 178, 92), (230, 116, 44)
        d.line([(0, y), (S, y)], fill=tuple(int(top[i] * (1 - t) + bot[i] * t) for i in range(3)) + (255,))
    rnd = random.Random(3)
    for _ in range(14):
        x, y, s = rnd.random() * S, rnd.random() * S * 0.6, S * rnd.choice((0.01, 0.015))
        d.ellipse([x, y, x + s, y + s], fill=(255, 246, 214, 220))
    return im.resize((size, size), Image.LANCZOS)
def kids_mono(size):
    f = kids_fig(size); a = np.asarray(f).astype(float)
    line = np.clip((a[..., 0] - 90) / 130.0, 0, 1) * (a[..., 3] / 255)
    return Image.merge('LA', (Image.new('L', f.size, 255), Image.fromarray(np.uint8(line * 255))))
import numpy as np  # noqa: E402
write('kids', kids_fig, kids_bg, kids_mono)
print('kids ok')
