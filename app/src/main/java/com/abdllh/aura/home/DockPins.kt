package com.abdllh.aura.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import com.abdllh.aura.R
import com.abdllh.aura.util.Prefs
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What sits in the dock: Aura's own apps ("aura:music", "aura:maps") and installed apps by package, in the order the
 * user pinned them (long-press in the app drawer). The "Apps" button is always there and is not a pin.
 */
object DockPins {
    const val MUSIC = "aura:music"
    const val MAPS = "aura:maps"
    const val MAX = 6
    private const val KEY = "dockPins"
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    /** The pins; a fresh install (or one from before pins existed) starts with Music only. */
    fun get(): List<String> = Prefs.raw.getString(KEY, null)?.split('\n')?.filter { it.isNotBlank() } ?: listOf(MUSIC)

    fun contains(id: String) = id in get()

    /** False when the dock is full. */
    fun add(id: String): Boolean {
        val l = get().toMutableList()
        if (id in l) return true
        if (l.size >= MAX) return false
        l.add(id)
        save(l)
        return true
    }

    fun remove(id: String) = save(get().filter { it != id })

    private fun save(l: List<String>) {
        Prefs.raw.edit().putString(KEY, l.joinToString("\n")).apply()
        for (x in listeners) x()
    }
}

/** The coloured tiles Aura draws for its own apps and the head unit's known ones (others show their own icon). */
object DockTiles {
    class Style(val icon: Int, val from: Int, val to: Int, val label: Int)

    fun style(id: String): Style? = when (id) {
        DockPins.MUSIC -> Style(R.drawable.ic_music, 0xFFFF4F79.toInt(), 0xFFD9234F.toInt(), R.string.dock_music)
        DockPins.MAPS -> Style(R.drawable.ic_nav, 0xFF4F86F7.toInt(), 0xFF2B57D0.toInt(), R.string.dock_nav)
        Known.PHONE -> Style(R.drawable.ic_phone, 0xFF3DD26C.toInt(), 0xFF1F9E4A.toInt(), R.string.dock_phone)
        Known.RADIO -> Style(R.drawable.ic_radio, 0xFFFFA43A.toInt(), 0xFFEA7408.toInt(), R.string.dock_radio)
        Known.CAM360 -> Style(R.drawable.ic_camera, 0xFF4CC9F5.toInt(), 0xFF1E8FCB.toInt(), R.string.dock_camera)
        Known.ZLINK -> Style(R.drawable.ic_smartphone, 0xFFB46AF2.toInt(), 0xFF7F3BC4.toInt(), R.string.dock_link)
        Known.VIDEO -> Style(R.drawable.ic_video, 0xFFFF6E4A.toInt(), 0xFFDB4422.toInt(), R.string.dock_video)
        else -> null
    }

    /** A tile as a bitmap (the app drawer shows Aura's own apps with it). */
    fun bitmap(ctx: Context, id: String, size: Int): Bitmap? {
        val s = style(id) ?: return null
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(s.from, s.to)).apply {
            cornerRadius = size * 0.3f
            setBounds(0, 0, size, size)
            draw(c)
        }
        val glyph = ctx.getDrawable(s.icon)?.mutate() ?: return b
        glyph.setTint(0xFFFFFFFF.toInt())
        val g = (size * 0.52f).toInt()
        val o = (size - g) / 2
        glyph.setBounds(o, o, o + g, o + g)
        glyph.draw(c)
        return b
    }
}
