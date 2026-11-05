package com.abdllh.aura.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.SparseIntArray
import com.abdllh.aura.R
import com.abdllh.aura.util.Prefs

/**
 * The tones of SPEC.md section 15, on the guidance stream, short mono files in res/raw made by design/sound/make_tones.py. The screen
 * has no haptics, so a press under a finger needs something to say it happened. Loaded when first asked for.
 * [tick]: row and dock presses, off with [Prefs.pressTick]; not on map gestures. [saher] once per camera at 400 m; [over] once when
 * crossing the limit by 10 km/h, and again after falling back. Who plays them keeps those rules.
 */
object Tones {
    private var pool: SoundPool? = null
    private val ids = SparseIntArray()
    private val ready = HashSet<Int>()

    private fun load(ctx: Context): SoundPool = pool ?: SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build().also { p ->
            pool = p
            p.setOnLoadCompleteListener { sp, id, status ->
                if (status == 0) {
                    ready.add(id)
                    if (id == waiting) { sp.play(id, 1f, 1f, 1, 0, 1f); waiting = 0 }
                }
            }
            val app = ctx.applicationContext
            for (res in intArrayOf(R.raw.tone_tick, R.raw.tone_saher, R.raw.tone_over)) ids.put(res, p.load(app, res, 1))
        }

    /** What was asked for before its file had loaded: the first tone of a run still sounds. */
    private var waiting = 0

    private fun play(ctx: Context, res: Int) {
        val p = try { load(ctx) } catch (_: Throwable) { return }
        val id = ids.get(res)
        if (id in ready) p.play(id, 1f, 1f, 1, 0, 1f) else waiting = id
    }

    fun tick(ctx: Context) { if (Prefs.pressTick) play(ctx, R.raw.tone_tick) }

    fun saher(ctx: Context) { if (Prefs.saherTone) play(ctx, R.raw.tone_saher) }

    fun over(ctx: Context) = play(ctx, R.raw.tone_over)
}
