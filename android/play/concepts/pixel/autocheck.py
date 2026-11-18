import sys, numpy as np, cv2
src = open('story3.py').read(); src = src[:src.index('# ---------------- simulate')]
sys.argv = ['x', '../auditout']; g = {'__name__': 'chk'}; exec(src, g)
import rig4
side = rig4.Side(); b = rig4.back(); b34 = rig4.back34()
chan, CH, DUR, view_at = g['chan'], g['CH'], g['DUR'], g['view_at']
worst = []
t = 0.0
while t < DUR:
    v = {c: chan(c, t) for c in CH}; view, face = view_at(t)
    if view == 'S':
        im, _ = side.pose(**{k: v[k] for k in ('torso','head','nsh','nel','fsh','fel','nth','nsn','fth','fsn')})
    else:
        im, _ = (b if view == 'B' else b34).pose(v['tuck'], (v['bl'], v['br']))
    a = np.asarray(im).astype(np.float32); al = a[..., 3] / 255
    solid = (al > 0.5).astype(np.uint8)
    # inside = well within the silhouette (closed, then eroded 6 px), so edge softness doesn't count
    closed = cv2.morphologyEx(solid, cv2.MORPH_CLOSE, np.ones((9, 9), np.uint8))
    inner = cv2.erode(closed, np.ones((13, 13), np.uint8)) > 0
    holes = inner & (al < 0.85)
    n_holes = int(holes.sum())
    # texture: fur detail strength in 24px tiles inside the body vs the body's median
    lum = a[..., :3].mean(2); det = np.abs(lum - cv2.GaussianBlur(lum, (0, 0), 3))
    tiles = []
    H, W = lum.shape
    for y in range(0, H - 24, 24):
        for x in range(0, W - 24, 24):
            if inner[y:y + 24, x:x + 24].all(): tiles.append((det[y:y + 24, x:x + 24].mean(), x, y))
    med = np.median([q[0] for q in tiles]) if tiles else 0
    flat = [q for q in tiles if q[0] < 0.45 * med]
    worst.append((n_holes, len(flat), round(t / 1.5, 2), view))
    if n_holes > 30 or len(flat) > 2:
        print('t=%.2f %s holes=%d flat_tiles=%d (of %d) %s' % (t / 1.5, view, n_holes, len(flat), len(tiles), [(x, y) for _, x, y in flat[:4]]))
        if n_holes > 30:
            ys, xs = np.nonzero(holes); print('   hole bbox', xs.min(), xs.max(), ys.min(), ys.max())
    t += 0.25 * 1.5
print('checked', len(worst), 'poses; max holes', max(w[0] for w in worst), 'max flat tiles', max(w[1] for w in worst))
