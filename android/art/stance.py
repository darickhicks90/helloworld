"""A new pose for the duel: front toward the traveller, semi-crouched, arms out ready to grab, a battle axe
in one hand. Built from the title art's symmetric full-body frame (322): the legs bowed and shortened into
a crouch, the arms swung up and out from the shoulders, a front face painted on (five eyes), and an axe."""
import json, math, sys
from PIL import Image, ImageDraw

A = sys.argv[1]                      # assets/images
d = json.load(open(A + "/title_story.json")); sheet = Image.open(A + "/yeti_frames.png").convert("RGBA")
x, y, w, h, _, _ = d["frames"][322]
src = sheet.crop((x, y, x + w, y + h))   # 137 x 191

CW, CH = 300, 205
OX = 82                                # where the source's x=0 sits on the canvas
GROUND = 200                            # feet rest here
D = 18                                  # how far the body drops into the crouch

def mask(poly, size=(w, h)):
    m = Image.new("L", size, 0); ImageDraw.Draw(m).polygon(poly, fill=255); return m

left_arm = [(4, 22), (40, 18), (38, 40), (35, 62), (33, 100), (31, 114), (30, 134), (0, 134), (2, 100), (10, 60)]
right_arm = [(w - 1 - px, py) for (px, py) in left_arm]
def cut(img, m):
    out = Image.new("RGBA", img.size, (0, 0, 0, 0)); out.paste(img, (0, 0), m); return out

la = cut(src, mask(left_arm)); ra = cut(src, mask(right_arm))
body_m = Image.new("L", (w, h), 255)
dr = ImageDraw.Draw(body_m); dr.polygon(left_arm, fill=0); dr.polygon(right_arm, fill=0)
torso_m = body_m.copy(); ImageDraw.Draw(torso_m).rectangle((0, 104, w, h), fill=0)
# round the sides where the arms were cut away (a waist that narrows a little toward the hips)
sil = Image.new("L", (w, h), 0)
ImageDraw.Draw(sil).polygon([(46, 0), (91, 0), (99, 14), (101, 34), (98, 60), (95, 84), (97, 106), (40, 106), (42, 84), (39, 60), (36, 34), (38, 14)], fill=255)
from PIL import ImageChops
torso_m = ImageChops.multiply(torso_m, sil)
torso_m = Image.composite(Image.new("L", (w, h), 255), torso_m, Image.composite(sil, Image.new("L", (w, h), 0), body_m.point(lambda v: 0)))
legs_m = body_m.copy(); ImageDraw.Draw(legs_m).rectangle((0, 0, w, 103), fill=0)
torso = cut(src, torso_m); legs = cut(src, legs_m)

canvas = Image.new("RGBA", (CW, CH), (0, 0, 0, 0))
base_y = GROUND - h                    # source y=0 on the canvas when standing

# ---- legs: bowed out and shortened (a crouch), feet kept flat
def leg(xa, xb, k):
    part = legs.crop((xa, 104, xb, h))
    pw, ph = part.size
    nh = GROUND - (base_y + D + 104) + 3
    part = part.resize((pw, nh), Image.NEAREST)
    lay = Image.new("RGBA", (CW, CH), (0, 0, 0, 0))
    for yy in range(nh):
        shift = int(k * yy)
        row = part.crop((0, yy, pw, yy + 1))
        lay.paste(row, (OX + xa + shift, GROUND - nh + yy), row)
    return lay
canvas = Image.alpha_composite(canvas, leg(34, 69, -0.42))
canvas = Image.alpha_composite(canvas, leg(68, 103, 0.42))

# ---- arms: swung up and out from the shoulders (behind the torso so the shoulders blend)
def arm(img, pivot, ang):
    lay = Image.new("RGBA", (CW, CH), (0, 0, 0, 0))
    lay.paste(img, (OX, base_y + D), img)
    px, py = pivot[0] + OX, pivot[1] + base_y + D
    return lay.rotate(ang, resample=Image.NEAREST, center=(px, py))
arms = Image.new("RGBA", (CW, CH), (0, 0, 0, 0))
arms = Image.alpha_composite(arms, arm(la, (30, 30), -42))
arms = Image.alpha_composite(arms, arm(ra, (w - 31, 30), 42))

# ---- the axe, in the right-hand (viewer's right) grip, raised
def hand_after(pivot, hand, ang):
    a = math.radians(ang); dx, dy = hand[0] - pivot[0], hand[1] - pivot[1]
    return (pivot[0] + math.cos(a) * dx + math.sin(a) * dy + OX, pivot[1] - math.sin(a) * dx + math.cos(a) * dy + base_y + D)
gx, gy = hand_after((w - 31, 30), (w - 18, 120), 42)
axe = Image.new("RGBA", (CW, CH), (0, 0, 0, 0)); ad = ImageDraw.Draw(axe)
INK = (40, 22, 16, 255)
hx0, hy0 = gx - 16, gy + 34            # butt of the haft
hx1, hy1 = gx + 20, gy - 84            # top of the haft
for t in range(0, 101):
    f = t / 100; px = hx0 + (hx1 - hx0) * f; py = hy0 + (hy1 - hy0) * f
    ad.rectangle((px - 2.5, py - 1, px + 2.5, py + 1), fill=INK)
for t in range(0, 101):
    f = t / 100; px = hx0 + (hx1 - hx0) * f; py = hy0 + (hy1 - hy0) * f
    col = (118, 72, 38, 255) if (t // 6) % 2 == 0 else (98, 58, 30, 255)
    ad.rectangle((px - 1.5, py - 1, px + 1.5, py + 1), fill=col)
    if t % 3 == 0: ad.point((px - 1, py), fill=(150, 98, 56, 255))
# leather wrap and pommel
for t in (6, 9, 12, 15):
    f = t / 100; px = hx0 + (hx1 - hx0) * f; py = hy0 + (hy1 - hy0) * f
    ad.rectangle((px - 3, py - 1, px + 3, py), fill=(70, 40, 26, 255))
ad.ellipse((hx0 - 4, hy0 - 3, hx0 + 4, hy0 + 4), fill=INK); ad.ellipse((hx0 - 3, hy0 - 2, hx0 + 3, hy0 + 3), fill=(150, 150, 160, 255))
# the head: a double-bitted head, crescent blades, dark iron with bright edges and a rune
cx, cy = hx0 + (hx1 - hx0) * 0.86, hy0 + (hy1 - hy0) * 0.86
def blade(sign):
    pts = []
    for i in range(0, 21):
        a = math.radians(-70 + i * 7)
        pts.append((cx + sign * (10 + 24 * math.cos(a)), cy + 26 * math.sin(a)))
    pts += [(cx + sign * 9, cy + 9), (cx + sign * 4, cy + 5), (cx + sign * 4, cy - 5), (cx + sign * 9, cy - 9)]
    return pts
for s in (-1, 1):
    ad.polygon(blade(s), fill=INK)
    inner = [(cx + (px - cx) * 0.9, cy + (py - cy) * 0.9) for (px, py) in blade(s)]
    ad.polygon(inner, fill=(92, 96, 108, 255))
    edge = [(cx + s * (10 + 22 * math.cos(math.radians(-70 + i * 7))), cy + 24 * math.sin(math.radians(-70 + i * 7))) for i in range(0, 21)]
    ad.line(edge, fill=(214, 220, 230, 255), width=2)
    ad.line([(cx + s * 12, cy - 12), (cx + s * 18, cy - 4), (cx + s * 12, cy + 4)], fill=(150, 156, 170, 255), width=1)
ad.rectangle((cx - 6, cy - 12, cx + 6, cy + 12), fill=INK); ad.rectangle((cx - 4, cy - 10, cx + 4, cy + 10), fill=(70, 72, 84, 255))
ad.line([(cx - 2, cy - 6), (cx + 2, cy - 2), (cx - 2, cy + 2), (cx + 2, cy + 6)], fill=(214, 120, 60, 255), width=1)   # a glowing rune
ad.polygon([(cx - 3, cy - 12), (cx + 3, cy - 12), (cx, cy - 20)], fill=INK); ad.polygon([(cx - 1, cy - 13), (cx + 1, cy - 13), (cx, cy - 18)], fill=(170, 176, 190, 255))

# ---- torso and head, dropped into the crouch
tl = Image.new("RGBA", (CW, CH), (0, 0, 0, 0)); tl.paste(torso, (OX, base_y + D), torso)

# ---- a front face (five eyes), painted in the art's own browns
fx, fy = OX + 68, base_y + D           # top centre of the head
muz = Image.new("RGBA", (CW, CH), (0, 0, 0, 0)); mz = ImageDraw.Draw(muz)
mz.ellipse((fx - 14, fy + 8, fx + 14, fy + 46), fill=(214, 160, 112, 110))
mz.ellipse((fx - 9, fy + 30, fx + 9, fy + 46), fill=(226, 176, 128, 120))
tl = Image.alpha_composite(tl, muz)
f = ImageDraw.Draw(tl)
SKIN = (196, 140, 98, 255); SKIN_L = (222, 172, 128, 255); SKIN_D = (150, 98, 66, 255); DARK = (64, 34, 22, 255); WHITE = (244, 236, 222, 255)
def eye(cx_, cy_, rw, rh, iris):
    f.ellipse((cx_ - rw - 1, cy_ - rh - 1, cx_ + rw + 1, cy_ + rh + 1), fill=DARK)
    f.ellipse((cx_ - rw, cy_ - rh, cx_ + rw, cy_ + rh), fill=WHITE)
    f.ellipse((cx_ - iris, cy_ - iris, cx_ + iris, cy_ + iris), fill=(196, 92, 36, 255))
    f.rectangle((cx_ - max(1, iris // 2), cy_ - max(1, iris // 2), cx_ + max(1, iris // 2) - 1, cy_ + max(1, iris // 2) - 1), fill=(20, 10, 8, 255))
    f.point((cx_ - iris + 1, cy_ - iris + 1), fill=(255, 255, 255, 255))
eye(fx, fy + 15, 6, 4, 3)                          # the great eye on the brow
f.line([(fx - 9, fy + 9), (fx - 3, fy + 11)], fill=DARK, width=2); f.line([(fx + 9, fy + 9), (fx + 3, fy + 11)], fill=DARK, width=2)
for sx in (-1, 1):
    eye(fx + sx * 8, fy + 24, 3, 2, 2)              # two beside the nose
    eye(fx + sx * 13, fy + 30, 2, 2, 1)             # two small ones lower out
    f.line([(fx + sx * 4, fy + 20), (fx + sx * 11, fy + 21)], fill=DARK, width=1)   # scowling brows
f.rectangle((fx - 1, fy + 24, fx + 1, fy + 36), fill=SKIN_L); f.line([(fx + 2, fy + 25), (fx + 2, fy + 36)], fill=SKIN_D)
f.ellipse((fx - 4, fy + 34, fx + 4, fy + 39), fill=SKIN_D); f.point((fx - 2, fy + 37), fill=DARK); f.point((fx + 2, fy + 37), fill=DARK)
f.line([(fx - 9, fy + 42), (fx - 4, fy + 41), (fx + 4, fy + 41), (fx + 9, fy + 42)], fill=DARK, width=2)     # a snarl
f.polygon([(fx - 6, fy + 42), (fx - 4, fy + 42), (fx - 5, fy + 46)], fill=WHITE); f.polygon([(fx + 4, fy + 42), (fx + 6, fy + 42), (fx + 5, fy + 46)], fill=WHITE)

out = Image.new("RGBA", (CW, CH), (0, 0, 0, 0))
out = Image.alpha_composite(out, arms)
out = Image.alpha_composite(out, canvas)
out = Image.alpha_composite(out, tl)
# deltoids: round caps of fur over each joint
patch = src.crop((52, 40, 84, 72))
for (px_, sgn) in ((30, -1), (w - 31, 1)):
    m = Image.new("L", patch.size, 0); ImageDraw.Draw(m).ellipse((0, 2, 31, 31), fill=255)
    cap = Image.new("RGBA", patch.size, (0, 0, 0, 0)); cap.paste(patch, (0, 0), m)
    out.alpha_composite(cap, (int(OX + px_ - 16 + sgn * 2), int(base_y + D + 14)))
out = Image.alpha_composite(out, axe)
# the fingers close over the haft
g = ImageDraw.Draw(out)
for k in range(3):
    g.rectangle((gx - 6 + k * 1, gy - 4 + k * 4, gx + 4 + k * 1, gy - 2 + k * 4), fill=(176, 120, 82, 255)); g.line([(gx - 6 + k, gy - 2 + k * 4), (gx + 4 + k, gy - 2 + k * 4)], fill=DARK)
# a one-pixel ink outline wherever the figure meets the air, and a soft rim shadow inside it
px = out.load(); W_, H_ = out.size
edge = []
for yy in range(H_):
    for xx in range(W_):
        if px[xx, yy][3] > 0: continue
        if any(0 <= xx + ax < W_ and 0 <= yy + ay < H_ and px[xx + ax, yy + ay][3] > 128 for ax, ay in ((1, 0), (-1, 0), (0, 1), (0, -1))): edge.append((xx, yy))
for (xx, yy) in edge: px[xx, yy] = (44, 22, 14, 255)
# clear stray specks (pixels with no opaque neighbours)
for yy in range(H_):
    for xx in range(W_):
        if px[xx, yy][3] == 0: continue
        n = sum(1 for ax in (-1, 0, 1) for ay in (-1, 0, 1) if (ax or ay) and 0 <= xx + ax < W_ and 0 <= yy + ay < H_ and px[xx + ax, yy + ay][3] > 0)
        if n <= 1: px[xx, yy] = (0, 0, 0, 0)
bb = out.getbbox(); out = out.crop(bb)
out.save(sys.argv[2])
print(out.size, bb)
