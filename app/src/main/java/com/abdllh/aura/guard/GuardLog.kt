package com.abdllh.aura.guard

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * files/guard-log.txt: what the guard saw and did or, in the 1.x versions, would have done. the Screen check's report shows its
 * end: the owner's photo of that page is how anything that happens on the unit gets back to the bench. The clock in a
 * line is for reading only (it may be wrong early after a boot, hence the seconds since the boot beside it); nothing is
 * ever decided from it.
 */
object GuardLog {
    private const val TAG = "AuraGuard"
    private const val FILE = "guard-log.txt"
    private const val TRIM_ABOVE = 24 * 1024L
    private const val TRIM_TO = 12 * 1024

    @Synchronized
    fun add(c: Context, text: String) {
        Log.i(TAG, text)
        try {
            val f = File(c.filesDir, FILE)
            if (f.length() > TRIM_ABOVE) {
                val all = f.readText()
                val cut = all.indexOf('\n', all.length - TRIM_TO)
                f.writeText(if (cut >= 0) all.substring(cut + 1) else "")
            }
            val at = SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(Date())
            FileOutputStream(f, true).use { it.write("$at +${SystemClock.elapsedRealtime() / 1000}s  $text\n".toByteArray()) }
        } catch (t: Throwable) {
            Log.w(TAG, "log: $t")
        }
    }

    /** A fault in the guard's process (the receiver, the service, Rescue) stays there: a line here, and no "keeps stopping" window over the car's screen. */
    fun quietCrashes(c: Context) {
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            try { add(c, "the guard itself crashed: $e") } catch (_: Throwable) { }
            Process.killProcess(Process.myPid())
        }
    }

    /** The newest lines, newest last. */
    fun tail(c: Context, lines: Int): List<String> = try {
        File(c.filesDir, FILE).takeIf { it.exists() }?.readLines()?.takeLast(lines) ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }
}
