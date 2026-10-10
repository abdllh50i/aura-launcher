package com.abdllh.aura.system

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.util.Prefs
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * The boot animation (the car turning in, then the AMRI OS logo) put in place by the app, so that an update from the
 * screen brings it too, with no new ROM. NWD's player (libbootanimation.so, findBootAnimationFile) plays
 * /cache/bootanimation.zip before anything else, then /system/config/app/bootanimation.zip, then the stock places.
 * That file is the firmware's own "dynamic logo": its factory setting and the broadcast
 * com.nwd.ACTION_THIRD_APP_SET_DYNAMIC_LOGO copy the chosen zip there, its factory reset deletes it, and bootanim.rc
 * gives the player the cache group to read it.
 *  - Copied as root (LocalAdb), once per animation (the asset's MD5): a logo someone sets later stays until an update
 *    brings a different animation.
 *  - A file that was there before is kept as bootanimation.zip.pre-aura; /cache/.aura-bootanim holds the MD5 of the
 *    file Aura wrote. The ROM's restore deletes Aura's file (only if it is still that one) and puts the old one back.
 */
object BootAnim {
    private const val TAG = "AuraBootAnim"
    private const val ASSET = "bootanimation.zip"
    private const val DEST = "/cache/bootanimation.zip"
    private const val MARK = "/cache/.aura-bootanim"
    private const val KEY_CHECKED = "bootAnimCheckedFor" // the build that has nothing more to do
    private const val KEY_MD5 = "bootAnimInstalledMd5"   // the animation last put in place
    private const val KEY_TRIES = "bootAnimTries"        // "versionCode:failed attempts"
    private const val MAX_TRIES = 3
    private const val DELAY_MS = 20_000L                 // not in the middle of the start-up

    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var started = false

    /** At start: puts this build's animation in place once, a little after the start-up. */
    fun ensure(ctx: Context) {
        if (started) return
        // debug builds on the emulator: setprop debug.aura.bootanim 1 (with the fake adbd, see AuraApp)
        if (!Device.isNwd && !(BuildConfig.DEBUG && SystemProps.get("debug.aura.bootanim") == "1")) return
        if (Prefs.raw.getInt(KEY_CHECKED, 0) == BuildConfig.VERSION_CODE) return
        started = true
        val c = ctx.applicationContext
        Handler(Looper.getMainLooper()).postDelayed({ worker.execute { install(c) } }, DELAY_MS)
    }

    private fun install(c: Context) {
        val tmp = File(c.cacheDir, ASSET)
        try {
            val md5 = copyAsset(c, tmp)
            if (md5 == Prefs.raw.getString(KEY_MD5, "")) { checked(); return } // this animation is in place already
            val r = LocalAdb.run(script(tmp.absolutePath, md5), 60_000)
            Log.i(TAG, "install: ${r.ok} ${r.output}")
            val out = r.output.lines()
            when {
                !r.ok -> failed() // no root shell (yet): next start
                "no-cache" in out -> checked() // nowhere to put it on this unit
                out.any { it.startsWith("installed") && (md5 in it || !HEX32.containsMatchIn(it)) } -> {
                    Prefs.raw.edit().putString(KEY_MD5, md5).apply()
                    checked()
                }
                else -> failed()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "install: $t")
            failed()
        } finally {
            tmp.delete()
        }
    }

    /**
     * Root shell: keep a file that is not Aura's as .pre-aura (once), write the new one next to it and rename it over
     * (the old one stays whole until then), owned like the firmware's own copy so its "dynamic logo" can replace it.
     */
    private fun script(src: String, md5: String) = """
        S='$src'; F=$DEST; M=$MARK
        [ -d /cache ] || { echo no-cache; exit 0; }
        [ -s "${'$'}S" ] || { echo no-source; exit 1; }
        B=0
        if [ -f ${'$'}F ] && [ ! -f ${'$'}M ] && [ ! -f ${'$'}F.pre-aura ]; then
          cp -p ${'$'}F ${'$'}F.pre-aura || { rm -f ${'$'}F.pre-aura; echo failed backup; exit 1; }; B=1
        fi
        if cp "${'$'}S" ${'$'}F.aura-new && chmod 644 ${'$'}F.aura-new && mv -f ${'$'}F.aura-new ${'$'}F; then
          chown 1000:2001 ${'$'}F 2>/dev/null
          echo $md5 > ${'$'}M; chmod 644 ${'$'}M
          sync
          echo "installed ${'$'}(md5sum ${'$'}F 2>/dev/null)"
        else
          rm -f ${'$'}F.aura-new; [ ${'$'}B = 1 ] && rm -f ${'$'}F.pre-aura; echo failed; exit 1
        fi
    """.trimIndent()

    /** The asset to [to], returning its MD5 (hex). */
    private fun copyAsset(c: Context, to: File): String {
        val md = MessageDigest.getInstance("MD5")
        c.assets.open(ASSET).use { inp ->
            to.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
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

    private fun checked() {
        Prefs.raw.edit().putInt(KEY_CHECKED, BuildConfig.VERSION_CODE).remove(KEY_TRIES).apply()
    }

    /** A few attempts per build (one per start), then it waits for the next build. */
    private fun failed() {
        val (code, n) = Prefs.raw.getString(KEY_TRIES, "")!!.split(":").let {
            (it.getOrNull(0)?.toIntOrNull() ?: 0) to (it.getOrNull(1)?.toIntOrNull() ?: 0)
        }
        val tries = if (code == BuildConfig.VERSION_CODE) n + 1 else 1
        if (tries >= MAX_TRIES) checked() else Prefs.raw.edit().putString(KEY_TRIES, "${BuildConfig.VERSION_CODE}:$tries").apply()
    }

    private val HEX32 = Regex("[0-9a-f]{32}")
}
