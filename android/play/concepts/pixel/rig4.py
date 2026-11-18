"""Skinned rig for the title animation (from the user's 3D-model screenshots).

Side view (side.png, facing left): one body layer (torso + head, the near arm painted out),
near and far legs, near and far arms. Every joint is skinned: pixels near a joint blend
between the bones on either side, so arms grow out of the shoulders, the neck bends, and
thighs stay joined to the hips. Back views: arms come down from the T-pose, skinned at the
shoulder. Same angle conventions as rig3 (PIL sense, + = counter-clockwise; facing left:
torso + leans forward, head + looks down, shoulder - swings forward/up, elbow - bends up,
thigh - knee forward, shin + folds back)."""
import math
from PIL import Image, ImageDraw, ImageFilter
import numpy as np
import cv2
import skin
import rig3

S = 0.5
def P(c, p): return (p[0] * S + c, p[1] * S + c)

class Side:
    HIP, KNEE, SHOULDER, ELBOW, NECK, HAND = (235, 610), (232, 875), (195, 320), (232, 545), (175, 262), (252, 762)
    ARM = rig3.Side.ARM
    ANKLE = (256, 1008)
    def __init__(self):
        raw = Image.open('side.png')
        noarm = self._inpaint(raw)
        self.src = raw
        sz = (round(raw.width * S), round(raw.height * S))
        self.PAD = pad = 360
        self.W, self.H = sz[0] + 2 * pad, sz[1] + 2 * pad
        def canvas(im):
            c = Image.new('RGBA', (self.W, self.H)); c.paste(im.resize(sz, Image.LANCZOS), (pad, pad)); return np.asarray(c).copy()
        orig = canvas(raw); body = canvas(noarm)
        import legfix
        legsrc = canvas(Image.fromarray(legfix.single_leg(noarm)))   # one clean leg (no overlap seams)
        yy, xx = np.mgrid[0:self.H, 0:self.W]
        sy = (yy - pad) / S; sx = (xx - pad) / S          # source (side.png) coordinates
        # round joint caps: both pieces of a joint share a soft disc around the pivot, so the
        # overlap looks the same at any angle (no straight cut shows when the joint bends)
        def disc(c, R): return 1 - skin.ss((np.hypot(sx - c[0], sy - c[1]) - R + 3) / 6.0)
        # arm: the painted-out region of the original, with a soft top so it melts into the shoulder
        armM = Image.new('L', raw.size, 0); ImageDraw.Draw(armM).polygon(self.ARM, fill=255)
        armM = np.asarray(Image.fromarray(np.asarray(armM)).resize(sz, Image.LANCZOS).filter(ImageFilter.GaussianBlur(1.5))).astype(float) / 255
        am = np.zeros((self.H, self.W)); am[pad:pad + sz[1], pad:pad + sz[0]] = armM
        top_fade = skin.ss((sy - 250) / 40.0)               # the arm layer fades in below the top of the shoulder
        a = orig.copy(); a[..., 3] = (a[..., 3] * am * top_fade).astype(np.uint8)
        self.arm = a
        # upper arm / forearm, overlapping softly at the elbow (along the arm's own axis)
        hx_, hy_ = self.HAND; shx, shy = self.SHOULDER; L = math.hypot(hx_ - shx, hy_ - shy); d = ((hx_ - shx) / L, (hy_ - shy) / L)
        sA = (sx - shx) * d[0] + (sy - shy) * d[1]; sE = (self.ELBOW[0] - shx) * d[0] + (self.ELBOW[1] - shy) * d[1]
        def part(img, w):
            o = img.copy(); o[..., 3] = (img[..., 3] * w).astype(np.uint8); return o
        upper_w = 1 - skin.ss((sA - sE - 6) / 22.0)
        fore_w = skin.ss((sA - sE + 16) / 22.0)
        # the top of the upper arm melts into the shoulder over a long, soft fade
        upper_w = upper_w * skin.ss((sA + 30) / 75.0)
        upper_w = np.maximum(upper_w, disc(self.ELBOW, 40) * skin.ss((sA + 30) / 75.0)); fore_w = np.maximum(fore_w, disc(self.ELBOW, 40))
        # round the fingertips: soften the hand end into a rounded silhouette
        sHand = (self.HAND[0] - shx) * d[0] + (self.HAND[1] - shy) * d[1]
        endz = skin.ss((sA - sHand + 70) / 30.0)
        al = Image.fromarray(a[..., 3]); rounded = np.asarray(al.filter(ImageFilter.GaussianBlur(3)).point(lambda v: 255 if v > 150 else 0)).astype(float) / 255
        a = a.copy(); a[..., 3] = (a[..., 3] * (1 - endz + endz * rounded)).astype(np.uint8)
        self.upper = part(a, upper_w); self.fore = part(a, fore_w)
        self.fupper = self.upper.copy(); self.fupper[..., :3] = (self.fupper[..., :3] * 0.82).astype(np.uint8)
        self.ffore = self.fore.copy(); self.ffore[..., :3] = (self.ffore[..., :3] * 0.82).astype(np.uint8)
        # the hip line: front of the thigh (low) up through the hip joint to the buttocks (lower at the back)
        hipline = np.interp(sx, [0, 110, 235, 330, 400], [575, 590, 612, 650, 660])
        # legs: everything below the hip line, faded in so the top tucks under the body
        lf = np.maximum(skin.ss((sy - hipline + 45) / 40.0), disc(self.HIP, 70) * skin.ss((sy - self.HIP[1] + 6) / 12.0))   # lower half: what's above the hip stays with the body
        l = legsrc.copy(); l[..., 3] = (l[..., 3] * lf).astype(np.uint8)
        self.leg = l
        kd = disc(self.KNEE, 62); ad = disc(self.ANKLE, 50)
        thw = np.maximum(1 - skin.ss((sy - self.KNEE[1] - 8) / 30.0), kd); snw = np.maximum(skin.ss((sy - self.KNEE[1] + 22) / 30.0), kd)
        snw = snw * np.maximum(1 - skin.ss((sy - self.ANKLE[1] - 6) / 20.0), ad)
        ftw = np.maximum(skin.ss((sy - self.ANKLE[1] + 10) / 20.0), ad)
        def part(img, w):
            o = img.copy(); o[..., 3] = (img[..., 3] * w).astype(np.uint8); return o
        self.thigh = part(l, thw); self.shin = part(l, snw); self.foot = part(l, ftw)
        dk = lambda im: np.concatenate([(im[..., :3] * 0.78).astype(np.uint8), im[..., 3:4]], 2)
        self.fthigh = dk(self.thigh); self.fshin = dk(self.shin); self.ffoot = dk(self.foot)
        # body: everything above the legs (head included), fading out over the thighs
        bf = 1 - skin.ss((sy - hipline + 4) / 18.0)
        bd = body.copy(); bd[..., 3] = (bd[..., 3] * bf).astype(np.uint8)
        self.body = bd
        # joint directions for the arm weights
        hx, hy = self.HAND; shx, shy = self.SHOULDER; L = math.hypot(hx - shx, hy - shy)
        self.adir = ((hx - shx) / L, (hy - shy) / L)
        self.s_el = (self.ELBOW[0] - shx) * self.adir[0] + (self.ELBOW[1] - shy) * self.adir[1]

    FARFOOT = [(90, 984), (214, 984), (218, 1022), (205, 1041), (90, 1046)]

    def _inpaint(self, raw):
        """Paint the near arm out of the body: a smooth fill of the surrounding fur, with fur
        texture borrowed from the body behind it blended in softly (no hard-edged patches).
        Also removes the far foot, so each leg layer carries exactly one foot."""
        a = np.asarray(raw).copy()
        m = Image.new('L', raw.size, 0); d = ImageDraw.Draw(m); d.polygon(self.ARM, fill=255)
        m = np.asarray(m.filter(ImageFilter.MaxFilter(7)))
        inside = (a[..., 3] > 0).astype(np.uint8)
        fill = cv2.inpaint(np.ascontiguousarray(a[..., :3]), ((m > 0) & (inside > 0)).astype(np.uint8) * 255, 9, cv2.INPAINT_TELEA)
        # texture: the fur 100 px further back (or 110 px forward where that falls outside the body)
        tex = np.roll(a[..., :3], -100, axis=1); ok = np.roll(inside, -100, axis=1)
        tex2 = np.roll(a[..., :3], 110, axis=1); ok2 = np.roll(inside, 110, axis=1)
        tex = np.where(ok[..., None] > 0, tex, np.where(ok2[..., None] > 0, tex2, fill))
        detail = tex.astype(np.float32) - cv2.GaussianBlur(tex.astype(np.float32), (0, 0), 6)
        painted = np.clip(fill.astype(np.float32) + detail * 0.9, 0, 255)
        soft = cv2.GaussianBlur((m > 0).astype(np.float32), (0, 0), 4)[..., None]
        out = a.astype(np.float32)
        out[..., :3] = painted * soft + out[..., :3] * (1 - soft)
        out = out.astype(np.uint8)
        # drop the far foot: it is drawn again (darker) by the far-leg layer, from the near foot
        ff = Image.new('L', raw.size, 0); ImageDraw.Draw(ff).polygon(self.FARFOOT, fill=255)
        ff = np.asarray(ff.filter(ImageFilter.GaussianBlur(1.5))).astype(np.float32) / 255
        out[..., 3] = (out[..., 3] * (1 - ff)).astype(np.uint8)
        return Image.fromarray(out)

    # ---- weights (functions of canvas coordinates of the *source* pixel)
    def src_xy(self, x, y): return (x - self.PAD) / S, (y - self.PAD) / S
    def w_arm(self, x, y):
        sx, sy = self.src_xy(x, y)
        s = (sx - self.SHOULDER[0]) * self.adir[0] + (sy - self.SHOULDER[1]) * self.adir[1]
        w1 = skin.ss((s + 15) / 70.0); w2 = skin.ss((s - self.s_el + 18) / 36.0)
        return [1 - w1, w1 * (1 - w2), w1 * w2]
    def w_leg(self, x, y):
        sx, sy = self.src_xy(x, y)
        w1 = skin.ss((sy - self.HIP[1] + 30) / 70.0); w2 = skin.ss((sy - self.KNEE[1] + 25) / 50.0)
        return [1 - w1, w1 * (1 - w2), w1 * w2]
    def w_body(self, x, y):
        sx, sy = self.src_xy(x, y)
        # the head: above and in front of the neck
        up = (self.NECK[1] - sy) + 0.35 * (self.NECK[0] - sx)
        wh = skin.ss((up + 40) / 120.0) * (1 - skin.ss((sx - 250) / 120.0))
        return [1 - wh, wh]

    def pose(self, torso=0, head=0, nsh=-8, nel=0, fsh=-4, fel=0, nth=0, nsn=0, fth=0, fsn=0, nft=0, fft=0):
        """Limbs move as solid pieces (they never crumple) and fade into the body at the shoulder
        and hip; the neck is skinned. Upper arm and forearm overlap at the elbow, thigh and shin at the knee."""
        c = self.PAD
        H_, K_, SH_, EL_, N_, AN_ = (P(c, p) for p in (self.HIP, self.KNEE, self.SHOULDER, self.ELBOW, self.NECK, self.ANKLE))
        T = skin.rot(torso, *H_)
        out = np.zeros((self.H, self.W, 4), np.float32)
        def over(layer):
            a = layer[..., 3:4].astype(np.float32) / 255
            out[..., :3] = layer[..., :3] * a + out[..., :3] * (1 - a)
            out[..., 3] = np.maximum(out[..., 3], layer[..., 3])
        def rigid(layer, M):
            f = layer.astype(np.float32); al = f[..., 3:4] / 255
            pm = np.concatenate([f[..., :3] * al, f[..., 3:4]], 2)
            r = cv2.warpAffine(pm, M[:2].astype(np.float32), (self.W, self.H), flags=cv2.INTER_LINEAR, borderValue=0)
            alr = r[..., 3:4] / 255
            o = np.zeros_like(layer); o[..., :3] = np.clip(np.where(alr > 1e-3, r[..., :3] / np.maximum(alr, 1e-3), 0), 0, 255); o[..., 3] = np.clip(r[..., 3], 0, 255)
            return o
        def arm(up, fo, sh, el):
            A = T @ skin.rot(sh, *SH_); F = A @ skin.rot(el, *EL_)
            return rigid(up, A), rigid(fo, F), A, F
        def leg(th_l, sn_l, ft_l, th, sn, ft, off=(0.0, 0.0)):
            O = np.eye(3); O[0, 2], O[1, 2] = off[0] * S, off[1] * S
            Th = O @ skin.rot(th, *H_); Sn = Th @ skin.rot(sn, *K_); Ft = Sn @ skin.rot(ft, *AN_)
            return rigid(th_l, Th), rigid(sn_l, Sn), rigid(ft_l, Ft)
        fu, ff, fA, fF = arm(self.fupper, self.ffore, fsh, fel)
        over(fu); over(ff)
        a1, a2, a3 = leg(self.fthigh, self.fshin, self.ffoot, fth, fsn, fft, (38, -6)); over(a3); over(a2); over(a1)
        b1, b2, b3 = leg(self.thigh, self.shin, self.foot, nth, nsn, nft); over(b3); over(b2); over(b1)
        over(skin.warp(self.body, [T, T @ skin.rot(head, *N_)], self.w_body))
        nu, nf, nA, nF = arm(self.upper, self.fore, nsh, nel)
        over(nu); over(nf)
        hand = P(c, self.HAND)
        info = dict(hip=H_, nhand=skin.apply(nF, *hand), fhand=skin.apply(fF, *hand))
        return Image.fromarray(np.clip(out, 0, 255).astype(np.uint8)), info

class Back:
    """Back views. The upper body is ONE skinned layer: each arm swings in from the A-pose about
    its shoulder, and the shoulder fur stretches with it (linear blend skinning), so there is no
    cut at the shoulder. The arm itself is a second layer warped by the very same field and drawn
    on top, so where it swings in over the body's side it stays in front."""
    def __init__(self, name, arms, lhang, rhang, crotch):
        raw = Image.open(name); self.src = raw
        sz = (round(raw.width * S), round(raw.height * S)); self.PAD = pad = 300
        self.W, self.H = sz[0] + 2 * pad, sz[1] + 2 * pad
        c = Image.new('RGBA', (self.W, self.H)); c.paste(raw.resize(sz, Image.LANCZOS), (pad, pad)); full = np.asarray(c).copy()
        yy, xx = np.mgrid[0:self.H, 0:self.W]; sx = (xx - pad) / S; sy = (yy - pad) / S
        self.armdefs = []; body_alpha = full[..., 3].astype(float); armlayer = np.zeros_like(full)
        for poly, sh, hand, hang in ((arms[0][0], arms[0][1], arms[0][2], lhang), (arms[1][0], arms[1][1], arms[1][2], rhang)):
            m = Image.new('L', raw.size, 0); ImageDraw.Draw(m).polygon(poly, fill=255)
            mw = np.asarray(m.filter(ImageFilter.MaxFilter(41)).resize(sz, Image.LANCZOS).filter(ImageFilter.GaussianBlur(10))).astype(float) / 255
            m = np.asarray(m.resize(sz, Image.LANCZOS).filter(ImageFilter.GaussianBlur(2))).astype(float) / 255
            M = np.zeros((self.H, self.W)); M[pad:pad + sz[1], pad:pad + sz[0]] = m
            MW = np.zeros((self.H, self.W)); MW[pad:pad + sz[1], pad:pad + sz[0]] = mw
            d = (hand[0] - sh[0], hand[1] - sh[1]); L = math.hypot(*d); d = (d[0] / L, d[1] / L)
            sA = (sx - sh[0]) * d[0] + (sy - sh[1]) * d[1]
            # the arm layer starts a little past the shoulder; the body keeps everything before it
            af = M * skin.ss((sA - 20) / 40.0)
            armlayer[..., :3] = np.where(af[..., None] > 0.01, full[..., :3], armlayer[..., :3])
            armlayer[..., 3] = np.maximum(armlayer[..., 3], (full[..., 3] * af)).astype(np.uint8)
            body_alpha = body_alpha * (1 - M * skin.ss((sA - 62) / 20.0))   # only once the arm layer is opaque
            self.armdefs.append(((sh[0] * S + pad, sh[1] * S + pad), d, sh, hang, MW))
        b = full.copy(); b[..., 3] = np.clip(body_alpha, 0, 255).astype(np.uint8)
        # legs, split at the crotch, so each foot can step during a turn
        cx_, cy_ = crotch
        legf = skin.ss((sy - cy_ + 70) / 40.0)
        self.legs = []
        for side in (0, 1):
            m = (sx < cx_) if side == 0 else (sx >= cx_)
            l = b.copy(); l[..., 3] = (b[..., 3] * legf * m).astype(np.uint8)
            self.legs.append(l)
        bodyf = 1 - skin.ss((sy - cy_ - 10) / 20.0)
        b[..., 3] = (b[..., 3] * bodyf).astype(np.uint8)
        self.body = b; self.armlayer = armlayer
        self.crotch = (cx_ * S + pad, cy_ * S + pad)
        self.hipx = ((arms[0][1][0] + arms[1][1][0]) / 2) * S + pad

    def _weights(self):
        pad = self.PAD; defs = self.armdefs
        def w(x, y):
            sx = (x - pad) / S; sy = (y - pad) / S
            ws = []
            for piv, d, sh, hang, MW in defs:
                sA = (sx - sh[0]) * d[0] + (sy - sh[1]) * d[1]
                # the arm (dilated into the armpit gap), and everything outward of its axis, belongs to the
                # arm (so the inverse warp has one answer and nothing is left behind)
                t = (d[0] * (sy - sh[1]) - d[1] * (sx - sh[0])) * (-1 if d[0] > 0 else 1)
                xi = np.clip(np.round(x).astype(int), 0, self.W - 1); yi = np.clip(np.round(y).astype(int), 0, self.H - 1)
                ws.append(np.maximum(MW[yi, xi], skin.ss(t / 30.0)) * skin.ss((sA + 50) / 110.0))
            tot = ws[0] + ws[1]; sc = np.where(tot > 1, 1 / np.maximum(tot, 1e-6), 1.0)
            ws = [ws[0] * sc, ws[1] * sc]
            return [1 - ws[0] - ws[1]] + ws
        return w

    def _arm_weights(self):
        """For the arm layer alone: each half of the picture follows its arm (ramping in from the
        shoulder exactly as the body's field does inside the arm), so a hand that swings in front of
        the hips is still found."""
        pad = self.PAD; defs = self.armdefs; mid = self.hipx
        def w(x, y):
            sx = (x - pad) / S; sy = (y - pad) / S; ws = []
            for k, (piv, d, sh, hang, MW) in enumerate(defs):
                sA = (sx - sh[0]) * d[0] + (sy - sh[1]) * d[1]
                half = (x < mid) if k == 0 else (x >= mid)
                ws.append(skin.ss((sA + 50) / 110.0) * half)
            return [1 - ws[0] - ws[1]] + ws
        return w

    def pose(self, tuck=0.0, lift=(0.0, 0.0)):
        """tuck: hands in toward the body; lift: how far each foot (left, right as seen) is raised in a step."""
        out = np.zeros((self.H, self.W, 4), np.float32)
        def over(l):
            a = l[..., 3:4].astype(np.float32) / 255
            out[..., :3] = l[..., :3] * a + out[..., :3] * (1 - a); out[..., 3] = np.maximum(out[..., 3], l[..., 3])
        for side, l in enumerate(self.legs):
            u = lift[side]
            if u > 0.01:
                # a stepping foot comes up and in a little, the knee bending toward the viewer's side
                dx = (-1 if side == 0 else 1) * 3 * u; dy = -22 * u * S
                ang = (5 if side == 0 else -5) * u
                M = skin.rot(ang, *self.crotch); M[0, 2] += dx; M[1, 2] += dy
                f = l.astype(np.float32); al = f[..., 3:4] / 255
                pm = np.concatenate([f[..., :3] * al, f[..., 3:4]], 2)
                r = cv2.warpAffine(pm, M[:2].astype(np.float32), (self.W, self.H), flags=cv2.INTER_LINEAR, borderValue=0)
                alr = r[..., 3:4] / 255; l = np.zeros_like(l); l[..., :3] = np.clip(np.where(alr > 1e-3, r[..., :3] / np.maximum(alr, 1e-3), 0), 0, 255); l[..., 3] = np.clip(r[..., 3], 0, 255)
            over(l)
        bones = [np.eye(3)]
        for piv, d, sh, hang, MW in self.armdefs:
            ang = hang + (8 * tuck if hang > 0 else -8 * tuck)
            bones.append(skin.rot(ang, *piv))
        w = self._weights()
        over(skin.warp(self.body, bones, w))
        over(skin.warp(self.armlayer, bones, self._arm_weights()))
        return Image.fromarray(out.astype(np.uint8)), dict(hip=(self.hipx, 0))

def back():
    L = [(300, 240), (330, 80), (-40, 380), (-40, 640), (90, 640), (110, 560)]
    R = [(906 - x, y) for x, y in L]
    return Back('back.png', ((L, (300, 205), (40, 500)), (R, (606, 205), (866, 500))), 20, -20, (453, 600))
def back34():
    L = [(330, 330), (372, 150), (-40, 330), (-40, 700), (90, 700), (140, 620)]
    R = [(690, 380), (640, 150), (1010, 300), (1010, 700), (900, 700), (860, 640)]
    return Back('back34.png', ((L, (320, 235), (60, 580)), (R, (690, 268), (930, 590))), 18, -18, (496, 690))
