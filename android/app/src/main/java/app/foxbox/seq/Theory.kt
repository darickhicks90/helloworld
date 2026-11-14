package app.foxbox.seq

/** Scales, scale notes and diatonic chords for the chord selector and strum pad. */
object Theory {
    val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    class Scale(val name: String, val steps: IntArray)

    val SCALES = listOf(
        Scale("Major", intArrayOf(0, 2, 4, 5, 7, 9, 11)),
        Scale("Minor", intArrayOf(0, 2, 3, 5, 7, 8, 10)),
        Scale("Dorian", intArrayOf(0, 2, 3, 5, 7, 9, 10)),
        Scale("Phrygian", intArrayOf(0, 1, 3, 5, 7, 8, 10)),
        Scale("Lydian", intArrayOf(0, 2, 4, 6, 7, 9, 11)),
        Scale("Mixolydian", intArrayOf(0, 2, 4, 5, 7, 9, 10)),
        Scale("Harm. Minor", intArrayOf(0, 2, 3, 5, 7, 8, 11)),
        Scale("Pent. Major", intArrayOf(0, 2, 4, 7, 9)),
        Scale("Pent. Minor", intArrayOf(0, 3, 5, 7, 10)),
    )

    fun noteName(n: Int) = NOTE_NAMES[((n % 12) + 12) % 12]
    fun noteNameOct(n: Int) = noteName(n) + (n / 12 - 1)

    /** The i-th note of the scale (i may exceed one octave), from MIDI note `base`. */
    fun degree(scale: Scale, root: Int, base: Int, i: Int): Int {
        val n = scale.steps.size
        val oct = Math.floorDiv(i, n)
        return base + root + oct * 12 + scale.steps[Math.floorMod(i, n)]
    }

    class Chord(val degree: Int, val notes: IntArray, val name: String, val roman: String)

    /** Chord built by stacking scale thirds on each degree. */
    fun chord(scale: Scale, root: Int, base: Int, degree: Int, seventh: Boolean): Chord {
        val tones = if (seventh) 4 else 3
        val notes = IntArray(tones) { degree(scale, root, base, degree + it * 2) }
        val third = notes[1] - notes[0]
        val fifth = notes[2] - notes[0]
        val quality = when {
            third == 4 && fifth == 7 -> ""
            third == 3 && fifth == 7 -> "m"
            third == 3 && fifth == 6 -> "dim"
            third == 4 && fifth == 8 -> "aug"
            third == 5 -> "sus4"
            third == 2 -> "sus2"
            else -> ""
        }
        val sev = if (seventh) when (notes[3] - notes[0]) { 11 -> "maj7"; 10 -> "7"; 9 -> "6"; else -> "7" } else ""
        val name = noteName(notes[0]) + (if (quality == "m" && sev == "maj7") "m" else quality) + sev
        val numerals = listOf("I", "II", "III", "IV", "V", "VI", "VII")
        var roman = numerals[degree % 7]
        if (quality == "m" || quality == "dim") roman = roman.lowercase()
        if (quality == "dim") roman += "°"
        return Chord(degree, notes, name, roman)
    }
}
