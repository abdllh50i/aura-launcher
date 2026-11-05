package com.abdllh.aura.nav

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.abdllh.aura.system.Device
import com.abdllh.aura.system.SystemProps
import java.io.File

/**
 * MapLibre's native library, loaded off the main thread right after the home screen's first frame, so it is there when the
 * live map starts (700 ms later). Until 1.19 that map loaded it on the main thread, during the entrance (41-55 ms on the
 * emulator, some ten times that on the unit [estimate]). As the process started it held the first frame back by about
 * 100 ms of 1200 on a loaded emulator (the linker's and the class loader's locks), hence after it. The map's own load then
 * finds it there; the Runtime's lock means the main thread can only be made to wait for what is left of this load, never
 * more than it waited before. A map opened first (no home screen in this process) loads it itself, as before.
 *
 * Not with AMRI switched off, not while [MapGuard] has anything against the map, and not after a load that never came back
 * (a mark on disk, as a native crash ends the process with no chance to write anything): those starts leave it to the map
 * itself, as before. What happened goes to the log and the Screen check's report.
 */
object MapLib {
    private const val TAG = "AuraMap"
    private const val LIB = "mapbox-gl"

    private var asked = false

    /** What the load at this start did (for the Screen check's report). */
    @Volatile var note = ""
        private set

    /** Once per process (main thread). */
    fun preload(ctx: Context) {
        if (asked) return
        asked = true
        val c = ctx.applicationContext
        Thread({ load(c) }, "aura-maplib").apply { isDaemon = true; start() }
    }

    private fun load(c: Context) {
        val mark = File(c.noBackupFilesDir, "map-lib-loading")
        try {
            if (SystemProps.get(Device.DISABLED_PROP) == "1") {
                report("not loaded ahead: AMRI is switched off")
                return
            }
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
