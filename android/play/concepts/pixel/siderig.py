"""Side-view rig of the yeti (from the profile screenshot): torso bends at the hip,
the near arm swings at the shoulder, legs bend at hip and knee. Facing left."""
import math, sys
from PIL import Image, ImageDraw, ImageChops, ImageFilter
import numpy as np

base = Image.open('side.png')
PAD = 500
CW, CH = base.width + 2 * PAD, base.height + 2 * PAD
def P(x, y): return (x + PAD, y + PAD)
full = Image.new('RGBA', (CW, CH)); full.paste(base, (PAD, PAD))
def poly(pts):
    m = Image.new('L', (CW, CH), 0); ImageDraw.Draw(m).polygon([P(*p) for p in pts], fill=255); return m
def masked(im, m):
    o = im.copy(); o.putalpha(ImageChops.multiply(im.getchannel('A'), m)); return o
def rot(im, ang, piv): return im.rotate(ang, resample=Image.BICUBIC, center=P(*piv))
def disc(c, r):
    m = Image.new('L', (CW, CH), 0); x, y = P(*c); ImageDraw.Draw(m).ellipse([x - r, y - r, x + r, y + r], fill=255); return m

HIP, KNEE, SHOULDER = (235, 610), (205, 880), (195, 320)
ARM = [(150, 300), (240, 290), (255, 470), (280, 620), (282, 765), (212, 770), (198, 620), (160, 470)]
TORSO = [(-50, -50), (450, -50), (450, 640), (-50, 640)]
THIGH = [(-50, 600), (450, 600), (450, 885), (-50, 885)]
SHIN = [(-50, 870), (450, 870), (450, 1200), (-50, 1200)]
armL = masked(full, poly(ARM))
torsoL = masked(full, poly(TORSO))
thighL = masked(full, poly(THIGH))
shinL = masked(full, poly(SHIN))
hipJ = masked(full, disc(HIP, 95)); kneeJ = masked(full, disc(KNEE, 70))
# the arm left on the body reads as the far arm: shade it a touch darker
far = masked(full, poly(ARM)); far = Image.eval(far.convert('RGBA'), lambda v: v)
fa = np.asarray(far).astype(float); fa[..., :3] *= 0.8; far = Image.fromarray(fa.astype(np.uint8))

def pose(torso, arm, thigh, shin):
    """Angles in degrees (PIL sense, + = counter-clockwise: for a figure facing left, + bends forward)."""
    out = Image.new('RGBA', (CW, CH))
    # far arm (darker), behind everything, swinging opposite to the near arm
    out.alpha_composite(rot(rot(shinL, shin, KNEE), thigh, HIP))
    out.alpha_composite(rot(rot(kneeJ, 0, KNEE), thigh, HIP))
    out.alpha_composite(rot(thighL, thigh, HIP))
    out.alpha_composite(rot(torsoL, torso, HIP))
    out.alpha_composite(rot(hipJ, torso * 0.5, HIP))
    out.alpha_composite(rot(rot(armL, arm, SHOULDER), torso, HIP))
    bb = out.getbbox()
    return out.crop(bb), (PAD - bb[0], PAD - bb[1])

if __name__ == '__main__':
    im, off = pose(float(sys.argv[1]), float(sys.argv[2]), float(sys.argv[3]), float(sys.argv[4]))
    bg = Image.new('RGB', im.size, (20, 60, 20)); bg.paste(im, mask=im)
    bg.resize((im.width // 2, im.height // 2)).save('side-pose.png'); print(im.size, off)
