package com.abdllh.aura.system

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.abdllh.aura.R
import com.abdllh.aura.util.Prefs
import java.util.concurrent.Executors

/**
 * Removing apps the unit would not let go of (its own app manager has no uninstall for apps a dealer installed), as
 * root through the unit's adbd: `pm uninstall`, then for the current user only, and as the last resort disabled. An
 * app that made itself a device administrator is unbound from that first (its admin receiver disabled), which is what
 * otherwise refuses the uninstall.
 *  - [uninstall]: the app list's long-press "Uninstall" (apps installed on the unit, not the firmware's).
 *  - [removeRequestedOnce]: the apps the owner asked to be removed with an update: a launcher called "Vivid" that
 *    started at every power-on (matched by its name or package, only apps with a home or launcher screen, never the
 *    stock launcher or AMRI).
 */
object AppRemover {
    private const val TAG = "AuraRemove"
    private val REQUESTED = listOf("vivid", "فيفيد")
    private const val KEY_DONE = "removedRequested1"
    private const val KEY_TRIES = "removedRequestedTries"

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    /** An app the long-press menu may offer to uninstall: installed on the unit, not part of the firmware. */
    fun removable(ctx: Context, pkg: String): Boolean = try {
        val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
        ai.flags and ApplicationInfo.FLAG_SYSTEM == 0 && pkg != ctx.packageName && pkg != Device.STOCK_LAUNCHER
    } catch (_: Throwable) {
        false
    }

    /** Uninstalls [pkg]; [done] (main thread) with true when it is gone or switched off. */
    fun uninstall(ctx: Context, pkg: String, done: (Boolean) -> Unit) {
        val c = ctx.applicationContext
        worker.execute {
            val ok = remove(c, pkg)
            main.post { done(ok) }
        }
    }

    /** Once: the apps the owner asked for (a few tries over the next starts if the root shell is not there yet). */
    fun removeRequestedOnce(ctx: Context) {
        val test = com.abdllh.aura.BuildConfig.DEBUG && SystemProps.get("debug.aura.remove") == "1" // emulator tests
        if (!(Device.isNwd || test) || Prefs.raw.getBoolean(KEY_DONE, false)) return
        val c = ctx.applicationContext
        main.postDelayed({
            worker.execute {
                val targets = requested(c)
                Log.i(TAG, "requested: $targets")
                if (targets.isNotEmpty() && !LocalAdb.run("echo ok").output.startsWith("ok")) {
                    val tries = Prefs.raw.getInt(KEY_TRIES, 0) + 1
                    Prefs.raw.edit().putInt(KEY_TRIES, tries).putBoolean(KEY_DONE, tries >= 3).apply()
                    return@execute
                }
                val removed = targets.filter { remove(c, it.first) }
                Prefs.raw.edit().putBoolean(KEY_DONE, true).apply()
                if (removed.isNotEmpty()) main.post {
                    Toast.makeText(c, c.getString(R.string.removed_apps, removed.joinToString("، ") { it.second }), Toast.LENGTH_LONG).show()
                }
            }
        }, 25_000L)
    }

    /** (package, label) of the installed apps with a home or launcher screen whose name matches the requested ones. */
    private fun requested(c: Context): List<Pair<String, String>> {
        val pm = c.packageManager
        val infos = listOf(Intent.CATEGORY_HOME, Intent.CATEGORY_LAUNCHER).flatMap { cat ->
            try {
                pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), PackageManager.MATCH_DISABLED_COMPONENTS)
            } catch (_: Throwable) { emptyList() }
        }.map { it.activityInfo.applicationInfo }.distinctBy { it.packageName }
        return infos.mapNotNull { ai ->
            val label = try { pm.getApplicationLabel(ai).toString() } catch (_: Throwable) { "" }
            val hit = REQUESTED.any { n -> label.contains(n, ignoreCase = true) || ai.packageName.contains(n, ignoreCase = true) }
            if (hit && ai.packageName != c.packageName && ai.packageName != Device.STOCK_LAUNCHER) ai.packageName to label.ifBlank { ai.packageName }
            else null
        }
    }

    /** Blocking (worker thread): gone (or at least off) afterwards? */
    private fun remove(c: Context, pkg: String): Boolean {
        if (!pkg.matches(Regex("[A-Za-z0-9_.]+"))) return false
        LocalAdb.run("am force-stop $pkg")
        var r = LocalAdb.run("pm uninstall $pkg")
        Log.i(TAG, "uninstall $pkg: ${r.ok} ${r.output}")
        if (r.output.contains("Success")) return true
        if (r.output.contains("DEVICE_POLICY", ignoreCase = true)) {
            // a device administrator cannot be uninstalled: unbind it by disabling its admin receiver(s)
            val dpm = c.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            val admins = try { dpm?.activeAdmins.orEmpty().filter { it.packageName == pkg } } catch (_: Throwable) { emptyList() }
            // single-quoted: an inner class's name has a '$' the shell would expand
            for (a in admins) a.flattenToShortString().takeIf { it.matches(Regex("[A-Za-z0-9_.\$/]+")) }
                ?.let { LocalAdb.run("pm disable '$it'") }
            Thread.sleep(1500)
            r = LocalAdb.run("pm uninstall $pkg")
            Log.i(TAG, "uninstall $pkg after admin: ${r.output}")
            if (r.output.contains("Success")) return true
        }
        r = LocalAdb.run("pm uninstall --user 0 $pkg")
        Log.i(TAG, "uninstall --user 0 $pkg: ${r.output}")
        if (r.output.contains("Success")) return true
        r = LocalAdb.run("pm disable-user --user 0 $pkg")
        Log.i(TAG, "disable $pkg: ${r.output}")
        return r.output.contains("disabled")
    }
}
