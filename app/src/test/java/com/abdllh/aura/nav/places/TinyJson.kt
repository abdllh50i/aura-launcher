package com.abdllh.aura.nav.places

/** Enough of JSON for the tests' files: org.json is a stub under src/test. Objects are maps, arrays lists, numbers doubles. */
internal class TinyJson private constructor(private val s: String) {
    private var i = 0

    private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

    private fun value(): Any? {
        ws()
        return when (s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            't' -> { i += 4; true }
            'f' -> { i += 5; false }
            'n' -> { i += 4; null }
            else -> num()
        }
    }

    private fun obj(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        i++
        ws()
        if (s[i] == '}') { i++; return m }
        while (true) {
            ws()
            val k = str()
            ws()
            i++ // :
            m[k] = value()
            ws()
            if (s[i++] == '}') return m
        }
    }

    private fun arr(): List<Any?> {
        val a = ArrayList<Any?>()
        i++
        ws()
        if (s[i] == ']') { i++; return a }
        while (true) {
            a.add(value())
            ws()
            if (s[i++] == ']') return a
        }
    }

    private fun str(): String {
        val b = StringBuilder()
        i++
        while (s[i] != '"') {
            if (s[i] == '\\') {
                i++
                when (s[i]) {
                    'n' -> b.append('\n')
                    't' -> b.append('\t')
                    'r' -> b.append('\r')
                    'b' -> b.append('\b')
                    'f' -> b.append('\u000C')
                    'u' -> { b.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                    else -> b.append(s[i])
                }
            } else b.append(s[i])
            i++
        }
        i++
        return b.toString()
    }

    private fun num(): Double {
        val from = i
        while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
        return s.substring(from, i).toDouble()
    }

    companion object {
        fun parse(text: String): Any? = TinyJson(text).value()

        /** A list of the tests' test resources or a file of the repo: the tests run in the module's folder. */
        fun resource(name: String): Any? = parse(TinyJson::class.java.classLoader!!.getResource("places/$name")!!.readText(Charsets.UTF_8))
    }
}
