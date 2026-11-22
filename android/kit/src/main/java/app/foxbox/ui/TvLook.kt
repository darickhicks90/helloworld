package app.foxbox.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/**
 * The studio's cabinet: an old wood-grain projection TV console. Walnut veneer
 * around everything, woven speaker-grille cloth behind the rail and the clip
 * bar, and (in [XyPad]) the XY pads as the set's glass screen. Both textures
 * are small tiles drawn in art pixels and repeated, so they stay crisp and cheap.
 */
object Tv {
    // walnut veneer
    const val WOOD = 0xFF6A4128.toInt()
    const val WOOD_L = 0xFF775032.toInt()
    const val WOOD_D = 0xFF56341F.toInt()
    const val WOOD_DD = 0xFF3E2414.toInt()
    // the grille cloth: warm charcoal weave with a few gold threads
    const val CLOTH = 0xFF302A26.toInt()
    const val CLOTH_L = 0xFF3A332E.toInt()
    const val CLOTH_D = 0xFF262120.toInt()
    const val THREAD = 0xFF8C7450.toInt()
    // the screen's bezel and on-screen display
    const val BEZEL = 0xFF1B1815.toInt()
    const val OSD = 0xD00C1012.toInt()

    private fun hash(x: Int, y: Int, s: Int): Int {
        var h = x * 374761393 + y * 668265263 + s * 982451653
        h = (h xor (h ushr 13)) * 1274126177
        return h xor (h ushr 16)
    }

    /** A tileable walnut tile: wavy grain lines that wrap in both directions. */
    private val woodTile: Bitmap by lazy {
        val tw = 192; val th = 64
        val b = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        for (y in 0 until th) for (x in 0 until tw) {
            val fx = x / tw.toFloat(); val fy = y / th.toFloat()
            // long, gently wandering grain (whole cycles across the tile, so it repeats seamlessly)
            val f = fy * 9f + 0.45f * sin(2 * PI * (fx + fy)).toFloat() + 0.18f * sin(2 * PI * (fx * 3 + fy * 2)).toFloat() + 0.06f * sin(2 * PI * fx * 7).toFloat()
            val ring = f - floor(f)
            val band = ((floor(f).toInt() % 3) + 3) % 3
            val n = hash(x, y, 1) and 31
            val col = when {
                ring < 0.07f -> WOOD_DD
                ring < 0.16f -> WOOD_D
                band == 1 && ring > 0.5f && ring < 0.75f -> WOOD_L
                n == 0 -> WOOD_D
                else -> WOOD
            }
            b.setPixel(x, y, col)
        }
        b
    }

    /** A woven tile: alternating over/under threads, with the odd gold and cream fleck. */
    private val clothTile: Bitmap by lazy {
        val s = 64
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        for (y in 0 until s) for (x in 0 until s) {
            val over = ((x / 2) + (y / 2)) % 2 == 0
            var col = if (over) { if (x % 2 == 0) CLOTH_L else CLOTH } else { if (y % 2 == 0) CLOTH else CLOTH_D }
            val h = hash(x, y, 7) and 1023
            if (h < 5) col = THREAD
            else if (h < 7) col = 0xFF9A4A1E.toInt()
            b.setPixel(x, y, col)
        }
        b
    }

    private fun tilePaint(tile: Bitmap): Paint {
        val p = Paint().apply { isAntiAlias = false; isFilterBitmap = false }
        val sh = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        sh.setLocalMatrix(Matrix().apply { setScale(Px.u, Px.u) })
        p.shader = sh
        return p
    }
    val woodPaint: Paint by lazy { tilePaint(woodTile) }
    val clothPaint: Paint by lazy { tilePaint(clothTile) }
    private val solid = Paint().apply { isAntiAlias = false }

    fun wood(c: Canvas, l: Float, t: Float, r: Float, b: Float) = c.drawRect(l, t, r, b, woodPaint)

    /** Grille cloth set into the cabinet: a dark recess line, the cloth, and a lit lower lip. */
    fun grille(c: Canvas, l: Float, t: Float, r: Float, b: Float) {
        val u = Px.u
        solid.color = WOOD_DD; c.drawRect(l, t, r, b, solid)
        c.drawRect(l + u, t + u, r - u, b - u, clothPaint)
        solid.color = Pal.alpha(0xFF000000.toInt(), 0.35f); c.drawRect(l + u, t + u, r - u, t + u * 2, solid); c.drawRect(l + u, t + u, l + u * 2, b - u, solid)
        solid.color = WOOD_L; c.drawRect(l, b - u, r, b, solid)
    }

    /** The cabinet behind the whole studio. */
    class WoodDrawable : Drawable() {
        override fun draw(c: Canvas) {
            val b = bounds
            wood(c, b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.OPAQUE
    }

    /** Speaker cloth behind a strip of the interface (the rail, the clip bar). */
    class GrilleDrawable : Drawable() {
        override fun draw(c: Canvas) {
            val b = bounds
            wood(c, b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
            val u = Px.u
            grille(c, b.left + u, b.top + u, b.right - u, b.bottom - u)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.OPAQUE
    }
}
