package app.sketchsolve.recog

import app.sketchsolve.ink.Box
import app.sketchsolve.ink.Geom
import app.sketchsolve.ink.Stroke
import kotlin.math.max
import kotlin.math.abs

/** One recognised symbol, with the characters of the line text it produced. */
data class Sym(
    val key: String,
    val strokeIds: List<Int>,
    var label: String,
    val cands: List<Cand>,
    val box: Box,
    var start: Int,
    var end: Int,
    val overridden: Boolean,
    val role: String? = null
)

data class Line(val index: Int, val text: String, val syms: List<Sym>, val box: Box, val strokeIds: List<Int>)

/**
 * Reads a page of handwritten strokes as lines of maths:
 * lines → fractions → square roots → symbols → text like "2x^(2)+(1)/(3)".
 */
object Layout {
    const val SPLIT = "__split"

    fun keyOf(strokes: List<Stroke>) = strokes.map { it.id }.sorted().joinToString(",")

    private sealed class Item(val box: Box) {
        class Sym(val key: String, val strokes: List<Stroke>, box: Box, var label: String, var cands: List<Cand>, val overridden: Boolean) : Item(box)
        class Frac(val bar: Stroke, val num: List<Item>, val den: List<Item>, box: Box, val barBox: Box) : Item(box)
        class Sqrt(val stroke: Stroke, val body: List<Item>, box: Box, val cands: List<Cand>) : Item(box)
    }

    class Opts(val overrides: Map<String, String> = emptyMap(), val allowed: Set<String>? = null, val cache: MutableMap<String, List<Cand>>? = null)

    private fun boxOf(strokes: List<Stroke>) = Geom.bbox(strokes.flatMap { it.pts })

    fun analyze(strokes: List<Stroke>, opts: Opts = Opts()): Pair<List<Line>, Double> {
        val items = strokes.filter { it.pts.isNotEmpty() }.map { it to Geom.bbox(it.pts) }
        if (items.isEmpty()) return emptyList<Line>() to 30.0
        val hs = items.map { max(it.second.h, it.second.w * 0.35) }.filter { it > 6 }.sorted()
        val ref = max(14.0, if (hs.isEmpty()) 30.0 else hs[hs.size / 2])

        val sorted = items.sortedBy { it.second.y0 }
        val groups = ArrayList<Pair<MutableList<Stroke>, DoubleArray>>()
        for ((s, b) in sorted) {
            val cur = groups.lastOrNull()
            if (cur != null && b.y0 <= cur.second[0] + 0.5 * ref) { cur.first.add(s); cur.second[0] = max(cur.second[0], b.y1) }
            else groups.add(mutableListOf(s) to doubleArrayOf(b.y1))
        }
        val lines = groups.mapIndexed { li, (ss, _) ->
            val tree = parseRegion(ss, ref, opts)
            val sb = StringBuilder()
            val syms = ArrayList<Sym>()
            emitList(tree, sb, syms)
            Line(li, sb.toString(), syms, boxOf(ss), ss.map { it.id })
        }
        return lines to ref
    }

    private fun isBar(s: Stroke, ref: Double): Boolean {
        val st = Geom.stats(s.pts)
        return st.straight > 0.88 && st.ang < 20 && st.box.w > 0.35 * ref
    }

    private fun parseRegion(strokes: List<Stroke>, ref: Double, opts: Opts): List<Item> {
        var pool = strokes.toMutableList()
        val items = ArrayList<Item>()

        // fractions: widest bar first
        val bars = pool.filter { isBar(it, ref) }.sortedByDescending { Geom.bbox(it.pts).w }
        for (bar in bars) {
            if (bar !in pool) continue
            val bb = Geom.bbox(bar.pts)
            val barY = bb.cy
            val pad = 0.12 * bb.w
            fun within(b: Box) = b.cx > bb.x0 - pad && b.cx < bb.x1 + pad
            val others = pool.filter { it !== bar }
            val above = others.filter { val b = Geom.bbox(it.pts); within(b) && b.cy < barY && b.y1 < barY + 0.25 * ref && barY - b.y1 < 2.2 * ref }
            val below = others.filter { val b = Geom.bbox(it.pts); within(b) && b.cy > barY && b.y0 > barY - 0.25 * ref && b.y0 - barY < 2.2 * ref }
            fun solid(a: List<Stroke>) = a.filter { !isBar(it, ref) || Geom.bbox(it.pts).w < 0.7 * bb.w }
            if (solid(above).isEmpty() || solid(below).isEmpty()) continue
            val twin = others.any { isBar(it, ref) && abs(Geom.bbox(it.pts).w - bb.w) < 0.3 * bb.w && abs(Geom.bbox(it.pts).cy - barY) < 0.8 * ref }
            if (twin && above.size + below.size <= 2) continue
            val take = (listOf(bar) + above + below).toSet()
            pool = pool.filter { it !in take }.toMutableList()
            items.add(Item.Frac(bar, parseRegion(above, ref, opts), parseRegion(below, ref, opts), boxOf(take.toList()), bb))
        }

        // square roots
        for (s in pool.toList()) {
            if (s !in pool) continue
            val b = Geom.bbox(s.pts)
            if (b.w < 0.8 * ref) continue
            val inside = pool.filter { o -> o !== s && Geom.bbox(o.pts).let { ob -> ob.cx > b.x0 + 0.28 * b.w && ob.cx < b.x1 + 0.1 * ref && ob.cy > b.y0 && ob.cy < b.y1 + 0.2 * ref } }
            if (inside.isEmpty()) continue
            val override = opts.overrides[keyOf(listOf(s))]
            val cands = Recognizer.recognize(listOf(s.pts))
            val isRoot = if (override != null) override == "√" else cands[0].label == "√" || (cands.size > 1 && cands[1].label == "√" && cands[0].score - cands[1].score < 0.04)
            if (!isRoot) continue
            val take = (listOf(s) + inside).toSet()
            pool = pool.filter { it !in take }.toMutableList()
            items.add(Item.Sqrt(s, parseRegion(inside, ref, opts), boxOf(take.toList()), cands))
        }

        // ordinary symbols: strokes overlapping left-to-right
        pool.sortBy { Geom.bbox(it.pts).x0 }
        val groups = ArrayList<Pair<MutableList<Stroke>, Box>>()
        for (s in pool) {
            val b = Geom.bbox(s.pts)
            var merged = false
            for (k in groups.indices.reversed()) {
                if (k < groups.size - 2) break
                val (gs, gb) = groups[k]
                val ov = minOf(gb.x1, b.x1) - maxOf(gb.x0, b.x0)
                val minW = max(2.0, minOf(gb.w, b.w))
                val vertOk = b.cy > gb.y0 - 0.9 * ref && b.cy < gb.y1 + 0.9 * ref
                val tiny = max(b.w, b.h) < 0.18 * ref || max(gb.w, gb.h) < 0.18 * ref
                // a thin upright stroke (the bar of a "+", a "t") joins whatever it crosses
                val thinInside = (b.w < 0.15 * ref && b.cx > gb.x0 && b.cx < gb.x1) || (gb.w < 0.15 * ref && gb.cx > b.x0 && gb.cx < b.x1)
                if ((ov > 0.35 * minW || thinInside) && vertOk && gs.size < 4 && !tiny) {
                    gs.add(s); groups[k] = gs to boxOf(gs); merged = true; break
                }
            }
            if (!merged) groups.add(mutableListOf(s) to b)
        }
        for ((gs, gb) in groups) {
            if (gs.size > 1 && opts.overrides[keyOf(gs)] == SPLIT) for (s in gs) items.add(recogniseGroup(listOf(s), Geom.bbox(s.pts), ref, opts))
            else items.add(recogniseGroup(gs, gb, ref, opts))
        }
        items.sortBy { if (it is Item.Frac) it.barBox.x0 else it.box.x0 }
        resolveDots(items)
        return items
    }

    private fun recogniseGroup(strokes: List<Stroke>, box: Box, ref: Double, opts: Opts): Item.Sym {
        val key = keyOf(strokes)
        val size = max(box.w, box.h)
        var cands: List<Cand>
        if (strokes.size == 1 && size < 0.2 * ref) {
            cands = listOf(Cand(".", 1.0, dot = true), Cand("·", 0.9))
        } else {
            val ck = key + "|" + (opts.allowed?.sorted()?.joinToString("") ?: "")
            val cached = opts.cache?.get(ck)
            if (cached != null) cands = cached
            else {
                cands = contextFix(Recognizer.recognize(strokes.map { it.pts }, opts.allowed).toMutableList(), box, ref)
                opts.cache?.put(ck, cands)
            }
        }
        val override = opts.overrides[key]
        val label = if (override != null && override != SPLIT) override else cands.firstOrNull()?.label ?: "?"
        return Item.Sym(key, strokes, box, label, cands, override != null && override != SPLIT)
    }

    private fun contextFix(c: MutableList<Cand>, box: Box, ref: Double): List<Cand> {
        if (c.isEmpty()) return c
        val tall = box.h > 0.95 * ref
        fun swap(a: String, b: String) {
            val i = c.indexOfFirst { it.label == b }
            if (c[0].label == a && i > 0 && c[0].score - c[i].score < 0.08) c.add(0, c.removeAt(i))
        }
        if (tall) swap("c", "(") else swap("(", "c")
        return c
    }

    private fun resolveDots(items: List<Item>) {
        items.forEachIndexed { i, it ->
            if (it !is Item.Sym || it.cands.firstOrNull()?.dot != true || it.overridden) return@forEachIndexed
            val nb = listOfNotNull(items.getOrNull(i - 1), items.getOrNull(i + 1)).filter { x -> !(x is Item.Sym && x.cands.firstOrNull()?.dot == true) }
            if (nb.isEmpty()) return@forEachIndexed
            if (nb.none { x -> it.box.cy > x.box.y0 + 0.68 * x.box.h }) {
                it.label = "·"
                it.cands = listOf(Cand("·", 1.0), Cand(".", 0.9))
            }
        }
    }

    private val BASEABLE = ("0123456789".map { it.toString() } + listOf("x", "y", "a", "b", "c", "n", "t", ")", "π")).toSet()

    private fun isBaseable(it: Item) = it !is Item.Sym || it.label in BASEABLE || it.label.length == 1 && it.label[0].isLetter()

    private fun isSuperscript(it: Item, base: Item): Boolean {
        if (it is Item.Sym && it.label in listOf("=", "-", "+", "·", ".")) return false
        val bb = base.box; val b = it.box
        return b.y1 < bb.y0 + 0.6 * bb.h && b.h < 0.9 * bb.h && b.x0 > bb.x0 + 0.45 * bb.w
    }

    private fun emitList(items: List<Item>, sb: StringBuilder, syms: MutableList<Sym>) {
        var i = 0
        while (i < items.size) {
            val it = items[i]
            emitItem(it, sb, syms)
            if (isBaseable(it)) {
                var j = i + 1
                val sup = ArrayList<Item>()
                while (j < items.size && isSuperscript(items[j], it)) { sup.add(items[j]); j++ }
                if (sup.isNotEmpty()) {
                    sb.append("^("); emitList(sup, sb, syms); sb.append(")")
                    i = j; continue
                }
            }
            i++
        }
    }

    private fun emitItem(it: Item, sb: StringBuilder, syms: MutableList<Sym>) {
        val start = sb.length
        when (it) {
            is Item.Sym -> {
                sb.append(if (it.label == "·") "*" else it.label)
                syms.add(Sym(it.key, it.strokes.map { s -> s.id }, it.label, it.cands, it.box, start, sb.length, it.overridden))
            }
            is Item.Frac -> {
                val sym = Sym(keyOf(listOf(it.bar)), listOf(it.bar.id), "—", listOf(Cand("—", 1.0)), it.barBox, start, 0, false, "fraction bar")
                syms.add(sym)
                sb.append("("); emitList(it.num, sb, syms); sb.append(")/("); emitList(it.den, sb, syms); sb.append(")")
                sym.end = sb.length
            }
            is Item.Sqrt -> {
                val sym = Sym(keyOf(listOf(it.stroke)), listOf(it.stroke.id), "√", it.cands, Geom.bbox(it.stroke.pts), start, 0, false, "square root")
                syms.add(sym)
                sb.append("√("); emitList(it.body, sb, syms); sb.append(")")
                sym.end = sb.length
            }
        }
    }

    /** Grow [s, e) until brackets balance, so a circled piece is valid maths. */
    fun balanceRange(text: String, s0: Int, e0: Int): Pair<Int, Int> {
        var s = s0; var e = e0
        fun bal(a: Int, b: Int): Boolean {
            var d = 0
            for (i in a until b) { if (text[i] == '(') d++ else if (text[i] == ')') { d--; if (d < 0) return false } }
            return d == 0
        }
        var guard = 0
        while (guard++ < 200 && !bal(s, e)) {
            var d = 0; var minD = 0
            for (i in s until e) { if (text[i] == '(') d++ else if (text[i] == ')') { d--; minD = minOf(minD, d) } }
            if (minD < 0 && s > 0) s-- else if (e < text.length) e++ else if (s > 0) s-- else break
        }
        while (e + 1 < text.length && (text[e] == '^' || text[e] == '/') && text[e + 1] == '(') {
            var d = 0; var k = e + 1
            while (k < text.length) { if (text[k] == '(') d++ else if (text[k] == ')') { d--; if (d == 0) break }; k++ }
            e = minOf(k + 1, text.length)
        }
        return s to e
    }
}
