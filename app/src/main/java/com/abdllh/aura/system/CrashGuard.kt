package com.abdllh.aura.system

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Process
import android.widget.Toast
import com.abdllh.aura.util.Prefs
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Crash-loop protection. A launcher that crashes on start would leave the car without a home screen,
 * so after repeated crashes we hand the home role back to the stock launcher and tell the user.
 */
object CrashGuard {
    private const val WINDOW_MS = 3 * 60_000L
    private const val LIMIT = 3
    private const val KEY = "crashTimes"

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                record(app, e)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(t, e) ?: Process.killProcess(Process.myPid())
        }
    }

    private fun record(ctx: Context, e: Throwable) {
        val now = System.currentTimeMillis()
        val kept = recent(now) + now
        Prefs.raw.edit().putString(KEY, kept.joinToString(",")).commit()
        try {
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            File(ctx.filesDir, "last_crash.txt").writeText("time=$now\n$sw")
        } catch (_: Throwable) {
        }
    }

    private fun recent(now: Long): List<Long> =
        (Prefs.raw.getString(KEY, "") ?: "").split(",").mapNotNull { it.toLongOrNull() }.filter { now - it < WINDOW_MS }

    fun isCrashLoop(): Boolean = recent(System.currentTimeMillis()).size >= LIMIT

    fun clear() {
        Prefs.raw.edit().remove(KEY).apply()
    }

    fun lastCrash(ctx: Context): String? = try {
        File(ctx.filesDir, "last_crash.txt").takeIf { it.exists() }?.readText()
    } catch (_: Throwable) {
        null
    }

    /** Give the home role back to the stock launcher and open it. */
    fun bailOut(ctx: Context) {
        Device.setHome(Device.STOCK_LAUNCHER)
        SystemProps.set(Device.DISABLED_PROP, "1")
        // marker the boot script (running as root) also honours, in case property writes are blocked
        try { File(ctx.filesDir, "disable_home").writeText("crash loop ${System.currentTimeMillis()}") } catch (_: Throwable) { }
        Prefs.safeModeNotice = true
        clear()
        try {
            Toast.makeText(ctx, "Aura stopped after repeated crashes — stock launcher restored", Toast.LENGTH_LONG).show()
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setPackage(Device.STOCK_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (_: Throwable) {
        }
    }
}
