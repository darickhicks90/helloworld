"""Faces turned toward the viewer, for the side view.

His head and shoulders are one dome; a head turn moves the face across the front of that dome.
The face region of a 3/4-front screenshot of the model is cut out (background keyed off),
scaled to the side view (model height -> side.png height), colour-matched to the side view's fur,
and laid over the front of the side view's dome with a soft elliptical mask. Inside that ellipse
the side view's own profile face is replaced, including its silhouette (the profile nose and lips
go away where the turned face doesn't reach)."""
import numpy as np, cv2
from PIL import Image

def key(shot):
    a = np.asarray(shot.convert('RGB')).astype(np.float32)
    s = a.sum(2); warm = a[..., 0] - a[..., 2]
    al = np.clip((s - 230) / 60.0, 0, 1) * np.clip((warm - 8) / 12.0, 0, 1)
    # anything enclosed by the model (the dark pupils, shadowed creases) is part of him, not background
    bgm = (al < 0.5).astype(np.uint8); h, w = bgm.shape
    ff = bgm.copy(); mask = np.zeros((h + 2, w + 2), np.uint8)
    for seed in ((0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)):
        if ff[seed[1], seed[0]] == 1: cv2.floodFill(ff, mask, seed, 2)
    al = np.where((bgm == 1) & (ff != 2), 1.0, al)
    al = cv2.GaussianBlur(al.astype(np.float32), (0, 0), 0.8)
    return a, al

def make(side_rgba, shot, fig_top, fig_h, src_box, ell, offset, front=(170, 28, 330)):
    """side_rgba: HxWx4 float (side.png space). shot: PIL screenshot.
    src_box: (x0,y0,x1,y1) in shot px; ell: (cx,cy,rx,ry) in shot px; offset: side x of shot x=src_box[0]."""
    H, W = side_rgba.shape[:2]
    k = H / fig_h
    a, al = key(shot)
    x0, y0, x1, y1 = src_box
    patch = a[y0:y1, x0:x1]; pal = al[y0:y1, x0:x1]
    pw, ph = int((x1 - x0) * k), int((y1 - y0) * k)
    patch = cv2.resize(patch, (pw, ph), interpolation=cv2.INTER_CUBIC); pal = cv2.resize(pal, (pw, ph), interpolation=cv2.INTER_LINEAR)
    yy, xx = np.mgrid[0:ph, 0:pw]
    sx = xx / k + x0; sy = yy / k + y0                     # back to shot coords
    cx, cy, rx, ry = ell
    d = np.sqrt(((sx - cx) / rx) ** 2 + ((sy - cy) / ry) ** 2)
    E = np.clip((1.0 - d) / 0.25, 0, 1); E = E * E * (3 - 2 * E)
    # place
    oy = int((y0 - fig_top) * k); ox = int(offset)
    out = side_rgba.copy()
    ys0, xs0 = max(0, oy), max(0, ox); ys1, xs1 = min(H, oy + ph), min(W, ox + pw)
    py0, px0 = ys0 - oy, xs0 - ox; py1, px1 = py0 + (ys1 - ys0), px0 + (xs1 - xs0)
    P = patch[py0:py1, px0:px1]; PA = pal[py0:py1, px0:px1]; EE = E[py0:py1, px0:px1]
    S = out[ys0:ys1, xs0:xs1]
    # colour match: fur of the patch vs fur of the side view, in the ellipse's outer ring
    ring = (EE > 0.05) & (EE < 0.7) & (PA > 0.9) & (S[..., 3] > 230)
    if ring.sum() > 50:
        for c in range(3):
            pm, ps = P[..., c][ring].mean(), P[..., c][ring].std() + 1e-3
            sm, sd = S[..., c][ring].mean(), S[..., c][ring].std() + 1e-3
            P[..., c] = (P[..., c] - pm) * (sd / ps) * 0.9 + sm
    Pc = np.clip(P, 0, 255)
    sa = S[..., 3] / 255
    def ss(v): v = np.clip(v, 0, 1); return v * v * (3 - 2 * v)
    PAc = ss((PA - 0.6) / 0.4)
    # the outline: along the front of the face (left of x=front[0], between y=front[1] and front[2])
    # it is the turned face's own; everywhere else the side view keeps its outline
    gy, gx = np.mgrid[ys0:ys1, xs0:xs1]
    L = (1 - ss((gx - front[0]) / 40.0)) * ss((gy - front[1]) / 30.0) * (1 - ss((gy - front[2]) / 30.0))
    na = sa * (1 - L) + (sa * (1 - EE) + PAc * EE) * L
    na = np.where(L < 0.01, sa, na)
    # where the old profile still sticks out in front of the turned face, take it away
    na = na * (1 - L * (1 - ss((PA - 0.2) / 0.5)))
    PAc = ss((PA - 0.6) / 0.4)          # colour only from solid fur (keyed edges carry background)
    w1 = sa * (1 - EE * PAc); w2 = PAc * EE
    rgb = (S[..., :3] * w1[..., None] + Pc * w2[..., None]) / np.maximum(w1 + w2, 1e-3)[..., None]
    S[..., :3] = rgb; S[..., 3] = na * 255
    out[ys0:ys1, xs0:xs1] = S
    return out
