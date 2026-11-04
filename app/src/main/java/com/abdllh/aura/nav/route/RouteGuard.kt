package com.abdllh.aura.nav.route

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.abdllh.aura.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Valhalla's native code in :route may crash on the unit: nothing there was ever run on its kernel and CPU. A native crash passes
 * through no Java code, so while a request is worked on a marker file lies on the disk (written by :route, [working]). A process
 * that dies leaves it. The main process counts: two deaths in a row trip the guard, and routing goes on without Valhalla
 * ([RouteChain]). A timeout counts as a death: the process is killed for it, and a driver should not wait 30 s twice. An answer of
 * any kind from the engine, "no road" too, ends the row. A process the system reclaimed while idle has no marker and counts for
 * nothing.
 *
 * After a death Valhalla rests for [GuardState.REST_MS]: a second crash of one process within a minute is what makes Android show
 * "AMRI OS keeps stopping" over the home screen (a first crash shows nothing: AppErrors, MIN_CRASH_INTERVAL; seen on the emulator
 * with a real SIGSEGV). Apart from a minute later the same two crashes trip the guard and nobody sees anything. Meanwhile routing
 * goes on with the next router.
 *
 * The state is on the disk, so a trip survives a restart, and goes with a new app version (the new one may have a new library) or
 * after [GuardState.EXPIRES_DAYS] days (a try again costs two failed requests and no more). Everything it decides goes to a small log
 * the Screen check can send ([logText]). All but [working] and [notWorking]: main process.
 */
internal object RouteGuard {
    private const val TAG = "AuraRouteGuard"
    private const val LOG_MAX = 16 * 1024

    private fun dir(c: Context) = File(c.filesDir, "diag").apply { mkdirs() }
    private fun marker(c: Context) = File(dir(c), "route-working.marker")
    private fun stateFile(c: Context) = File(dir(c), "route-guard.state")
    fun logFile(c: Context) = File(dir(c), "route-guard.log")

    private var state: GuardState? = null

    // ------------------------------------------------------------------------------------------ in :route

    fun working(c: Context, what: String) {
        // (a file written and closed outlives the process; only a power cut in this moment loses it)
        try { marker(c).writeText("${android.os.Process.myPid()} $what") } catch (t: Throwable) { Log.w(TAG, "$t") }
    }

    fun notWorking(c: Context) {
        try { marker(c).delete() } catch (_: Throwable) { }
    }

    // ------------------------------------------------------------------------------------------ in the main process

    /** May Valhalla be asked? Looks first for a marker left by a process that died with nobody listening (this one restarted, a power cut). */
    fun allowed(c: Context): Boolean {
        val s = load(c)
        if (s.expired(BuildConfig.VERSION_CODE, System.currentTimeMillis())) {
            note(c, if (s.code != BuildConfig.VERSION_CODE) "cleared: new app version" else "cleared: ${GuardState.EXPIRES_DAYS} days have passed")
            s.clear(BuildConfig.VERSION_CODE)
            store(c, s)
        }
        if (leftMarker(c)) died(c, "a marker left from before")
        return !s.tripped && !s.resting(SystemClock.uptimeMillis())
    }

    /** A process :route ended with work in hand. */
    fun died(c: Context, why: String) {
        val s = load(c)
        s.died(System.currentTimeMillis(), SystemClock.uptimeMillis())
        store(c, s)
        notWorking(c)
        note(c, "died: $why (${s.deaths} in a row)${if (s.tripped) "; tripped: routing goes on without Valhalla" else ""}")
    }

    /** The engine answered. */
    fun fine(c: Context) {
        val s = load(c)
        if (s.deaths == 0) return
        s.fine()
        store(c, s)
        note(c, "answered: the row of deaths ends")
    }

    /** The marker of a process no longer alive. */
    fun leftMarker(c: Context): Boolean {
        val m = marker(c)
        if (!m.exists()) return false
        val pid = try { m.readText().substringBefore(' ').toInt() } catch (_: Throwable) { 0 }
        val alive = pid > 0 && try { File("/proc/$pid/cmdline").readText().contains(":route") } catch (_: Throwable) { false }
        return !alive
    }

    fun hasMarker(c: Context) = marker(c).exists()

    /** For the Screen check, and a test: start over. */
    fun reset(c: Context) {
        val s = load(c)
        s.clear(BuildConfig.VERSION_CODE)
        store(c, s)
        notWorking(c)
        note(c, "reset by hand")
    }

    /** One line for a report: "tripped 2026-11-04 18:56, 2 deaths". */
    fun status(c: Context): String {
        val s = load(c)
        return when {
            s.tripped -> "tripped ${stamp(s.trippedAt)}, ${s.deaths} deaths"
            s.resting(SystemClock.uptimeMillis()) -> "resting after a death, ${s.deaths} in a row"
            else -> "open, ${s.deaths} deaths in a row"
        }
    }

    fun tripped(c: Context) = load(c).tripped

    /** What it decided, oldest first, at most 16 KB. */
    fun logText(c: Context): String = try {
        logFile(c).takeIf { it.isFile }?.readText() ?: ""
    } catch (_: Throwable) {
        ""
    }

    /** A line of the chain's own, for the same log: which engine answered, when it was not the first. */
    fun note(c: Context, line: String) {
        Log.i(TAG, line)
        try {
            val f = logFile(c)
            f.appendText("${stamp(System.currentTimeMillis())} $line\n")
            if (f.length() > LOG_MAX) f.writeText(f.readText().takeLast(LOG_MAX / 2).substringAfter('\n'))
        } catch (t: Throwable) {
            Log.w(TAG, "$t")
        }
    }

    private fun stamp(at: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(at))

    private fun load(c: Context): GuardState = state ?: GuardState.parse(try { stateFile(c).readText() } catch (_: Throwable) { "" }, BuildConfig.VERSION_CODE).also { state = it }

    private fun store(c: Context, s: GuardState) {
        try {
            val f = stateFile(c)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(s.text())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (t: Throwable) {
            Log.w(TAG, "$t")
        }
    }
}

/** The guard's rule, apart from the disk. Two deaths in a row trip it. */
internal class GuardState(var code: Int, var deaths: Int = 0, var trippedAt: Long = 0L, var lastDeathUptime: Long = 0L) {
    val tripped get() = trippedAt != 0L

    /** [now]: the wall clock, [uptime]: SystemClock.uptimeMillis, which is what Android times its minute with. */
    fun died(now: Long, uptime: Long) {
        deaths++
        lastDeathUptime = uptime
        if (deaths >= TRIP_AT && !tripped) trippedAt = now
    }

    /** (a smaller uptime than the death's: the unit has restarted since, and Android has forgotten) */
    fun resting(uptime: Long) = lastDeathUptime != 0L && uptime >= lastDeathUptime && uptime - lastDeathUptime < REST_MS

    fun fine() {
        deaths = 0
        trippedAt = 0L
        lastDeathUptime = 0L
    }

    /**
     * A new app version, or a guard tripped long enough ago. A clock now before the trip (head units start with a late or old
     * clock) says nothing: it stays.
     */
    fun expired(appCode: Int, now: Long) = (code != appCode && (tripped || deaths > 0)) || (tripped && now - trippedAt >= EXPIRES_DAYS * 86_400_000L)

    fun clear(appCode: Int) {
        code = appCode
        deaths = 0
        trippedAt = 0L
        lastDeathUptime = 0L
    }

    fun text() = "$code $deaths $trippedAt $lastDeathUptime"

    companion object {
        const val TRIP_AT = 2
        const val EXPIRES_DAYS = 7L
        /** Android's minute, and a little. */
        const val REST_MS = 65_000L

        fun parse(s: String, appCode: Int): GuardState {
            val p = s.trim().split(' ')
            return if (p.size == 4) GuardState(p[0].toIntOrNull() ?: appCode, p[1].toIntOrNull() ?: 0, p[2].toLongOrNull() ?: 0L, p[3].toLongOrNull() ?: 0L) else GuardState(appCode)
        }
    }
}
