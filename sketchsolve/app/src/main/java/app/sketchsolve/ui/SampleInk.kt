package app.sketchsolve.ui

import app.sketchsolve.ink.Pt
import app.sketchsolve.ink.Stroke
import app.sketchsolve.recog.Recognizer
import kotlin.math.*

/**
 * Ready-made "handwriting" and sketches for the tutorial: drawn from the
 * recogniser's own templates with a gentle hand wobble, so the app can
 * read them like real writing.
 */
object SampleInk {
    private var id = 900_000
    private var phase = 0.0

    private fun wob(p: Pt, k: Double = 1.2): Pt {
        phase += 0.37
        return Pt(p.x + k * sin(phase + p.y / 37), p.y + k * cos(phase * 0.8 + p.x / 41))
    }

    fun stroke(pts: List<Pt>, kind: String) = Stroke(id++, kind, pts)

    fun line(a: Pt, b: Pt, n: Int = 18) = List(n + 1) { wob(Pt(a.x + (b.x - a.x) * it / n, a.y + (b.y - a.y) * it / n)) }

    fun poly(pts: List<Pt>): List<Pt> {
        val out = ArrayList<Pt>()
        val P = pts + pts[0]
        for (i in 1 until P.size) out += line(P[i - 1], P[i]).drop(if (i > 1) 1 else 0)
        return out
    }

    fun ellipse(cx: Double, cy: Double, rx: Double, ry: Double, a0: Double = -90.0, a1: Double = 290.0, n: Int = 48) =
        List(n + 1) { val a = (a0 + (a1 - a0) * it / n) * PI / 180; wob(Pt(cx + rx * cos(a), cy + ry * sin(a))) }

    /** Write text with the template shapes. '^' raises the next character. */
    fun write(text: String, x0: Double, y: Double, h: Double, kind: String = "ink"): List<Stroke> {
        val out = ArrayList<Stroke>()
        var x = x0
        var sup = false
        for (ch in text) {
            if (ch == '^') { sup = true; continue }
            if (ch == ' ') { x += h * 0.35; continue }
            val variants = Recognizer.BASE[ch.toString()] ?: continue
            val small = ch in "-=+"
            val hh = if (sup) h * .55 else if (small) h * .6 else h
            val yy = if (sup) y - h * .3 else if (small) y + h * .2 else y
            for (st in variants[0]) out += stroke(st.map { wob(Pt(x + hh * it.x, yy + hh * it.y), 0.5) }, kind)
            x += hh * (mapOf('1' to .35, '-' to .7, '=' to .7, '+' to .8, 'x' to .8)[ch] ?: .75) + h * .28
            sup = false
        }
        return out
    }

    /** A wire with a zigzag resistor in the middle, as one stroke. */
    fun zigWire(a: Pt, b: Pt, amp: Double = 11.0, peaks: Int = 6): List<Pt> {
        val d = b - a; val u = d.norm(); val n = Pt(-u.y, u.x)
        val p0 = a + d * 0.35; val p1 = a + d * 0.65
        val pts = ArrayList<Pt>(line(a, p0))
        val zz = arrayListOf(p0)
        for (k in 1..peaks) zz += p0 + (p1 - p0) * ((k - 0.5) / peaks) + n * (if (k % 2 == 0) -amp else amp)
        zz += p1
        for (i in 1 until zz.size) pts += line(zz[i - 1], zz[i], 4).drop(1)
        pts += line(p1, b).drop(1)
        return pts
    }

    /** Equations tutorial: "2x + 3 = 11", ready to circle the x. */
    fun equation() = write("2x+3=11", 80.0, 110.0, 56.0)

    /** Shapes tutorial: a triangle, ready for brackets. */
    fun triangle() = listOf(stroke(poly(listOf(Pt(120.0, 330.0), Pt(430.0, 330.0), Pt(250.0, 110.0))), "shape"))

    /** Circuits tutorial: a battery and two resistors in a loop. */
    fun seriesCircuit(): List<Stroke> = listOf(
        stroke(line(Pt(110.0, 110.0), Pt(110.0, 200.0)), "shape"), stroke(line(Pt(88.0, 200.0), Pt(132.0, 200.0), 8), "shape"),
        stroke(line(Pt(99.0, 220.0), Pt(121.0, 220.0), 6), "shape"), stroke(line(Pt(110.0, 220.0), Pt(110.0, 330.0)), "shape"),
        stroke(zigWire(Pt(110.0, 110.0), Pt(450.0, 110.0)), "shape"), stroke(zigWire(Pt(450.0, 110.0), Pt(450.0, 330.0)), "shape"),
        stroke(line(Pt(450.0, 330.0), Pt(110.0, 330.0)), "shape"))

    const val SAMPLE_PACK = """# Optics
formula: Thin lens equation
equation: 1/f = 1/d_o + 1/d_i
f: focal length (cm)
d_o: distance from the object to the lens (cm)
d_i: distance from the lens to the image (cm)
keywords: lens, focal, magnifying, image
note: A negative d_i means the image is on the same side as the object.
example: A magnifying glass has a focal length of 10 cm. A stamp is 6 cm away. Where is the image?
---
formula: Magnification
equation: m = -d_i/d_o
m: magnification
d_i: image distance (cm)
d_o: object distance (cm)
keywords: magnification, bigger, smaller
"""
}
