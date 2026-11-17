import sys
from PIL import Image, ImageFilter
import numpy as np
src, out, y0, y1 = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
im = Image.open(src).convert('RGB')
a = np.asarray(im).astype(int)
r, g, b = a[..., 0], a[..., 1], a[..., 2]
m = ((r - b > 22) & (r > 60)).astype(np.uint8) * 255
m[:y0] = 0; m[y1:] = 0
mi = Image.fromarray(m).filter(ImageFilter.MedianFilter(5))
bb = mi.getbbox(); print(bb)
rgba = im.copy(); rgba.putalpha(mi)
rgba.crop(bb).save(out)
