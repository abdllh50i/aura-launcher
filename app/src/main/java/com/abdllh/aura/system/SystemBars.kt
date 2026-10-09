package com.abdllh.aura.system

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.abdllh.aura.util.Prefs

/**
 * Hides Android's top status bar on the whole unit (it slides in when you swipe down from the top edge), using the
 * window manager's immersive policy (Settings.Global "policy_control", present in this firmware's PolicyControl).
 * Other entries of that setting are kept; what Aura added is removed again (and a value it replaced is put back) when
 * the option is switched off or Aura stops being the home screen. Needs WRITE_SECURE_SETTINGS (granted to the
 * /system/priv-app build).
 */
object SystemBars {
    private const val TAG = "AuraBars"
    private const val KEY = "policy_control"
    private const val STATUS = "immersive.status"
    private const val PRECONFIRM = "immersive.preconfirms"
    private const val ADDED = "barsAdded"          // entries Aura put there
    private const val REPLACED = "barsReplacedStatus" // an immersive.status value that was there before Aura's

    /** Brings the setting in line with the user's choice (Settings → Display & sound). */
    fun apply(ctx: Context): Boolean = update(ctx, Prefs.hideStatusBar)

    /** Gives the status bar back (Aura is no longer the home screen); the user's choice itself is kept. */
    fun release(ctx: Context): Boolean = update(ctx, false)

    private fun update(ctx: Context, hide: Boolean): Boolean {
        val cr = ctx.contentResolver
        val current = try { Settings.Global.getString(cr, KEY) } catch (_: Throwable) { null } ?: ""
        val entries = LinkedHashMap<String, String>()
        for (part in current.split(':')) {
            val i = part.indexOf('=')
            if (i > 0) entries[part.substring(0, i)] = part.substring(i + 1)
        }
        val added = Prefs.raw.getStringSet(ADDED, emptySet()) ?: emptySet()
        var replaced = Prefs.raw.getString(REPLACED, null)
        val nowAdded = HashSet<String>()
        if (hide) {
            if (entries["immersive.full"] != "*" && entries[STATUS] != "*") {
                val before = entries[STATUS]
                if (before != null && STATUS !in added) replaced = before // someone else's value: keep it to put back
                entries[STATUS] = "*"
                nowAdded.add(STATUS)
            } else if (STATUS in added) nowAdded.add(STATUS)
            if (entries[PRECONFIRM] == null) { entries[PRECONFIRM] = "*"; nowAdded.add(PRECONFIRM) }
            else if (PRECONFIRM in added) nowAdded.add(PRECONFIRM)
        } else {
            for (k in added) entries.remove(k)
            if (STATUS in added && replaced != null) entries[STATUS] = replaced
            replaced = null
        }
        val value = entries.entries.joinToString(":") { "${it.key}=${it.value}" }
        return try {
            if (value != current) Settings.Global.putString(cr, KEY, value.ifEmpty { null })
            Prefs.raw.edit().putStringSet(ADDED, nowAdded).putString(REPLACED, replaced).apply()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "policy_control: $t") // not a privileged build (emulator): nothing to do
            false
        }
    }
}
