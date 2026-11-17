"""Play Store graphics for 5 Eye Yeti (5IY).
The app icon is built from the blue yeti line drawing (see concepts/icon.py);
this script makes the 1024x500 feature graphic from the pixel-art title scene
(the clearing and one frame of the yeti reaching for a firefly) with just the name.
Run: python3 play/make_art.py"""
import os
from PIL import Image, ImageDraw, ImageFont

here = os.path.dirname(os.path.abspath(__file__))
imgs = os.path.join(here, '..', 'app', 'src', 'main', 'assets', 'images')
import json
scene = Image.open(os.path.join(imgs, 'title_bg.png')).convert('RGBA')
meta = json.load(open(os.path.join(imgs, 'yeti_anim.json')))
f = meta['frames'] - 2   # the yeti reaching up
cell = Image.open(os.path.join(imgs, 'yeti_frames.png')).crop((f * meta['w'], 0, (f + 1) * meta['w'], meta['h']))
scene.alpha_composite(cell, (meta['x'], meta['y']))
hx, hy = meta['hands'][f]
for dx, dy, col in ((0, -4, (246, 255, 154, 255)), (-1, -4, (232, 255, 138, 120)), (1, -4, (232, 255, 138, 120)), (0, -5, (232, 255, 138, 120)), (0, -3, (232, 255, 138, 120))):
    scene.putpixel((hx + dx, hy + dy), col)
scene = scene.convert('RGB')
W, H = 1024, 500
# scale the 480x240 scene by whole pixels (2x = 960x480), then mirror-extend the edges to 1024x500
import numpy as np
big = np.asarray(scene.resize((scene.width * 2, scene.height * 2), Image.NEAREST))
padx, pady = (W - big.shape[1]) // 2, (H - big.shape[0]) // 2
img = Image.fromarray(np.pad(big, ((pady, H - big.shape[0] - pady), (padx, W - big.shape[1] - padx), (0, 0)), mode='reflect'))
d = ImageDraw.Draw(img)
fonts = os.path.join(here, '..', 'app', 'src', 'main', 'assets', 'fonts')
head = ImageFont.truetype(os.path.join(fonts, 'PressStart2P-Regular.ttf'), 54)
body = ImageFont.truetype(os.path.join(fonts, 'PixelifySans.ttf'), 34)
x, y = 44, 96
for dx in (-4, 0, 4):
    for dy in (-4, 0, 4, 8):
        d.text((x + dx, y + dy), "5 EYE YETI", font=head, fill=(18, 12, 10))
d.text((x, y), "5 EYE YETI", font=head, fill=(255, 210, 122))
img.save(os.path.join(here, 'feature-graphic-1024x500.png'))
print('ok')
