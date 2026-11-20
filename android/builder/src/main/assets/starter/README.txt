5IY BUILDER: YOUR WORLD FOLDER
==============================

Everything the Builder uses lives in this folder. Drop files into the right sub-folder,
from a file manager on the phone, a computer over USB, or the Builder's own "+" button,
and they appear in the palette within a couple of seconds, even while the Builder is open.
The Builder never changes or deletes your own files.

  sprites/tiles/       Floor you can walk on. 16 x 16 px.
  sprites/walls/       Blocks the way (walls, water, hedges, shelves). 16 x 16 px.
  sprites/props/       Things that stand on the floor and block (pillars, barrels, trees).
  sprites/decor/       Things you walk over or past (rugs, banners, cracks, torches).
  sprites/characters/  People and creatures; the player is "player".
  sprites/kiosks/      Things you use with A: shops, signs, shrines, chests, teleporters.
  sprites/items/       Things you pick up by walking over them (coins, gems, keys).
  sounds/              Short sound effects.
  music/               Music that loops in a room.
  worlds/              Your saved worlds (.json). Made by the Builder; back them up.

Each folder has its own README with sizes and formats. The short version:

  * Pictures: PNG with a transparent background, drawn at 1 pixel = 1 pixel (no
    upscaling; the Builder scales without blurring, from half size up to 4x, per object).
  * The room is 240 x 176 px: 15 x 11 tiles of 16 px.
  * The bottom edge of a sprite is where it stands on the floor.
  * Animation: put the frames side by side in one PNG, all the same width, and end the
    name with _f and the number of frames, e.g. torch_f3.png. They play at 6 frames a second.
  * Characters face four ways: rows down, left, right, up in one PNG named like
    hero_4dir_f3.png (see sprites/characters/README.txt). The house style is 24 x 24.
  * Detail sprites: draw at 2, 3 or 4 picture pixels per world pixel for a smoother,
    hand-painted look, and add @2x, @3x or @4x to the end of the name (knight_4dir_f3@4x.png
    is 96 x 96 a frame and stands 24 x 24 in the world). They're drawn smoothly; plain ones
    stay crisp. If both exist, the detailed one is used.
  * Draw your own in the Builder's PIXEL STUDIO (the ✎ PIXEL button); it saves here.
  * Names: lowercase letters, numbers and underscores (my_tree.png). The name is the label.
  * Sounds: WAV or OGG. Music: OGG, MP3 or WAV.
  * Changed a file? Save over it with the same name: everything using it updates.
    Removed one? Things using it show a "?" box until it's back. Nothing breaks.

Limits (larger files are skipped and listed under Problems in the Builder's menu):
pictures at most 2048 px a side, 4 million pixels and 8 MB; sounds at most 1 MB; music at most 20 MB.
