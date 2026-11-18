"""Fast, flicker-free pixel-art conversion for animation frames: the same look as
render.snes_creature (fur ramp, eyes, outline), but vectorised, with the light, the
dither pattern and the brightness fixed to the scene rather than to each sprite."""
import numpy as np
from PIL import Image, ImageFilter
import render as R

FUR = np.array(R.FUR); SCL = np.array(R.SCLERA); IRI = np.array(R.IRIS); OUT = np.array(R.OUT)
B4 = R.B4

def lum_offset(src):
    """Brightness correction for a source view, so every view matches the front view."""
    a = np.asarray(src.resize((src.width // 4, src.height // 4), Image.LANCZOS)).astype(float)
    m = a[..., 3] > 110
    lum = (0.3 * a[..., 0] + 0.55 * a[..., 1] + 0.15 * a[..., 2]) / 255
    return R.REF_LUM - lum[m].mean()

def pixelize(hi, K, ox, oy, loff, eyes_above=10_000):
    """hi: hi-res RGBA pose; K: scale; (ox, oy): where its top-left lands in the scene (whole pixels);
    loff: lum_offset of the source view; eyes_above: scene y above which dark pixels may be pupils."""
    w, h = max(1, round(hi.width * K)), max(1, round(hi.height * K))
    a = np.asarray(hi.resize((w, h), Image.LANCZOS)).astype(float)
    rgb = a[..., :3]; mask = a[..., 3] > 110
    m8 = Image.fromarray((mask * 255).astype(np.uint8))
    blur = np.asarray(m8.filter(ImageFilter.GaussianBlur(5))).astype(float) / 255
    shift = np.asarray(m8.transform((w, h), Image.AFFINE, (1, 0, 3, 0, 1, 3)).filter(ImageFilter.GaussianBlur(3))).astype(float) / 255
    rim = np.clip(blur - shift, 0, 1); dark = np.clip(1 - blur, 0, 1)
    yy, xx = np.mgrid[0:h, 0:w]; wx = xx + ox; wy = yy + oy
    lum = (0.3 * rgb[..., 0] + 0.55 * rgb[..., 1] + 0.15 * rgb[..., 2]) / 255 + loff
    lum = (lum - 0.33) / 0.5
    lum = lum + 0.10 - 0.18 * ((wx - 300) / 160.0) - 0.10 * ((wy - 40) / 190.0) + 0.25 * rim - 0.35 * dark
    det = lum - np.asarray(Image.fromarray(np.uint8(np.clip(lum, 0, 1) * 255)).filter(ImageFilter.GaussianBlur(2))).astype(float) / 255
    lum = lum + det * 0.9 + B4[wy % 4, wx % 4] * 0.07
    r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]
    sat = (rgb.max(-1) - rgb.min(-1)) / np.maximum(rgb.max(-1), 1)
    col = FUR[np.clip((lum * 8 + 0.5).astype(int), 0, 8)]
    pupil = ((r + g + b) / 3 < 55) & (wy < eyes_above)
    col = np.where(pupil[..., None], OUT, col)
    iris = (sat > 0.5) & (r > 120) & (g < 120)
    col = np.where(iris[..., None], IRI[np.clip(((r + g) / 2 / 255 * 6 - 0.6).astype(int), 0, 4)], col)
    scl = (r > 150) & (sat < 0.22) & ((r + g + b) / 3 > 170)
    col = np.where(scl[..., None], SCL[np.clip(((r - 150) / 105 * 3).astype(int), 0, 2)], col)
    out = np.zeros((h + 2, w + 2, 4), np.uint8)
    out[1:-1, 1:-1, :3] = col; out[1:-1, 1:-1, 3] = np.where(mask, 255, 0)
    # outline: lit (warm) on the upper-left edges, dark ink elsewhere
    M = np.zeros((h + 2, w + 2), bool); M[1:-1, 1:-1] = mask
    right = np.roll(M, -1, 1); down = np.roll(M, -1, 0); left = np.roll(M, 1, 1); up = np.roll(M, 1, 0)
    edge = ~M & (right | down | left | up)
    lit = edge & (right | down) & ~(left | up)
    out[edge, :3] = OUT; out[lit, :3] = FUR[1]; out[edge, 3] = 255
    return Image.fromarray(out), ox - 1, oy - 1
