package com.abdllh.aura.voice

import android.content.Context
import android.util.Log
import com.abdllh.aura.R
import java.io.File

/**
 * Requests understood without the internet. The owner teaches each one in Settings (said three times, the way they
 * say it: any phrase will do), and a request is then compared with them all, like the wake word with its recordings.
 * Fixed requests only: "volume to 20" and the like need Google, with the internet.
 */
internal object OfflineCommands {
    private const val TAG = "AuraAmri"
    /**
     * Close enough to its recordings ... Tried with Windows' Arabic voice, six requests each taught with three quiet
     * recordings: the request said 0.59-1.31 (quiet and in road noise, other speeds and pitches); the closest other
     * request 1.81 and more ("طفّ الأغنية" against "غيّر الأغنية"); other phrases ("شغّل المكيف", "افتح الشباك"...) 2.34+.
     */
    private const val MAX_SCORE = 1.6f
    /** ... and clearly closer than to any other request's. */
    private const val MARGIN = 1.15f

    class Item(val id: String, val label: Int, val command: Command)

    val items = listOf(
        Item("next", R.string.cmd_next, Command.Next),
        Item("previous", R.string.cmd_previous, Command.Previous),
        Item("pause", R.string.cmd_pause, Command.Pause),
        Item("play", R.string.cmd_play, Command.Play),
        Item("vol_up", R.string.cmd_vol_up, Command.VolumeStep(true)),
        Item("vol_down", R.string.cmd_vol_down, Command.VolumeStep(false)),
        Item("vol_max", R.string.cmd_vol_max, Command.VolumeMax),
        Item("mute", R.string.cmd_mute, Command.Mute),
        Item("unmute", R.string.cmd_unmute, Command.Unmute),
        Item("music", R.string.cmd_music, Command.OpenMusic),
        Item("maps", R.string.cmd_maps, Command.OpenMaps),
        Item("home", R.string.cmd_home, Command.Navigate(true)),
        Item("work", R.string.cmd_work, Command.Navigate(false)),
        Item("time", R.string.cmd_time, Command.Time),
    )

    fun file(c: Context, id: String) = File(c.filesDir, "amri-cmd-$id.bin")
    fun taught(c: Context, id: String) = file(c, id).isFile
    fun anyTaught(c: Context) = items.any { taught(c, it.id) }

    // the models, read again only when their files change
    private val models = HashMap<String, Pair<Long, WakeModel?>>()

    private fun model(c: Context, id: String): WakeModel? {
        val f = file(c, id)
        if (!f.isFile) { models.remove(id); return null }
        val stamp = f.lastModified()
        models[id]?.let { if (it.first == stamp) return it.second }
        return WakeModel.load(f).also { models[id] = stamp to it }
    }

    /**
     * The request [s] is, or null when none is close enough (or two are about as close). On the microphone's thread
     * (some DTW against every recording of every taught request).
     */
    @Synchronized
    fun match(c: Context, s: Spectrum): Item? {
        val scored = items.mapNotNull { item -> model(c, item.id)?.let { item to it.score(s) } }.sortedBy { it.second }
        val best = scored.firstOrNull() ?: return null
        val second = scored.getOrNull(1)
        Log.d(TAG, "offline: ${scored.take(3).joinToString { "${it.first.id} %.2f".format(java.util.Locale.ROOT, it.second) }}")
        if (best.second >= MAX_SCORE) return null
        if (second != null && second.second < best.second * MARGIN) return null
        return best.first
    }
}
