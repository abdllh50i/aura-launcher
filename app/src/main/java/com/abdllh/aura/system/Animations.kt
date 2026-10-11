package com.abdllh.aura.system

import android.animation.ValueAnimator
import android.content.Context
import android.provider.Settings
import android.util.Log
import com.abdllh.aura.util.Prefs
import java.io.File

/**
 * Screen changes that move instead of cutting. This firmware's SettingsProvider writes Android's window and transition
 * animation scales as 0 at the first start (def_window_animation_scale / def_window_transition_scale = 0%, AOSP has
 * 100%), so every screen change on the unit was a cut. While the owner's switch is on (Settings → Display & sound),
 * AMRI puts them at 1 — Android's own default — where it finds 0 or nothing, and keeps the values it found in
 * files/anim-scales; switched off, or AMRI no longer the home screen, they are put back. The installers' restore puts
 * them back from the same file.
 *
 * Inside AMRI's process the animator scale is also kept at 1 ([ensureAnimators]): the stock launcher does the same
 * (com.launcher.AnimationDriver resets ValueAnimator's scale when it finds 0).
 */
object Animations {
    private const val TAG = "AuraAnim"
    private val KEYS = listOf("window_animation_scale", "transition_animation_scale")
    private const val FILE = "anim-scales"

    fun apply(ctx: Context): Boolean = update(ctx, Prefs.screenAnimations)

    /** Gives the firmware's values back (AMRI is no longer the home screen); the owner's choice itself is kept. */
    fun release(ctx: Context): Boolean = update(ctx, false)

    private fun update(ctx: Context, on: Boolean): Boolean {
        val app = ctx.applicationContext
        val cr = app.contentResolver
        val f = File(app.filesDir, FILE)
        val writes = ArrayList<Pair<String, String?>>()
        try {
            if (on) {
                val found = LinkedHashMap<String, String?>()
                for (k in KEYS) {
                    val v = Settings.Global.getString(cr, k)
                    val x = v?.toFloatOrNull()
                    if (x == null || x == 0f) { found[k] = v; writes.add(k to "1.0") } // (someone's own value is left alone)
                }
                if (writes.isEmpty()) return true
                // the firmware's values, kept from the first time (not the ones in between)
                if (!f.isFile) f.writeText(found.entries.joinToString("") { "${it.key} ${it.value ?: "null"}\n" })
            } else {
                if (!f.isFile) return true
                for (line in f.readLines()) {
                    val p = line.trim().split(' ')
                    if (p.size == 2 && p[0] in KEYS) writes.add(p[0] to p[1].takeIf { it != "null" })
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "scales: $t")
            return false
        }
        val failed = ArrayList<Pair<String, String?>>()
        for ((k, v) in writes) {
            try { Settings.Global.putString(cr, k, v) } catch (_: Throwable) { failed.add(k to v) }
        }
        if (failed.isEmpty()) {
            if (!on) f.delete()
            return true
        }
        // a build without WRITE_SECURE_SETTINGS (updated from the screen before the ROM granted it): the root shell
        Thread {
            val cmd = failed.joinToString("; ") { (k, v) -> if (v == null) "settings delete global $k" else "settings put global $k $v" }
            val r = LocalAdb.run(cmd)
            if (r.ok && !on) f.delete()
            Log.i(TAG, "scales through adb (${if (on) "on" else "off"}): ${r.ok}")
        }.start()
        return true
    }

    /** In this process: animations play even when the system's animator scale says 0. */
    fun ensureAnimators() {
        if (ValueAnimator.areAnimatorsEnabled()) return
        try {
            ValueAnimator::class.java.getMethod("setDurationScale", java.lang.Float.TYPE).invoke(null, 1f)
            Log.i(TAG, "animator scale was 0: now 1 in this process")
        } catch (t: Throwable) {
            Log.w(TAG, "animator scale: $t")
        }
    }
}
