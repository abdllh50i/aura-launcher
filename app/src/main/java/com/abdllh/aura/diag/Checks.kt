package com.abdllh.aura.diag

import com.abdllh.aura.R
import java.util.Locale

/**
 * The tests of the :diag process, by group: each group runs in a fresh process, so the memory read after a map test is
 * that test's. [Test.seconds]: about how long it takes on the unit (for the time left on the progress line), [Test.budgetMs]:
 * how long before it counts as hung and its process is killed.
 */
internal object Checks {
    class Test(val name: String, val label: Int, val seconds: Int, val budgetMs: Long)

    private const val UI_SECONDS = 4
    private const val UI_BUDGET = 70_000L

    val GROUPS: List<List<Test>> = listOf(
        listOf(
            Test("gl.es2", R.string.diag_t_gl, 1, 20_000L),
            Test("gl.es3", R.string.diag_t_gl, 1, 20_000L),
            Test("gl.formats", R.string.diag_t_gl, 1, 20_000L),
            Test("gl.fill", R.string.diag_t_fill, 25, 90_000L)
        ),
        listOf(
            Test("map.surface", R.string.diag_t_map_surface, 26, 110_000L),
            Test("ui.slide_layer", R.string.diag_t_motion, UI_SECONDS, UI_BUDGET),
            Test("ui.slide_plain", R.string.diag_t_motion, UI_SECONDS, UI_BUDGET),
            Test("ui.crossfade", R.string.diag_t_motion, UI_SECONDS, UI_BUDGET),
            Test("ui.bigtext", R.string.diag_t_motion, UI_SECONDS, UI_BUDGET),
            Test("ui.glass9", R.string.diag_t_motion, UI_SECONDS, UI_BUDGET),
            Test("ui.fill", R.string.diag_t_motion, UI_SECONDS, UI_BUDGET)
        ),
        listOf(
            Test("map.texture", R.string.diag_t_map_texture, 26, 110_000L)
        )
    )

    /** Seconds of a process starting between two groups. */
    const val START_SECONDS = 3

    fun of(name: String): Test? = GROUPS.firstNotNullOfOrNull { g -> g.firstOrNull { it.name == name } }

    /** These need a live map: with the map itself known to die, they are not tried. */
    fun needsMap(name: String) = name.startsWith("ui.")

    /** "1:05". */
    fun time(seconds: Int): String = String.format(Locale.ROOT, "%d:%02d", seconds.coerceAtLeast(0) / 60, seconds.coerceAtLeast(0) % 60)
}
