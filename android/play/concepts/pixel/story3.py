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
FPS = 12
SLOW = 1.5   # the whole story at two-thirds speed: unhurried
FEET = 228
# ---------------- background (the clearing; no moon) and foreground grass
src = open('yeti_dig.py').read()
ns = {}
exec(src[:src.index("# ---------------- frames")].replace("import siderig\n", "").replace("OUT = sys.argv[1]", "OUT = '" + OUT + "'").replace(
    "for y in range(FEET - 7, FEET + 5):", "for y in range(0):").replace("for _ in range(30):   # loose clods", "for _ in range(0):   # loose clods").replace(
    "FEET, BODYX = 228, 380", "FEET, BODYX = 228, 382"), ns)
W, H, GRASS, rnd = ns['W'], ns['H'], ns['GRASS'], random.Random(21)

# ---------------- keyframes
CH = ['torso', 'head', 'nsh', 'nel', 'fsh', 'fel', 'nth', 'nsn', 'fth', 'fsn', 'hipx', 'lift', 'tuck', 'look', 'breath', 'bl', 'br']
REST = dict(torso=0, head=0, nsh=-8, nel=0, fsh=-4, fel=0, nth=0, nsn=0, fth=0, fsn=0, hipx=392, lift=0, tuck=0, look=1, breath=1, bl=0, br=0)
keys = {c: [] for c in CH}
views = []        # (time, view, face)
def K(t, **kw):
    t = t * SLOW
    for c, v in kw.items(): keys[c].append((t, v))
def V(t, view, face): views.append((t * SLOW, view, face))
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
K(6.3, look=0, head=12, lift=3)
K(6.45, lift=0)
K(6.95, head=15, torso=8)
# turn around the way people do: weight shifts, then small alternating steps carry him round,
# through his back (never toward the viewer). Each view gets a step: one foot lifts and plants.
def turn(t0, from_face, to_face):
    K(t0, nth=0, nsn=0, fth=0, fsn=0, head=0, torso=1)
    K(t0 + 0.12, nth=-12, nsn=28, torso=3)                    # first step: the near foot lifts as he starts to turn
    V(t0 + 0.2, 'B34', from_face)
    K(t0 + 0.2, nth=0, nsn=0, bl=1, br=0, tuck=0.3)
    K(t0 + 0.34, bl=0); K(t0 + 0.42, br=1)
    V(t0 + 0.48, 'B', from_face)
    K(t0 + 0.56, br=0); K(t0 + 0.64, bl=1)
    V(t0 + 0.76, 'B34', to_face)
    K(t0 + 0.78, bl=0); K(t0 + 0.88, br=1)
    V(t0 + 1.02, 'S', to_face)
    K(t0 + 1.02, br=0, bl=0, fth=-12, fsn=28, tuck=0)           # last step brings the other foot round
    K(t0 + 1.18, fth=0, fsn=0, torso=1)
turn(7.0, 'L', 'R')
# two steps to a spot of his choosing (facing right now; hipx grows)
step(8.25, 0.45, 362, 376, 'n'); step(8.7, 0.45, 376, 390, 'f')
K(9.15, **STAND_LEGS, torso=2)
# kneel like a person: the near foot steps back, that knee lowers to the ground, the front
# foot stays planted; then he leans over the front knee to work
KN = dict(nth=6, nsn=96, fth=-78, fsn=82)
K(9.45, nth=16, nsn=22, fth=-14, fsn=16, torso=6, head=8)                     # step back
K(9.95, **KN, torso=14, head=12)                                              # knee down
K(10.3, torso=30, head=16, nsh=-30, nel=-4, fsh=-60, fel=-110)                # lean in, the far hand still holds it
# scratch a little hole with the near hand
for i in range(2):
    t = 10.3 + i * 0.34
    K(t + 0.17, nsh=-64, nel=-14, torso=40); K(t + 0.34, nsh=-18, nel=-2, torso=42)
# lay it in: both hands down to the hole
K(11.35, torso=44, nsh=-24, nel=-4, fsh=-28, fel=-6, head=18)
K(11.8, torso=42, nsh=-14, fsh=-12)
# three scoops, alternating hands, pulling earth over it
for i, (sh, el) in enumerate((('nsh', 'nel'), ('fsh', 'fel'), ('nsh', 'nel'))):
    t = 11.8 + i * 0.6
    K(t + 0.25, **{sh: -62, el: -12}, torso=46); K(t + 0.6, **{sh: -12, el: 0}, torso=42)
# pat, pat
K(13.8, nsh=-30, nel=-26, fsh=-24, fel=-20); K(13.95, nsh=-22, nel=-4, fsh=-18, fel=-2)
K(14.15, nsh=-30, nel=-26); K(14.3, nsh=-22, nel=-4)
# a moment, looking at it
K(14.9, head=18, torso=40, nsh=-16, nel=0, fsh=-10, fel=0, **KN)
# stays down on the one knee a while longer, looking at the little mound
K(15.4, head=22, torso=36); K(15.9, head=18, torso=38, **KN)
# get up: lean over the front foot, push, the back knee comes up, stand
K(16.3, torso=34, head=10, nsh=-12, fsh=-8)
K(16.75, fth=-40, fsn=44, nth=4, nsn=46, torso=22, head=4)
K(17.2, **STAND_LEGS, torso=2, head=0, nsh=-8, fsh=-4)
turn(17.3, 'R', 'L')
# idle while a new firefly drifts down; he looks up at it
K(18.6, look=1, breath=1)
K(19.6, hipx=390)
DUR = 21.0 * SLOW
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
FK0 = [(0.0, 352, 78), (1.0, 348, 84), (1.36, 344, 70), (1.62, 300, 150), (2.4, 296, 142), (3.0, 292, 150), (3.42, 300, 150), (3.62, 340, 36), (4.2, 352, 28), (5.15, 348, 34)]
FK = [(t * SLOW, x, y) for t, x, y in FK0]
def free_fly(t):
    for (t0, x0, y0), (t1, x1, y1) in zip(FK, FK[1:]):
        if t0 <= t <= t1:
            u = ease((t - t0) / (t1 - t0)); x, y = x0 + (x1 - x0) * u, y0 + (y1 - y0) * u
            return x + 3.0 * math.sin(t * 4.4) + 1.5 * math.sin(t * 9.0), y + 2.2 * math.sin(t * 5.6 + 1)
    return FK[-1][1] + 3 * math.sin(t * 4.4), FK[-1][2] + 2 * math.sin(t * 5.6)
T_CLAP, T_PLACE, T_COVER0, T_COVERED = (x * SLOW for x in (5.3, 11.35, 11.8, 13.6))
SCOOPS = [(11.8 + i * 0.6 + 0.45) * SLOW for i in range(3)]
DIGS = [(10.3 + i * 0.34 + 0.17) * SLOW for i in range(2)]

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
    v['torso'] += v['breath'] * 1.3 * math.sin(2 * math.pi * t / 4.5)
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
            look = max(-16, min(16, math.degrees(math.atan2(dy, max(8, abs(dx)))) * 0.7))
            pose['head'] = pose['head'] * (1 - v['look']) + look * v['look']
        key_pose = ('S', face) + tuple(int(round(pose[k] / 2.0)) for k in pose)
    else:
        key_pose = (view, face, int(round(v['tuck'] * 10)), int(round(v['bl'] * 4)), int(round(v['br'] * 4)))
    if key_pose not in cache:
        if view == 'S':
            im, info = side.pose(**pose)
        else:
            im, info = (b if view == 'B' else b34).pose(v['tuck'], (key_pose[3] / 4, key_pose[4] / 4))
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
    if t < T_CLAP - 0.18:
        x, y = free_fly(t); a = 1.0; state = 0
    elif t < T_PLACE:
        h = hands[n]
        if h: x, y = (h[0][0] + h[1][0]) / 2, (h[0][1] + h[1][1]) / 2; state = 1
        else: x, y = fly[-1][0], fly[-1][1]; state = 2     # hidden in his hands while he turns
        if t < T_CLAP:
            u = (t - (T_CLAP - 0.18)) / 0.18; fx_, fy_ = free_fly(t); x, y = fx_ + (x - fx_) * u, fy_ + (y - fy_) * u
        a = 1.0
    else:
        x, y = HOLE; state = 3
        a = 1.0 - 0.8 * min(1, max(0, (t - T_COVER0) / (T_COVERED - T_COVER0)))
        if t > T_COVERED: a = 0.2 * max(0.0, 1 - (t - T_COVERED) / 3.0)
    fly.append([round(x, 1), round(y, 1), round(a, 2), state])
# a new one drifts down from the sky toward where the next chase starts
T_NEW = 18.6 * SLOW
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
           'place': T_PLACE, 'scoops': SCOOPS, 'digs': DIGS, 'covered': T_COVERED, 'facing_at_dig': 'R', 'slow': SLOW},
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
