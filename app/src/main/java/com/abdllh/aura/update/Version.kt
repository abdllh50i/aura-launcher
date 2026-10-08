package com.abdllh.aura.update

/**
 * Semantic-version comparison for tags like "v1.2.3" or "1.3.0-beta.2".
 *
 * Android only looks at versionCode, so the build derives it from the version name in a way that keeps the same order:
 * (MAJOR*10000 + MINOR*100 + PATCH) * 100 + (99 for a stable release, or the pre-release number 1..98 for "-beta.N").
 * See `versionCodeFor` in app/build.gradle.kts.
 */
class Version private constructor(private val nums: List<Int>, private val pre: String) : Comparable<Version> {

    val isPrerelease: Boolean get() = pre.isNotEmpty()

    override fun compareTo(other: Version): Int {
        val n = maxOf(nums.size, other.nums.size)
        for (i in 0 until n) {
            val a = nums.getOrElse(i) { 0 }
            val b = other.nums.getOrElse(i) { 0 }
            if (a != b) return a.compareTo(b)
        }
        if (pre.isEmpty() && other.pre.isNotEmpty()) return 1
        if (pre.isNotEmpty() && other.pre.isEmpty()) return -1
        return comparePre(pre, other.pre)
    }

    private fun comparePre(a: String, b: String): Int {
        val x = a.split('.')
        val y = b.split('.')
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrNull(i) ?: return -1
            val q = y.getOrNull(i) ?: return 1
            val pn = p.toIntOrNull()
            val qn = q.toIntOrNull()
            val c = when {
                pn != null && qn != null -> pn.compareTo(qn)
                pn != null -> -1
                qn != null -> 1
                else -> p.compareTo(q)
            }
            if (c != 0) return c
        }
        return 0
    }

    override fun equals(other: Any?): Boolean = other is Version && compareTo(other) == 0
    override fun hashCode(): Int = nums.hashCode() * 31 + pre.hashCode()
    override fun toString(): String = nums.joinToString(".") + if (pre.isEmpty()) "" else "-$pre"

    companion object {
        fun parse(raw: String?): Version? {
            if (raw.isNullOrBlank()) return null
            var s = raw.trim()
            if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1)
            s = s.substringBefore('+')
            val pre = if (s.contains('-')) s.substringAfter('-') else ""
            val core = s.substringBefore('-')
            val nums = core.split('.').map { it.toIntOrNull() ?: return null }
            if (nums.isEmpty()) return null
            return Version(nums, pre)
        }
    }
}
