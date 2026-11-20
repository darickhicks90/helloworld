"""A mock-up of the duel screen (the hall dimmed behind, his shadow, the windows) to check he sits right."""
import sys
from PIL import Image, ImageDraw
W, H, K = 240, 176, 5
hall = Image.new("RGB", (W, H), (248, 248, 248)); d = ImageDraw.Draw(hall)
WHITE, PALE, MID, DARK = (248, 248, 248), (224, 224, 232), (184, 184, 200), (112, 112, 136)
d.rectangle((0, 0, W, 30), fill=PALE)
for row in range(3):
    for k in range(16):
        off = 0 if row % 2 == 0 else 8
        d.rectangle((k * 16 - off + 1, row * 9 + 1, k * 16 - off + 14, row * 9 + 7), fill=WHITE)
d.rectangle((0, 27, W, 29), fill=MID); d.rectangle((0, 30, W, 30), fill=DARK)
for ty in range(10):
    for tx in range(15):
        x0, y0 = tx * 16, 31 + ty * 16
        d.line((x0, y0, x0 + 16, y0), fill=PALE); d.line((x0, y0, x0, y0 + 16), fill=PALE)
d.rectangle((104, 86, 136, H), fill=PALE); d.rectangle((106, 86, 134, H), fill=WHITE)
d.rectangle((84, 80, 156, 86), fill=MID); d.rectangle((88, 74, 152, 80), fill=PALE); d.rectangle((92, 34, 148, 74), fill=WHITE)
for px in (44, 180):
    d.rectangle((px - 2, 10, px + 18, 14), fill=MID); d.rectangle((px, 14, px + 16, 76), fill=WHITE); d.rectangle((px - 2, 76, px + 18, 80), fill=MID)
over = Image.new("RGBA", (W, H), (16, 16, 32, 0x30)); hall = Image.alpha_composite(hall.convert("RGBA"), over)
big = hall.resize((W * K, H * K), Image.NEAREST)
fig = Image.open(sys.argv[1]).convert("RGBA")
# where his feet are in the picture
fp = fig.load(); fw, fh = fig.size
feet = [x for y in range(fh - 6, fh) for x in range(fw) if fp[x, y][3] > 0]
fl, fr = min(feet), max(feet); footC = (fl + fr) / 2
hT = 108 * K; s_ = hT / fh; wT = int(fw * s_)
footX, baseY = 182 * K, 126 * K
left = footX - footC * s_
sh = Image.new("RGBA", big.size, (0, 0, 0, 0)); sd = ImageDraw.Draw(sh)
sd.ellipse((footX - 30 * K, baseY - 3 * K, footX + 30 * K, baseY + 3 * K), fill=(112, 112, 136, 110))
for fx in (left + (fl + 10) * s_, left + (fr - 10) * s_): sd.ellipse((fx - 10 * K, baseY - 2 * K, fx + 10 * K, baseY + 1 * K), fill=(70, 70, 92, 150))
big = Image.alpha_composite(big, sh)
f2 = fig.resize((wT, hT), Image.NEAREST)
# toned to the dimmed hall
big.alpha_composite(f2, (int(left), int(baseY - hT)))
dd = ImageDraw.Draw(big)
def window(l, t, r, b):
    dd.rectangle((l * K, t * K, r * K, b * K), fill=(248, 248, 248)); dd.rectangle(((l + 1) * K, (t + 1) * K, (r - 1) * K, (b - 1) * K), fill=(16, 16, 24))
    dd.rectangle(((l + 2) * K, (t + 2) * K, (r - 2) * K, (b - 2) * K), fill=(184, 184, 200)); dd.rectangle(((l + 3) * K, (t + 3) * K, (r - 3) * K, (b - 3) * K), fill=(16, 16, 24))
window(4, 4, 100, 38); window(4, 128, 236, 172)
dd.rectangle((26 * K, 66 * K, 74 * K, 124 * K), fill=(60, 104, 208))   # where the traveller stands (a stand-in block)
big.save(sys.argv[2])
