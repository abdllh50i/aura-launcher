package styledump

import com.abdllh.aura.nav.MapStyle
import com.abdllh.aura.ui.Palette
import java.io.File

/** Writes the real style four times: night and day, Arabic and English. args[0]: the folder. */
fun main(args: Array<String>) {
    val dir = File(args[0]).apply { mkdirs() }
    for (night in listOf(true, false)) for (ar in listOf(true, false)) {
        Palette.set(night)
        val name = (if (night) "night" else "day") + "-" + (if (ar) "ar" else "en") + ".json"
        File(dir, name).writeText(MapStyle.json(ar), Charsets.UTF_8)
        println(name)
    }
}
