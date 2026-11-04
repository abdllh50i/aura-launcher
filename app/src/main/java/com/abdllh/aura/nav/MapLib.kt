package com.abdllh.aura.nav

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * MapLibre's native library, loaded off the main thread as the process starts. Until 1.19 the first map loaded it on the
 * main thread, during the home screen's entrance (41-55 ms on the emulator, some ten times that on the unit [estimate]);
 * the map's own later load then finds it there. The Runtime's lock means the main thread can only be made to wait
 * for what is left of this load, never more than it waited before.
 *
 * Not while [MapGuard] has anything against the map, and not after a load that never came back (a mark on disk, as a
 * native crash ends the process with no chance to write anything): those starts leave it to the map itself, as before.
 * What happened goes to the log and Settings → About.
 */
object MapLib {
    private const val TAG = "AuraMap"
    private const val LIB = "mapbox-gl"

    /** What the load at this start did (for Settings → About). */
    @Volatile var note = ""
        private set

    fun preload(ctx: Context) {
        val c = ctx.applicationContext
        Thread({ load(c) }, "aura-maplib").apply { isDaemon = true; start() }
    }

    private fun load(c: Context) {
        val mark = File(c.noBackupFilesDir, "map-lib-loading")
        try {
            if (mark.exists()) {
                mark.delete()
                report("not loaded ahead: the last load never came back")
                return
            }
            if (!MapGuard.looksFine(c)) {
                report("not loaded ahead: the map failed to start before")
                return
            }
            mark.writeText("")
            val t0 = SystemClock.elapsedRealtime()
            System.loadLibrary(LIB)
            val ms = SystemClock.elapsedRealtime() - t0
            mark.delete()
            report("$ms ms, off the main thread")
        } catch (t: Throwable) {
            // (the map tries itself, as it always did)
            try { mark.delete() } catch (_: Throwable) { }
            report("failed: $t")
        }
    }

    private fun report(what: String) {
        note = what
        Log.i(TAG, "native library: $what")
    }
}
