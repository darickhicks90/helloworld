package app.sketchsolve

import app.sketchsolve.math.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class MathTest {
    private fun eq(s: String, words: Boolean = false) = Expr.parse(s, words) as Node.Eq
    private fun le(s: String, words: Boolean = false) = LabeledEq(eq(s, words))
    private fun near(a: Double, b: Double, tol: Double = 1e-6) = assertTrue("$a != $b", abs(a - b) <= tol * max(1.0, abs(b)))

    @Test fun parsing() {
        assertEquals("2x + 3 = 11", Expr.show(eq("2x+3=11")))
        near(Expr.evaluate(Expr.parse("2x^2+1"), mapOf("x" to 3.0)), 19.0)
        near(Expr.evaluate(Expr.parse("-x^2"), mapOf("x" to 3.0)), -9.0)
        near(Expr.evaluate(Expr.parse("√(16)+√9")), 7.0)
        near(Expr.evaluate(Expr.parse("sin(30)")), 0.5)
        assertEquals(setOf("R_t", "R_1", "R_2"), Expr.vars(Expr.parse("R_t = R_1 + R_2")))
        assertEquals("R₁", Expr.nameOut("R_1"))
        for (bad in listOf("2+", "=3", "(2")) assertNull(bad, Expr.tryParse(bad))
    }

    @Test fun formatting() {
        assertEquals("4", Expr.fmt(4.000000000001))
        assertEquals("0.333333", Expr.fmt(1.0 / 3))
        assertEquals("1.234×10⁻⁵", Expr.fmt(0.00001234))
        assertEquals("6.89775", Expr.fmt(6.897748774))
    }

    @Test fun isolate() {
        assertEquals("(11 − 3)/2", Expr.show(Expr.isolate(eq("2x+3=11"), "x")!!.expr))
        assertEquals("V/I", Expr.show(Expr.isolate(eq("V = I*R", true), "R")!!.expr))
    }

    @Test fun equations() {
        Solver.solveFor(listOf(le("2x+3=11")), emptyMap(), "x").let { assertEquals(Status.SOLVED, it.status); near(it.value, 4.0) }
        Solver.solveFor(listOf(le("x^2-5x+6=0")), emptyMap(), "x").let { assertEquals(Status.MULTIPLE, it.status); near(it.values[0], 2.0); near(it.values[1], 3.0) }
        Solver.solveFor(listOf(le("x+y=10")), emptyMap(), "x").let { assertEquals(Status.UNDERDETERMINED, it.status); assertEquals(listOf("y"), it.need) }
        Solver.solveFor(listOf(le("x+y=10"), le("x-y=2")), emptyMap(), "x").let { near(it.value, 6.0) }
        Solver.solveFor(listOf(le("y=2x+1"), le("x=3")), emptyMap(), "y").let { near(it.value, 7.0) }
        Solver.solveFor(listOf(le("x=3")), emptyMap(), "z").let { assertEquals(Status.NO_EQUATION, it.status) }
        Solver.solveFor(listOf(le("2^x=32")), emptyMap(), "x").let { near(it.value, 5.0) }
        val q = LabeledEq(Node.Eq(Node.Var("__Q"), Expr.parse("x+y")))
        Solver.solveFor(listOf(le("x+y=10"), q), emptyMap(), "__Q").let { assertEquals(Status.SOLVED, it.status); near(it.value, 10.0) }
    }

    private val tri = listOf("A + B + C = 180", "a^2 = b^2 + c^2 - 2*b*c*cos(A)", "b^2 = a^2 + c^2 - 2*a*c*cos(B)",
        "c^2 = a^2 + b^2 - 2*a*b*cos(C)", "Area = 1/2*a*b*sin(C)", "Perimeter = a + b + c", "Area = 1/2*c*h").map { le(it, true) }
    private val dom = mapOf("A" to Domain.ANGLE, "B" to Domain.ANGLE, "C" to Domain.ANGLE)
    private fun solveTri(k: Map<String, Double>, t: String) = Solver.solveFor(tri, k, t, dom, Domain.POSITIVE)

    @Test fun triangles() {
        solveTri(mapOf("a" to 5.0, "b" to 7.0, "C" to 40.0), "c").let { assertEquals(Status.SOLVED, it.status); near(it.value, sqrt(74 - 70 * cos(40 * PI / 180))) }
        solveTri(mapOf("A" to 50.0, "B" to 60.0), "c").let { assertEquals(Status.UNDERDETERMINED, it.status) }
        solveTri(mapOf("b" to 6.0, "c" to 9.0), "a").let { assertEquals(Status.UNDERDETERMINED, it.status); assertTrue(it.need.containsAll(listOf("A", "Area"))) }
        solveTri(mapOf("a" to 3.0, "b" to 4.0, "c" to 5.0), "C").let { near(it.value, 90.0) }
        solveTri(mapOf("a" to 6.0, "b" to 8.0, "A" to 35.0), "c").let { assertEquals(Status.MULTIPLE, it.status) }
        solveTri(mapOf("a" to 1.0, "b" to 2.0, "c" to 10.0), "A").let { assertTrue(it.status == Status.NO_SOLUTION || it.status == Status.INCONSISTENT) }
    }

    @Test fun conflictsAndTinyValues() {
        val rect = listOf("Area = w*h", "Perimeter = 2*w + 2*h", "d^2 = w^2 + h^2").map { le(it, true) }
        assertEquals(Status.INCONSISTENT, Solver.solveFor(rect, mapOf("w" to 3.0, "h" to 4.0, "Area" to 20.0), "d", defaultDomain = Domain.POSITIVE).status)
        near(Solver.solveFor(rect, mapOf("w" to 3.0, "Perimeter" to 14.0), "Area", defaultDomain = Domain.POSITIVE).value, 12.0)
        near(Solver.solveFor(listOf(le("X_C = 1/(2*pi*f*C)", true)), mapOf("X_C" to 159.155, "f" to 1000.0), "C", defaultDomain = Domain.POSITIVE).value, 1e-6, 1e-4)
    }
}
