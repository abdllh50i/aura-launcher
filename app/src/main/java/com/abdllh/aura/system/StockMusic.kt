package com.abdllh.aura.system

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.abdllh.aura.util.Prefs
import java.util.concurrent.Executors

/**
 * Optional, user-switched (Settings → Display & sound → Music): "Aura Music instead of the stock music app".
 * When the user turns it on, com.nwd.android.music.ui is disabled and the firmware's music source (app id 2:
 * Music/Media/Mode keys, USB insertion, resume after ignition) is pointed at Aura Music through the firmware's own
 * override file /data/nwdappconfig/app/replace_source_list.xml (read by KernelService at start and on
 * com.nwd.ACTION_REPLACE_SOURCE_LIST_CHANGE). Both need root, so they go through the unit's adbd ([LocalAdb]).
 * Turning it off puts both back as they were (a list that was there before is restored; the stock app is enabled
 * again unless it had been disabled before). Nothing happens unless the user switched the option on at some point;
 * the ROM installer's restore undoes it as well.
 */
object StockMusic {
    private const val TAG = "AuraStockMusic"
    const val PKG = "com.nwd.android.music.ui"
    private const val KEY = "replaceStockMusic"
    private const val APPLIED = "stockMusicApplied"         // "on" while something of this is in place
    private const val WAS_DISABLED = "stockMusicWasDisabled" // the stock app was already disabled before Aura
    private const val LIST = "/data/nwdappconfig/app/replace_source_list.xml"
    private const val BACKUP = "$LIST.pre-aura"
    private const val MARK = "com.abdllh.aura.music.MusicActivity"
    private const val ITEM = """<ReplaceSourceItem appid="2" pkgName="com.abdllh.aura" className="$MARK"/>"""
    private const val END = "__aura_end__"
    private const val RELOAD = "am broadcast -a com.nwd.ACTION_REPLACE_SOURCE_LIST_CHANGE >/dev/null 2>&1"

    private val worker = Executors.newSingleThreadExecutor() // one change at a time, in the order asked

    /** The user's choice (off unless switched on in Settings). */
    var enabled: Boolean
        get() = Prefs.raw.getBoolean(KEY, false)
        set(v) { Prefs.raw.edit().putBoolean(KEY, v).apply() }

    /** Is the stock app present on this unit at all (the option is only shown then)? */
    fun available(c: Context) = Device.isNwd && installed(c)

    /** Brings the unit in line with the user's choice; [done] gets true when it worked (called on a worker thread). */
    fun apply(ctx: Context, done: ((Boolean) -> Unit)? = null) {
        if (!Device.isNwd) { done?.invoke(false); return }
        val c = ctx.applicationContext
        worker.execute {
            val want = enabled // the latest choice: a quick on/off/on ends where the switch ends
            // never touched: nothing to check (no root shell on every start for users who never used the option)
            if (!want && Prefs.raw.getString(APPLIED, "") != "on") { done?.invoke(true); return@execute }
            val ok = try { if (want) replace(c) else restore(c) } catch (t: Throwable) { Log.w(TAG, "apply: $t"); false }
            if (ok) Prefs.raw.edit().putString(APPLIED, if (want) "on" else "off").apply()
            done?.invoke(ok)
        }
    }

    private fun installed(c: Context) = try { c.packageManager.getApplicationInfo(PKG, PackageManager.MATCH_DISABLED_COMPONENTS); true } catch (_: Throwable) { false }

    private fun disabled(c: Context): Boolean = try {
        val s = c.packageManager.getApplicationEnabledSetting(PKG)
        s == PackageManager.COMPONENT_ENABLED_STATE_DISABLED || s == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
    } catch (_: Throwable) {
        false
    }

    /** Runs [cmd] as root and returns its output, or null when it did not run to the end (adbd down, refused...). */
    private fun sh(cmd: String): String? {
        val r = LocalAdb.run("$cmd; echo $END")
        if (!r.ok) return null
        val out = r.output.trimEnd()
        return if (out.endsWith(END)) out.removeSuffix(END).trimEnd() else null
    }

    private fun replace(c: Context): Boolean {
        Prefs.raw.edit().putString(APPLIED, "on").apply() // from here on there may be something to undo
        if (installed(c) && !disabled(c)) {
            if (!Prefs.raw.contains(WAS_DISABLED)) Prefs.raw.edit().putBoolean(WAS_DISABLED, false).apply()
            Log.i(TAG, "disable: ${sh("pm disable-user --user 0 $PKG")}")
            if (!disabled(c)) return false
        } else if (installed(c) && !Prefs.raw.contains(WAS_DISABLED)) {
            Prefs.raw.edit().putBoolean(WAS_DISABLED, true).apply() // disabled before Aura: leave it so later
        }
        val cur = sh("cat $LIST 2>/dev/null") ?: return false
        if (cur.contains(MARK)) return true
        // A list that is already there (not on a stock unit) is kept aside once and comes back when this is undone.
        val save = if (cur.isNotBlank()) "[ -f $BACKUP ] || cp $LIST $BACKUP; " else ""
        val r = sh("$save echo '<list>$ITEM</list>' > $LIST && chmod 644 $LIST && $RELOAD && echo written") ?: return false
        Log.i(TAG, "source list: $r")
        return r.contains("written")
    }

    private fun restore(c: Context): Boolean {
        if (installed(c) && disabled(c) && !Prefs.raw.getBoolean(WAS_DISABLED, false)) {
            Log.i(TAG, "enable: ${sh("pm enable $PKG")}")
            if (disabled(c)) return false
        }
        val cur = sh("cat $LIST 2>/dev/null") ?: return false
        if (cur.contains(MARK)) {
            val back = "if [ -f $BACKUP ]; then cat $BACKUP > $LIST && rm -f $BACKUP; else rm -f $LIST; fi"
            sh("$back && $RELOAD && echo restored")?.takeIf { it.contains("restored") } ?: return false
        }
        Prefs.raw.edit().remove(WAS_DISABLED).apply()
        return true
    }
}
