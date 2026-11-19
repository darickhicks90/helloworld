"""The hall's little creature (six 56x56 frames) with his battle axe: each frame goes into a 72x72 cell
(8 px either side, 16 above for the axe head), and the axe stands up from his right-hand fist."""
import sys
from PIL import Image
src = Image.open(sys.argv[1]).convert("RGBA")
FW, FH, PADX, PADY = 72, 72, 8, 16
out = Image.new("RGBA", (FW * 6, FH), (0, 0, 0, 0))
AXE = [
    ".KKK.K.KKK.",
    "KgGGKSKGGgK",
    "KgGGKSKGGgK",
    "KgGGKSKGGgK",
    ".KgGKSKGgK.",
    "..KKKSKKK..",
    "....KHK....",
    "....KHK....",
    "....KhK....",
    "....KHK....",
    "....KHK....",
    "....KhK....",
    "....KHK....",
    "....KHK....",
    "....KhK....",
    "....KHK....",
    "....KHK....",
    "....KKK....",
]
COL = {"K": (24, 24, 32, 255), "G": (196, 204, 214, 255), "g": (122, 128, 144, 255), "S": (64, 66, 78, 255),
       "H": (138, 82, 40, 255), "h": (96, 56, 28, 255)}
# the right-hand fist in each frame (in the 72x72 cell)
HANDS = [(59, 46), (49, 50), (48, 22), (59, 20), (49, 50), (61, 33)]
FIST = (226, 160, 112, 255); FIST_D = (150, 90, 56, 255)
for f in range(6):
    cell = Image.new("RGBA", (FW, FH), (0, 0, 0, 0))
    cell.alpha_composite(src.crop((f * 56, 0, f * 56 + 56, 56)), (PADX, PADY))   # him first; the axe is held in front
    hx, hy = HANDS[f]
    top = hy - 15; left = hx - 5
    for r, row in enumerate(AXE):
        for c, ch in enumerate(row):
            if ch in COL:
                x, y = left + c, top + r
                if 0 <= x < FW and 0 <= y < FH: cell.putpixel((x, y), COL[ch])
    # his fingers close round the haft, in front of it
    for (dx, dy, col) in ((-1, -1, (24, 24, 32, 255)), (0, -1, FIST), (1, -1, (24, 24, 32, 255)), (-1, 0, FIST_D), (0, 0, FIST), (1, 0, FIST_D), (0, 1, FIST_D)):
        cell.putpixel((hx + dx, hy + dy), col)
    out.alpha_composite(cell, (f * FW, 0))
out.save(sys.argv[2])
big = out.resize((out.size[0] * 4, out.size[1] * 4), Image.NEAREST)
bg = Image.new("RGBA", big.size, (230, 230, 236, 255)); bg.paste(big, (0, 0), big); bg.save(sys.argv[3])
