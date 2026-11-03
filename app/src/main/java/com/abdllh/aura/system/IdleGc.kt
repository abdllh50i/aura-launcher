package com.abdllh.aura.system

import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.abdllh.aura.home.AppRepo
import com.abdllh.aura.home.IconLoader

/**
 * One garbage collection when the home screen has done with other apps' files. A name or an icon of another app is read
 * through a Resources of that app, which maps its APK; nothing lets go of those mappings but a collection, and a process
 * this quiet can go for hours without one, so the app list's start left 15.7 MB (PSS, emulator) mapped for nothing, and
 * a few MB of dead Java objects and bitmaps with it. It runs off the main thread (the collector is concurrent: the main
 * thread stops for a few ms at most), only after the lists and icons asked for have come in, and at most once a minute.
 * What it freed goes to the log and Settings → About.
 */
object IdleGc {
    private const val TAG = "AuraGc"
    private const val RETRY_MS = 1000L
    private const val MAX_RETRIES = 20
    private const val MIN_APART_MS = 60_000L
    private const val FINALIZERS_MS = 1500L // the files are let go of by the finalizers, after the collection

    private val main = Handler(Looper.getMainLooper())
    private var waiting: Runnable? = null
    private var lastAt = -MIN_APART_MS

    /** What the last one did (for Settings → About). */
    @Volatile var note = ""
        private set

    /** Asks for a collection [delayMs] from now (main thread); a request already waiting is replaced. */
    fun request(why: String, delayMs: Long) {
        waiting?.let { main.removeCallbacks(it) }
        val r = object : Runnable {
            var tries = 0
            override fun run() {
                // (what is still loading would map more)
                if ((IconLoader.busy || AppRepo.busy) && ++tries <= MAX_RETRIES) { main.postDelayed(this, RETRY_MS); return }
                waiting = null
                val now = SystemClock.elapsedRealtime()
                if (now - lastAt < MIN_APART_MS) return
                lastAt = now
                Thread({ collect(why) }, "aura-gc").apply { isDaemon = true; start() }
            }
        }
        waiting = r
        main.postDelayed(r, delayMs)
    }

    private fun collect(why: String) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            val before = Debug.getPss()
            val t0 = SystemClock.elapsedRealtime()
            Runtime.getRuntime().gc()
            val ms = SystemClock.elapsedRealtime() - t0
            Thread.sleep(FINALIZERS_MS)
            val after = Debug.getPss()
            note = "$why: PSS ${before / 1024} -> ${after / 1024} MB ($ms ms)"
            Log.i(TAG, note)
        } catch (t: Throwable) {
            Log.w(TAG, "$why: $t")
        }
    }
}
