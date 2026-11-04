package com.abdllh.aura.guard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.abdllh.aura.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * What the main process leaves on disk for the guard ([GuardService], a process of its own), since a native crash, a
 * freeze or a throw in AuraApp ends it without a chance to say anything (CrashGuard sees none of those: docs/DEV.md).
 *
 * Two small files in files/. Plain files for the reason MapGuard's marker is one: written and closed, they outlive the
 * process, and the main thread does not wait for the disk the way a preferences commit() does.
 *  - guard-marks: one line per event, each one write() in append mode (the guard adds lines of its own). A start is
 *    marked first thing in AuraApp, before anything native is loaded, and "OK" once home has drawn and stayed up.
 *    Stamped with the boot id and the monotonic clock: head units set their wall clock late (see CrashGuard).
 *  - guard-beat: this process's pid and a counter the main thread moves on. A thread of its own writes it, so a slow
 *    disk never holds the screen; a frozen main thread shows as a counter that stops while the process lives.
 *
 * Everything here swallows what it throws: the home screen must never go down because of its own guard.
 */
object GuardMarks {
    private const val TAG = "AuraGuard"
    const val FILE_MARKS = "guard-marks"
    const val FILE_BEAT = "guard-beat"
    private const val BEAT_MS = 5_000L
    /** Home on the screen this long means this build works here. */
    private const val OK_AFTER_DRAW_MS = 20_000L
    /** A process that never had to show home (the unit's boot receiver, a kill switch that is on) and lives this long did not crash at its start. */
    private const val ALIVE_MS = 60_000L
    private const val TRIM_ABOVE = 24 * 1024L
    private const val TRIM_TO = 8 * 1024

    const val START = "S"
    const val HOME = "H"
    const val OK = "O"
    const val ALIVE = "B"
    const val WIFI = "W"
    /** Written by the guard or Rescue: counting starts anew from here. */
    const val RESET = "R"
    /** Written by the guard: this start's main thread froze. */
    const val FROZEN = "F"
    /** Written by the guard: what it did (or in 1.x would have done) about this boot's failures was done. */
    const val VERDICT = "V"
    /** Written by the guard: its own Wi-Fi step ran this boot. */
    const val GUARD_WIFI = "G"

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var homeMarked = false
    private var drawnMarked = false
    @Volatile private var mainBeat = 0L

    fun bootId(): String = try {
        File("/proc/sys/kernel/random/boot_id").readText().trim().take(8)
    } catch (_: Throwable) {
        ""
    }

    /** One line, in one write. */
    fun add(c: Context, kind: String, tail: String = "") {
        try {
            val line = "$kind ${bootId()} ${SystemClock.elapsedRealtime()} ${Process.myPid()}${if (tail.isEmpty()) "" else " $tail"}\n"
            FileOutputStream(File(c.filesDir, FILE_MARKS), true).use { it.write(line.toByteArray()) }
        } catch (t: Throwable) {
            Log.w(TAG, "mark $kind: $t")
        }
    }

    /** The very first thing the main process does. Then the guard is asked to be up: it may be needed in a second. */
    fun started(c: Context) {
        val a = c.applicationContext ?: c
        app = a
        try {
            trim(File(a.filesDir, FILE_MARKS))
        } catch (t: Throwable) {
            Log.w(TAG, "trim: $t")
        }
        add(a, START, BuildConfig.VERSION_CODE.toString())
        GuardService.start(a, "main")
        try {
            main.post(beat)
            Thread({ beatWriter(a) }, "AuraGuardBeat").apply { isDaemon = true }.start()
            main.postDelayed({ if (!homeMarked) add(a, ALIVE) }, ALIVE_MS)
        } catch (t: Throwable) {
            Log.w(TAG, "beat: $t")
        }
    }

    /** The home screen is wanted (it got past the crash check): from here the guard expects a first frame. */
    fun homeWanted() {
        if (homeMarked) return
        homeMarked = true
        app?.let { add(it, HOME) }
    }

    /** Home's first frame is up. In about 20 s, if this process is still here, this build has shown it works. */
    fun homeDrawn() {
        if (drawnMarked) return
        drawnMarked = true
        val a = app ?: return
        if (BuildConfig.DEBUG) GuardTest.atDrawn()
        main.postDelayed({
            add(a, OK)
            if (BuildConfig.DEBUG) com.abdllh.aura.system.PrefTypes.check(a)
        }, OK_AFTER_DRAW_MS)
    }

    /** The main process's own Wi-Fi step ([com.abdllh.aura.system.WifiOnStart]) runs this boot: the guard need not. */
    fun wifiSeen() {
        app?.let { add(it, WIFI) }
    }

    private val beat = object : Runnable {
        override fun run() {
            mainBeat++
            main.postDelayed(this, BEAT_MS)
        }
    }

    private fun beatWriter(c: Context) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            // one fixed block rewritten in place: nothing to allocate, and a block written back twice a minute at most
            RandomAccessFile(File(c.filesDir, FILE_BEAT), "rw").use { f ->
                while (true) {
                    val line = "${Process.myPid()} $mainBeat\n".padEnd(31, ' ') + "\n"
                    f.seek(0)
                    f.write(line.toByteArray())
                    Thread.sleep(BEAT_MS)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "beat writer: $t")
        }
    }

    /** Only the main process trims, at its start: no other writer is busy then, and a line lost to the race costs nothing. */
    private fun trim(f: File) {
        if (f.length() <= TRIM_ABOVE) return
        val text = f.readText()
        val cut = text.indexOf('\n', text.length - TRIM_TO)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(if (cut >= 0) text.substring(cut + 1) else "")
        if (!tmp.renameTo(f)) tmp.delete()
    }
}
