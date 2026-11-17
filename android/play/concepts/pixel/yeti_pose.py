import sys; sys.path.insert(0, '.')
import rig
from PIL import Image, ImageDraw
im, off = rig.pose(('L', 8, 0), ('R', 95, 70))
im.save('pose-play.png'); print(im.size, off)
import json; json.dump(off, open('pose-play.json', 'w'))
bg = Image.new('RGB', im.size, (20, 60, 20)); bg.paste(im, mask=im); bg.resize((im.width // 2, im.height // 2)).save('pose-play-chk.png')
