package app.sketchsolve

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import app.sketchsolve.ink.Pt
import app.sketchsolve.ink.Stroke
import app.sketchsolve.ui.modes.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** Renders every page with sample drawings and saves PNGs to build/screens. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {
    private val out = File("build/screens").apply { mkdirs() }

    private fun settle(a: MainActivity) {
        repeat(60) { Thread.sleep(50); shadowOf(Looper.getMainLooper()).idle() }
        a.window.decorView.requestLayout()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun shot(a: MainActivity, name: String) {
        val v = a.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        FileOutputStream(File(out, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shift(list: List<Stroke>, dx: Double, dy: Double) = list.map { s -> Stroke(s.id, s.kind, s.pts.map { Pt(it.x + dx, it.y + dy) }) }

    private fun run(prefix: String) {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        settle(a)
        val eq = a.modes.first { it is EquationsMode } as EquationsMode
        val strokes = Draw.write("2x+3=11", 60.0, 110.0, 54.0) + Draw.write("y=x^2-1", 60.0, 230.0, 54.0)
        val ring = Draw.s(Draw.ell(138.0, 137.0, 36.0, 42.0, -90.0, 290.0), "select")
        eq.paper.replaceStrokes(strokes + ring)
        a.select(eq); settle(a); shot(a, "$prefix-1-equations")

        val sh = a.modes.first { it is ShapesMode } as ShapesMode
        val T = listOf(Pt(110.0, 330.0), Pt(420.0, 330.0), Pt(250.0, 90.0))
        val shapeS = listOf(Draw.s(Draw.poly(T), "shape"))
        val marks = listOf(Draw.s(Draw.line(Pt(110.0, 352.0), Pt(420.0, 352.0)), "mark")) + Draw.write("9", 250.0, 360.0, 30.0, "mark") +
            listOf(Draw.s(Draw.line(Pt(92.0, 318.0), Pt(232.0, 98.0)), "mark")) + Draw.write("6", 120.0, 190.0, 30.0, "mark") +
            listOf(Draw.s(Draw.ell(110.0, 330.0, 55.0, 55.0, 302.0, 360.0, 12), "mark")) + Draw.write("50", 180.0, 288.0, 20.0, "mark")
        val find = Draw.s(Draw.ell(345.0, 205.0, 40.0, 40.0), "find")
        sh.paper.replaceStrokes(shapeS + marks + find)
        a.select(sh); settle(a); shot(a, "$prefix-2-shapes")

        val ci = a.modes.first { it is CircuitsMode } as CircuitsMode
        val loop = listOf(
            Draw.s(Draw.line(Pt(100.0, 100.0), Pt(100.0, 190.0)), "shape"), Draw.s(Draw.line(Pt(80.0, 190.0), Pt(120.0, 190.0), 8), "shape"),
            Draw.s(Draw.line(Pt(90.0, 210.0), Pt(110.0, 210.0), 6), "shape"), Draw.s(Draw.line(Pt(100.0, 210.0), Pt(100.0, 320.0)), "shape"),
            Draw.s(Draw.zigWire(Pt(100.0, 100.0), Pt(440.0, 100.0)), "shape"), Draw.s(Draw.zigWire(Pt(440.0, 100.0), Pt(440.0, 320.0)), "shape"),
            Draw.s(Draw.line(Pt(440.0, 320.0), Pt(100.0, 320.0)), "shape"))
        val cs = shift(loop, 10.0, 20.0)
        val vals = Draw.write("12", 40.0, 250.0, 26.0, "mark") + Draw.write("4", 262.0, 60.0, 26.0, "mark") + Draw.write("2", 478.0, 210.0, 26.0, "mark")
        ci.paper.replaceStrokes(cs + vals + Draw.s(Draw.ell(280.0, 120.0, 70.0, 34.0), "find"))
        a.select(ci); settle(a); shot(a, "$prefix-3-circuits")

        a.select(a.modes.first { it is FormulasMode }); settle(a); shot(a, "$prefix-4-formulas")
        a.select(a.modes.first { it is TeachMode }); settle(a); shot(a, "$prefix-5-teach")
    }

    @Test @Config(sdk = [34], qualifiers = "w800dp-h360dp-land-hdpi") fun phone() = run("phone")
    @Test @Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi") fun tablet() = run("tablet")
}
