# Twenty bushes in the bold, outlined style of the wild trees: seven families (leafy, holly,
# pine, fern, flowering, autumn, clumped), each lit from the top left, outlined dark, with
# leaf texture of its own. 32 x 28 cells in one strip; the feet sit on the cell's bottom.
import math, random, sys
from PIL import Image
W, H = 32, 28
OUT = (16, 26, 20, 255)
FAM = {
    'leafy':  [(34, 82, 44), (56, 122, 52), (96, 166, 64), (170, 214, 92)],
    'holly':  [(18, 52, 36), (30, 80, 50), (50, 112, 64), (98, 150, 88)],
    'pine':   [(16, 56, 52), (28, 86, 72), (48, 120, 92), (110, 170, 120)],
    'fern':   [(46, 96, 40), (78, 140, 54), (124, 186, 74), (196, 232, 120)],
    'flower': [(40, 92, 48), (62, 130, 58), (104, 170, 72), (176, 214, 100)],
    'autumn': [(92, 70, 28), (148, 104, 36), (196, 142, 52), (236, 196, 96)],
    'clump':  [(30, 76, 46), (52, 112, 56), (88, 156, 66), (156, 204, 92)],
}
def bush(fam, seed):
    r = random.Random(seed); pal = FAM[fam]
    px = [[None] * W for _ in range(H)]
    lobes = []
    if fam == 'pine':
        # two or three tiers of a short spiky shrub
        cx = 16 + r.uniform(-2, 2)
        for t in range(3):
            y0 = 25 - t * 6; hw = 11 - t * 3 + r.uniform(-1, 1)
            for y in range(int(y0 - 8), int(y0) + 1):
                k = (y0 - y) / 8
                for x in range(W):
                    if abs(x - cx) <= hw * (1 - k) + 0.5 and 0 <= y < H: px[y][x] = 1
        mode = 'pine'
    else:
        n = {'leafy': 5, 'holly': 5, 'fern': 4, 'flower': 5, 'autumn': 5, 'clump': 8}[fam]
        wide = 13 if fam == 'clump' else 10
        for i in range(n):
            a = i / n * math.pi + r.uniform(-0.3, 0.3)
            lx = 16 + math.cos(a) * r.uniform(wide * 0.4, wide); ly = 20 - math.sin(a) * r.uniform(4, 9)
            rad = r.uniform(4.5, 7.5) if fam != 'clump' else r.uniform(4, 6.5)
            lobes.append((lx, ly, rad))
        lobes.append((16, 19, 8.5 if fam != 'clump' else 7))
        if fam == 'clump': lobes += [(9, 21, 6), (23, 21, 6)]
        for y in range(H):
            for x in range(W):
                for (lx, ly, rad) in lobes:
                    if (x + 0.5 - lx) ** 2 + ((y + 0.5 - ly) * 1.15) ** 2 < rad * rad: px[y][x] = 1; break
        mode = 'lobes'
    img = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    for y in range(H):
        for x in range(W):
            if not px[y][x]: continue
            # light from the top left: which lobe's edge is near, how high up
            if mode == 'lobes':
                best = min(lobes, key=lambda L: ((x + 0.5 - L[0]) ** 2 + (y + 0.5 - L[1]) ** 2) / (L[2] ** 2))
                dx = (x + 0.5 - best[0]) / best[2]; dy = (y + 0.5 - best[1]) / best[2]
                l = -dx * 0.55 - dy * 0.75 + 0.15
            else:
                l = -(x - 16) / 14 * 0.5 - (y - 14) / 12 * 0.6
            i = 0 if l < -0.45 else 1 if l < 0.1 else 2 if l < 0.6 else 3
            # leaf texture
            hsh = (x * 73 + y * 151 + seed * 31) % 97
            if fam in ('leafy', 'flower', 'clump', 'autumn') and hsh % 9 == 0: i = max(0, i - 1)
            if fam in ('leafy', 'clump') and hsh % 13 == 0 and i < 3: i += 1
            if fam == 'holly' and (x + y * 2 + seed) % 5 == 0: i = max(0, i - 1)
            if fam == 'pine' and (x * 3 + y) % 4 == 0: i = max(0, i - 1)
            if fam == 'fern' and (x - y + seed) % 3 == 0: i = min(3, i + 1)
            c = pal[i]
            img.putpixel((x, y), (*c, 255))
    # the dark outline
    out = img.copy()
    for y in range(H):
        for x in range(W):
            if img.getpixel((x, y))[3] == 0 and any(0 <= x + a < W and 0 <= y + b < H and img.getpixel((x + a, y + b))[3] > 0 for a, b in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                out.putpixel((x, y), OUT)
    img = out
    def put(x, y, c):
        if 0 <= x < W and 0 <= y < H and img.getpixel((x, y))[3] > 0 and img.getpixel((x, y)) != OUT: img.putpixel((x, y), (*c, 255))
    # family details
    if fam == 'holly':
        for k in range(r.randint(3, 6)):
            x, y = r.randint(8, 24), r.randint(10, 22); put(x, y, (200, 36, 40)); put(x + 1, y, (240, 90, 80))
    if fam == 'flower':
        col = r.choice([(250, 246, 240), (246, 160, 192), (250, 220, 90), (190, 150, 240)])
        for k in range(r.randint(5, 9)):
            x, y = r.randint(6, 26), r.randint(8, 22); put(x, y, col); put(x + 1, y, col); put(x, y - 1, col); put(x + 1, y - 1, (255, 240, 150))
    if fam == 'fern':
        for k in range(5):
            a = math.pi * (0.15 + 0.7 * k / 4); 
            for d in range(2, 12): put(int(16 + math.cos(a) * d), int(24 - math.sin(a) * d * 1.1), pal[3] if d % 2 else pal[1])
    if fam == 'autumn':
        for k in range(6):
            x, y = r.randint(6, 26), r.randint(8, 22); put(x, y, (220, 90, 40))
    if fam == 'clump':
        for k in range(3):
            x, y = r.randint(6, 26), r.randint(12, 22); put(x, y, (240, 230, 180))
    # a shadow line at the foot
    for x in range(W):
        for y in range(H - 1, -1, -1):
            if img.getpixel((x, y))[3] > 0:
                if y + 1 < H and img.getpixel((x, y))[:3] != OUT[:3]: pass
                break
    return img
fams = ['leafy'] * 4 + ['holly'] * 3 + ['pine'] * 3 + ['fern'] * 3 + ['flower'] * 3 + ['autumn'] * 2 + ['clump'] * 2
sheet = Image.new('RGBA', (W * len(fams), H), (0, 0, 0, 0))
for i, f in enumerate(fams): sheet.paste(bush(f, i * 7 + 3), (i * W, 0))
sheet.save(sys.argv[1])
prev = Image.new('RGBA', (W * 10 * 4, H * 2 * 4), (110, 160, 80, 255))
for i in range(len(fams)):
    b = sheet.crop((i * W, 0, i * W + W, H)).resize((W * 4, H * 4), Image.NEAREST)
    prev.paste(b, ((i % 10) * W * 4, (i // 10) * H * 4), b)
prev.save(sys.argv[2]); print('ok', len(fams))
