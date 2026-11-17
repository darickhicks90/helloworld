"""Play Store graphics for 5 Eye Yeti (5IY).
The app icon is built from the blue yeti line drawing (see concepts/icon.py);
this script makes the 1024x500 feature graphic from the pixel-art title scene.
Run: python3 play/make_art.py"""
import os
from PIL import Image, ImageDraw, ImageFont

here = os.path.dirname(os.path.abspath(__file__))
scene = Image.open(os.path.join(here, '..', 'app', 'src', 'main', 'assets', 'images', 'title_scene.png')).convert('RGB')
W, H = 1024, 500
# scale the 480x240 scene by whole pixels, keep the right edge (the yeti), crop to 1024x500
k = 3
big = scene.resize((scene.width * k, scene.height * k), Image.NEAREST)
img = big.crop((big.width - W, (big.height - H) // 2, big.width, (big.height - H) // 2 + H))
d = ImageDraw.Draw(img)
fonts = os.path.join(here, '..', 'app', 'src', 'main', 'assets', 'fonts')
head = ImageFont.truetype(os.path.join(fonts, 'PressStart2P-Regular.ttf'), 54)
body = ImageFont.truetype(os.path.join(fonts, 'PixelifySans.ttf'), 34)
x, y = 48, 120
for dx in (-4, 0, 4):
    for dy in (-4, 0, 4, 8):
        d.text((x + dx, y + dy), "5 EYE YETI", font=head, fill=(18, 12, 10))
d.text((x, y), "5 EYE YETI", font=head, fill=(255, 210, 122))
d.text((x + 4, y + 90), "write it in the studio", font=body, fill=(247, 239, 220))
d.text((x + 4, y + 132), "perform it in Clibbins", font=body, fill=(247, 239, 220))
img.save(os.path.join(here, 'feature-graphic-1024x500.png'))
print('ok')
