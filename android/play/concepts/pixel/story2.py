"""5 Eye Yeti title story, v2. The yeti minds his own business in a starlit clearing:
watches a firefly, swipes with both hands and misses, steps after it, lunges and misses
again as it zips over his head, then slowly cups his hands around it and claps them shut.
He peeks at it, turns around (through his back, never toward the viewer), walks a couple
of steps, kneels one knee at a time like a person, scratches a little hole, lays the
firefly in, covers it with three scoops, pats it down, looks at it a moment, gets up the
way people do (front foot, push, stand), turns back and idles while a new one drifts in.

Channels are keyed separately and eased; his head follows the firefly when he watches it.
Writes title_bg.png, title_fg.png, yeti_frames.png and title_story.json into OUT."""
import sys, os, math, json, random
sys.path.insert(0, '.')
from PIL import Image
import numpy as np
import rig3, rig4, pxfast

OUT = sys.argv[1]
FPS = 15
FEET = 228
# ---------------- background (the clearing; no moon) and foreground grass
src = open('yeti_dig.py').read()
ns = {}
exec(src[:src.index("# ---------------- frames")].replace("import siderig\n", "").replace("OUT = sys.argv[1]", "OUT = '" + OUT + "'").replace(
    "for y in range(FEET - 7, FEET + 5):", "for y in range(0):").replace("for _ in range(30):   # loose clods", "for _ in range(0):   # loose clods").replace(
    "FEET, BODYX = 228, 380", "FEET, BODYX = 228, 382"), ns)
W, H, GRASS, rnd = ns['W'], ns['H'], ns['GRASS'], random.Random(21)

# ---------------- keyframes
CH = ['torso', 'head', 'nsh', 'nel', 'fsh', 'fel', 'nth', 'nsn', 'fth', 'fsn', 'hipx', 'lift', 'tuck', 'look', 'breath']
REST = dict(torso=0, head=0, nsh=-8, nel=0, fsh=-4, fel=0, nth=0, nsn=0, fth=0, fsn=0, hipx=392, lift=0, tuck=0, look=1, breath=1)
keys = {c: [] for c in CH}
views = []        # (time, view, face)
def K(t, **kw):
    for c, v in kw.items(): keys[c].append((t, v))
def V(t, view, face): views.append((t, view, face))
HOLD = dict(nsh=-70, nel=-118, fsh=-64, fel=-110)
STAND_LEGS = dict(nth=0, nsn=0, fth=0, fsn=0)

K(0.0, **REST); V(0.0, 'S', 'L')
# watching; then miss 1: crouch back (anticipation), two-handed swipe up-forward, clap on nothing
K(1.0, torso=1, nsh=-8, fsh=-4, look=1, breath=1, **STAND_LEGS)
K(1.28, torso=-5, nsh=18, nel=-10, fsh=24, fel=-10, nth=-12, nsn=24, fth=-12, fsn=24, breath=0)
K(1.48, torso=13, nsh=-128, nel=-22, fsh=-118, fel=-32, nth=4, nsn=0, fth=4, fsn=0)
K(1.6, nel=-8, fel=-14)
K(2.05, torso=2, nsh=-12, nel=-6, fsh=-6, fel=-4, nth=0, fth=0, nsn=0, fsn=0)
# two steps after it (alternating legs, a little bob)
def step(t0, dur, x0, x1, lead):
    a, b = ('nth', 'nsn'), ('fth', 'fsn')
    sw, st = (a, b) if lead == 'n' else (b, a)
    K(t0, hipx=x0, **{sw[0]: 0, sw[1]: 0, st[0]: 0, st[1]: 0}, lift=0)
    K(t0 + dur * 0.5, **{sw[0]: -20, sw[1]: 34, st[0]: 8, st[1]: 4}, lift=2)
    K(t0 + dur, hipx=x1, **{sw[0]: -16, sw[1]: 6, st[0]: 16, st[1]: 8}, lift=0)
step(2.05, 0.42, 392, 378, 'n'); step(2.47, 0.42, 378, 364, 'f')
K(2.95, **STAND_LEGS)
# miss 2: lunge low with the near arm (front leg bends, back leg straight), it zips up over his head
K(3.05, torso=3, nsh=-10, fsh=-6)
K(3.4, torso=34, nsh=-72, nel=-4, fsh=-48, fel=-8, fth=-46, fsn=46, nth=18, nsn=6, hipx=356)
K(3.55, torso=36, nsh=-80)
K(4.05, torso=4, nsh=-10, nel=0, fsh=-6, fel=0, fth=0, fsn=0, nth=0, nsn=0, hipx=362)
# patient catch: slowly bring both hands up around it, then clap
K(4.3, nsh=-12, fsh=-8)
K(5.15, nsh=-150, nel=-44, fsh=-142, fel=-52, torso=-3)
K(5.32, nel=-12, fel=-20)
# bring the cupped hands down, look at them, a little happy bounce
K(5.6, nsh=-140, fsh=-132)
K(6.15, look=1, **HOLD, torso=6, lift=0)
K(6.3, look=0, head=16, lift=3)
K(6.45, lift=0)
K(6.95, head=22, torso=8)
# turn around through his back (never toward the viewer)
K(7.0, head=6, torso=0)
V(7.05, 'B34', 'L'); V(7.2, 'B', 'L'); V(7.35, 'B34', 'R'); V(7.5, 'S', 'R')
K(7.05, tuck=0.3); K(7.5, tuck=0.3); K(7.55, tuck=0)
# two steps to a spot of his choosing (facing right now; hipx grows)
step(7.6, 0.45, 362, 376, 'n'); step(8.05, 0.45, 376, 390, 'f')
K(8.5, **STAND_LEGS, torso=2)
# kneel like a person: near knee goes down first (half kneel), then the other, then sit back
K(9.05, nth=6, nsn=96, fth=-74, fsn=78, torso=10, head=12)
K(9.55, fth=-6, fsn=94, torso=14)
K(9.95, nth=-58, nsn=152, fth=-58, fsn=152, torso=30, head=22)
# scratch a little hole with the near hand (other hand still holds it)
K(10.15, torso=40, nsh=-34, nel=-2)
for i in range(2):
    t = 10.15 + i * 0.32
    K(t + 0.16, nsh=-6, nel=-22); K(t + 0.32, nsh=-34, nel=-2)
# lay it in: both hands down to the hole, open
K(11.15, torso=44, nsh=-26, nel=0, fsh=-24, fel=-2, head=26)
K(11.6, torso=40, nsh=-12, fsh=-8)
# three scoops, alternating hands, pulling earth over it
for i, (sh, el) in enumerate((('nsh', 'nel'), ('fsh', 'fel'), ('nsh', 'nel'))):
    t = 11.6 + i * 0.6
    K(t + 0.25, **{sh: -60, el: -12}, torso=44); K(t + 0.6, **{sh: -10, el: 0}, torso=40)
# pat, pat
K(13.6, nsh=-28, nel=-26, fsh=-22, fel=-20); K(13.75, nsh=-24, nel=-4, fsh=-20, fel=-2)
K(13.95, nsh=-28, nel=-26); K(14.1, nsh=-24, nel=-4)
# a moment, looking at it
K(14.7, head=26, torso=40, nsh=-14, nel=0, fsh=-8, fel=0)
# get up: front foot planted, lean forward, push, stand
K(15.15, fth=-74, fsn=78, nth=6, nsn=96, torso=24, head=10)
K(15.65, fth=-30, fsn=36, nth=8, nsn=40, torso=14, head=0)
K(16.05, **STAND_LEGS, torso=2, nsh=-8, fsh=-4)
# turn back, through his back again
V(16.15, 'B34', 'R'); V(16.3, 'B', 'R'); V(16.45, 'B34', 'L'); V(16.6, 'S', 'L')
K(16.6, tuck=0)
# idle while a new firefly drifts down; he looks up at it
K(16.7, look=1, breath=1)
K(18.0, hipx=390)
DUR = 19.2
K(DUR, **REST)

def ease(u): return u * u * (3 - 2 * u)
def chan(c, t):
    ks = keys[c]
    if not ks: return REST[c]
    if t <= ks[0][0]: return ks[0][1]
    for (t0, v0), (t1, v1) in zip(ks, ks[1:]):
        if t0 <= t <= t1: return v0 + (v1 - v0) * ease((t - t0) / max(1e-6, t1 - t0))
    return ks[-1][1]
for c in CH: keys[c].sort(key=lambda kv: kv[0])
views.sort()
def view_at(t):
    v = views[0]
    for x in views:
        if x[0] <= t: v = x
    return v[1], v[2]

# ---------------- the firefly: free (with jitter), dodging, caught, carried, laid in the hole
FK = [(0.0, 352, 78), (1.0, 348, 84), (1.36, 344, 70), (1.62, 300, 150), (2.4, 296, 142), (3.0, 292, 150), (3.42, 300, 150), (3.62, 340, 36), (4.2, 352, 28), (5.15, 348, 34)]
def free_fly(t):
    for (t0, x0, y0), (t1, x1, y1) in zip(FK, FK[1:]):
        if t0 <= t <= t1:
            u = ease((t - t0) / (t1 - t0)); x, y = x0 + (x1 - x0) * u, y0 + (y1 - y0) * u
            return x + 3.0 * math.sin(t * 5.3) + 1.5 * math.sin(t * 11.0), y + 2.2 * math.sin(t * 6.7 + 1)
    return FK[-1][1] + 3 * math.sin(t * 5.3), FK[-1][2] + 2 * math.sin(t * 6.7)
T_CLAP, T_PLACE, T_COVER0, T_COVERED = 5.3, 11.15, 11.6, 13.4
SCOOPS = [11.6 + i * 0.6 + 0.45 for i in range(3)]
DIGS = [10.15 + i * 0.32 + 0.16 for i in range(2)]

# ---------------- simulate and render
side = rig4.Side(); b = rig4.back(); b34 = rig4.back34()
LO = {'S': pxfast.lum_offset(side.src), 'B': pxfast.lum_offset(b.src), 'B34': pxfast.lum_offset(b34.src)}
KV = {'S': 0.17 / rig3.S, 'B': 189 / 902 / rig3.S, 'B34': 189 / 1094 / rig3.S}
cache = {}; sprites = []; steps = []; hands = []; eyes = []
N = int(DUR * FPS)
prev_fly = None
for n in range(N):
    t = n / FPS
    v = {c: chan(c, t) for c in CH}
    view, face = view_at(t)
    # breathing while idle
    v['torso'] += v['breath'] * 1.3 * math.sin(2 * math.pi * t / 3.2)
    fx, fy = free_fly(t)
    key_pose = None
    if view == 'S':
        # head follows the firefly while he watches it (needs the eye position: one cheap pass with head 0)
        if v['look'] > 0.01 and t < T_CLAP:
            _, info0 = side.pose(**{k: v[k] for k in ('torso', 'nsh', 'nel', 'fsh', 'fel', 'nth', 'nsn', 'fth', 'fsn')}) if False else (None, None)
        pose = {k: v[k] for k in ('torso', 'head', 'nsh', 'nel', 'fsh', 'fel', 'nth', 'nsn', 'fth', 'fsn')}
        if v['look'] > 0.01 and t < T_CLAP:
            # approximate eye: above the hip by his height; angle to the firefly (left-facing frame)
            ex = v['hipx'] + (-1 if face == 'L' else 1) * 30; ey = FEET - 158 + (v['torso'] * 0.6)
            dx = (ex - fx) if face == 'L' else (fx - ex); dy = fy - ey
            look = max(-26, min(26, math.degrees(math.atan2(dy, max(8, abs(dx))))))
            pose['head'] = pose['head'] * (1 - v['look']) + look * v['look']
        key_pose = ('S', face) + tuple(int(round(pose[k] / 2.0)) for k in pose)
    else:
        key_pose = (view, face, int(round(v['tuck'] * 10)))
    if key_pose not in cache:
        if view == 'S':
            im, info = side.pose(**pose)
        else:
            im, info = (b if view == 'B' else b34).pose(v['tuck'])
        bb = im.getbbox(); im = im.crop(bb)
        hip = (info['hip'][0] - bb[0], info['hip'][1] - bb[1])
        hn = (info['nhand'][0] - bb[0], info['nhand'][1] - bb[1]) if 'nhand' in info else None
        hf = (info['fhand'][0] - bb[0], info['fhand'][1] - bb[1]) if 'fhand' in info else None
        if face == 'R':
            im = im.transpose(Image.FLIP_LEFT_RIGHT)
            hip = (im.width - hip[0], hip[1])
            if hn: hn = (im.width - hn[0], hn[1]); hf = (im.width - hf[0], hf[1])
        a = np.asarray(im)[..., 3] > 110
        low = int(np.nonzero(a.any(axis=1))[0].max())
        cache[key_pose] = (im, hip, hn, hf, low, view)
    im, hip, hn, hf, low, vw = cache[key_pose]
    k = KV[vw]
    ox = int(round(v['hipx'] - hip[0] * k)); oy = int(round(FEET + 1 - low * k - v['lift']))
    rk = (key_pose, ox, oy)
    if rk not in cache:
        spr, sx0, sy0 = pxfast.pixelize(im, k, ox, oy, LO[vw], eyes_above=FEET - 130)
        cache[rk] = len(sprites); sprites.append((spr, sx0, sy0))
    steps.append(cache[rk])
    hands.append(((ox + hn[0] * k, oy + hn[1] * k), (ox + hf[0] * k, oy + hf[1] * k)) if hn else None)
print('distinct sprites', len(sprites), 'steps', N)

# the hole is where his hands meet the ground when he lays it in
pi = int(T_PLACE * FPS)
hp = hands[pi] or hands[pi - 1]
HOLE = ((hp[0][0] + hp[1][0]) / 2, FEET - 1)
fly = []
for n in range(N):
    t = n / FPS
    if t < T_CLAP - 0.12:
        x, y = free_fly(t); a = 1.0; state = 0
    elif t < T_PLACE:
        h = hands[n]
        if h: x, y = (h[0][0] + h[1][0]) / 2, (h[0][1] + h[1][1]) / 2; state = 1
        else: x, y = fly[-1][0], fly[-1][1]; state = 2     # hidden in his hands while he turns
        if t < T_CLAP:
            u = (t - (T_CLAP - 0.12)) / 0.12; fx_, fy_ = free_fly(t); x, y = fx_ + (x - fx_) * u, fy_ + (y - fy_) * u
        a = 1.0
    else:
        x, y = HOLE; state = 3
        a = 1.0 - 0.8 * min(1, max(0, (t - T_COVER0) / (T_COVERED - T_COVER0)))
        if t > T_COVERED: a = 0.2 * max(0.0, 1 - (t - T_COVERED) / 3.0)
    fly.append([round(x, 1), round(y, 1), round(a, 2), state])
# a new one drifts down from the sky toward where the next chase starts
T_NEW = 16.9
for n in range(int(T_NEW * FPS), N):
    u = ease((n / FPS - T_NEW) / (DUR - T_NEW)); x0, y0 = 300, 6; x1, y1 = free_fly(0.0)
    fly[n] = [round(x0 + (x1 - x0) * u, 1), round(y0 + (y1 - y0) * u, 1), round(min(1, u * 3), 2), 4]

# ---------------- pack sprites (shelves, 2048 wide)
order = sorted(range(len(sprites)), key=lambda i: -sprites[i][0].height)
SW = 2048; x = y = shelf = 0; place = {}
for i in order:
    s = sprites[i][0]
    if x + s.width > SW: x = 0; y += shelf; shelf = 0
    place[i] = (x, y); x += s.width; shelf = max(shelf, s.height)
sheet = Image.new('RGBA', (SW, y + shelf), (0, 0, 0, 0))
meta = []
for i, (s, sx0, sy0) in enumerate(sprites):
    px_, py_ = place[i]; sheet.paste(s, (px_, py_)); meta.append([px_, py_, s.width, s.height, sx0, sy0])
sheet.save(os.path.join(OUT, 'yeti_frames.png'), optimize=True)
json.dump({'fps': FPS, 'duration': DUR, 'frames': meta, 'steps': steps, 'fly': fly, 'hole': [round(HOLE[0], 1), HOLE[1]],
           'place': T_PLACE, 'scoops': SCOOPS, 'digs': DIGS, 'covered': T_COVERED, 'facing_at_dig': 'R'},
          open(os.path.join(OUT, 'title_story.json'), 'w'))
# foreground: a few blades of grass in front of his feet
fg = Image.new('RGBA', (W, H), (0, 0, 0, 0)); fp = fg.load()
for _ in range(260):
    xx = 392 + rnd.randrange(-120, 80); base = FEET + rnd.randrange(2, 10)
    # taller around where he kneels, so his folded legs sink into the meadow
    near = abs(xx - 388) < 30
    h = rnd.randrange(8, 16) if near else rnd.randrange(3, 9); lean = rnd.choice((-1, 0, 0, 1)); col = GRASS[rnd.choice((3, 4, 5, 5))]
    if near and abs(xx - HOLE[0]) < 5: continue   # keep the hole itself in view
    for k3 in range(h):
        x2 = xx + lean * (k3 // 3); y2 = base - k3
        if 0 <= x2 < W and 0 <= y2 < H: fp[x2, y2] = (*col, 255)
fg.save(os.path.join(OUT, 'title_fg.png'))
print('ok', sheet.size, 'hole', HOLE)
