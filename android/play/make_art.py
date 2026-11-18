"""Play Store graphics for 5 Eye Yeti (5IY).
The app icon is built from the blue yeti line drawing (see concepts/icon.py);
this script makes the 1024x500 feature graphic from the pixel-art title scene
(the clearing and the yeti holding a firefly he has just caught) with just the name.
Run: python3 play/make_art.py"""
import os
from PIL import Image, ImageDraw, ImageFont

here = os.path.dirname(os.path.abspath(__file__))
imgs = os.path.join(here, '..', 'app', 'src', 'main', 'assets', 'images')
import json
scene = Image.open(os.path.join(imgs, 'title_bg.png')).convert('RGBA')
meta = json.load(open(os.path.join(imgs, 'title_story.json')))
step = int(6.6 * meta['fps'])   # just caught a firefly, peeking at it in his hands
fx_, fy_, fw_, fh_, x_, y_ = meta['frames'][meta['steps'][step]]
cell = Image.open(os.path.join(imgs, 'yeti_frames.png')).crop((fx_, fy_, fx_ + fw_, fy_ + fh_))
scene.alpha_composite(cell, (x_, y_))
hx_, hy_ = meta['fly'][step][:2]
for dx, dy, col in ((0, 0, (246, 255, 154, 255)), (-1, 0, (232, 255, 138, 120)), (1, 0, (232, 255, 138, 120)), (0, -1, (232, 255, 138, 120)), (0, 1, (232, 255, 138, 120))):
    scene.putpixel((int(hx_) + dx, int(hy_) + dy), col)
scene.alpha_composite(Image.open(os.path.join(imgs, 'title_fg.png')).convert('RGBA'))
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
