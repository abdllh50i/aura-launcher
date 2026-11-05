package com.abdllh.aura.system

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.abdllh.aura.util.Prefs
import java.util.concurrent.Executors

/**
 * "Aura Music instead of the stock music app", for a unit where the owner switched that on in an older version (the switch
 * went in 2.0.0-beta.3; on his unit it is on): com.nwd.android.music.ui is disabled and the firmware's music source (app id 2:
 * Music/Media/Mode keys, USB insertion, resume after ignition) is pointed at Aura Music through the firmware's own
 * override file /data/nwdappconfig/app/replace_source_list.xml (read by KernelService at start and on
 * com.nwd.ACTION_REPLACE_SOURCE_LIST_CHANGE). Both need root, so they go through the unit's adbd ([LocalAdb]).
 * With the kill switch on both go back as they were (a list that was there before is restored; the stock app is enabled
 * again unless it had been disabled before), and come again when it goes off: that is the way back now that no switch
 * is left. Nothing happens on a unit that never had the option on; the ROM installer's restore undoes it as well.
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

    /** Brings the unit in line with the stored choice and the kill switch. */
    fun apply(ctx: Context) {
        if (!Device.isNwd) return
        val c = ctx.applicationContext
        worker.execute {
            val want = Prefs.raw.getBoolean(KEY, false) && SystemProps.get(Device.DISABLED_PROP) != "1"
            // never touched: nothing to check (no root shell on every start for users who never used the option)
            if (!want && Prefs.raw.getString(APPLIED, "") != "on") return@execute
            val ok = try { if (want) replace(c) else restore(c) } catch (t: Throwable) { Log.w(TAG, "apply: $t"); false }
            if (ok) Prefs.raw.edit().putString(APPLIED, if (want) "on" else "off").apply()
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
