package com.abdllh.aura.system

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.util.Prefs

/**
 * AMRI's code compiled ahead of time from what the runtime saw it run (speed-profile, through the root shell), once per
 * build. 37 of the 38 stock privileged apps come compiled and AMRI does not; on the emulator this took 17 % off the main
 * thread's CPU at a start and the JIT's 100 ms, and whether the system's own idle job ever runs on a unit that sleeps with
 * the ignition is not known.
 *  - The unit only. Asked a few minutes after the process started (the profile needs a run to have something in it) and
 *    then every minute until the car is parked: the gear P ([Gear], a guess included), the screen on (with the ignition
 *    off the unit is going to sleep), no phone call. dex2oat takes a core for seconds to a minute there, and memory.
 *  - It runs as a child of installd and swaps its files in as it ends: the running process keeps the code it has, and the
 *    new code is used from the next process start. A failure leaves things as they were.
 *  - A few attempts per build (one per process), then it waits for the next build. What happened goes to the log and to
 *    the Screen check's report.
 */
object DexCompile {
    private const val TAG = "AuraCompile"
    private const val KEY_CHECKED = "compileCheckedFor" // the build that has nothing more to do
    private const val KEY_TRIES = "compileTries"        // "versionCode:failed attempts"
    private const val KEY_NOTE = "compileNote"
    private const val MAX_TRIES = 3
    private const val FIRST_MS = 5 * 60_000L            // of the unit being awake (a sleeping unit's clock stands still)
    private const val AGAIN_MS = 60_000L
    private const val TIMEOUT_MS = 5 * 60_000           // the root shell's silence while dex2oat runs
    private const val FILTER = "speed-profile"
    private const val MIN_PROFILE = 64L                 // an empty profile file is its header alone

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var started = false

    /** What the last attempt came to, or that one is waiting (for the Screen check's report). */
    val note: String get() = Prefs.raw.getString(KEY_NOTE, "").orEmpty().ifEmpty { if (started) "waiting for the car to stand parked" else "" }

    /** At start: asks for the compile once, at a quiet moment. */
    fun ensure(ctx: Context) {
        if (started) return
        // debug builds on the emulator: setprop debug.aura.compile <minutes to the first look> (with the fake adbd, see AuraApp)
        val debugMin = if (BuildConfig.DEBUG) SystemProps.get("debug.aura.compile").toLongOrNull() else null
        if (!Device.isNwd && debugMin == null) return
        if (Prefs.raw.getInt(KEY_CHECKED, 0) == BuildConfig.VERSION_CODE) return
        started = true
        val c = ctx.applicationContext
        main.postDelayed({ look(c) }, if (debugMin != null) debugMin * 60_000L else FIRST_MS)
    }

    private fun look(c: Context) {
        if (!parked(c)) {
            main.postDelayed({ look(c) }, AGAIN_MS)
            return
        }
        Thread({ compile(c) }, "aura-compile").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }

    private fun parked(c: Context): Boolean = try {
        Gear.current == Gear.Pos.P && !com.abdllh.aura.phone.Calls.busy &&
            (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
    } catch (t: Throwable) {
        Log.w(TAG, "parked: $t")
        false
    }

    private fun compile(c: Context) {
        try {
            val pkg = c.packageName
            // (the profile's size first: with nothing in it the compile would come to nothing, and say "Success")
            val size = LocalAdb.run("stat -c %s /data/misc/profiles/cur/0/$pkg/primary.prof")
            val bytes = size.output.trim().toLongOrNull()
            if (!size.ok || bytes == null) { failed("no root shell or no profile: ${size.output.take(120)}"); return }
            if (bytes < MIN_PROFILE) { failed("the profile has nothing yet: $bytes bytes"); return }
            val t0 = SystemClock.elapsedRealtime()
            val r = LocalAdb.run("cmd package compile -m $FILTER $pkg; dumpsys package $pkg | grep status=", TIMEOUT_MS)
            val secs = (SystemClock.elapsedRealtime() - t0) / 1000
            val out = r.output.lines().map { it.trim() }
            val state = out.filter { it.contains("status=") }.joinToString(" ")
            when {
                !r.ok -> failed("root shell after $secs s: ${r.output.take(120)}")
                "Success" in out && state.contains("status=$FILTER") -> {
                    note("$FILTER in $secs s, profile $bytes bytes: from the next start")
                    Prefs.raw.edit().putInt(KEY_CHECKED, BuildConfig.VERSION_CODE).remove(KEY_TRIES).apply()
                }
                else -> failed("after $secs s: ${out.joinToString(" ").take(160)}")
            }
        } catch (t: Throwable) {
            failed("$t")
        }
    }

    /** One attempt gone: the next start tries again, a few times per build. */
    private fun failed(why: String) {
        note(why)
        val (code, n) = Prefs.raw.getString(KEY_TRIES, "").orEmpty().split(":").let {
            (it.getOrNull(0)?.toIntOrNull() ?: 0) to (it.getOrNull(1)?.toIntOrNull() ?: 0)
        }
        val tries = if (code == BuildConfig.VERSION_CODE) n + 1 else 1
        if (tries >= MAX_TRIES) Prefs.raw.edit().putInt(KEY_CHECKED, BuildConfig.VERSION_CODE).remove(KEY_TRIES).apply()
        else Prefs.raw.edit().putString(KEY_TRIES, "${BuildConfig.VERSION_CODE}:$tries").apply()
    }

    private fun note(what: String) {
        Log.i(TAG, what)
        Prefs.raw.edit().putString(KEY_NOTE, "${BuildConfig.VERSION_NAME}: $what").apply()
    }
}
