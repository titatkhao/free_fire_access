package dev.ffaccess

/** Forgiving text matching: OCR often drops or swaps a letter ("defeoted", "a1ive"). */
object Fuzzy {
    fun distance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[b.length]
    }

    /** True when the two strings are at least 75% alike (case-insensitive). */
    fun similar(a: String, b: String): Boolean {
        val m = maxOf(a.length, b.length)
        if (m == 0) return true
        return 1.0 - distance(a.lowercase(), b.lowercase()).toDouble() / m >= 0.75
    }

    /** Does the text contain [word], allowing one misread letter (two for long words)? */
    fun hasWord(text: String, word: String): Boolean {
        val allowed = if (word.length >= 7) 2 else 1
        return text.lowercase().split(Regex("[^a-z0-9]+")).any {
            it.length in (word.length - allowed)..(word.length + allowed) && distance(it, word) <= allowed
        }
    }

    private val verbs = listOf("knocked down", "eliminated", "defeated", "killed")

    /**
     * Your own kill banner: it must START with a kill verb (optionally after "You"), spelled
     * approximately. Returns the victim's name, or null. Lines about other players never match.
     */
    fun killVictim(text: String): String? {
        var t = text.trim()
        if (t.lowercase().startsWith("you ")) t = t.substring(4).trim()
        val low = t.lowercase()
        for (v in verbs) {
            if (low.length < v.length + 2) continue
            val allowed = if (v.length >= 8) 2 else 1
            if (distance(low.substring(0, v.length), v) <= allowed) {
                val name = t.substring(v.length).trim(' ', ':', '-', '.')
                if (name.length in 2..24) return name
            }
        }
        return null
    }
}
