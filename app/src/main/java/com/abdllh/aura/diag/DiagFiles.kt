package com.abdllh.aura.diag

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.abdllh.aura.system.LocalAdb
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

/*
 * The screen check's parts that both of its processes use (the main one, and :diag where everything that loads GL or
 * native code runs). The two only talk through files in files/diag/: nothing that dies in :diag can leave the main
 * process waiting on a binder, and what a test wrote before it died is still there.
 */

/** The files in files/diag/. Every write is whole or not at all (a temporary file renamed), and on the disk at once. */
internal object DiagFiles {
    fun dir(c: Context): File = File(c.filesDir, "diag").apply { mkdirs() }

    /** A test is running: written before it starts, deleted when it ended. Found left over, the test killed its process. */
    fun marker(c: Context) = File(dir(c), "marker.json")
    /** What one test found. */
    fun result(c: Context, test: String) = File(dir(c), "r-$test.json")
    /** The tests the next :diag process is to run. */
    fun plan(c: Context) = File(dir(c), "plan.json")
    /** :diag ran its plan to the end (or was stopped: "interrupted"). */
    fun planDone(c: Context) = File(dir(c), "plan-done.json")
    /** The owner pressed Cancel on the :diag screen. */
    fun cancel(c: Context) = File(dir(c), "cancel")
    /** The tests that killed their process once: reported and not run again (see [ScreenCheck]). */
    fun skip(c: Context) = File(dir(c), "skip.json")
    /** A run is under way, in the main process with this pid. */
    fun run(c: Context) = File(dir(c), "run.json")
    fun report(c: Context) = File(dir(c), "report.json")
    /** How sending [report] went. */
    fun sent(c: Context) = File(dir(c), "report-sent.json")
    fun trip(c: Context) = File(dir(c), "trip.jsonl")

    fun write(f: File, o: JSONObject) = writeBytes(f, o.toString().toByteArray(Charsets.UTF_8))

    fun writeBytes(f: File, b: ByteArray) {
        val tmp = File(f.path + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(b)
            out.flush()
            // (a test that restarts the whole unit must still be found out at the next run)
            try { out.fd.sync() } catch (_: Throwable) { }
        }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun read(f: File): JSONObject? = try {
        if (f.isFile) JSONObject(f.readText()) else null
    } catch (t: Throwable) {
        Log.w("AuraDiag", "${f.name}: $t")
        null
    }
}

/**
 * The root shell for the check: [LocalAdb] with a hard end. A call never takes longer than its time, whatever the
 * command does: LocalAdb's own timeout is per read, so a command that keeps talking would otherwise never end; its
 * thread is left to finish on its own (the read timeout ends it). Once the shell has failed, the other calls answer at
 * once, so a unit without it does not spend two seconds on each.
 */
internal object Sh {
    class Out(val ok: Boolean, val text: String)

    val pool = Executors.newCachedThreadPool { r -> Thread(r, "aura-diag-sh").apply { isDaemon = true } }
    @Volatile private var known: Boolean? = null

    /** The shell works, as root. Asks once. */
    fun root(): Boolean {
        known?.let { return it }
        val r = call("id", 5000)
        val ok = r.ok && r.text.contains("uid=0")
        known = ok
        return ok
    }

    fun run(cmd: String, timeoutMs: Int = 8000): Out {
        if (!root()) return Out(false, "")
        return call(cmd, timeoutMs)
    }

    /** The command's words, or "" when it could not be run. */
    fun text(cmd: String, timeoutMs: Int = 8000): String = run(cmd, timeoutMs).let { if (it.ok) it.text else "" }

    private fun call(cmd: String, timeoutMs: Int): Out = try {
        val f = pool.submit(Callable { LocalAdb.run(cmd, timeoutMs) })
        try {
            val r = f.get(timeoutMs + 1500L, TimeUnit.MILLISECONDS)
            Out(r.ok, r.output)
        } catch (t: java.util.concurrent.TimeoutException) {
            f.cancel(true)
            Out(false, "timed out")
        }
    } catch (t: Throwable) {
        Out(false, t.toString())
    }
}

/** Small things for numbers and text in the report. */
internal object Num {
    /** A number JSON can carry, to [digits] places (org.json throws on NaN and infinity): null when there is none. */
    fun of(v: Double, digits: Int = 1): Any {
        if (v.isNaN() || v.isInfinite()) return JSONObject.NULL
        var k = 1.0
        repeat(digits) { k *= 10 }
        return (v * k).roundToLong() / k
    }

    /** Nearest-rank percentile of [sorted]. */
    fun pct(sorted: DoubleArray, p: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        val i = (Math.ceil(p / 100.0 * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)
        return sorted[i]
    }

    /** p50, p90, p95, p99 and the largest of [values] (milliseconds) into [o]. */
    fun spread(o: JSONObject, values: DoubleArray) {
        val s = values.sortedArray()
        o.put("p50", of(pct(s, 50.0)))
        o.put("p90", of(pct(s, 90.0)))
        o.put("p95", of(pct(s, 95.0)))
        o.put("p99", of(pct(s, 99.0)))
        o.put("max", of(s.lastOrNull() ?: Double.NaN))
    }

    fun lines(text: String): JSONArray = JSONArray().apply { for (l in text.lineSequence()) if (l.isNotBlank()) put(l.trimEnd()) }

    /** At most [cap] characters of [text]. */
    fun cut(text: String, cap: Int): String = if (text.length <= cap) text else text.substring(0, cap) + "\n[cut at $cap]"
}

/** A marker, and what is known of whoever left one. */
internal object Marker {
    fun set(c: Context, test: String, budgetMs: Long) {
        DiagFiles.write(DiagFiles.marker(c), JSONObject()
            .put("test", test)
            .put("deadline", SystemClock.elapsedRealtime() + budgetMs)
            .put("boot", bootId())
            .put("pid", Process.myPid()))
    }

    fun clear(c: Context) { DiagFiles.marker(c).delete() }

    fun bootId(): String = try { File("/proc/sys/kernel/random/boot_id").readText().trim() } catch (_: Throwable) { "" }
}
