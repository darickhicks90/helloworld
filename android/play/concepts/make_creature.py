"""The five-eyed creature (from a pencil drawing), as a chunky Game Boy Color
style monster sprite: a big shaggy head-and-body, short legs, long ape arms,
a thick black outline and a small, limited palette. Five natural-coloured
eyes show three emotions: the calm third eye, the alarmed upper pair and the
sorrowful lower pair. Writes creature.png (8x). Run: python3 make_creature.py"""
from PIL import Image

W, H = 56, 60
g = [['.'] * W for _ in range(H)]
def put(x, y, c):
    if 0 <= x < W and 0 <= y < H: g[int(y)][int(x)] = c
def ell(cx, cy, rx, ry, c):
    for y in range(H):
        for x in range(W):
            if ((x + .5 - cx) / rx) ** 2 + ((y + .5 - cy) / ry) ** 2 <= 1: put(x, y, c)
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

# Palette (GBC-like: few colours, strong contrast)
#  F fur  f fur light  d fur dark  S skin  s skin shade  W white  k black
#  1 brown iris  2 hazel iris  3 blue iris  T teeth
# --- short stubby legs and big flat feet
poly([(16, 46), (25, 46), (25, 56), (15, 56)], 'F'); poly([(31, 46), (40, 46), (41, 56), (31, 56)], 'F')
ell(19.5, 56.5, 6, 2.4, 'd'); ell(36.5, 56.5, 6, 2.4, 'd')
# --- long ape arm hanging to the ground (viewer's right) with a big knuckly hand
poly([(41, 16), (50, 22), (53, 40), (52, 52), (46, 52), (45, 38), (42, 27)], 'F')
ell(49, 53, 4.6, 3.2, 's'); row(45, 53, 55, 'k'); put(47, 52, 'k'); put(50, 52, 'k')
# --- the big shaggy head-and-body, hunched forward
ell(28, 25, 22, 20, 'F')
for (x, y) in ((7, 16), (6, 22), (7, 30), (10, 10), (15, 6), (22, 4), (30, 4), (37, 6), (43, 10), (47, 15), (49, 21)):
    put(x, y, 'F'); put(x + (1 if x < 28 else -1), y + 1, 'F'); put(x + (1 if x < 28 else -1), y, 'F')
# --- belly
ell(28, 39, 12, 9, 'S'); ell(31, 42, 9, 6, 's'); ell(27, 38, 9, 6.5, 'S'); put(28, 43, 'k'); put(27, 43, 's')
# --- the face: long and pale, sunk between the shoulders
poly([(21, 6), (35, 6), (38, 15), (37, 26), (33, 32), (23, 32), (19, 26), (18, 15)], 'S')
poly([(21, 7), (24, 7), (24, 28), (21, 27), (19, 17)], 's')
# brow ridge of hair over the forehead
poly([(18, 4), (38, 4), (40, 9), (34, 7), (28, 5), (22, 7), (16, 9)], 'F')

def eye(cx, cy, rx, ry, iris, look=(0, 0)):
    ell(cx, cy, rx + 1, ry + 1, 'k'); ell(cx, cy, rx, ry, 'W')
    ix, iy = cx + look[0], cy + look[1]
    ell(ix, iy, max(1.2, ry * 0.95), max(1.2, ry * 0.95), iris); put(ix, iy, 'k'); put(ix - 0.5, iy - 0.5, 'k')
    put(ix - 1, iy - 1, 'W')   # glint

# --- (1) the calm third eye: wide, relaxed, a warm brown iris looking straight out
eye(28, 10, 4.2, 2.2, '1')
row(25, 31, 7, 'k')
# --- (2) the alarmed upper pair: wide whites, small pupils, brows hiked up and pinched together
for ex in (23.5, 32.5):
    ell(ex, 16, 3.6, 3.0, 'k'); ell(ex, 16, 2.7, 2.2, 'W'); ell(ex, 16, 1.1, 1.1, '3'); put(int(ex), 16, 'k'); put(int(ex) - 1, 15, 'W')
row(19, 23, 12, 'd'); put(24, 11, 'd'); put(25, 11, 'd'); row(33, 37, 12, 'd'); put(32, 11, 'd'); put(31, 11, 'd')
# --- (3) the sorrowful lower pair: heavy lids half shut, pupils sunk low, drooping outer corners, bags, a tear
for ex, side in ((17.5, -1), (38.5, 1)):
    ell(ex, 22, 3.6, 2.6, 'k'); ell(ex, 22.6, 2.6, 1.5, 'W')
    row(ex - 3, ex + 3, 20, 's'); row(ex - 3, ex + 3, 21, 'k')          # heavy upper lid
    ell(ex, 23, 1.2, 1.0, '2'); put(int(ex), 23, 'k')                    # hazel iris, looking down
    put(ex + side * 3, 23, 'k'); put(ex + side * 4, 24, 'k')            # drooping outer corner
    row(ex - 2, ex + 2, 25, 's'); row(ex - 1, ex + 1, 26, 's')          # bags
    put(ex - side * 1, 26, 'W'); put(ex - side * 1, 27, 'W')            # a tear
row(13, 17, 19, 'd'); put(18, 18, 'd'); row(39, 43, 19, 'd'); put(38, 18, 'd')
# --- the long nose with a heavy bulb and nostrils
for y in range(15, 26): put(28, y, 's'); put(29, y, 'S')
ell(28.5, 26.5, 2.6, 1.6, 's'); put(27, 27, 'k'); put(30, 27, 'k')
# --- the grimace: clenched teeth between thin dark lips
row(23, 34, 29, 'k'); row(24, 33, 30, 'T'); [put(x, 30, 'k') for x in (26, 29, 32)]; row(23, 34, 31, 'k')
# --- the bent arm, the hand clutching the chest (viewer's left)
poly([(13, 16), (4, 24), (3, 38), (7, 43), (12, 40), (10, 30)], 'F')
poly([(5, 38), (9, 45), (22, 40), (24, 35), (18, 34), (9, 37)], 'F')
# the hand, outlined so it stands out against the belly: four long fingers splayed over the chest
hand = [(17, 32), (24, 31), (30, 32), (30, 34), (29, 36), (28, 38), (25, 40), (21, 41), (17, 39)]
poly([(x + dx, y + dy) for (x, y) in hand for (dx, dy) in [(0, 0)]], 'k')
poly([(18, 33), (24, 32), (29, 33), (28, 35), (27, 37), (24, 39), (21, 40), (18, 38)], 's')
for (x0, x1, y) in ((24, 28, 33), (24, 27, 35), (23, 26, 37), (21, 23, 39)): row(x0, x1, y, 'S')
for (x0, x1, y) in ((23, 28, 34), (23, 27, 36), (22, 25, 38)): row(x0, x1, y, 'k')   # gaps between fingers
for (x, y) in ((28, 33), (27, 35), (26, 37), (23, 39)): put(x, y, 'W')                   # pale fingertips

# --- chunky GBC fur: a lit upper-left edge, dark lower-right, and blocky 2x2 clumps
import random
random.seed(3)
for y in range(H):
    for x in range(W):
        if g[y][x] != 'F': continue
        edge_ul = any(0 <= y + dy < H and 0 <= x + dx < W and g[y + dy][x + dx] == '.' for dx, dy in ((-1, 0), (0, -1), (-2, 0), (0, -2)))
        edge_dr = any(0 <= y + dy < H and 0 <= x + dx < W and g[y + dy][x + dx] == '.' for dx, dy in ((1, 0), (0, 1), (2, 0), (0, 2)))
        if edge_ul and x < 30: g[y][x] = 'f'
        elif edge_dr and (x > 26 or y > 40): g[y][x] = 'd'
for _ in range(90):
    x = random.randrange(0, W - 1); y = random.randrange(0, H - 2)
    if all(g[y + j][x + i] == 'F' for i in (0, 1) for j in (0, 1, 2)):
        c = 'd' if (x > 28 or y > 30) else 'f' if random.random() < 0.5 else 'd'
        put(x, y + 1, c); put(x, y + 2, c); put(x + 1, y + 2, c)

COL = {'F': (0x8A, 0x52, 0x2C), 'f': (0xC0, 0x84, 0x4C), 'd': (0x4E, 0x2C, 0x18), 'S': (0xF2, 0xDC, 0xB8), 's': (0xC8, 0x9C, 0x74),
       'W': (0xFF, 0xFF, 0xFF), 'k': (0x18, 0x10, 0x10), '1': (0x6A, 0x40, 0x22), '2': (0x7E, 0x6A, 0x30), '3': (0x4A, 0x72, 0xA0),
       'M': (0x50, 0x18, 0x18), 'T': (0xF0, 0xEC, 0xDC)}
img = Image.new('RGBA', (W + 2, H + 2), (0, 0, 0, 0))
def filled(x, y): return 0 <= x < W and 0 <= y < H and g[y][x] != '.'
for y in range(H + 2):
    for x in range(W + 2):
        gx, gy = x - 1, y - 1
        if filled(gx, gy): img.putpixel((x, y), COL[g[gy][gx]] + (255,))
        elif any(filled(gx + dx, gy + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))): img.putpixel((x, y), (0x18, 0x10, 0x10, 255))
img.resize(((W + 2) * 8, (H + 2) * 8), Image.NEAREST).save('creature.png')
print('ok')
