package com.abdllh.aura.phone

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.R
import com.abdllh.aura.system.Device
import com.abdllh.aura.system.LocalAdb
import com.abdllh.aura.system.SystemProps
import com.abdllh.aura.util.Prefs
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * AMRI's ring for an incoming call (Settings → Display & sound → Calls; on by default).
 *
 * The ring is not an app's to play on this firmware: the Bluetooth service (com.bt.bc03.PlayerService) plays it
 * itself when a call comes in, a file looped on the phone-call stream at the phone-call volume, and stops it when the
 * call is answered or ends. The file is /system/ring.mp3 — unless there is a "ring.mp3" in the root of the internal
 * storage (its Config.RING_CUSTOM_PATH: ro.nwd.internal.path + "/ring.mp3", /mnt/media_rw/sdcard, which init links to
 * the primary storage), looked for again at every ring. So AMRI's ring (res/raw/aura_ring.mp3, made by
 * design/sound/make_ring.py) is put there, as root; a ring.mp3 of the owner's own is kept beside it as
 * ring.mp3.pre-aura and comes back when the switch goes off. Playing a second ring from here instead would sound
 * over the firmware's, which has no switch.
 */
object Ringtone {
    private const val TAG = "AuraRing"
    private const val KEY = "auraRing"
    private const val KEY_MD5 = "auraRingInstalled" // the MD5 of the file AMRI put in place ("" none)
    /** Beside the ring, the MD5 of AMRI's file: what tells it from a ring the owner put there (the ROM's restore reads it too). */
    private const val MARK = ".aura-ring"
    private const val END = "__aura_end__"

    private val worker = Executors.newSingleThreadExecutor()
    private var preview: MediaPlayer? = null

    /** The user's switch (on by default). */
    var enabled: Boolean
        get() = Prefs.raw.getBoolean(KEY, true)
        set(v) { Prefs.raw.edit().putBoolean(KEY, v).apply() }

    private val unit get() = Device.isNwd || (BuildConfig.DEBUG && SystemProps.get("debug.aura.unit") == "1")
    val available get() = unit

    /** Brings the unit in line with the switch; [done] gets true when it worked (on a worker thread). */
    fun apply(ctx: Context, done: ((Boolean) -> Unit)? = null) {
        if (!unit) { done?.invoke(true); return }
        val c = ctx.applicationContext
        worker.execute {
            val ok = try {
                if (enabled && SystemProps.get(Device.DISABLED_PROP) != "1") install(c) else remove()
            } catch (t: Throwable) {
                Log.w(TAG, "apply: $t")
                false
            }
            done?.invoke(ok)
        }
    }

    /** AMRI stops being the home screen (the kill switch): the ring that was there before comes back. */
    fun release() {
        if (!unit) return
        worker.execute { try { remove() } catch (t: Throwable) { Log.w(TAG, "release: $t") } }
    }

    private fun install(c: Context): Boolean {
        val tmp = File(c.cacheDir, "aura_ring.mp3")
        try {
            val md5 = copyRing(c, tmp)
            // in place already, as far as can be seen without the root shell (not one at every start)
            if (md5 == Prefs.raw.getString(KEY_MD5, "") && inPlace(tmp.length())) return true
            val out = sh("""
                S='${tmp.absolutePath}'; NEW=$md5
                $DIR
                F="${'$'}D/ring.mp3"; M="${'$'}D/$MARK"
                OLD=${'$'}(cat "${'$'}M" 2>/dev/null)
                CUR=${'$'}(md5sum "${'$'}F" 2>/dev/null | cut -d' ' -f1)
                if [ "${'$'}CUR" != ${'$'}NEW ]; then
                  # a ring of the owner's own (not one AMRI put there): kept, once
                  if [ -n "${'$'}CUR" ] && [ "${'$'}CUR" != "${'$'}OLD" ] && [ ! -f "${'$'}F.pre-aura" ]; then
                    cp "${'$'}F" "${'$'}F.pre-aura" || { echo failed backup; exit 1; }
                  fi
                  cp "${'$'}S" "${'$'}F.aura-new" && mv -f "${'$'}F.aura-new" "${'$'}F" || { rm -f "${'$'}F.aura-new"; echo failed; exit 1; }
                fi
                echo ${'$'}NEW > "${'$'}M" && echo installed
            """.trimIndent()) ?: return false
            Log.i(TAG, "install: $out")
            val lines = out.lines()
            if ("no-storage" in lines) return true // nowhere to put it on this unit: the stock ring stays
            if ("installed" !in lines) return false
            Prefs.raw.edit().putString(KEY_MD5, md5).apply()
            return true
        } finally {
            tmp.delete()
        }
    }

    /** AMRI's file goes (only while it still is AMRI's), the one from before comes back. */
    private fun remove(): Boolean {
        if (Prefs.raw.getString(KEY_MD5, "").isNullOrEmpty()) return true // never put there
        val out = sh("""
            $DIR
            F="${'$'}D/ring.mp3"; M="${'$'}D/$MARK"
            if [ -f "${'$'}M" ]; then
              CUR=${'$'}(md5sum "${'$'}F" 2>/dev/null | cut -d' ' -f1)
              [ "${'$'}CUR" = "${'$'}(cat "${'$'}M")" ] && rm -f "${'$'}F"
              [ -f "${'$'}F.pre-aura" ] && [ ! -f "${'$'}F" ] && mv -f "${'$'}F.pre-aura" "${'$'}F"
              rm -f "${'$'}M"
            fi
            echo removed
        """.trimIndent()) ?: return false
        Log.i(TAG, "remove: $out")
        val lines = out.lines()
        if ("removed" !in lines && "no-storage" !in lines) return false
        Prefs.raw.edit().remove(KEY_MD5).apply()
        return true
    }

    /** Runs [script] as root; its output, or null when it did not run to its end (no root shell, cut off). */
    private fun sh(script: String): String? {
        // (in a subshell: the script may leave early with exit, the end mark still comes)
        val r = LocalAdb.run("(\n$script\n)\necho $END", 20_000)
        if (!r.ok) return null
        val out = r.output.trimEnd()
        return if (out.endsWith(END)) out.removeSuffix(END).trimEnd() else null
    }

    /** Is a ring.mp3 of that size where the firmware looks (false also when the storage cannot be read from here)? */
    private fun inPlace(size: Long): Boolean = try {
        @Suppress("DEPRECATION")
        val f = File(android.os.Environment.getExternalStorageDirectory(), "ring.mp3")
        f.isFile && f.length() == size
    } catch (_: Throwable) {
        false
    }

    private fun copyRing(c: Context, to: File): String {
        val md = MessageDigest.getInstance("MD5")
        c.resources.openRawResource(R.raw.aura_ring).use { inp ->
            to.outputStream().use { out ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
            }
        }
        to.setReadable(true, false)
        return md.digest().joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it.toInt() and 0xFF) }
    }

    // ------------------------------------------------------------------------------------------ hearing it
    /** Plays the ring once through, for the settings page (the media volume: no call is going on). */
    fun play(ctx: Context) {
        stop()
        try {
            preview = MediaPlayer.create(ctx.applicationContext, R.raw.aura_ring,
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(), 0)
                ?.apply {
                    setOnCompletionListener { stop() }
                    start()
                }
        } catch (t: Throwable) {
            Log.w(TAG, "play: $t")
        }
    }

    fun stop() {
        val p = preview ?: return
        preview = null
        try { p.stop() } catch (_: Throwable) { }
        try { p.release() } catch (_: Throwable) { }
    }

    /** The folder the firmware's ring player looks in (the shell's D), or out: nothing to do on this unit. */
    private val DIR = """
        D=${'$'}(getprop ro.nwd.internal.path); [ -n "${'$'}D" ] || D=/mnt/media_rw/sdcard
        ${if (BuildConfig.DEBUG) "T=${'$'}(getprop debug.aura.ringdir); [ -n \"${'$'}T\" ] && D=${'$'}T" else ""}
        [ -d "${'$'}D/" ] || { echo no-storage; exit 0; }
    """.trimIndent()
}
