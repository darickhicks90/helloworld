"""Builds the five-eyed creature (from a pencil drawing) as a 48x64 pixel sprite in FoxBox's palette.
Writes rows.txt and creature.png (8x). Run: python3 make_creature.py"""
import math
from PIL import Image
W, H = 48, 64
g = [['.'] * W for _ in range(H)]
def put(x, y, c):
    if 0 <= x < W and 0 <= y < H: g[y][x] = c
def ell(cx, cy, rx, ry, c, only=None):
    for y in range(H):
        for x in range(W):
            if ((x + .5 - cx) / rx) ** 2 + ((y + .5 - cy) / ry) ** 2 <= 1:
                if only is None or g[y][x] in only: put(x, y, c)
def poly(pts, c):
    for y in range(H):
        for x in range(W):
            px, py = x + .5, y + .5; inside = False; j = len(pts) - 1
            for i in range(len(pts)):
                xi, yi = pts[i]; xj, yj = pts[j]
                if (yi > py) != (yj > py) and px < (xj - xi) * (py - yi) / (yj - yi) + xi: inside = not inside
                j = i
            if inside: put(x, y, c)
def line(x0, y0, x1, y1, c, w=1):
    n = int(max(abs(x1 - x0), abs(y1 - y0))) + 1
    for i in range(n + 1):
        t = i / n; x = x0 + (x1 - x0) * t; y = y0 + (y1 - y0) * t
        for dx in range(w): put(int(round(x)) + dx, int(round(y)), c)

# palette letters: F fur, f fur light, d fur dark, S skin, s skin shade, k ink, W eye white, A aqua, O orange, o orange light, B belly, b belly shade, M mouth, T teeth, r sore/red rim
# --- legs (shaggy)
poly([(13, 50), (23, 50), (22, 63), (13, 63)], 'F'); poly([(25, 50), (35, 50), (35, 63), (26, 63)], 'F')
# --- long hanging arm (viewer's right)
poly([(35, 20), (44, 24), (46, 40), (45, 52), (41, 54), (38, 46), (36, 32)], 'F')
# --- hunched shaggy back/shoulders and hair around the head
ell(24, 20, 21, 15, 'F')
# --- body: chest and the big round belly
poly([(12, 28), (36, 28), (38, 40), (37, 50), (11, 50), (10, 40)], 'S')
ell(24, 43, 13, 10, 'B')
# --- the face: long, narrow, pale, sunk into the shoulders
poly([(19, 8), (29, 8), (31, 16), (30, 26), (27, 33), (21, 33), (18, 26), (17, 16)], 'S')
# hair swept over the forehead in a swirl, framing the face
poly([(13, 3), (35, 3), (37, 9), (32, 8), (24, 5), (16, 8), (11, 9)], 'F')
for x in range(16, 33): put(x, 3 + abs(x - 24) // 4, 'd') if x % 2 else None
# --- the third eye: big, lashed, with a swirling aqua iris
ell(24, 9.5, 5.5, 3.1, 'k'); ell(24, 9.5, 4.6, 2.3, 'W'); ell(24, 9.5, 2.5, 2.5, 'A')
for (x, y) in ((23, 8), (24, 8), (25, 9), (25, 10), (24, 11), (23, 10)): put(x, y, 'O')
put(24, 9, 'k'); put(24, 10, 'k')
for x in (19, 21, 27, 29): put(x, 6, 'd')
# --- four sunken eyes: deep dark sockets, small round whites, pinprick pupils
for (ex, ey) in ((20.5, 15.5), (27.5, 15.5), (16.5, 20.5), (31.5, 20.5)):
    ell(ex, ey, 3.0, 2.6, 'd'); ell(ex, ey, 1.8, 1.5, 'W'); put(int(ex), int(ey), 'k')
    put(int(ex) - 2, int(ey) + 2, 's'); put(int(ex) + 1, int(ey) + 2, 's')
# heavy brows
line(17, 12, 22, 13, 'd'); line(26, 13, 31, 12, 'd'); line(13, 17, 18, 18, 'd'); line(30, 18, 35, 17, 'd')
# --- the long nose: a shaded ridge and a heavy bulb
line(24, 13, 24, 24, 's'); line(25, 14, 25, 23, 'S'); ell(24, 25, 2.6, 1.6, 's'); put(23, 26, 'k'); put(25, 26, 'k')
# --- the grimace: thin lips, clenched teeth
ell(24, 29.5, 4.2, 1.7, 's'); ell(24, 29.5, 3.4, 1.0, 'M')
for x in range(21, 28): put(x, 29, 'T' if x % 2 else 'W')
put(20, 29, 'k'); put(28, 29, 'k')
# a shadow under the chin where the face sinks into the chest
for x in range(20, 29): put(x, 33, 's')
for x in range(19, 30): put(x, 34, 'b')
# --- chest folds and the navel
for x in range(14, 35): put(x, 36, 's') if x % 3 else None
put(24, 47, 'k'); put(23, 47, 'b'); put(25, 47, 'b')
for y in range(H):
    for x in range(W):
        if g[y][x] == 'B' and ((x + .5 - 24) / 13) ** 2 + ((y + .5 - 43) / 10) ** 2 > 0.62 and (x - 24) + (y - 43) > 2: put(x, y, 'b')
# --- the bent arm: upper arm down the side, a thick furry forearm across the chest
poly([(13, 20), (4, 26), (2, 36), (5, 43), (11, 42), (9, 33), (13, 27)], 'F')
poly([(4, 42), (8, 48), (21, 45), (22, 40), (17, 39), (8, 40)], 'F')
# the bony hand clutching the chest, long fingers with dark nails
poly([(17, 39), (23, 37), (25, 40), (21, 44), (17, 43)], 's')
for (x0, y0, x1, y1) in ((23, 37, 28, 36), (24, 39, 29, 39), (23, 41, 28, 42), (21, 43, 24, 46)): line(x0, y0, x1, y1, 's')
for (x, y) in ((28, 36), (29, 39), (28, 42), (24, 46)): put(x, y, 'W')
for (x, y) in ((22, 38), (23, 40), (22, 42)): put(x, y, 'S')
for (x0, y0, x1, y1) in ((23, 38, 27, 37), (24, 40, 28, 40), (23, 42, 27, 43)): line(x0, y0 + 0, x1, y1, 'b')
# --- fur: dark streaks and light strands, combed downward
import random
random.seed(7)
for _ in range(260):
    x = random.randrange(W); y = random.randrange(H)
    if g[y][x] == 'F':
        n = random.randint(2, 5); c = 'd' if random.random() < 0.6 else 'f'
        for k in range(n):
            yy = y + k; xx = x + (1 if (k == n - 1 and x > 24) else (-1 if k == n - 1 else 0))
            if 0 <= yy < H and g[yy][xx] == 'F': put(xx, yy, c)
# straggly hair ends at the edges
for (x, y) in ((3, 18), (4, 16), (44, 18), (45, 21), (12, 63), (36, 63), (46, 46), (2, 40), (8, 6), (40, 6)):
    put(x, y, 'd')
rows = [''.join(r) for r in g]
open('rows.txt', 'w').write('\n'.join(rows))
COL = {'F': (0x6E, 0x44, 0x28), 'f': (0xA8, 0x77, 0x4C), 'd': (0x3D, 0x24, 0x14), 'S': (0xE4, 0xD8, 0xBC), 's': (0xB8, 0x9E, 0x78),
       'k': (0x20, 0x14, 0x18), 'W': (0xF7, 0xEF, 0xDC), 'A': (0x3F, 0xB8, 0xAF), 'O': (0xE8, 0x74, 0x2C), 'o': (0xF4, 0xA2, 0x59),
       'B': (0xEA, 0xDE, 0xC2), 'b': (0xC9, 0xB4, 0x8E), 'M': (0x3D, 0x14, 0x14), 'T': (0xE8, 0xE0, 0xC8), 'r': (0xB8, 0x52, 0x1B)}
img = Image.new('RGBA', (W + 2, H + 2), (0, 0, 0, 0))
def filled(x, y): return 0 <= x < W and 0 <= y < H and g[y][x] != '.'
for y in range(H + 2):
    for x in range(W + 2):
        gx, gy = x - 1, y - 1
        if filled(gx, gy): img.putpixel((x, y), COL[g[gy][gx]] + (255,))
        elif any(filled(gx + dx, gy + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))): img.putpixel((x, y), (0x3D, 0x24, 0x14, 255))
img.resize(((W + 2) * 8, (H + 2) * 8), Image.NEAREST).save('creature.png')
print('ok')
