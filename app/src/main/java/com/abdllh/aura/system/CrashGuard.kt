package com.abdllh.aura.system

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Process
import android.os.SystemClock
import android.widget.Toast
import com.abdllh.aura.R
import com.abdllh.aura.util.Prefs
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Crash-loop protection. A launcher that crashes on start would leave the car without a home screen,
 * so after repeated crashes we hand the home role back to the stock launcher and tell the user.
 *
 * The bookkeeping lives in its own preferences file and is stamped with the boot id plus the monotonic clock: head
 * units set their wall clock late (GPS / network time) and a wall-clock window would then forget or invent crashes.
 */
object CrashGuard {
    private const val WINDOW_MS = 3 * 60_000L
    private const val LIMIT = 3
    private const val KEY = "crashes"
    private const val KEY_DISABLED = "crashDisabled"
    private const val MARKER = "disable_home"

    private var sp: SharedPreferences? = null

    /** Must be the very first thing the app does, so a crash while the other singletons start up is counted too. */
    fun install(app: Application) {
        sp = app.getSharedPreferences("aura_crash", Context.MODE_PRIVATE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                record(app, e)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(t, e) ?: Process.killProcess(Process.myPid())
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
     */
    fun setAuraEnabled(ctx: Context, enabled: Boolean, why: String): Boolean {
        try {
            val marker = File(ctx.filesDir, MARKER)
            if (enabled) marker.delete() else marker.writeText("$why ${System.currentTimeMillis()}")
        } catch (_: Throwable) {
        }
        sp?.edit()?.putBoolean(KEY_DISABLED, !enabled && why == "crash")?.apply()
        SystemProps.set(Device.DISABLED_PROP, if (enabled) "0" else "1")
        // the hidden status bar belongs to Aura's home screen: the stock launcher gets it back
        try { if (enabled) SystemBars.apply(ctx) else SystemBars.release(ctx) } catch (_: Throwable) { }
        return Device.setHome(if (enabled) ctx.packageName else Device.STOCK_LAUNCHER)
    }

    /** A new build gets another chance after a crash-loop shutdown (the user's own choice of the stock launcher is kept). */
    fun retryAfterUpdate(ctx: Context) {
        if (sp?.getBoolean(KEY_DISABLED, false) == true) {
            clear()
            setAuraEnabled(ctx, true, "update")
        }
    }

    /** Give the home role back to the stock launcher and open it. */
    fun bailOut(ctx: Context) {
        val restored = setAuraEnabled(ctx, false, "crash")
        try { Prefs.safeModeNotice = true } catch (_: Throwable) { }
        clear()
        try {
            if (restored) Toast.makeText(ctx, R.string.safe_mode_notice, Toast.LENGTH_LONG).show()
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setPackage(Device.STOCK_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (_: Throwable) {
        }
    }
}
