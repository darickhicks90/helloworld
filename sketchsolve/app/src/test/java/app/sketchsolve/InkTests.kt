package app.sketchsolve

import app.sketchsolve.circuit.Circuit
import app.sketchsolve.circuit.CompType
import app.sketchsolve.ink.*
import app.sketchsolve.math.*
import app.sketchsolve.recog.Layout
import app.sketchsolve.recog.Recognizer
import app.sketchsolve.shapes.Shapes
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** Fake handwriting built from the templates, plus simple drawing helpers. */
object Draw {
    private var seed = 7
    private fun rnd(): Double { seed = ((seed.toLong() * 16807) % 2147483647).toInt(); return seed / 2147483647.0 }
    var nextId = 1000
    // a smooth hand wobble (slow drift) plus a little sensor noise
    private var phase = 0.0
    fun wob(p: Pt, k: Double = 1.5): Pt {
        phase += 0.35
        return Pt(p.x + k * sin(phase + p.y / 40) + (rnd() - .5) * 0.6, p.y + k * cos(phase * 0.8 + p.x / 40) + (rnd() - .5) * 0.6)
    }
    fun line(a: Pt, b: Pt, n: Int = 20) = List(n + 1) { wob(Pt(a.x + (b.x - a.x) * it / n, a.y + (b.y - a.y) * it / n)) }
    fun poly(pts: List<Pt>, closed: Boolean = true): List<Pt> {
        val P = if (closed) pts + pts[0] else pts
        val out = ArrayList<Pt>()
        for (i in 1 until P.size) out += line(P[i - 1], P[i]).drop(if (i > 1) 1 else 0)
        return out
    }
    fun ell(cx: Double, cy: Double, rx: Double, ry: Double, a0: Double = 0.0, a1: Double = 360.0, n: Int = 60) =
        List(n + 1) { val a = (a0 + (a1 - a0) * it / n) * PI / 180; wob(Pt(cx + rx * cos(a), cy + ry * sin(a))) }
    fun s(pts: List<Pt>, kind: String = "ink") = Stroke(nextId++, kind, pts)

    fun write(text: String, x0: Double, y: Double, h: Double = 40.0, kind: String = "ink"): List<Stroke> {
        val out = ArrayList<Stroke>()
        var x = x0
        var sup = false
        for (ch in text) {
            if (ch == '^') { sup = true; continue }
            if (ch == '.') { out += s(listOf(Pt(x, y + h * .95), Pt(x + 1, y + h * .97), Pt(x + 2, y + h)), kind); x += h * .3; continue }
            val small = ch in "-=+"
            val hh = if (sup) h * .55 else if (small) h * .6 else h
            val yy = if (sup) y - h * .3 else if (small) y + h * .2 else y
            for (st in Recognizer.BASE.getValue(ch.toString())[0]) out += s(st.map { wob(Pt(x + hh * it.x, yy + hh * it.y), 0.5) }, kind)
            x += hh * (mapOf('1' to .35, '-' to .7, '=' to .7, '+' to .8, 'x' to .8)[ch] ?: .75) + h * .25
            sup = false
        }
        return out
    }

    /** Wire with a zigzag resistor in the middle, as one stroke. */
    fun zigWire(a: Pt, b: Pt, z0: Double = 0.35, z1: Double = 0.65, amp: Double = 12.0, peaks: Int = 6): List<Pt> {
        val d = b - a; val u = d.norm(); val n = Pt(-u.y, u.x)
        val p0 = a + d * z0; val p1 = a + d * z1
        val pts = ArrayList<Pt>()
        pts += line(a, p0)
        val zz = ArrayList<Pt>()
        zz += p0
        for (k in 1..peaks) zz += p0 + (p1 - p0) * ((k - 0.5) / peaks) + n * (if (k % 2 == 0) -amp else amp)
        zz += p1
        for (i in 1 until zz.size) pts += line(zz[i - 1], zz[i], 4).drop(1)
        pts += line(p1, b).drop(1)
        return pts
    }
}

class RecognitionTest {
    private fun read(strokes: List<Stroke>) = Layout.analyze(strokes).first.map { it.text }

    @Test fun selfConsistency() {
        var ok = 0; var tot = 0
        for ((label, variants) in Recognizer.BASE) for (v in variants) {
            val strokes = v.map { st -> st.map { Draw.wob(Pt(100 + 50 * it.x, 100 + 50 * it.y), 0.6) } }
            tot++
            if (Recognizer.recognize(strokes)[0].label == label) ok++ else println("miss $label -> ${Recognizer.recognize(strokes)[0].label}")
        }
        assertTrue("$ok/$tot", ok >= tot - 2)
    }

    @Test fun lines() {
        assertEquals(listOf("2x+3=11"), read(Draw.write("2x+3=11", 50.0, 50.0)))
        assertEquals(listOf("x^(2)+1"), read(Draw.write("x^2+1", 50.0, 50.0)))
        assertEquals(listOf("y=2x", "x=3"), read(Draw.write("y=2x", 50.0, 50.0) + Draw.write("x=3", 50.0, 150.0)))
        assertEquals(listOf("2.5"), read(Draw.write("2.5", 50.0, 50.0)))
        val frac = Draw.write("x=", 20.0, 60.0) + Draw.write("1", 100.0, 30.0) + Draw.s(Draw.line(Pt(85.0, 88.0), Pt(135.0, 88.0))) + Draw.write("2", 95.0, 100.0)
        assertEquals(listOf("x=(1)/(2)"), read(frac))
        assertEquals(1 to 4, Layout.balanceRange("(1+2)/(3)", 1, 4))
        assertEquals(0 to 9, Layout.balanceRange("(1+2)/(3)", 0, 3))
    }
}

class ShapeTest {
    private fun rec(vararg strokes: List<Pt>) = Shapes.recognise(strokes.toList())?.type

    @Test fun shapes() {
        assertEquals("triangle", rec(Draw.poly(listOf(Pt(100.0, 300.0), Pt(320.0, 300.0), Pt(180.0, 120.0)))))
        assertEquals("rightTriangle", rec(Draw.poly(listOf(Pt(100.0, 300.0), Pt(300.0, 300.0), Pt(100.0, 150.0)))))
        assertEquals("rectangle", rec(Draw.line(Pt(100.0, 100.0), Pt(300.0, 100.0)), Draw.line(Pt(300.0, 100.0), Pt(300.0, 220.0)), Draw.line(Pt(300.0, 220.0), Pt(100.0, 220.0)), Draw.line(Pt(100.0, 220.0), Pt(100.0, 100.0))))
        assertEquals("square", rec(Draw.poly(listOf(Pt(100.0, 100.0), Pt(250.0, 100.0), Pt(250.0, 250.0), Pt(100.0, 250.0)))))
        assertEquals("parallelogram", rec(Draw.poly(listOf(Pt(150.0, 100.0), Pt(350.0, 100.0), Pt(300.0, 220.0), Pt(100.0, 220.0)))))
        assertEquals("trapezoid", rec(Draw.poly(listOf(Pt(150.0, 100.0), Pt(270.0, 100.0), Pt(340.0, 220.0), Pt(100.0, 220.0)))))
        assertEquals("regular6", rec(Draw.poly(List(6) { Pt(200 + 100 * cos(it * PI / 3), 200 + 100 * sin(it * PI / 3)) })))
        assertEquals("circle", rec(Draw.ell(200.0, 200.0, 100.0, 96.0, -80.0, 285.0)))
        assertEquals("ellipse", rec(Draw.ell(200.0, 200.0, 140.0, 60.0)))
        assertEquals("sphere", rec(Draw.ell(200.0, 200.0, 100.0, 100.0), Draw.ell(200.0, 200.0, 100.0, 28.0, 0.0, 180.0)))
        assertEquals("cylinder", rec(Draw.ell(200.0, 100.0, 80.0, 22.0), Draw.line(Pt(120.0, 100.0), Pt(120.0, 300.0)), Draw.line(Pt(280.0, 100.0), Pt(280.0, 300.0)), Draw.ell(200.0, 300.0, 80.0, 22.0, 0.0, 180.0)))
        assertEquals("cone", rec(Draw.ell(200.0, 300.0, 90.0, 25.0), Draw.line(Pt(110.0, 300.0), Pt(200.0, 80.0)), Draw.line(Pt(200.0, 80.0), Pt(290.0, 300.0))))
        val F = listOf(Pt(100.0, 300.0), Pt(250.0, 300.0), Pt(250.0, 150.0), Pt(100.0, 150.0)); val Bk = F.map { Pt(it.x + 70, it.y - 60) }
        assertEquals("cube", rec(Draw.poly(F), Draw.line(F[3], Bk[3]), Draw.line(F[2], Bk[2]), Draw.line(F[1], Bk[1]), Draw.line(Bk[3], Bk[2]), Draw.line(Bk[2], Bk[1])))
        val base = listOf(Pt(100.0, 320.0), Pt(260.0, 320.0), Pt(320.0, 270.0), Pt(160.0, 270.0)); val apex = Pt(210.0, 80.0)
        assertEquals("pyramid", rec(Draw.poly(base), Draw.line(apex, base[0]), Draw.line(apex, base[1]), Draw.line(apex, base[2])))
    }

    @Test fun triangleBracketsToAnswer() {
        val T = listOf(Pt(100.0, 300.0), Pt(340.0, 300.0), Pt(180.0, 120.0))
        val r = Shapes.recognise(listOf(Draw.poly(T)))!!
        val model = Shapes.buildModel(r.type, r.fit, Geom.bbox(T))
        val marks = listOf(Draw.s(Draw.line(Pt(100.0, 320.0), Pt(340.0, 320.0)))) + Draw.write("8", 210.0, 330.0, 30.0) +
            Draw.s(Draw.line(Pt(90.0, 290.0), Pt(170.0, 110.0))) + Draw.write("5", 95.0, 170.0, 30.0) +
            Draw.s(Draw.ell(100.0, 300.0, 40.0, 40.0, 300.0, 360.0, 12)) + Draw.write("60", 150.0, 255.0, 18.0)
        val ann = Shapes.annotate(model, marks, listOf(Draw.s(Draw.ell(265.0, 205.0, 35.0, 35.0))))
        assertEquals(mapOf("c" to 8.0, "b" to 5.0, "A" to 60.0), ann.values)
        assertEquals("a", ann.target!!.part!!.v)
        val res = Solver.solveFor(model.def.eqs.map { LabeledEq(Expr.parse(it, true) as Node.Eq) }, ann.values, "a",
            mapOf("A" to Domain.ANGLE, "B" to Domain.ANGLE, "C" to Domain.ANGLE), Domain.POSITIVE)
        assertEquals(7.0, res.value, 1e-6)
    }
}

class CircuitTest {
    private fun eqsKnowns(s: app.sketchsolve.circuit.Schematic, vals: Map<String, Double>, t: String) = Circuit.solve(s, Circuit.equations(s), vals, t)

    /** A loop: battery on the left (long plate on top = +), zigzag resistors on top and right. */
    private fun seriesLoop(): List<Stroke> = listOf(
        Draw.s(Draw.line(Pt(100.0, 100.0), Pt(100.0, 190.0)), "shape"),
        Draw.s(Draw.line(Pt(80.0, 190.0), Pt(120.0, 190.0), 8), "shape"),
        Draw.s(Draw.line(Pt(90.0, 210.0), Pt(110.0, 210.0), 6), "shape"),
        Draw.s(Draw.line(Pt(100.0, 210.0), Pt(100.0, 300.0)), "shape"),
        Draw.s(Draw.zigWire(Pt(100.0, 100.0), Pt(400.0, 100.0)), "shape"),
        Draw.s(Draw.zigWire(Pt(400.0, 100.0), Pt(400.0, 300.0)), "shape"),
        Draw.s(Draw.line(Pt(400.0, 300.0), Pt(100.0, 300.0)), "shape")
    )

    @Test fun seriesCircuit() {
        val s = Circuit.recognise(seriesLoop())!!
        val types = s.components.map { it.type }.sortedBy { it.ordinal }
        assertEquals(listOf(CompType.RESISTOR, CompType.RESISTOR, CompType.BATTERY), types)
        assertEquals("Series circuit", s.topology)
        val vals = mapOf("B1" to 12.0, "R1" to 4.0, "R2" to 2.0)
        assertEquals(2.0, eqsKnowns(s, vals, "I_total").value, 1e-6)
        assertEquals(6.0, eqsKnowns(s, vals, "R_total").value, 1e-6)
        assertEquals(8.0, eqsKnowns(s, vals, "V_R1").value, 1e-6)
        assertEquals(16.0, eqsKnowns(s, vals, "P_R1").value, 1e-6)
        val under = eqsKnowns(s, mapOf("B1" to 12.0, "R1" to 4.0), "I_R1")
        assertEquals(Status.UNDERDETERMINED, under.status)
        assertTrue(under.need.toString(), "R2" in under.need)
    }

    @Test fun handwrittenValuesAndCircle() {
        val strokes = seriesLoop()
        val s = Circuit.recognise(strokes)!!
        val r1 = s.components.first { it.name == "R1" }
        val marks = Draw.write("12", 40.0, 230.0, 28.0, "mark") + Draw.write("4", r1.center.x - 8, 40.0, 28.0, "mark") +
            Draw.write("2", 430.0, 190.0, 28.0, "mark")
        val reading = Circuit.annotate(s, marks, listOf(Draw.s(Draw.ell(r1.center.x, r1.center.y, 60.0, 35.0), "find")), null)
        assertEquals(mapOf("B1" to 12.0, "R1" to 4.0, "R2" to 2.0), reading.values)
        assertEquals("R1", reading.target!!.name)
    }

    @Test fun parallelCircuit() {
        // battery on the left, two resistors as rungs between the top and bottom rails
        val st = listOf(
            Draw.s(Draw.line(Pt(100.0, 100.0), Pt(100.0, 190.0)), "shape"),
            Draw.s(Draw.line(Pt(80.0, 190.0), Pt(120.0, 190.0), 8), "shape"),
            Draw.s(Draw.line(Pt(90.0, 210.0), Pt(110.0, 210.0), 6), "shape"),
            Draw.s(Draw.line(Pt(100.0, 210.0), Pt(100.0, 300.0)), "shape"),
            Draw.s(Draw.line(Pt(100.0, 100.0), Pt(400.0, 100.0)), "shape"),
            Draw.s(Draw.line(Pt(100.0, 300.0), Pt(400.0, 300.0)), "shape"),
            Draw.s(Draw.zigWire(Pt(250.0, 100.0), Pt(250.0, 300.0)), "shape"),
            Draw.s(Draw.zigWire(Pt(400.0, 100.0), Pt(400.0, 300.0)), "shape")
        )
        val s = Circuit.recognise(st)!!
        assertEquals(3, s.components.size)
        assertEquals("Parallel circuit", s.topology)
        val vals = mapOf("B1" to 12.0, "R1" to 6.0, "R2" to 3.0)
        assertEquals(2.0, eqsKnowns(s, vals, "R_total").value, 1e-6)
        assertEquals(6.0, eqsKnowns(s, vals, "I_total").value, 1e-6)
    }

    @Test fun capacitorAndLamp() {
        val st = listOf(
            Draw.s(Draw.line(Pt(100.0, 100.0), Pt(100.0, 190.0)), "shape"),
            Draw.s(Draw.line(Pt(80.0, 190.0), Pt(120.0, 190.0), 8), "shape"),
            Draw.s(Draw.line(Pt(90.0, 210.0), Pt(110.0, 210.0), 6), "shape"),
            Draw.s(Draw.line(Pt(100.0, 210.0), Pt(100.0, 300.0)), "shape"),
            Draw.s(Draw.line(Pt(100.0, 100.0), Pt(225.0, 100.0)), "shape"),
            Draw.s(Draw.ell(250.0, 100.0, 25.0, 25.0), "shape"),
            Draw.s(Draw.line(Pt(232.0, 82.0), Pt(268.0, 118.0), 8), "shape"),
            Draw.s(Draw.line(Pt(268.0, 82.0), Pt(232.0, 118.0), 8), "shape"),
            Draw.s(Draw.line(Pt(275.0, 100.0), Pt(400.0, 100.0)), "shape"),
            Draw.s(Draw.line(Pt(400.0, 100.0), Pt(400.0, 300.0)), "shape"),
            Draw.s(Draw.line(Pt(400.0, 300.0), Pt(100.0, 300.0)), "shape")
        )
        val s = Circuit.recognise(st)!!
        assertEquals(setOf(CompType.LAMP, CompType.BATTERY), s.components.map { it.type }.toSet())
        assertEquals(3.0, eqsKnowns(s, mapOf("B1" to 6.0, "L1" to 2.0), "I_L1").value, 1e-6)

        // capacitor: two equal plates in the bottom wire
        val st2 = st.take(9) + listOf(Draw.s(Draw.line(Pt(400.0, 100.0), Pt(400.0, 300.0)), "shape"),
            Draw.s(Draw.line(Pt(400.0, 300.0), Pt(260.0, 300.0)), "shape"),
            Draw.s(Draw.line(Pt(260.0, 280.0), Pt(260.0, 320.0), 8), "shape"),
            Draw.s(Draw.line(Pt(240.0, 280.0), Pt(240.0, 320.0), 8), "shape"),
            Draw.s(Draw.line(Pt(240.0, 300.0), Pt(100.0, 300.0)), "shape"))
        val s2 = Circuit.recognise(st2)!!
        assertEquals(setOf(CompType.LAMP, CompType.BATTERY, CompType.CAPACITOR), s2.components.map { it.type }.toSet())
        // DC: no current flows once the capacitor is charged; it holds the full battery voltage
        val vals = mapOf("B1" to 6.0, "L1" to 2.0, "C1" to 100.0)
        assertEquals(0.0, eqsKnowns(s2, vals, "I_L1").value, 1e-9)
        assertEquals(600.0, abs(eqsKnowns(s2, vals, "Q_C1").value), 1e-6)
    }

    @Test fun equivalentResistanceBetweenEnds() {
        // two box resistors in series on one wire, open ends at both sides
        val st = listOf(
            Draw.s(Draw.line(Pt(50.0, 200.0), Pt(120.0, 200.0)), "shape"),
            Draw.s(Draw.poly(listOf(Pt(120.0, 188.0), Pt(190.0, 188.0), Pt(190.0, 212.0), Pt(120.0, 212.0))), "shape"),
            Draw.s(Draw.line(Pt(190.0, 200.0), Pt(260.0, 200.0)), "shape"),
            Draw.s(Draw.poly(listOf(Pt(260.0, 188.0), Pt(330.0, 188.0), Pt(330.0, 212.0), Pt(260.0, 212.0))), "shape"),
            Draw.s(Draw.line(Pt(330.0, 200.0), Pt(400.0, 200.0)), "shape")
        )
        val s = Circuit.recognise(st)!!
        assertEquals(2, s.components.count { it.type == CompType.RESISTOR })
        assertEquals(2, s.terminalNodes.size)
        assertEquals(150.0, eqsKnowns(s, mapOf("R1" to 100.0, "R2" to 50.0), "R_total").value, 1e-6)
    }
}
