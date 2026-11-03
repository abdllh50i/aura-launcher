package com.abdllh.aura.nav

import com.abdllh.aura.util.Prefs

/**
 * The last things navigation did on its own, for Settings → About (the owner's photo of that page is the only way to learn
 * what happens on the unit): a late route taken, the route left and by which sign, a trip resumed or not and why. Three
 * lines, newest first.
 */
object NavNote {
    private const val KEY = "navNote"
    private const val KEEP = 3

    val text: String get() = try { Prefs.raw.getString(KEY, "").orEmpty() } catch (_: Throwable) { "" }

    fun add(what: String) {
        try {
            val at = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date())
            val lines = listOf("$at  $what") + text.lines().filter { it.isNotBlank() }
            Prefs.raw.edit().putString(KEY, lines.take(KEEP).joinToString("\n")).apply()
        } catch (_: Throwable) {
        }
    }
}
