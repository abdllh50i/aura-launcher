// Just enough of Android and of the app for the app's own MapStyle.kt and Poi.kt to compile and run on a PC, unchanged.
// dump_style.ps1 compiles those two real files with this one, so the JSON it writes is the style the app builds.
@file:Suppress("unused")

package android.graphics

object Color {
    @JvmStatic fun alpha(c: Int) = c ushr 24
    @JvmStatic fun red(c: Int) = (c shr 16) and 0xFF
    @JvmStatic fun green(c: Int) = (c shr 8) and 0xFF
    @JvmStatic fun blue(c: Int) = c and 0xFF
    @JvmStatic fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
    @JvmStatic fun parseColor(s: String): Int = (s.removePrefix("#").toLong(16) or 0xFF000000L).toInt()
}

class Bitmap private constructor() {
    enum class Config { ARGB_8888 }
    var density = 0
    companion object {
        @JvmStatic fun createBitmap(px: IntArray, w: Int, h: Int, c: Config) = Bitmap()
    }
}
