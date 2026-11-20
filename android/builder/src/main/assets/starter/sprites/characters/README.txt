CHARACTERS (people and creatures)
House style: 24 x 24 px per frame, four directions, a three-frame walk. Bigger creatures
(32 x 32, 48 x 48, up to 128 x 128) work the same way.

Four directions: one PNG with a row per direction, top to bottom:
    row 1  facing you (down)
    row 2  facing left
    row 3  facing right
    row 4  facing away (up)
and the frames left to right. Name it with _4dir and the frame count:
    knight_4dir_f3.png  =  3 frames x 4 rows  =  72 x 96 px for 24 x 24 frames
With 3 frames: frame 1 is standing, frames 2 and 3 are the two steps (played
step, stand, other step, stand). With 2 or 4 frames they simply loop while walking.

Single direction (older style): frames side by side, name_f2.png; they're mirrored to
face left and right.

Format:  PNG with transparency. The bottom-centre of each frame is where it stands.
The player: a file named player (player_4dir_f3.png, or player.png) is who you play.

Easiest way: the Builder's PIXEL STUDIO (NEW -> Character) sets all of this up for you,
with ▶ TO ◀ to mirror the right-facing row into the left.

In the Builder: give each character lines to say, a voice sound, a facing (FACE), and
whether it wanders about (it then faces the way it walks, and turns to face you to talk).
