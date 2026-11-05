package com.abdllh.aura.music

import com.abdllh.aura.home.Entry
import com.abdllh.aura.home.Screens

/**
 * The name Music keeps for whoever starts it from outside: Music itself is a panel of the one window ([MusicPanel]). The
 * firmware's source list names this class when the owner has switched that on ([com.abdllh.aura.system.StockMusic]). With it
 * live what others read of it.
 */
class MusicActivity : Entry(Screens.MUSIC) {
    companion object {
        const val EXTRA_SOURCE = "source" // "bt" / "local"

        /** The delay as the screen says it: seconds, two decimals ("0.45"). */
        fun delaySeconds(ms: Long): String = String.format(java.util.Locale.ROOT, "%.2f", ms / 1000.0)
    }
}
