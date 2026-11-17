"""5 Eye Yeti title story (about 15 s, looping): the yeti swipes at a firefly and misses
twice, catches it on the third try, looks at it, turns around, kneels (thighs then shins
folding like a person's), lays it in the ground, covers it with a few scoops, pats it down,
gets up and turns back while a new firefly drifts in. Busy with his own business.
Writes title_bg.png, title_fg.png, yeti_frames.png and title_story.json into OUT."""
import sys, os, math, json, random
sys.path.insert(0, '.')
from PIL import Image
import numpy as np
import siderig2 as S
import render as R

OUT = sys.argv[1]
src = open('yeti_dig.py').read()
exec(src[:src.index("# ---------------- frames")].replace("import siderig\n", "").replace("OUT = sys.argv[1]", "").replace(
    "DIRT = [hx(c)", "DIRT0 = [hx(c)").replace("for y in range(FEET - 7, FEET + 5):", "for y in range(0):").replace("for _ in range(30):   # loose clods", "for _ in range(0):   # loose clods"))
FEET, HX = 228, 372
K = 0.17
FPS = 12

# ---------------- keyframes: (time, facing, pose dict, squash)
L, Rt = -1, 1
STAND = dict(torso=0, shoulder=-8, elbow=0, thigh=0, shin=0)
def P(**kw): d = dict(STAND); d.update(kw); return d
HOLD = dict(shoulder=-70, elbow=-115)
keys = [
    (0.0, L, P()), (0.8, L, P(torso=2)),
    (1.2, L, P(torso=4, shoulder=-150, elbow=-25)), (1.45, L, P(torso=6, shoulder=-158, elbow=-10)),      # miss 1 (up)
    (2.0, L, P(torso=2, shoulder=-30)),
    (2.5, L, P(torso=12, shoulder=-110, elbow=-45)), (2.75, L, P(torso=16, shoulder=-120, elbow=-25)),    # miss 2 (forward lunge)
    (3.3, L, P(torso=3, shoulder=-25)),
    (3.75, L, P(torso=0, shoulder=-165, elbow=-15)), (3.95, L, P(torso=-2, shoulder=-172, elbow=-5)),    # catch (up high)
    (4.6, L, P(torso=6, **HOLD)), (5.2, L, P(torso=8, **HOLD)),                                          # look at it
    (5.35, L, P(torso=4, **HOLD), 0.55), (5.5, Rt, P(torso=4, **HOLD), 0.55), (5.65, Rt, P(torso=4, **HOLD)),  # turn around
    (6.1, Rt, P(torso=14, thigh=-45, shin=85, **HOLD)),                                                  # lowering
    (6.6, Rt, P(torso=28, thigh=-80, shin=165, **HOLD)),                                                 # kneeling
    (7.1, Rt, P(torso=44, thigh=-80, shin=165, shoulder=-25, elbow=0)),                                  # lay it in the ground
    (7.5, Rt, P(torso=44, thigh=-80, shin=165, shoulder=-25, elbow=0)),
]
t = 7.5
for i in range(3):                                                                                     # three scoops of earth over it
    keys += [(t + 0.35, Rt, P(torso=40, thigh=-80, shin=165, shoulder=-62, elbow=-12)),
             (t + 0.8, Rt, P(torso=46, thigh=-80, shin=165, shoulder=-12, elbow=0))]
    t += 0.8
keys += [(t + 0.25, Rt, P(torso=42, thigh=-80, shin=165, shoulder=-32, elbow=-25)), (t + 0.45, Rt, P(torso=44, thigh=-80, shin=165, shoulder=-28, elbow=-5)),   # pat, pat
         (t + 0.65, Rt, P(torso=42, thigh=-80, shin=165, shoulder=-32, elbow=-25)), (t + 0.85, Rt, P(torso=44, thigh=-80, shin=165, shoulder=-28, elbow=-5))]
t += 1.1
keys += [(t + 0.5, Rt, P(torso=34, thigh=-55, shin=110, shoulder=-20)),                                 # lean forward, push up
         (t + 1.0, Rt, P(torso=10, thigh=-15, shin=25)), (t + 1.3, Rt, P()),
         (t + 1.5, Rt, P(), 0.55), (t + 1.65, L, P(), 0.55), (t + 1.8, L, P()), (t + 3.2, L, P(torso=2))]
DUR = t + 3.2
EVENTS = {'catch': 3.95, 'turn': 5.5, 'place': 7.1, 'scoops': [7.5 + 0.8 * i + 0.75 for i in range(3)], 'covered': 7.5 + 2.4, 'newfly': t + 2.0}

def ease(x): return 0.5 - 0.5 * math.cos(math.pi * x)
def sample(tt):
    for i in range(len(keys) - 1):
        a, b = keys[i], keys[i + 1]
        if a[0] <= tt <= b[0]:
            u = ease((tt - a[0]) / max(1e-6, b[0] - a[0]))
            pose = {k: a[2][k] + (b[2][k] - a[2][k]) * u for k in a[2]}
            sq = (a[3] if len(a) > 3 else 1.0) + ((b[3] if len(b) > 3 else 1.0) - (a[3] if len(a) > 3 else 1.0)) * u
            return (a[1] if u < 0.5 else b[1]), pose, sq
    return keys[-1][1], keys[-1][2], 1.0

# ---------------- render the distinct poses
cache = {}; frames = []
def q(pose, face, sq):
    return (face, round(sq * 10), *(int(round(pose[k] / 3.0)) for k in ('torso', 'shoulder', 'elbow', 'thigh', 'shin')))
def render(key):
    face, sq10, *qq = key
    pose = dict(zip(('torso', 'shoulder', 'elbow', 'thigh', 'shin'), [v * 3.0 for v in qq]))
    im, off, hand = S.pose(**pose)
    spr = R.snes_creature(im, round(im.height * K)); k = spr.width / im.width
    hipx = (235 + off[0]) * k; hx_, hy_ = hand[0] * k, hand[1] * k
    a = np.asarray(spr)[..., 3] > 0
    low = int(np.nonzero(a.any(axis=1))[0].max())
    if sq10 != 10:   # squash sideways for the turn
        nw = max(1, round(spr.width * sq10 / 10)); spr = spr.resize((nw, spr.height), Image.NEAREST)
        hipx *= sq10 / 10; hx_ *= sq10 / 10
    if face == 1:
        spr = spr.transpose(Image.FLIP_LEFT_RIGHT); hipx = spr.width - hipx; hx_ = spr.width - hx_
    x = HX - hipx; y = FEET - low
    return spr, int(round(x)), int(round(y)), (x + hx_, y + hy_)

steps = []
for n in range(int(DUR * FPS)):
    face, pose, sq = sample(n / FPS)
    key = q(pose, face, sq)
    if key not in cache:
        cache[key] = len(frames); frames.append(render(key))
    steps.append(cache[key])
print('distinct poses', len(frames), 'steps', len(steps))

# pack frames into a sheet (rows of 10)
cw = max(f[0].width for f in frames); ch = max(f[0].height for f in frames)
cols = 10; rows = (len(frames) + cols - 1) // cols
sheet = Image.new('RGBA', (cw * cols, ch * rows), (0, 0, 0, 0))
meta = []
for i, (spr, x, y, hand) in enumerate(frames):
    sx, sy = (i % cols) * cw, (i // cols) * ch
    sheet.paste(spr, (sx, sy), spr)
    meta.append([sx, sy, spr.width, spr.height, x, y, round(hand[0], 1), round(hand[1], 1)])
sheet.save(os.path.join(OUT, 'yeti_frames.png'))

# ---------------- the firefly he chases: dodges twice, caught, carried, buried
def fly(tt):
    hand = lambda: frames[steps[min(len(steps) - 1, int(tt * FPS))]][3]
    def orbit(cx, cy, r=6): return (cx + r * math.sin(tt * 2.3), cy + r * 0.6 * math.sin(tt * 3.7))
    if tt < 1.35: return (*orbit(322, 62), 1.0)
    if tt < 1.6: u = (tt - 1.35) / 0.25; a = orbit(322, 62); b = orbit(296, 96); return (a[0] + (b[0] - a[0]) * u, a[1] + (b[1] - a[1]) * u, 1.0)   # darts away
    if tt < 2.65: return (*orbit(296, 96), 1.0)
    if tt < 2.95: u = (tt - 2.65) / 0.3; a = orbit(296, 96); b = orbit(352, 40); return (a[0] + (b[0] - a[0]) * u, a[1] + (b[1] - a[1]) * u, 1.0)   # over his head
    if tt < 3.75: return (*orbit(352, 40), 1.0)
    if tt < 3.95: u = (tt - 3.75) / 0.2; a = orbit(352, 40); h = hand(); return (a[0] + (h[0] - a[0]) * u, a[1] + (h[1] - a[1]) * u, 1.0)
    if tt < EVENTS['place']: h = hand(); return (h[0], h[1], 1.0)
    hole = HOLE
    if tt < EVENTS['covered']: return (hole[0], hole[1], 1.0 - 0.75 * (tt - EVENTS['place']) / (EVENTS['covered'] - EVENTS['place']))
    return (hole[0], hole[1], 0.25 * max(0.0, 1 - (tt - EVENTS['covered']) / 2.5))
HOLE = frames[steps[int(EVENTS['place'] * FPS)]][3]
HOLE = (HOLE[0], FEET - 1)
flight = []
for n in range(len(steps)):
    x, y, a = fly(n / FPS); flight.append([round(x, 1), round(y, 1), round(a, 2)])
# a new firefly drifts in at the end and becomes the one he chases next time
for n in range(int(EVENTS['newfly'] * FPS), len(steps)):
    u = (n / FPS - EVENTS['newfly']) / (DUR - EVENTS['newfly'])
    x0, y0 = 250, 20; tt = n / FPS
    a = (322 + 6 * math.sin(DUR * 2.3), 62 + 3.6 * math.sin(DUR * 3.7))
    flight[n] = [round(x0 + (a[0] - x0) * u, 1), round(y0 + (a[1] - y0) * u, 1), round(min(1, u * 2), 2)]
json.dump({'fps': FPS, 'frames': meta, 'steps': steps, 'fly': flight, 'hole': [round(HOLE[0], 1), HOLE[1]],
           'place': EVENTS['place'], 'scoops': EVENTS['scoops'], 'covered': EVENTS['covered'], 'newfly': EVENTS['newfly'], 'duration': DUR},
          open(os.path.join(OUT, 'title_story.json'), 'w'))
fg = Image.new('RGBA', (W, H), (0, 0, 0, 0)); fp = fg.load()
for _ in range(110):
    x = HX + rnd.randrange(-110, 90); base = FEET + rnd.randrange(2, 10)
    h = rnd.randrange(3, 9); lean = rnd.choice((-1, 0, 0, 1)); col = GRASS[rnd.choice((3, 4, 5, 5))]
    for k3 in range(h):
        xx = x + lean * (k3 // 3); yy = base - k3
        if 0 <= xx < W and 0 <= yy < H: fp[xx, yy] = (*col, 255)
fg.save(os.path.join(OUT, 'title_fg.png'))
print('ok', DUR, 'hole', HOLE)
