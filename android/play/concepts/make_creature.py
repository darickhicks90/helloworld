"""The five-eyed creature as a friendly, fluffy monster sprite: a round
fur-ball body with pointed pink ears, big furry forearms and little pink
claws, in a chunky retro-game style with a dark outline. From the original
pencil drawing it keeps five eyes showing three emotions (here gentle ones:
a calm third eye, a curious upper pair, a content, sleepy lower pair), the
long nose, a hand on its chest and the round belly. All eyes are natural
colours. Writes creature.png (8x). Run: python3 make_creature.py"""
import math, random
from PIL import Image

W, H = 60, 62
g = [['.'] * W for _ in range(H)]
def put(x, y, c):
    if 0 <= x < W and 0 <= y < H: g[int(y)][int(x)] = c
def ell(cx, cy, rx, ry, c, keep=None):
    for y in range(H):
        for x in range(W):
            if ((x + .5 - cx) / rx) ** 2 + ((y + .5 - cy) / ry) ** 2 <= 1 and (keep is None or g[y][x] in keep): put(x, y, c)
def poly(pts, c):
    for y in range(H):
        for x in range(W):
            px, py = x + .5, y + .5; inside = False; j = len(pts) - 1
            for i in range(len(pts)):
                xi, yi = pts[i]; xj, yj = pts[j]
                if (yi > py) != (yj > py) and px < (xj - xi) * (py - yi) / (yj - yi) + xi: inside = not inside
                j = i
            if inside: put(x, y, c)
def row(x0, x1, y, c):
    for x in range(int(x0), int(x1) + 1): put(x, y, c)
def fuzzy(cx, cy, rx, ry, c, seed, amp=0.08):
    """A furry blob: an ellipse whose edge is broken into little tufts."""
    rnd = random.Random(seed)
    tufts = [rnd.uniform(-amp, amp) for _ in range(48)]
    for y in range(H):
        for x in range(W):
            dx = (x + .5 - cx) / rx; dy = (y + .5 - cy) / ry
            a = (math.atan2(dy, dx) + math.pi) / (2 * math.pi) * 48
            t = tufts[int(a) % 48] + (0.06 if int(a * 2) % 2 == 0 else 0)
            if dx * dx + dy * dy <= (1 + t) ** 2: put(x, y, c)

# Palette:  F fur  f fur light  d fur dark  P pink  p pink dark  S face  s face shade
#           W white  k ink  1 brown iris  2 green-hazel iris  3 blue-grey iris  M mouth
# --- stubby legs with pink claws
fuzzy(19, 53, 7, 5.5, 'F', 11); fuzzy(41, 53, 7, 5.5, 'F', 12)
for x in (14, 18, 22, 37, 41, 45): poly([(x - 1.6, 57), (x + 1.6, 57), (x, 61)], 'P')
# --- rounded furry ears (same fur, a darker inner hollow)
fuzzy(10, 12, 6.5, 6, 'F', 31, 0.05); ell(10.5, 12.5, 3.6, 3.2, 'd')
fuzzy(50, 12, 6.5, 6, 'F', 32, 0.05); ell(49.5, 12.5, 3.6, 3.2, 'd')
# --- the round fluffy body, a little taller so the big eye sits near the peak
fuzzy(30, 30, 23, 24, 'F', 3, 0.07)
# a soft lighter belly
ell(30, 41, 11, 9, 'f', keep='F')
# --- the face patch: pale, a little long, like the drawing
ell(30, 27, 10.5, 9.5, 'S')
ell(26, 28, 4, 8, 's', keep='S'); ell(27.5, 27, 4, 8, 'S')

# --- (1) the big calm cyclops eye near the peak of the head: a round white, a large warm brown iris
ell(30, 12, 7.6, 7.0, 'k'); ell(30, 12, 6.6, 6.0, 'W')
ell(30, 12.5, 3.4, 3.4, '1'); ell(30, 12.5, 2.3, 2.3, 'i'); ell(30, 12.5, 1.4, 1.4, 'k')
put(28, 10, 'W'); put(29, 10, 'W'); put(28, 11, 'W'); put(32, 14, 'W')       # glints
ell(30, 15.8, 6.2, 1.2, 'e', keep='W')                                        # soft shadow under the lid
# fur tufts curling over the eye like lashes
for (x, y) in ((23, 6), (26, 4), (30, 4), (34, 4), (37, 6)): put(x, y, 'd'); put(x, y + 1, 'F')
# --- (2) curious upper pair: big round whites, blue-grey irises glancing up, brows raised in interest
for ex in (25, 35):
    ell(ex, 23.5, 3.2, 3.0, 'k'); ell(ex, 23.5, 2.4, 2.2, 'W'); ell(ex, 23, 1.4, 1.4, '3'); put(ex, 23, 'k'); put(ex - 1, 22, 'W')
row(22, 26, 19, 'd'); row(34, 38, 19, 'd')
# --- (3) content, sleepy lower pair: happy half-closed lids curving up, green-hazel irises peeking
for ex in (20.5, 39.5):
    ell(ex, 29, 3.4, 2.8, 'k'); ell(ex, 29.6, 2.5, 1.6, 'W'); ell(ex, 30, 1.3, 1.1, '2'); put(int(ex), 30, 'k')
    row(ex - 3, ex + 3, 27, 'k'); row(ex - 2, ex + 2, 28, 's')          # relaxed, heavy upper lid
    put(ex - 3, 30, 'k'); put(ex + 3, 30, 'k')                            # lashes
# rosy cheeks
for (x, y) in ((22, 33), (23, 33), (37, 33), (38, 33)): put(x, y, 'P')
# --- the long nose, a soft round tip
for y in range(22, 30): put(30, y, 's'); put(31, y, 'S')
ell(30.5, 30, 2.2, 1.4, 'p'); put(29, 30, 'k'); put(32, 30, 'k'); put(30, 29, 'P')
# --- a small friendly smile with one little tooth
row(27, 34, 33, 'k'); row(28, 33, 34, 'M'); row(26, 26, 32, 'k'); row(35, 35, 32, 'k'); put(30, 34, 'W'); row(28, 33, 35, 'k')

# --- arms: big furry forearms, each outlined with a dark crease so it reads in front of the body
def arm(draw):
    before = [r[:] for r in g]
    draw()
    mask = [[g[y][x] != before[y][x] for x in range(W)] for y in range(H)]
    for y in range(H):
        for x in range(W):
            if mask[y][x] and any(0 <= y + dy < H and 0 <= x + dx < W and not mask[y + dy][x + dx] and g[y + dy][x + dx] != '.' for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                g[y][x] = 'd'
def claw(x, y, dx=0, dy=1):
    # a little pink claw, three pixels long, pointing (dx, dy)
    for k in range(3):
        put(x + dx * k, y + dy * k, 'P')
        if k < 2: put(x + dx * k + abs(dy), y + dy * k + abs(dx), 'P')
arm(lambda: fuzzy(43, 42, 7.5, 10.5, 'A', 21, 0.06))      # the hanging arm
for x in (38, 42, 46): claw(x, 51)
arm(lambda: fuzzy(15, 38, 6.5, 8.5, 'A', 22, 0.06))      # upper arm
arm(lambda: fuzzy(22, 41.5, 8, 5, 'A', 23, 0.05))        # forearm across the belly
for y in (38, 41, 44): claw(29, y, 1, 0)                 # claws resting on the chest

# arms are a touch lighter than the body so they come forward
for y in range(H):
    for x in range(W):
        if g[y][x] == 'A': g[y][x] = 'F' if (x * 7 + y * 3) % 11 == 0 else 'f'

# --- fluffy shading: lit tufts on the upper left, darker tufts lower right
rnd = random.Random(5)
for y in range(H):
    for x in range(W):
        if g[y][x] != 'F': continue
        edge = lambda dx, dy: not (0 <= x + dx < W and 0 <= y + dy < H) or g[y + dy][x + dx] in '.'
        if (edge(-1, 0) or edge(0, -1) or edge(-2, 0)) and x < 32 and y < 40: g[y][x] = 'f'
        elif (edge(1, 0) or edge(0, 1) or edge(2, 0)) and (x > 28 or y > 44): g[y][x] = 'd'
for _ in range(120):
    x = rnd.randrange(1, W - 2); y = rnd.randrange(1, H - 3)
    if all(g[y + j][x + i] == 'F' for i in (0, 1) for j in (0, 1, 2)):
        c = 'd' if (x > 32 or y > 42) else ('f' if rnd.random() < 0.55 else 'd')
        put(x, y, c); put(x, y + 1, c); put(x + 1, y + 2, c)

COL = {'F': (0xA0, 0x58, 0x44), 'f': (0xC4, 0x7E, 0x62), 'd': (0x5A, 0x2C, 0x22), 'P': (0xEC, 0x86, 0xB2), 'p': (0xB8, 0x5C, 0x6C),
       'S': (0xF2, 0xDE, 0xC6), 's': (0xD6, 0xB2, 0x98), 'W': (0xFF, 0xFF, 0xFF), 'k': (0x2A, 0x14, 0x14),
       '1': (0x7A, 0x4A, 0x26), '2': (0x6A, 0x7A, 0x3A), '3': (0x5A, 0x7A, 0x96), 'M': (0xC8, 0x5C, 0x7A), 'i': (0x5A, 0x34, 0x1A), 'e': (0xE2, 0xDE, 0xE0)}
img = Image.new('RGBA', (W + 2, H + 2), (0, 0, 0, 0))
def filled(x, y): return 0 <= x < W and 0 <= y < H and g[y][x] != '.'
for y in range(H + 2):
    for x in range(W + 2):
        gx, gy = x - 1, y - 1
        if filled(gx, gy): img.putpixel((x, y), COL[g[gy][gx]] + (255,))
        elif any(filled(gx + dx, gy + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))): img.putpixel((x, y), (0x2A, 0x14, 0x14, 255))
img.resize(((W + 2) * 8, (H + 2) * 8), Image.NEAREST).save('creature.png')
print('ok')
