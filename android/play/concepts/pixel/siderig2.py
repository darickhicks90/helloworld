"""Side-view rig (facing left) from the profile screenshot, with an elbow, and the
hand position tracked through the joints. Angles in degrees, PIL sense (+ = counter-
clockwise). For this left-facing figure: torso + bends forward; shoulder - swings the
arm forward/up; elbow - bends the forearm up; thigh - brings the knee forward; shin +
folds the shin back under."""
import math
from PIL import Image, ImageDraw, ImageChops
import numpy as np

base = Image.open('side.png')
PAD = 600
CW, CH = base.width + 2 * PAD, base.height + 2 * PAD
def P(x, y): return (x + PAD, y + PAD)
full = Image.new('RGBA', (CW, CH)); full.paste(base, (PAD, PAD))
def poly(pts):
    m = Image.new('L', (CW, CH), 0); ImageDraw.Draw(m).polygon([P(*p) for p in pts], fill=255); return m
def masked(im, m):
    o = im.copy(); o.putalpha(ImageChops.multiply(im.getchannel('A'), m)); return o
def rot(im, ang, piv): return im.rotate(ang, resample=Image.BICUBIC, center=P(*piv)) if ang else im
def disc(c, r):
    m = Image.new('L', (CW, CH), 0); x, y = P(*c); ImageDraw.Draw(m).ellipse([x - r, y - r, x + r, y + r], fill=255); return m
def rp(pt, ang, piv):
    """Where point pt goes under rot(…, ang, piv)."""
    a = math.radians(ang); x, y = pt[0] - piv[0], pt[1] - piv[1]
    return (piv[0] + x * math.cos(a) + y * math.sin(a), piv[1] - x * math.sin(a) + y * math.cos(a))

HIP, KNEE, SHOULDER, ELBOW = (235, 610), (205, 880), (195, 320), (232, 545)
HAND = (252, 762)
ARM = [(150, 300), (240, 290), (255, 470), (280, 620), (282, 775), (212, 778), (198, 620), (160, 470)]
UPPER = [(140, 280), (260, 280), (262, 552), (196, 552)]
FORE = [(180, 538), (290, 538), (290, 790), (180, 790)]
TORSO = [(-60, -60), (460, -60), (460, 640), (-60, 640)]
THIGH = [(-60, 600), (460, 600), (460, 885), (-60, 885)]
SHIN = [(-60, 870), (460, 870), (460, 1220), (-60, 1220)]
armM = poly(ARM)
upperL = masked(full, ImageChops.multiply(armM, poly(UPPER)))
foreL = masked(full, ImageChops.multiply(armM, poly(FORE)))
elbowJ = masked(full, ImageChops.multiply(armM, disc(ELBOW, 34)))
torsoL = masked(full, poly(TORSO))
thighL = masked(full, poly(THIGH)); shinL = masked(full, poly(SHIN))
hipJ = masked(full, disc(HIP, 95)); kneeJ = masked(full, disc(KNEE, 70))

def pose(torso=0.0, shoulder=0.0, elbow=0.0, thigh=0.0, shin=0.0):
    """Returns (image cropped, offset of side.png origin in it, hand point in image coords)."""
    out = Image.new('RGBA', (CW, CH))
    out.alpha_composite(rot(rot(shinL, shin, KNEE), thigh, HIP))
    out.alpha_composite(rot(kneeJ, thigh, HIP))
    out.alpha_composite(rot(thighL, thigh, HIP))
    out.alpha_composite(rot(torsoL, torso, HIP))
    out.alpha_composite(rot(hipJ, torso * 0.5, HIP))
    # the near arm: forearm about the elbow, then the whole arm about the shoulder, then with the torso
    fore = rot(rot(rot(foreL, elbow, ELBOW), shoulder, SHOULDER), torso, HIP)
    up = rot(rot(upperL, shoulder, SHOULDER), torso, HIP)
    ej = rot(rot(elbowJ, shoulder, SHOULDER), torso, HIP)
    out.alpha_composite(up); out.alpha_composite(fore); out.alpha_composite(ej)
    hand = rp(rp(rp(HAND, elbow, ELBOW), shoulder, SHOULDER), torso, HIP)
    bb = out.getbbox()
    return out.crop(bb), (PAD - bb[0], PAD - bb[1]), (hand[0] + PAD - bb[0], hand[1] + PAD - bb[1])
