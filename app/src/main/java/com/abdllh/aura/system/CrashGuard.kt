package com.abdllh.aura.system

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.R
import com.abdllh.aura.update.Rollback
import com.abdllh.aura.util.Prefs
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Crash-loop protection. A launcher that crashes on start would leave the car without a home screen,
 * so after repeated crashes we hand the home role back to the stock launcher and tell the user.
 *
 * The bookkeeping lives in its own preferences file and is stamped with the boot id plus the monotonic clock: head
 * units set their wall clock late (GPS / network time) and a wall-clock window would then forget or invent crashes.
 */
object CrashGuard {
    private const val TAG = "AuraCrash"
    private const val WINDOW_MS = 3 * 60_000L
    private const val LIMIT = 3
    private const val KEY = "crashes"
    private const val KEY_DISABLED = "crashDisabled"
    private const val MARKER = "disable_home"
    /** Time for the give-backs, each on its own worker through the root shell. */
    private const val LEAVE_AFTER_MS = 8_000L
    /** The crash loop's start waits this long for the root shell to say whether there is a version to go back to. */
    private const val LOOK_FOR_COPY_MS = 5_000L

    private var sp: SharedPreferences? = null

    /** A crash loop was found and the home role is being handed over, or the way back is running: no home screen from this process ([bailOutAtStart]). */
    @Volatile var bailedOut = false
        private set

    /** Must be the very first thing the app does, so a crash while the other singletons start up is counted too. */
    fun install(app: Application) {
        sp = app.getSharedPreferences("aura_crash", Context.MODE_PRIVATE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            var again = false
            try {
                record(app, e)
                again = recent(SystemClock.elapsedRealtime()).size >= 2 || SystemProps.get(Device.DISABLED_PROP) == "1"
            } catch (_: Throwable) {
            }
            // From the second crash in a row Android puts "keeps stopping" over the screen, and this process waits for
            // the answer, 5 minutes if nobody gives one. The next start, which would find the crash loop, waits with it (seen
            // on the emulator): so from there on it leaves without asking. With the kill switch on, a crash behind the
            // stock launcher has no business on the screen at all.
            if (again) {
                Log.e(TAG, "crashed again, leaving", e)
                Process.killProcess(Process.myPid())
            } else {
                previous?.uncaughtException(t, e) ?: Process.killProcess(Process.myPid())
            }
        }
    }

    private fun bootId(): String = try {
        File("/proc/sys/kernel/random/boot_id").readText().trim()
    } catch (_: Throwable) {
        ""
    }

    private fun record(ctx: Context, e: Throwable) {
        val now = SystemClock.elapsedRealtime()
        val kept = recent(now) + now
        sp?.edit()?.putString(KEY, bootId() + "|" + kept.joinToString(","))?.commit()
        try {
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            File(ctx.filesDir, "last_crash.txt").writeText("time=${System.currentTimeMillis()}\n$sw")
        } catch (_: Throwable) {
        }
    }

    /** Crash times (uptime, ms) of this boot that are still inside the window. Entries of another boot are ignored. */
    private fun recent(now: Long): List<Long> {
        val raw = sp?.getString(KEY, "") ?: ""
        val bar = raw.indexOf('|')
        if (bar < 0 || raw.substring(0, bar) != bootId()) return emptyList()
        return raw.substring(bar + 1).split(',').mapNotNull { it.toLongOrNull() }.filter { it <= now && now - it < WINDOW_MS }
    }

    fun isCrashLoop(): Boolean = recent(SystemClock.elapsedRealtime()).size >= LIMIT

    fun clear() {
        sp?.edit()?.remove(KEY)?.apply()
    }

    fun lastCrash(ctx: Context): String? = try {
        File(ctx.filesDir, "last_crash.txt").takeIf { it.exists() }?.readText()
    } catch (_: Throwable) {
        null
    }

    /**
     * Switches the home role between Aura and the stock launcher and keeps the boot-time kill switch
     * (`persist.aura.disabled` and `files/disable_home`, both read by aura-prepare.sh as root) in step, so the choice
     * survives a reboot. [why] is "user" (Settings) or "crash" (this class). Returns whether the home property changed.
     * [flipHome] false: everything but the property itself, which someone else is about to do ([bailOut]).
     */
    fun setAuraEnabled(ctx: Context, enabled: Boolean, why: String, flipHome: Boolean = true): Boolean {
        try {
            val marker = File(ctx.filesDir, MARKER)
            if (enabled) marker.delete() else marker.writeText("$why ${System.currentTimeMillis()}")
        } catch (_: Throwable) {
        }
        // AMRI is the home screen again: the guard counts failed starts from nothing
        if (enabled) com.abdllh.aura.guard.GuardMarks.add(ctx, com.abdllh.aura.guard.GuardMarks.RESET)
        sp?.edit()?.putBoolean(KEY_DISABLED, !enabled && why == "crash")?.apply()
        SystemProps.set(Device.DISABLED_PROP, if (enabled) "0" else "1")
        // the hidden status bar belongs to Aura's home screen: the stock launcher gets it back (and the firmware's
        // animation scales with it)
        try { if (enabled) SystemBars.apply(ctx) else SystemBars.release(ctx) } catch (_: Throwable) { }
        try { if (enabled) Animations.apply(ctx) else Animations.release(ctx) } catch (_: Throwable) { }
        // so does the stock volume bar (AuraApp asks again at every start while the kill switch is on); back on, the
        // display and the music buttons start here when this process began with the switch on (both idempotent)
        try {
            if (enabled) {
                VolumeHud.init(ctx); MusicKeys.init(ctx); HomeGuard.init(ctx); Gear.init(ctx); VolumeHud.apply(ctx)
                com.abdllh.aura.phone.Calls.init(ctx); com.abdllh.aura.phone.Calls.apply(ctx); MusicKeys.applyRules()
                Notices.apply(ctx)
            } else {
                // (the stock call window, the stock ring, the firmware's own music keys and Android's notifications with it)
                VolumeHud.release(ctx); com.abdllh.aura.phone.Calls.release(ctx); MusicKeys.release(); Notices.release(ctx)
            }
        } catch (_: Throwable) {
        }
        return flipHome && Device.setHome(if (enabled) ctx.packageName else Device.STOCK_LAUNCHER)
    }

    /** A new build gets another chance after a crash-loop shutdown (the user's own choice of the stock launcher is kept). */
    fun retryAfterUpdate(ctx: Context) {
        if (sp?.getBoolean(KEY_DISABLED, false) == true) {
            clear()
            setAuraEnabled(ctx, true, "update")
        }
    }

    /**
     * The crash loop as AuraApp finds it, right after [install]. Until 1.19 only HomeActivity looked, which a throw in
     * AuraApp's own start-up never reaches: the home screen crashed for ever. Nothing of the start-up runs now, since one
     * of its steps is what crashes; the settings are opened as far as they can be, because the give-backs read what to
     * give back from them (Prefs keeps the file even when its first read is what throws). The process then leaves, a
     * moment later: without its singletons it cannot serve anything, and the next start finds the kill switch on.
     *
     * While this version is on trial with a version to go back to, it goes back from here, and home is never handed to the
     * stock launcher: the moment the stock launcher comes up the firmware may force-stop the guard that was to do it
     * ([com.abdllh.aura.guard.BackgroundList]). What the guard would have done, nothing here depends on it living.
     */
    fun bailOutAtStart(app: Application) {
        bailedOut = true
        try { Prefs.init(app) } catch (_: Throwable) { }
        val main = Handler(Looper.getMainLooper())
        val leave = Runnable { Process.killProcess(Process.myPid()) }
        if (SystemProps.get(Device.DISABLED_PROP) == "1") { // (a crash loop behind the stock launcher: nothing to hand over)
            clear()
            main.postDelayed(leave, LEAVE_AFTER_MS)
            return
        }
        val decided = AtomicBoolean(false)
        val handOver = Runnable {
            if (decided.compareAndSet(false, true)) {
                try { bailOut(app) } catch (t: Throwable) { Log.w(TAG, "bail out: $t") }
                main.postDelayed(leave, LEAVE_AFTER_MS)
            }
        }
        main.postDelayed(handOver, LOOK_FOR_COPY_MS) // (a root shell that does not answer: as if there were no copy)
        Thread({
            val back = try { goBack(app) } catch (t: Throwable) { Log.w(TAG, "going back: $t"); false }
            if (!back) main.post(handOver)
            else if (decided.compareAndSet(false, true)) { main.removeCallbacks(handOver); main.postDelayed(leave, LEAVE_AFTER_MS) }
        }, "AuraCrash").start()
    }

    /** The way back's script started (detached: it outlives this process and ends it). False: on trial no more, no copy, no root shell. */
    private fun goBack(app: Application): Boolean {
        if (BuildConfig.VERSION_CODE < com.abdllh.aura.guard.GuardService.ACTS_FROM_CODE || com.abdllh.aura.guard.GuardTrial.load(app).passed) return false
        if (!Rollback.usable(Rollback.payload())) return false
        // (should the script fail, its own fallback is the kill switch: a new build then gets another chance, as after a
        // bail-out. The crash list stays: a start while the install runs finds the loop and comes here again, and the script
        // removes it when it is through)
        sp?.edit()?.putBoolean(KEY_DISABLED, true)?.commit()
        Log.w(TAG, "a crash loop on trial: going back")
        return Rollback.runScript(app, false)
    }

    /**
     * Gives the home role back to the stock launcher and opens it. The property itself goes last and through the script
     * ([Rollback.toStock]), as root and outside this process: it also gives back what its workers may not live to, since the
     * firmware force-stops AMRI within a second of the stock launcher coming up unless AMRI is on its list.
     */
    fun bailOut(ctx: Context) {
        bailedOut = true // (a home intent meanwhile, the property not flipped yet: nothing more of AMRI's home screen)
        setAuraEnabled(ctx, false, "crash", flipHome = false)
        try { Prefs.safeModeNotice = true } catch (_: Throwable) { }
        clear()
        try {
            if (SystemProps.get(Device.DISABLED_PROP) == "1") Toast.makeText(ctx, R.string.safe_mode_notice, Toast.LENGTH_LONG).show()
        } catch (_: Throwable) {
        }
        val c = ctx.applicationContext
        Thread({ Rollback.toStock(c, "crash") }, "AuraCrash").start()
    }
}
