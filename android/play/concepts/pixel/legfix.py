import numpy as np, cv2
def ss(x): x = np.clip(x, 0, 1); return x * x * (3 - 2 * x)
def single_leg(img, y0=560, y1=1000):
    """Rebuild the side view's leg (source coordinates) as ONE leg. The source shows both legs
    overlapping plus the painted-out arm, which leaves seams that read as extra legs once the
    legs separate. Each row keeps its silhouette, gets one rounded shade (light at the front,
    darker at the back) and fur detail copied from the clean front strip of the same leg."""
    a = np.asarray(img).astype(np.float32); al = a[..., 3] / 255; rgb = a[..., :3]
    H, W = al.shape
    low = cv2.GaussianBlur(rgb * al[..., None], (0, 0), 14) / np.maximum(cv2.GaussianBlur(al, (0, 0), 14), 1e-3)[..., None]
    detail = rgb - cv2.GaussianBlur(rgb, (0, 0), 4)
    out = rgb.copy()
    # one base colour per row from the leg's front, smoothed down the leg (no dark band at the knee)
    bases = np.zeros((H, 3), np.float32); have = np.zeros(H, bool)
    for y in range(y0, min(H, y1 + 40)):
        xs = np.nonzero(al[y] > 0.5)[0]
        if len(xs) >= 8: bases[y] = low[y, xs.min() + 8:xs.min() + 50].mean(0); have[y] = True
    yi = np.nonzero(have)[0]
    for ch in range(3): bases[yi, ch] = np.interp(yi, yi, bases[yi, ch])
    sm = cv2.GaussianBlur(bases[None], (0, 0), sigmaX=60, sigmaY=0.1)[0] if False else None
    k = np.exp(-0.5 * (np.arange(-120, 121) / 45.0) ** 2)
    num = np.stack([np.convolve(bases[:, c] * have, k, 'same') for c in range(3)], 1); den = np.convolve(have.astype(float), k, 'same')[:, None]
    bases = num / np.maximum(den, 1e-6)
    for y in range(y0, min(H, y1 + 40)):
        xs = np.nonzero(al[y] > 0.5)[0]
        if len(xs) < 8: continue
        x0, x1 = xs.min(), xs.max(); wd = x1 - x0
        u = (np.arange(W) - x0) / max(1, wd)
        base = bases[y]
        shade = 1.04 - 0.26 * np.clip(np.abs(u - 0.3) / 0.7, 0, 1) ** 1.5
        # fur: tile the front 56 px strip across the row (mirrored so the tiles meet without a seam)
        n = 44; fur = 0
        for ph in (0, n // 2):                            # two tile sets half a tile apart, cross-faded: no seams
            idx = np.arange(W) - x0 - ph; tile = idx // n; r = idx % n
            dy = ((tile * 37 + ph) % 23) - 11             # each tile borrows from a different height
            sy_ = np.clip(y + dy, 0, H - 1); sx_ = np.clip(x0 + 14 + r, 0, W - 1)
            wt = 1 - np.abs(r / n - 0.5) * 2
            fur = fur + detail[sy_, sx_] * wt[:, None]
        row = base[None] * shade[:, None] + fur * 1.25
        w = ss((y - y0) / 40.0) * (1 - ss((y - y1) / 30.0))
        out[y] = row * w + rgb[y] * (1 - w)
    res = a.copy(); res[..., :3] = np.clip(out, 0, 255)
    # back edge of the near leg (traced by hand over the model): everything behind it is the
    # far leg and the buttock, which the far-leg layer and the body draw instead
    by = [600, 612, 640, 660, 700, 740, 800, 860, 920, 960, 1000, 1030]
    bx = [345, 338, 300, 262, 250, 250, 256, 294, 314, 310, 292, 284]
    yy, xx = np.mgrid[0:H, 0:W]
    edge = np.interp(yy, by, bx)
    keep = 1 - ss((xx - edge + 2) / 5.0)
    on = (1 - ss((yy - 1014) / 14.0)) * ss((yy - 590) / 15.0)     # only between the hip and the ankle
    res[..., 3] = res[..., 3] * (1 - on * (1 - keep))
    return res.astype(np.uint8)
