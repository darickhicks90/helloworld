"""Full rig for the title animation, built from the user's 3D-model screenshots.

Side view (side.png, facing left): head (neck), torso (hip), near and far arm (shoulder,
elbow), near and far leg (hip, knee). The near arm is lifted off the body and the gap is
filled with fur from the body behind it; the far limbs are darker copies.
Back views (back.png, back34.png): arms brought down from the T-pose at the shoulders.

All work is done at half resolution (plenty above the final pixel size) for speed.
Angles are in degrees, PIL sense (+ = counter-clockwise). For the left-facing side view:
torso + leans forward; head + looks down; shoulder - swings the arm forward/up; elbow -
bends the forearm up; thigh - brings the knee forward; shin + folds the shin back."""
import math
from PIL import Image, ImageDraw, ImageChops, ImageFilter
import numpy as np

S = 0.5
def load(name):
    im = Image.open(name)
    return im.resize((round(im.width * S), round(im.height * S)), Image.LANCZOS)
def sc(p): return (p[0] * S, p[1] * S)
def rp(pt, ang, piv):
    a = math.radians(ang); x, y = pt[0] - piv[0], pt[1] - piv[1]
    return (piv[0] + x * math.cos(a) + y * math.sin(a), piv[1] - x * math.sin(a) + y * math.cos(a))

class Canvas:
    def __init__(self, base, pad):
        self.pad = pad; self.W = base.width + 2 * pad; self.H = base.height + 2 * pad
    def P(self, p): return (p[0] + self.pad, p[1] + self.pad)
    def blank(self): return Image.new('RGBA', (self.W, self.H))
    def place(self, im): c = self.blank(); c.paste(im, (self.pad, self.pad)); return c
    def poly(self, pts):
        m = Image.new('L', (self.W, self.H), 0); ImageDraw.Draw(m).polygon([self.P(sc(p)) for p in pts], fill=255); return m
    def disc(self, c, r):
        m = Image.new('L', (self.W, self.H), 0); x, y = self.P(sc(c)); r *= S; ImageDraw.Draw(m).ellipse([x - r, y - r, x + r, y + r], fill=255); return m
    def rot(self, im, ang, piv): return im.rotate(ang, resample=Image.BICUBIC, center=self.P(sc(piv))) if abs(ang) > 0.01 else im

def masked(im, m):
    o = im.copy(); o.putalpha(ImageChops.multiply(im.getchannel('A'), m)); return o
def darker(im, f):
    a = np.asarray(im).astype(float); a[..., :3] *= f; return Image.fromarray(a.astype(np.uint8))

# ======================= side view
class Side:
    HIP, KNEE, SHOULDER, ELBOW, NECK = (235, 610), (205, 880), (195, 320), (232, 545), (175, 262)
    HAND = (252, 762)
    ARM = [(150, 300), (240, 290), (255, 470), (276, 620), (276, 690), (268, 745), (254, 774), (236, 784), (220, 772), (208, 735), (202, 680), (198, 620), (160, 470)]
    UPPER = [(130, 270), (270, 270), (272, 552), (190, 552)]
    FORE = [(170, 538), (300, 538), (300, 800), (170, 800)]
    HEAD = [(-60, -60), (265, -60), (265, 185), (190, 262), (70, 300), (-60, 300)]
    FACE = [(-60, -60), (150, -60), (150, 300), (-60, 300)]   # the part of the head the body layer leaves out
    TORSO = [(-60, -60), (470, -60), (470, 640), (-60, 640)]
    THIGH = [(-60, 600), (470, 600), (470, 885), (-60, 885)]
    SHIN = [(-60, 870), (470, 870), (470, 1230), (-60, 1230)]

    def __init__(self):
        raw = Image.open('side.png')
        base = self._inpaint_arm(raw)
        self.src = raw
        b = base.resize((round(raw.width * S), round(raw.height * S)), Image.LANCZOS)
        r = raw.resize(b.size, Image.LANCZOS)
        self.c = c = Canvas(b, 420)
        full = c.place(b); orig = c.place(r)
        armM = c.poly(self.ARM)
        self.upper = masked(orig, ImageChops.multiply(armM, c.poly(self.UPPER)))
        self.fore = masked(orig, ImageChops.multiply(armM, c.poly(self.FORE)))
        self.elbowJ = masked(orig, ImageChops.multiply(armM, c.disc(self.ELBOW, 34)))
        ua = self.upper.getchannel('A'); x0, y0 = c.P(sc(self.SHOULDER))
        z = Image.new('L', (c.W, c.H), 0); ImageDraw.Draw(z).ellipse([x0 - 60, y0 - 60, x0 + 60, y0 + 60], fill=255)
        self.upper.putalpha(Image.composite(ImageChops.multiply(ua.filter(ImageFilter.GaussianBlur(8)).point(lambda v: 255 if v > 160 else 0), ua), ua, z))
        self.shoulderJ = masked(orig, ImageChops.multiply(armM, c.disc((self.SHOULDER[0] + 12, self.SHOULDER[1] + 20), 42)))
        self.fupper, self.ffore, self.felbowJ = (darker(x, 0.8) for x in (self.upper, self.fore, self.elbowJ))
        headM = c.poly(self.HEAD)
        self.head = masked(full, headM)
        self.neckJ = masked(full, c.disc(self.NECK, 75))   # drawn under the head, turned half as far, so no gap opens
        # the body keeps the back of the head underneath, so turning the head never opens a gap behind it
        self.torso = masked(full, ImageChops.multiply(c.poly(self.TORSO), ImageChops.invert(c.poly(self.FACE))))
        self.thigh = masked(full, c.poly(self.THIGH)); self.shin = masked(full, c.poly(self.SHIN))
        self.kneeJ = masked(full, c.disc(self.KNEE, 70)); self.hipJ = masked(full, c.disc(self.HIP, 95))
        self.fthigh, self.fshin, self.fkneeJ = (darker(x, 0.87) for x in (self.thigh, self.shin, self.kneeJ))

    def _inpaint_arm(self, raw):
        """Fill where the near arm hangs with fur from beside it, so the arm can move away."""
        a = np.asarray(raw).copy()
        m = Image.new('L', raw.size, 0); ImageDraw.Draw(m).polygon(self.ARM, fill=255)
        m = np.asarray(m.filter(ImageFilter.MaxFilter(5))) > 0
        H, W = m.shape
        out = a.copy()
        for y in range(H):
            xs = np.nonzero(m[y])[0]
            if len(xs) == 0: continue
            for x in xs:
                for dx in (95, 120, -115, -140, 70):
                    sx = x + dx
                    if 0 <= sx < W and a[y, sx, 3] > 200 and not m[y, sx]:
                        out[y, x] = a[y, sx]; break
                else:
                    out[y, x] = a[y, x]
        img = Image.fromarray(out)
        # soften the seam
        blur = img.filter(ImageFilter.GaussianBlur(2))
        edge = Image.fromarray((m * 255).astype(np.uint8)).filter(ImageFilter.MaxFilter(9))
        edge = ImageChops.subtract(edge, Image.fromarray((m * 255).astype(np.uint8)).filter(ImageFilter.MinFilter(9)))
        img.paste(blur, (0, 0), edge)
        return img

    def pose(self, torso=0, head=0, nsh=-8, nel=0, fsh=-4, fel=0, nth=0, nsn=0, fth=0, fsn=0):
        c = self.c; H, K, SH, EL, N = self.HIP, self.KNEE, self.SHOULDER, self.ELBOW, self.NECK
        out = c.blank()
        T = lambda im: c.rot(im, torso, H)
        def arm(up, fo, ej, sh, el):
            return (T(c.rot(up, sh, SH)), T(c.rot(c.rot(fo, el, EL), sh, SH)), T(c.rot(ej, sh, SH)))
        # far arm, far leg, near leg, torso, head, near arm
        for im in arm(self.fupper, self.ffore, self.felbowJ, fsh, fel): out.alpha_composite(im)
        out.alpha_composite(c.rot(c.rot(self.fshin, fsn, K), fth, H)); out.alpha_composite(c.rot(self.fkneeJ, fth, H)); out.alpha_composite(c.rot(self.fthigh, fth, H))
        out.alpha_composite(c.rot(c.rot(self.shin, nsn, K), nth, H)); out.alpha_composite(c.rot(self.kneeJ, nth, H)); out.alpha_composite(c.rot(self.thigh, nth, H))
        out.alpha_composite(T(self.torso)); out.alpha_composite(c.rot(self.hipJ, torso * 0.5, H))
        out.alpha_composite(T(c.rot(self.neckJ, head * 0.5, N))); out.alpha_composite(T(c.rot(self.head, head, N)))
        for im in arm(self.upper, self.fore, self.elbowJ, nsh, nel): out.alpha_composite(im)
        out.alpha_composite(T(c.rot(self.shoulderJ, nsh * 0.35, SH)))
        hand = lambda sh, el: c.P(sc(rp(rp(rp(self.HAND, el, EL), sh, SH), torso, H)))
        hip = c.P(sc(H))
        eye = c.P(sc(rp(rp((60, 150), head, N), torso, H)))
        return out, dict(hip=hip, nhand=hand(nsh, nel), fhand=hand(fsh, fel), eye=eye)

# ======================= back views (arms down from the T-pose)
class Back:
    def __init__(self, name, larm, lsh, lhand, rarm, rsh, rhand, lhang, rhang):
        raw = Image.open(name); b = raw.resize((round(raw.width * S), round(raw.height * S)), Image.LANCZOS)
        self.c = c = Canvas(b, 360); full = c.place(b)
        self.src = raw
        mL, mR = c.poly(larm), c.poly(rarm)
        def trimmed(m, sh, hand):
            # keep only the arm beyond the shoulder (plus a round cap), so nothing swings out above it
            x0, y0 = c.P(sc(sh)); hx_, hy_ = c.P(sc(hand)); dx, dy = hx_ - x0, hy_ - y0; L = math.hypot(dx, dy); dx, dy = dx / L, dy / L
            yy, xx = np.mgrid[0:c.H, 0:c.W]
            keep = (((xx - x0) * dx + (yy - y0) * dy) > -8) | (np.hypot(xx - x0, yy - y0) < 28)
            return ImageChops.multiply(m, Image.fromarray(np.uint8(keep) * 255))
        self.armL = masked(full, trimmed(mL, lsh, lhand)); self.armR = masked(full, trimmed(mR, rsh, rhand))
        keep = ImageChops.multiply(ImageChops.invert(mL), ImageChops.invert(mR))
        body = masked(full, keep)
        al = body.getchannel('A')
        zone = Image.new('L', (c.W, c.H), 0); zd = ImageDraw.Draw(zone)
        for sh in (lsh, rsh):
            x, y = c.P(sc(sh)); zd.ellipse([x - 120, y - 120, x + 120, y + 120], fill=255)
        opened = al.filter(ImageFilter.GaussianBlur(12)).point(lambda v: 255 if v > 150 else 0)
        al2 = Image.composite(opened, al, zone)
        # round the whole upper body (head and shoulders) into a smooth dome
        top = Image.new('L', (c.W, c.H), 0); ImageDraw.Draw(top).rectangle([0, 0, c.W, c.P(sc((0, (lsh[1] + rsh[1]) / 2 + 120)))[1]], fill=255)
        dome = al2.filter(ImageFilter.GaussianBlur(22)).point(lambda v: 255 if v > 140 else 0)
        body.putalpha(Image.composite(ImageChops.multiply(dome, al2), al2, top))
        self.body = body; self.lsh, self.rsh = lsh, rsh; self.lhang, self.rhang = lhang, rhang
        # hips: midpoint of the shoulders, dropped to hip height (used to line views up)
        self.hipx = (lsh[0] + rsh[0]) / 2
    def pose(self, tuck=0.0):
        """Arms hang at the sides; tuck 0..1 brings both hands in front of the chest (hidden)."""
        c = self.c; out = c.blank()
        la = self.lhang + 12 * tuck; ra = self.rhang - 12 * tuck
        out.alpha_composite(self.body)
        for arm, ang, sh in ((self.armL, la, self.lsh), (self.armR, ra, self.rsh)):
            a = c.rot(arm, ang, sh)
            # round the shoulder end so nothing pokes out
            al = a.getchannel('A'); x, y = c.P(sc(sh))
            z = Image.new('L', (c.W, c.H), 0); ImageDraw.Draw(z).ellipse([x - 70, y - 70, x + 70, y + 70], fill=255)
            a.putalpha(Image.composite(ImageChops.multiply(al.filter(ImageFilter.GaussianBlur(10)).point(lambda v: 255 if v > 150 else 0), al), al, z))
            out.alpha_composite(a)
        return out, dict(hip=c.P(sc((self.hipx, 0))))

def back():
    L = [(300, 240), (320, 90), (-40, 380), (-40, 640), (90, 640), (110, 560)]
    R = [(906 - x, y) for x, y in L]
    return Back('back.png', L, (300, 205), (40, 500), R, (606, 205), (866, 500), 40, -40)
def back34():
    L = [(330, 330), (372, 150), (-40, 330), (-40, 700), (90, 700), (140, 620)]
    R = [(690, 380), (640, 150), (1010, 300), (1010, 700), (900, 700), (860, 640)]
    return Back('back34.png', L, (320, 235), (60, 580), R, (690, 268), (930, 590), 35, -35)
