package com.abdllh.aura.guard

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.abdllh.aura.system.LocalAdb
import com.abdllh.aura.system.SystemProps
import java.io.File

/**
 * This firmware force-stops whatever is not the home app and not on its list. NwdBackgroundManagerService
 * (`persist.nwd.bms.enable=true` in its build.prop; framework.jar, services.jar): at every activity event of any app it
 * posts a look for 500 ms later, and that look force-stops each package with a running service that is neither the top
 * task nor on its white list. The home property counts as on the list, so nothing of this shows while AMRI is the home
 * screen; the moment the property names the stock launcher, the guard (a running service, in a package no longer on the
 * list) is force-stopped along with the rest, the package is left in the stopped state (no BOOT_COMPLETED after that), and
 * [android.content.Context.startForegroundService] of a package off the list silently returns null. The emulator has no
 * such service, which is how the guard passed its tests there.
 *
 * So AMRI goes on the list, in the two places the service reads it:
 *  - the setting `whitelist_packagename` ("[a,b]"), which it watches: this one works at once. The stock launcher keeps a
 *    short list of its own there and may push ours out, so every start of the guard looks again.
 *  - `$(ro.nwd.config.path)/app/BackgroundManager.ini`, read once when the framework starts: from the next boot, and whatever
 *    the stock launcher does with its list. Kept first as .pre-aura, which the ROM's restore (old ones too) puts back.
 *
 * It stays on while the kill switch is on: that is exactly when it is needed. A restore that leaves the setting leaves
 * the name of a package that is gone, which means nothing.
 */
internal object BackgroundList {
    private const val TAG = "AuraGuard"
    private const val KEY = "whitelist_packagename"
    private const val PKG = "com.abdllh.aura"
    private const val ENTRY = "white:$PKG"
    private const val END = "__aura_end__"

    /** As far as can be seen without a root shell, nothing is left to do. */
    private fun inPlace(c: Context): Boolean = try {
        val setting = Settings.System.getString(c.contentResolver, KEY).orEmpty()
        val dir = SystemProps.get("ro.nwd.config.path")
        val ini = File(dir, "app/BackgroundManager.ini")
        PKG in setting && dir.isNotEmpty() && ini.isFile && ini.readLines().any { it.trim() == ENTRY }
    } catch (_: Throwable) {
        false
    }

    /** [ok]: it can rest (else no root shell yet, at a cold boot: it asks again). [text]: what happened, for the guard's log, when anything did. */
    class Outcome(val ok: Boolean, val text: String?)

    /** Off the main thread. */
    fun ensure(c: Context): Outcome {
        if (inPlace(c)) return Outcome(true, null)
        // The ini: the old bytes first, then our one line (the firmware's file ends without a newline, and a line run together
        // with its last one would lose both), nothing else; checked on a copy, then written through the file itself, which
        // keeps its owner and label. A file the firmware cannot read loses every entry of its list.
        val script = """
            P=$PKG; K=$KEY
            V=${'$'}(settings get system ${'$'}K)
            case "${'$'}V" in
              *${'$'}P*) ;;
              ''|null|'[]') settings put system ${'$'}K "[${'$'}P]" ;;
              *']') settings put system ${'$'}K "${'$'}{V%]},${'$'}P]" ;;
              *) settings put system ${'$'}K "${'$'}V,${'$'}P" ;;
            esac
            case "${'$'}(settings get system ${'$'}K)" in *${'$'}P*) echo setting-on ;; *) echo setting-failed ;; esac
            C=${'$'}(getprop ro.nwd.config.path); F=${'$'}C/app/BackgroundManager.ini; T=${'$'}F.aura-tmp
            if [ -z "${'$'}C" ] || [ ! -f "${'$'}F" ]; then echo ini-none; exit 0; fi
            if grep -qx '$ENTRY' "${'$'}F"; then echo ini-on; exit 0; fi
            [ -f "${'$'}F.pre-aura" ] || cp -p "${'$'}F" "${'$'}F.pre-aura" || { echo ini-failed backup; exit 1; }
            { cat "${'$'}F"; [ -z "${'$'}(tail -c 1 "${'$'}F")" ] || echo; echo '$ENTRY'; } > "${'$'}T" || { rm -f "${'$'}T"; echo ini-failed write; exit 1; }
            N=${'$'}(wc -c < "${'$'}F")
            A=${'$'}(md5sum < "${'$'}F" | cut -d' ' -f1); B=${'$'}(head -c "${'$'}N" "${'$'}T" | md5sum | cut -d' ' -f1)
            W=${'$'}(grep -c 'white:' "${'$'}F")
            if [ "${'$'}A" = "${'$'}B" ] && [ "${'$'}(tail -n 1 "${'$'}T")" = '$ENTRY' ] && [ "${'$'}(grep -c 'white:' "${'$'}T")" = "${'$'}((W + 1))" ]; then
              cat "${'$'}T" > "${'$'}F" && rm -f "${'$'}T" && sync && echo ini-added
            else
              rm -f "${'$'}T"; echo ini-failed check; exit 1
            fi
        """.trimIndent()
        val r = LocalAdb.run("(\n$script\n)\necho $END", 20_000)
        if (!r.ok || !r.output.trimEnd().endsWith(END)) {
            Log.w(TAG, "background list: ${r.output}")
            return Outcome(false, null)
        }
        val out = r.output.trimEnd().removeSuffix(END).trim().replace('\n', ' ')
        return Outcome(true, "the firmware's background list: $out")
    }
}
