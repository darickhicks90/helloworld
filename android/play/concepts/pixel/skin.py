"""2-D linear-blend skinning for cut-out images: every pixel follows a weighted blend of
bone transforms, so limbs bend out of the body smoothly instead of hinging on a seam.
The inverse map is found by fixed-point iteration and sampled with cv2.remap."""
import math
import numpy as np
import cv2

def rot(deg, cx, cy):
    """Rotation matching PIL's Image.rotate(deg, center=(cx, cy)) as a 3x3 matrix on (x, y, 1)."""
    a = math.radians(deg); c, s = math.cos(a), math.sin(a)
    return np.array([[c, s, cx - c * cx - s * cy], [-s, c, cy + s * cx - c * cy], [0, 0, 1.0]])

def ss(x):
    x = np.clip(x, 0.0, 1.0); return x * x * (3 - 2 * x)

def apply(M, x, y): return M[0, 0] * x + M[0, 1] * y + M[0, 2], M[1, 0] * x + M[1, 1] * y + M[1, 2]

def warp(img, bones, weights, iters=8, pad=4):
    """img: HxWx4 uint8 (canvas-sized). bones: list of 3x3. weights(x, y) -> list of arrays (sum 1).
    Returns the warped HxWx4 uint8 image."""
    H, W = img.shape[:2]
    a = img[..., 3] > 0
    ys, xs = np.nonzero(a)
    if len(xs) == 0: return img.copy()
    x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
    corners = np.array([[x0, y0], [x1, y0], [x0, y1], [x1, y1]], float)
    ox, oy = [], []
    for B in bones:
        cx, cy = apply(B, corners[:, 0], corners[:, 1]); ox += list(cx); oy += list(cy)
    bx0 = int(max(0, min(ox) - pad)); bx1 = int(min(W, max(ox) + pad + 1)); by0 = int(max(0, min(oy) - pad)); by1 = int(min(H, max(oy) + pad + 1))
    if bx1 <= bx0 or by1 <= by0: return np.zeros_like(img)
    py, px = np.mgrid[by0:by1, bx0:bx1].astype(np.float64)
    qx, qy = px.copy(), py.copy()
    for _ in range(iters):
        ws = weights(qx, qy)
        m00 = sum(w * B[0, 0] for w, B in zip(ws, bones)); m01 = sum(w * B[0, 1] for w, B in zip(ws, bones)); m02 = sum(w * B[0, 2] for w, B in zip(ws, bones))
        m10 = sum(w * B[1, 0] for w, B in zip(ws, bones)); m11 = sum(w * B[1, 1] for w, B in zip(ws, bones)); m12 = sum(w * B[1, 2] for w, B in zip(ws, bones))
        det = m00 * m11 - m01 * m10
        det = np.where(np.abs(det) < 1e-6, 1e-6, det)
        dx = px - m02; dy = py - m12
        qx = (m11 * dx - m01 * dy) / det; qy = (-m10 * dx + m00 * dy) / det
    # premultiplied alpha so edges don't pick up dark fringes
    f = img.astype(np.float32); al = f[..., 3:4] / 255.0
    pm = np.concatenate([f[..., :3] * al, f[..., 3:4]], axis=2)
    r = cv2.remap(pm, qx.astype(np.float32), qy.astype(np.float32), cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=0)
    alr = r[..., 3:4] / 255.0
    rgb = np.where(alr > 1e-3, r[..., :3] / np.maximum(alr, 1e-3), 0)
    out = np.zeros_like(img)
    out[by0:by1, bx0:bx1, :3] = np.clip(rgb, 0, 255).astype(np.uint8)
    out[by0:by1, bx0:bx1, 3] = np.clip(r[..., 3], 0, 255).astype(np.uint8)
    return out

def point(bones, weights, x, y):
    ws = weights(np.array([x], float), np.array([y], float))
    X = sum(w[0] * apply(B, x, y)[0] for w, B in zip(ws, bones)); Y = sum(w[0] * apply(B, x, y)[1] for w, B in zip(ws, bones))
    return float(X), float(Y)
