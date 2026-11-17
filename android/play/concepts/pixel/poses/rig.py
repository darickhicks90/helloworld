"""Cut-out rig of the creature's front view: the arms come off at the shoulders and bend at
the elbows, so the model can be reposed before pixelling. Writes pose-*.png (RGBA, hi-res)."""
import math
from PIL import Image, ImageDraw, ImageChops
import numpy as np
base = Image.open('front.png')
PAD = 420
Wc, Hc = base.width + 2 * PAD, base.height + PAD
def canvas(): return Image.new('RGBA', (Wc, Hc), (0, 0, 0, 0))
def place(im):
    c = canvas(); c.paste(im, (PAD, PAD)); return c
def side_mask(p0, p1, keep_left):
    """Mask of the canvas on one side of the line p0->p1 (in front.png coordinates)."""
    m = Image.new('L', (Wc, Hc), 0)
    (x0, y0), (x1, y1) = [(x + PAD, y + PAD) for x, y in (p0, p1)]
    # extend the line to the canvas edges
    dx, dy = x1 - x0, y1 - y0
    a = (x0 - dx * 10, y0 - dy * 10); b = (x1 + dx * 10, y1 + dy * 10)
    poly = [a, b, (0 if keep_left else Wc, b[1]), (0 if keep_left else Wc, a[1])]
    ImageDraw.Draw(m).polygon(poly, fill=255)
    return m
def masked(im, m):
    out = im.copy(); out.putalpha(ImageChops.multiply(im.getchannel('A'), m)); return out
def P(x, y): return (x + PAD, y + PAD)

full = place(base)
# arms off at the shoulders: explicit arm regions (the shoulder hump stays on the body)
def poly_mask(pts):
    m = Image.new('L', (Wc, Hc), 0); ImageDraw.Draw(m).polygon([P(*p) for p in pts], fill=255); return m
LPOLY = [(262, 190), (236, 160), (-40, 300), (-40, 640), (120, 640), (262, 432)]
RPOLY = [(860 - x, y) for x, y in LPOLY]
mL, mR = poly_mask(LPOLY), poly_mask(RPOLY)
ARM_L = masked(full, mL); ARM_R = masked(full, mR)
# round the shoulders off where the arms came away
CLIP_L = [(-40, 120), (205, 120), (228, 180), (250, 245), (265, 320), (272, 432), (-40, 432)]
CLIP_R = [(860 - x, y) for x, y in CLIP_L]
keep = ImageChops.multiply(ImageChops.invert(mL), ImageChops.invert(mR))
from PIL import ImageFilter
BODY = masked(full, keep)
# smooth away the corners left at the shoulders: a morphological opening, only there
al = BODY.getchannel('A')
opened = al.filter(ImageFilter.GaussianBlur(30)).point(lambda v: 255 if v > 150 else 0)
zone = Image.new('L', (Wc, Hc), 0)
zd = ImageDraw.Draw(zone)
zd.rectangle([P(-40, 60), P(300, 460)], fill=255); zd.rectangle([P(560, 60), P(900, 460)], fill=255)
BODY.putalpha(Image.composite(opened, al, zone))
SH_L, SH_R = (270, 236), (590, 236)
EL_L, EL_R = (148, 372), (712, 372)

def split(arm, elbow, shoulder):
    """Upper arm and forearm, cut across the arm at the elbow."""
    ex, ey = P(*elbow); sx, sy = P(*shoulder)
    dx, dy = ex - sx, ey - sy; L = math.hypot(dx, dy); dx, dy = dx / L, dy / L
    yy, xx = np.mgrid[0:Hc, 0:Wc]
    fore = ((xx - ex) * dx + (yy - ey) * dy) > 0
    m = Image.fromarray(np.uint8(fore) * 255)
    return masked(arm, ImageChops.invert(m)), masked(arm, m)

def joint(arm, elbow, r=34):
    """A disc of fur cut from the elbow, to cover the seam when the arm bends."""
    m = Image.new('L', (Wc, Hc), 0); ex, ey = P(*elbow)
    ImageDraw.Draw(m).ellipse([ex - r, ey - r, ex + r, ey + r], fill=255)
    return masked(arm, m)

def rot(im, ang, piv):
    return im.rotate(ang, resample=Image.BICUBIC, center=P(*piv))

def rotp(pt, ang, piv):
    """Where a point goes under rot()."""
    a = -math.radians(ang); x, y = pt[0] - piv[0], pt[1] - piv[1]
    return (piv[0] + x * math.cos(a) - y * math.sin(a), piv[1] + x * math.sin(a) + y * math.cos(a))

def arm(side, a_sh, a_el=0.0, fore_front=False):
    """One arm swung `a_sh` degrees at the shoulder (PIL sense: + is counter-clockwise),
    with the forearm bent `a_el` degrees at the elbow."""
    a, sh, el = (ARM_L, SH_L, EL_L) if side == 'L' else (ARM_R, SH_R, EL_R)
    # trim the arm to a round cap at the shoulder, so nothing pokes out when it swings
    sx, sy = P(*sh); ex, ey = P(*el)
    dx, dy = ex - sx, ey - sy; L = math.hypot(dx, dy); dx, dy = dx / L, dy / L
    yy, xx = np.mgrid[0:Hc, 0:Wc]
    inpoly = np.asarray(mL if side == 'L' else mR) > 0
    sg = -1 if side == 'L' else 1
    disc = (np.hypot(xx - sx, yy - sy) < 64) & (sg * ((xx - sx) * dy - (yy - sy) * dx) > -12)
    keep = (inpoly & (((xx - sx) * dx + (yy - sy) * dy) > 0)) | disc
    a = masked(full, Image.fromarray(np.uint8(keep) * 255))
    # round off the cut corners near the shoulder so the arm's top reads as a rounded joint
    al = a.getchannel('A')
    opened = al.filter(ImageFilter.GaussianBlur(26)).point(lambda v: 255 if v > 150 else 0)
    zone = Image.new('L', (Wc, Hc), 0); ImageDraw.Draw(zone).ellipse([sx - 150, sy - 150, sx + 150, sy + 150], fill=255)
    a.putalpha(Image.composite(ImageChops.multiply(opened, al), al, zone))
    up, fo = split(a, el, sh); j = joint(a, el)
    back, front = canvas(), canvas()
    back.alpha_composite(rot(up, a_sh, sh))
    fo2 = rot(rot(fo, a_el, el), a_sh, sh)
    (front if fore_front else back).alpha_composite(fo2)
    (front if fore_front else back).alpha_composite(rot(j, a_sh, sh))
    return back, front

def pose(*arms):
    """Arms sit behind the body (its rounded shoulders cover the joins); a forearm can cross in front."""
    out = canvas(); fronts = []
    for a in arms:
        b, f = arm(*a); out.alpha_composite(b); fronts.append(f)
    out.alpha_composite(BODY)
    for f in fronts: out.alpha_composite(f)
    bb = out.getbbox()
    return out.crop(bb), (PAD - bb[0], PAD - bb[1])

POSES = {
    'arms-down': pose(('L', 36, 4), ('R', -36, -4)),
    'wave': pose(('L', 36, 4), ('R', 95, 70)),
    'cheer': pose(('L', -70, -25), ('R', 70, 25)),
    'belly': pose(('L', 34, 100, True), ('R', -36, -4)),
    'shrug': pose(('L', -5, -75), ('R', 5, 75)),
}
if __name__ == '__main__':
    import json
    offs = {}
    for k, (im, off) in POSES.items():
        im.save(f'pose-{k}.png'); offs[k] = off
        print(k, im.size, off)
    json.dump(offs, open('pose-offsets.json', 'w'))
