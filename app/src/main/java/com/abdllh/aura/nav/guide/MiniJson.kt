package com.abdllh.aura.nav.guide

/**
 * Just enough JSON for a router's answer: objects as maps, arrays as lists, numbers as doubles. org.json is Android's and
 * is not there on the JVM the tests run on, and an answer can reach 2.35 MB (`najran-tabuk`, 3 routes) of which guidance
 * reads a small part: [skip] leaves a value unread and unkept. Bad input throws; [GuideAnswer.parse] catches it.
 */
internal class MiniJson private constructor(private val s: String, private val skip: (String, Int) -> Boolean) {
    private var i = 0

    private fun value(depth: Int): Any? {
        if (depth > MAX_DEPTH) throw IllegalArgumentException("too deep")
        space()
        return when (val c = s[i]) {
            '{' -> obj(depth + 1)
            '[' -> arr(depth + 1)
            '"' -> str()
            't' -> { i += 4; true }
            'f' -> { i += 5; false }
            'n' -> { i += 4; null }
            else -> if (c == '-' || c in '0'..'9') num() else throw IllegalArgumentException("at $i")
        }
    }

    private fun obj(depth: Int): Map<String, Any?> {
        i++
        val m = HashMap<String, Any?>()
        space()
        if (s[i] == '}') { i++; return m }
        while (true) {
            space()
            val k = str()
            space()
            if (s[i] != ':') throw IllegalArgumentException("at $i")
            i++
            if (skip(k, depth)) passOver() else m[k] = value(depth)
            space()
            when (s[i]) {
                ',' -> i++
                '}' -> { i++; return m }
                else -> throw IllegalArgumentException("at $i")
            }
        }
    }

    private fun arr(depth: Int): List<Any?> {
        i++
        val a = ArrayList<Any?>()
        space()
        if (s[i] == ']') { i++; return a }
        while (true) {
            a.add(value(depth))
            space()
            when (s[i]) {
                ',' -> i++
                ']' -> { i++; return a }
                else -> throw IllegalArgumentException("at $i")
            }
        }
    }

    private fun str(): String {
        if (s[i] != '"') throw IllegalArgumentException("at $i")
        val from = ++i
        // (most strings have no escape: a substring of the answer)
        while (s[i] != '"' && s[i] != '\\') i++
        if (s[i] == '"') return s.substring(from, i++)
        val b = StringBuilder(s.substring(from, i))
        while (s[i] != '"') {
            val c = s[i++]
            if (c != '\\') { b.append(c); continue }
            when (val e = s[i++]) {
                'n' -> b.append('\n')
                't' -> b.append('\t')
                'r' -> b.append('\r')
                'b' -> b.append('\b')
                'f' -> b.append('\u000C')
                'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                else -> b.append(e)
            }
        }
        i++
        return b.toString()
    }

    private fun num(): Double {
        val from = i
        while (i < s.length && (s[i] in '0'..'9' || s[i] == '-' || s[i] == '+' || s[i] == '.' || s[i] == 'e' || s[i] == 'E')) i++
        return s.substring(from, i).toDouble()
    }

    /** Past one value, without keeping it. */
    private fun passOver() {
        space()
        when (s[i]) {
            '"' -> passString()
            '{', '[' -> {
                var level = 0
                while (true) {
                    when (s[i]) {
                        '"' -> { passString(); continue }
                        '{', '[' -> level++
                        '}', ']' -> if (--level == 0) { i++; return }
                    }
                    i++
                }
            }
            else -> while (i < s.length && s[i] != ',' && s[i] != '}' && s[i] != ']') i++
        }
    }

    private fun passString() {
        i++
        while (s[i] != '"') i += if (s[i] == '\\') 2 else 1
        i++
    }

    private fun space() {
        while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
    }

    companion object {
        private const val MAX_DEPTH = 40

        /** [skip] (key, depth of the object it is in, the outermost being 1): leave this value unread. */
        fun parse(text: String, skip: (String, Int) -> Boolean = { _, _ -> false }): Any? = MiniJson(text, skip).value(0)
    }
}

@Suppress("UNCHECKED_CAST")
internal fun Any?.obj(k: String): Map<String, Any?>? = (this as? Map<String, Any?>)?.get(k) as? Map<String, Any?>
@Suppress("UNCHECKED_CAST")
internal fun Any?.list(k: String): List<Any?> = ((this as? Map<String, Any?>)?.get(k) as? List<Any?>).orEmpty()
@Suppress("UNCHECKED_CAST")
internal fun Any?.str(k: String): String = (this as? Map<String, Any?>)?.get(k) as? String ?: ""
@Suppress("UNCHECKED_CAST")
internal fun Any?.num(k: String): Double? = (this as? Map<String, Any?>)?.get(k) as? Double
@Suppress("UNCHECKED_CAST")
internal fun Any?.bool(k: String): Boolean = (this as? Map<String, Any?>)?.get(k) as? Boolean ?: false
