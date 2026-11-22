package app.sketchsolve.word

import app.sketchsolve.math.Node

/**
 * Units and dimensions, used to check that a word-problem setup makes sense:
 * you can't add hours to miles, and adding minutes to hours needs a
 * conversion first.
 */
data class Dim(val e: Map<String, Int>) {
    operator fun times(o: Dim) = Dim((e.keys + o.e.keys).associateWith { (e[it] ?: 0) + (o.e[it] ?: 0) }.filterValues { it != 0 })
    operator fun div(o: Dim) = this * o.pow(-1)
    fun pow(k: Int) = Dim(e.mapValues { it.value * k }.filterValues { it != 0 })
    val isNone get() = e.isEmpty()
    override fun toString() = if (e.isEmpty()) "no unit" else e.entries.joinToString("·") { if (it.value == 1) it.key else "${it.key}^${it.value}" }
    companion object { val NONE = Dim(emptyMap()); fun of(vararg p: Pair<String, Int>) = Dim(p.toMap()) }
}

/** A parsed unit: its dimension and a "family" name used to spot mixed units (minutes + hours). */
data class UnitInfo(val dim: Dim, val family: String)

object Units {
    private val L = Dim.of("length" to 1); private val T = Dim.of("time" to 1); private val M = Dim.of("mass" to 1)
    private val MONEY = Dim.of("money" to 1); private val COUNT = Dim.of("count" to 1); private val TEMP = Dim.of("temperature" to 1)
    private val VOLT = Dim.of("voltage" to 1); private val AMP = Dim.of("current" to 1); private val ANGLE = Dim.of("angle" to 1)

    private val BASE: Map<String, UnitInfo> = buildMap {
        fun put(names: List<String>, dim: Dim, fam: String) { for (n in names) this[n] = UnitInfo(dim, fam) }
        put(listOf("mm", "millimeter", "millimeters", "millimetre", "millimetres"), L, "mm")
        put(listOf("cm", "centimeter", "centimeters", "centimetre", "centimetres"), L, "cm")
        put(listOf("m", "meter", "meters", "metre", "metres"), L, "m")
        put(listOf("km", "kilometer", "kilometers", "kilometre", "kilometres"), L, "km")
        put(listOf("in", "inch", "inches"), L, "in")
        put(listOf("ft", "foot", "feet"), L, "ft")
        put(listOf("yd", "yard", "yards"), L, "yd")
        put(listOf("mi", "mile", "miles"), L, "mi")
        put(listOf("s", "sec", "secs", "second", "seconds"), T, "s")
        put(listOf("min", "mins", "minute", "minutes"), T, "min")
        put(listOf("h", "hr", "hrs", "hour", "hours"), T, "h")
        put(listOf("day", "days"), T, "day")
        put(listOf("week", "weeks"), T, "week")
        put(listOf("month", "months"), T, "month")
        put(listOf("yr", "yrs", "year", "years"), T, "year")
        put(listOf("g", "gram", "grams"), M, "g")
        put(listOf("kg", "kilogram", "kilograms"), M, "kg")
        put(listOf("lb", "lbs", "pound", "pounds"), M, "lb")
        put(listOf("oz", "ounce", "ounces"), M, "oz")
        put(listOf("ton", "tons", "tonne", "tonnes"), M, "ton")
        put(listOf("$", "dollar", "dollars", "usd"), MONEY, "$")
        put(listOf("cent", "cents", "¢"), MONEY, "cent")
        put(listOf("€", "euro", "euros"), MONEY, "€")
        put(listOf("£"), MONEY, "£")
        put(listOf("l", "liter", "liters", "litre", "litres"), L.pow(3), "L")
        put(listOf("ml", "milliliter", "milliliters", "millilitre", "millilitres"), L.pow(3), "mL")
        put(listOf("gal", "gallon", "gallons"), L.pow(3), "gal")
        put(listOf("qt", "quart", "quarts"), L.pow(3), "qt")
        put(listOf("cup", "cups"), L.pow(3), "cup")
        put(listOf("°c", "celsius"), TEMP, "°C")
        put(listOf("°f", "fahrenheit"), TEMP, "°F")
        put(listOf("k", "kelvin"), TEMP, "K")
        put(listOf("°", "deg", "degree", "degrees"), ANGLE, "°")
        put(listOf("rad", "radian", "radians"), ANGLE, "rad")
        put(listOf("v", "volt", "volts"), VOLT, "V")
        put(listOf("a", "amp", "amps", "ampere", "amperes"), AMP, "A")
        put(listOf("ω", "ohm", "ohms"), VOLT / AMP, "Ω")
        put(listOf("w", "watt", "watts"), VOLT * AMP, "W")
        put(listOf("kw", "kilowatt", "kilowatts"), VOLT * AMP, "kW")
        put(listOf("j", "joule", "joules"), VOLT * AMP * T, "J")
        put(listOf("kwh"), VOLT * AMP * T, "kWh")
        put(listOf("n", "newton", "newtons"), M * L / T.pow(2), "N")
        put(listOf("hz", "hertz"), T.pow(-1), "Hz")
        put(listOf("%", "percent", "pct"), Dim.NONE, "%")
        // counted things
        for (w in listOf("item", "items", "people", "person", "persons", "student", "students", "ticket", "tickets", "book", "books",
            "apple", "apples", "car", "cars", "coin", "coins", "page", "pages", "worker", "workers", "box", "boxes", "unit", "units",
            "piece", "pieces", "game", "games", "point", "points", "child", "children", "adult", "adults", "animal", "animals",
            "egg", "eggs", "bag", "bags", "marble", "marbles", "cookie", "cookies", "seat", "seats", "row", "rows", "job", "jobs", "house", "houses"))
            this[w] = UnitInfo(COUNT, "count")
        this["mph"] = UnitInfo(L / T, "mi/h"); this["kph"] = UnitInfo(L / T, "km/h"); this["km/h"] = UnitInfo(L / T, "km/h")
        this["mpg"] = UnitInfo(L / L.pow(3), "mi/gal")
    }

    /** Parse a unit like "mi/h", "miles per hour", "m^2", "$ per pound", "dollars/hour". Null = unknown. */
    fun parse(unit: String?): UnitInfo? {
        if (unit == null) return null
        val u = unit.trim().lowercase().replace("²", "^2").replace("³", "^3").replace("·", "*")
        if (u.isEmpty() || u == "none" || u == "1" || u == "number" || u == "times") return UnitInfo(Dim.NONE, "")
        BASE[u]?.let { return it }
        val parts = u.split(Regex("\\s*/\\s*|\\s+per\\s+"))
        if (parts.size > 2) return null
        val num = product(parts[0]) ?: return null
        if (parts.size == 1) return num
        val den = product(parts[1]) ?: return null
        return UnitInfo(num.dim / den.dim, "${num.family}/${den.family}")
    }

    private fun product(s: String): UnitInfo? {
        val pieces = s.trim().split(Regex("\\s*\\*\\s*|\\s+")).filter { it.isNotEmpty() && it != "square" && it != "cubic" }
        var square = 1
        if (s.contains("square")) square = 2
        if (s.contains("cubic")) square = 3
        var dim = Dim.NONE
        val fam = StringBuilder()
        for (p in pieces) {
            val m = Regex("^(.+?)\\^(-?\\d)$").matchEntire(p)
            val name = m?.groupValues?.get(1) ?: p
            val k = m?.groupValues?.get(2)?.toInt() ?: 1
            val info = BASE[name] ?: return null
            dim *= info.dim.pow(k)
            if (fam.isNotEmpty()) fam.append("·")
            fam.append(info.family + if (k != 1) "^$k" else "")
        }
        if (pieces.isEmpty()) return null
        return UnitInfo(dim.pow(square), fam.toString() + if (square > 1) "^$square" else "")
    }

    /** Units of an expression: null means "can't tell" (e.g. a bare number). */
    sealed class Check { object Ok : Check(); data class Mismatch(val a: String, val b: String) : Check(); data class Mixed(val a: String, val b: String) : Check() }

    class Analyzer(private val units: Map<String, UnitInfo?>) {
        val problems = ArrayList<Check>()

        fun of(n: Node): UnitInfo? = when (n) {
            is Node.Num -> null
            is Node.Var -> if (n.name == "pi" || n.name == "e") UnitInfo(Dim.NONE, "") else units[n.name]
            is Node.Neg -> of(n.a)
            is Node.Fn -> { of(n.a); if (n.name == "sqrt") of(n.a)?.let { u -> if (u.dim.e.values.all { it % 2 == 0 }) UnitInfo(Dim(u.dim.e.mapValues { it.value / 2 }), "") else null } else UnitInfo(Dim.NONE, "") }
            is Node.Eq -> { sum(of(n.lhs), of(n.rhs)); null }
            is Node.Op -> when (n.op) {
                '+', '-' -> sum(of(n.a), of(n.b))
                '*' -> { val a = of(n.a); val b = of(n.b); if (a == null || b == null) null else UnitInfo(a.dim * b.dim, "") }
                '/' -> { val a = of(n.a); val b = of(n.b); if (a == null || b == null) null else UnitInfo(a.dim / b.dim, "") }
                else -> { val a = of(n.a); val k = (n.b as? Node.Num)?.v; if (a != null && k != null && k == Math.floor(k)) UnitInfo(a.dim.pow(k.toInt()), "") else if (a?.dim?.isNone == true) a else null }
            }
        }

        private fun sum(a: UnitInfo?, b: UnitInfo?): UnitInfo? {
            if (a == null) return b
            if (b == null) return a
            if (a.dim != b.dim) { problems += Check.Mismatch(a.dim.toString(), b.dim.toString()); return a }
            if (a.family.isNotEmpty() && b.family.isNotEmpty() && a.family != b.family && a.family != "count" && b.family != "count" && a.family != "%" && b.family != "%")
                problems += Check.Mixed(a.family, b.family)
            return a
        }
    }

    fun isCountable(u: UnitInfo?) = u?.dim == COUNT
    fun mustBePositive(u: UnitInfo?) = u != null && !u.dim.isNone && u.dim.e.keys.none { it == "temperature" || it == "voltage" || it == "current" || it == "money" }
}
