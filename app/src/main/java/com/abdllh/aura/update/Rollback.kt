package com.abdllh.aura.update

import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.guard.GuardTrial
import com.abdllh.aura.system.Device
import com.abdllh.aura.system.LocalAdb
import com.abdllh.aura.system.SystemProps
import org.json.JSONObject
import java.io.File

/**
 * A real way back: one script (assets/rollback.sh, copied to /data/local/tmp/amri-rollback as root) that any root caller
 * can pull (Settings, the guard, AMRI Rescue, a PC), and one saved APK for it to go back to.
 *
 * Why `pm install -r -d` works at all: this firmware has ro.debuggable=1, which lets any downgrade through, and the app's
 * data stays. Never `pm uninstall`: with an older ROM copy under it, that deletes the data (docs/DEV.md).
 *
 * The saved APK takes no room in this APK. Before the updater installs a new version, the running one copies its own
 * APK there ([saveRunning]); and on a unit that came from a version without this code, the published 1.19.0 is fetched
 * once with the updater's own download code ([prepare]). Only a version that has passed its trial (GuardTrial) replaces
 * a saved copy that works: a bad beta updated to the next beta must not take the way back with it.
 *
 * Everything here runs off the main thread and never throws at its caller; what happened goes into a small file in
 * files/ (rollback-state), which Settings → About reads without a root shell.
 */
object Rollback {
    private const val TAG = "AuraRollback"
    private const val DIR = "/data/local/tmp/amri-rollback"
    private const val APK = "$DIR/aura-prev.apk"
    private const val JSON = "$DIR/aura-prev.json"
    private const val SCRIPT = "$DIR/rollback.sh"
    private const val RESULT = "$DIR/result"
    private const val END = "__aura_end__"
    private const val PKG = "com.abdllh.aura"

    /** The published 1.19.0: what a unit that came from 1.19.0 or older goes back to. */
    const val BASE_VERSION = "1.19.0"
    private const val BASE_CODE = 1190099L
    private const val BASE_SIZE = 18_305_423L
    private const val BASE_SHA = "512ca3fea863d08bd9821f6741df7fad79991b37f5d490a7e88470c90f54ad61"
    private const val BASE_URL = "https://github.com/abdllh50i/aura-launcher/releases/download/v1.19.0/aura-1.19.0.apk"

    private const val PREPARE_AFTER_MS = 45_000L
    private const val OFFLINE_POLL_MS = 60_000L
    private const val FETCH_RETRY_MS = 15 * 60_000L
    private const val FETCH_TRIES = 8
    private const val RESULT_WAIT_MS = 7 * 60_000L
    private const val SPARE_BYTES = 32L * 1024 * 1024

    private const val FILE_STATE = "rollback-state"
    private const val FILE_PENDING = "rollback-pending"
    private const val FILE_SCRIPT = "rollback.sh"

    /** The saved copy, as its JSON describes it (its hash was checked when it was read). */
    class Payload(val version: String, val code: Long, val sha256: String, val size: Long, val source: String)

    /** Why there is no saved copy (the keys of the strings in Settings → About). */
    object Why {
        const val LOOKING = "looking"
        const val NO_ROOT = "no-root"
        const val WAITING_NET = "waiting-net"
        const val FETCHING = "fetching"
        const val NO_SPACE = "no-space"
        const val FAILED = "failed"
        const val NOT_UNIT = "not-unit"
    }

    /** What Settings shows: the saved copy if any, else why none; the last time the way back was used. */
    class Info(val payload: Payload?, val why: String, val detail: String, val lastExit: String, val lastFrom: String, val lastTo: String,
               val lastTest: Boolean, val lastDataStayed: Boolean, val lastPm: String)

    private val fetchUrl: String
        get() {
            if (BuildConfig.DEBUG) SystemProps.get("debug.aura.rollback.url").takeIf { it.startsWith("http") }?.let { return it }
            return BASE_URL
        }

    /** The fetch happens on the unit only. Debug builds on the emulator: setprop debug.aura.rollback.url http://10.0.2.2:PORT/.... */
    private val mayFetch: Boolean
        get() = Device.isNwd || (BuildConfig.DEBUG && SystemProps.get("debug.aura.rollback.url").startsWith("http"))

    // ------------------------------------------------------------------------------------------------- root shell
    private fun sh(script: String, timeoutMs: Int = 20_000): String? {
        val r = LocalAdb.run("(\n$script\n)\necho $END", timeoutMs)
        if (!r.ok) return null
        val out = r.output.trimEnd()
        return if (out.endsWith(END)) out.removeSuffix(END).trimEnd() else null
    }

    /** The script on the unit is this build's, also after an update changed it. Goes through a file of the app's, so nothing of it passes through shell quoting. */
    fun ensureScript(c: Context): Boolean {
        return try {
            val text = c.assets.open(FILE_SCRIPT).use { it.readBytes() }.toString(Charsets.UTF_8).replace("\r", "")
            val own = File(c.filesDir, FILE_SCRIPT)
            if (!own.exists() || own.readText() != text) own.writeText(text)
            val out = sh("""
                mkdir -p $DIR && chmod 755 $DIR || { echo failed; exit 1; }
                cmp -s '${own.absolutePath}' $SCRIPT && { echo script-ok; exit 0; }
                cp '${own.absolutePath}' $SCRIPT.tmp && chmod 755 $SCRIPT.tmp && mv -f $SCRIPT.tmp $SCRIPT && echo script-ok
            """.trimIndent())
            out != null && out.lines().contains("script-ok")
        } catch (t: Throwable) {
            Log.w(TAG, "script: $t")
            false
        }
    }

    /** The saved copy, if it is there and whole; null: none, or no root shell to look with ([looked] says which). */
    fun payload(): Payload? = look().first

    /** first: the saved copy; second: false when there was no root shell to look with. */
    private fun look(): Pair<Payload?, Boolean> {
        val out = sh("""
            [ -f $JSON ] && [ -f $APK ] || { echo none; exit 0; }
            cat $JSON
            echo
            sha256sum $APK | cut -d' ' -f1
        """.trimIndent(), 60_000) ?: return null to false
        val lines = out.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty() || lines[0] == "none") return null to true
        return try {
            val o = JSONObject(lines[0])
            val sha = o.getString("sha256")
            if (lines.getOrNull(1) != sha) {
                Log.w(TAG, "the saved copy does not match its hash")
                null to true
            } else Payload(o.getString("version"), o.getLong("code"), sha, o.optLong("size"), o.optString("source")) to true
        } catch (t: Throwable) {
            Log.w(TAG, "saved copy's description: $t")
            null to true
        }
    }

    /** Can [p] be gone back to from here? An older one only, and not one below the ROM copy (at the next boot the firmware would undo it). */
    fun usable(p: Payload?): Boolean {
        if (p == null || p.code >= BuildConfig.VERSION_CODE) return false
        val rom = Version.parse(SystemProps.get("ro.aura.rom.version")) ?: return true
        val v = Version.parse(p.version) ?: return false
        return v >= rom
    }

    /** Moves [src] (readable by root) into place as the saved copy; its hash is checked against [want] on the way when given. */
    private fun install(src: String, version: String, code: Long, want: String?, source: String): Boolean {
        val out = sh("""
            D=$DIR
            mkdir -p ${'$'}D && chmod 755 ${'$'}D || { echo failed mkdir; exit 1; }
            A=${'$'}D/aura-prev.apk; J=${'$'}D/aura-prev.json
            cp "$src" ${'$'}A.tmp && chmod 644 ${'$'}A.tmp || { rm -f ${'$'}A.tmp; echo failed copy; exit 1; }
            H=${'$'}(sha256sum ${'$'}A.tmp | cut -d' ' -f1); S=${'$'}(wc -c < ${'$'}A.tmp)
            W='${want.orEmpty()}'
            if [ -n "${'$'}W" ] && [ "${'$'}W" != "${'$'}H" ]; then rm -f ${'$'}A.tmp; echo failed hash; exit 1; fi
            printf '{"version":"%s","code":%s,"sha256":"%s","size":%s,"source":"%s"}\n' '$version' '$code' "${'$'}H" "${'$'}S" '$source' > ${'$'}J.tmp || { rm -f ${'$'}A.tmp ${'$'}J.tmp; echo failed json; exit 1; }
            chmod 644 ${'$'}J.tmp
            mv -f ${'$'}A.tmp ${'$'}A && mv -f ${'$'}J.tmp ${'$'}J && sync && echo saved
        """.trimIndent(), 120_000)
        Log.i(TAG, "saving $version: $out")
        return out != null && out.lines().contains("saved")
    }

    // ---------------------------------------------------------------------------------------------- the saved copy
    /**
     * The running version becomes the way back, just before the updater replaces it. Never stops the update: a failure
     * only leaves the saved copy as it was, and says so.
     */
    fun saveRunning(c: Context, incomingSize: Long) {
        try {
            val (saved, shell) = look()
            if (!shell) { state(c) { put("why", Why.NO_ROOT) }; return }
            if (saved != null && saved.code == BuildConfig.VERSION_CODE.toLong()) return // (already this one)
            if (saved != null && saved.code < BuildConfig.VERSION_CODE && !GuardTrial.load(c).passed) {
                Log.i(TAG, "${BuildConfig.VERSION_NAME} has not finished its trial: the way back stays ${saved.version}")
                return
            }
            val mine = c.applicationInfo.sourceDir
            val size = File(mine).length()
            if (c.filesDir.usableSpace < size + incomingSize + SPARE_BYTES) {
                Log.w(TAG, "no room to keep ${BuildConfig.VERSION_NAME} as the way back")
                state(c) { put("why", Why.NO_SPACE) }
                return
            }
            // (what the package manager says is installed; the path this process runs from if it says nothing)
            val path = sh("pm path $PKG | head -n 1 | cut -d: -f2", 20_000)?.trim()?.takeIf { it.startsWith("/") } ?: mine
            if (install(path, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong(), null, "self")) {
                ensureScript(c)
                refreshState(c)
            } else state(c) { put("why", Why.FAILED); put("detail", "copy") }
        } catch (t: Throwable) {
            Log.w(TAG, "save: $t")
        }
    }

    /**
     * At every start of the main process, late and at low priority. The script in place; a look at the saved copy;
     * what the last use of the way back came to; and when no copy exists on a unit, the published 1.19.0 fetched as
     * soon as there is internet.
     */
    fun prepare(ctx: Context) {
        val c = ctx.applicationContext
        Thread({
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                Thread.sleep(PREPARE_AFTER_MS)
                if (!ensureScript(c)) { state(c) { put("why", Why.NO_ROOT) }; return@Thread }
                report(c)
                val saved = refreshState(c)
                if (saved == null) {
                    if (mayFetch) fetchBase(c) else state(c) { put("why", Why.NOT_UNIT) }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "prepare: $t")
            }
        }, "AuraRollback").start()
    }

    private fun online(c: Context): Boolean = try {
        (c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).activeNetworkInfo?.isConnected == true
    } catch (_: Throwable) {
        false
    }

    private fun fetchBase(c: Context) {
        val part = File(File(c.cacheDir, "rollback"), "aura-$BASE_VERSION.part")
        var tries = 0
        while (tries < FETCH_TRIES) {
            if (!online(c)) {
                state(c) { put("why", Why.WAITING_NET) }
                Thread.sleep(OFFLINE_POLL_MS)
                continue
            }
            tries++
            try {
                if (c.cacheDir.usableSpace < BASE_SIZE * 2 - (if (part.exists()) part.length() else 0L) + SPARE_BYTES) {
                    state(c) { put("why", Why.NO_SPACE) }
                    Thread.sleep(FETCH_RETRY_MS)
                    continue
                }
                state(c) { put("why", Why.FETCHING) }
                val whole = part.exists() && part.length() == BASE_SIZE && GitHub.sha256(part) == BASE_SHA
                if (!whole) {
                    if (part.exists() && part.length() >= BASE_SIZE) part.delete()
                    GitHub.download(fetchUrl, part, BASE_SIZE, { false }) { _, _ -> }
                    if (GitHub.sha256(part) != BASE_SHA) {
                        part.delete()
                        throw UpdateException(UpdateException.Kind.HASH, "SHA-256 mismatch")
                    }
                }
                if (install(part.absolutePath, BASE_VERSION, BASE_CODE, BASE_SHA, "published")) {
                    part.delete()
                    refreshState(c)
                    Log.i(TAG, "the published $BASE_VERSION is saved as the way back")
                    return
                }
                state(c) { put("why", Why.FAILED); put("detail", "copy") }
            } catch (e: UpdateException) {
                Log.w(TAG, "fetching $BASE_VERSION: ${e.kind} ${e.message}")
                state(c) { put("why", Why.FAILED); put("detail", "${e.kind}") }
            }
            Thread.sleep(FETCH_RETRY_MS)
        }
    }

    // ---------------------------------------------------------------------------------------------- going back
    /**
     * Starts the script; true when it runs (or already did). A successful install then ends this process, wherever it
     * was called from. [test]: a failure leaves everything as it was, instead of falling back to the stock launcher.
     */
    fun runScript(c: Context, test: Boolean): Boolean {
        if (!ensureScript(c)) return false
        val r = LocalAdb.run("sh $SCRIPT${if (test) " --test" else ""}", 15_000)
        Log.i(TAG, "script: ${r.ok} ${r.output}")
        return r.ok && (r.output.contains("started") || r.output.contains("already-running"))
    }

    /** Waits for the script's outcome: its result file, or null when it never told (this process usually does not live to see it). */
    fun awaitResult(): String? {
        val until = SystemClock.elapsedRealtime() + RESULT_WAIT_MS
        while (SystemClock.elapsedRealtime() < until) {
            Thread.sleep(2000L)
            val out = sh("cat $RESULT 2>/dev/null", 8_000) ?: continue
            if (out.lines().any { it == "DONE" }) return out
        }
        return null
    }

    fun failed(result: String?): Boolean = result != null && !result.lines().contains("EXIT=0")

    /** What the installer said, for a message: its "Failure [...]" line, or the outcome's first telling line. */
    fun failureText(result: String?): String {
        val lines = result?.lines().orEmpty()
        return lines.firstOrNull { it.startsWith("Failure") || it.contains("hash") }
            ?: lines.firstOrNull { it.startsWith("EXIT=") } ?: "no answer"
    }

    /**
     * From Settings: [done] gets the reason on the main thread if the way back failed; on success this process ends.
     * The channel goes back to stable and the release found by the last check is forgotten (with commit: the install
     * ends this process), so the version left behind is not offered as "an update" a moment later. Not for a test, which
     * is meant to be followed by that update.
     */
    fun goBack(ctx: Context, test: Boolean, done: (String) -> Unit) {
        val c = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread({
            val reason = try {
                val p = payload()
                if (!usable(p)) "no-copy"
                else {
                    if (test) File(c.filesDir, FILE_PENDING).writeText("${BuildConfig.VERSION_NAME} ${p?.version}\n")
                    else com.abdllh.aura.util.Prefs.raw.edit().putBoolean("updateBeta", false).putString("availableTag", "").putString("releaseCache", "").commit()
                    if (!runScript(c, test)) "no-root"
                    else {
                        val r = awaitResult()
                        report(c)
                        if (failed(r) || r == null) failureText(r) else null
                    }
                }
            } catch (t: Throwable) {
                t.toString()
            }
            if (reason != null) {
                try { File(c.filesDir, FILE_PENDING).delete() } catch (_: Throwable) { }
                main.post { try { done(reason) } catch (_: Throwable) { } }
            }
        }, "AuraRollback").start()
    }

    /**
     * The kill switch and the stock launcher (the fallback when there is nothing to go back to): through the script, which
     * also gives back what a dead AMRI cannot; else with what this process may do itself.
     */
    fun toStock(c: Context, why: String) {
        try { File(c.filesDir, "disable_home").writeText("$why ${System.currentTimeMillis()}") } catch (_: Throwable) { }
        val r = if (ensureScript(c)) LocalAdb.run("sh $SCRIPT --stock", 30_000) else null
        if (r != null && r.ok && r.output.contains("stock-done")) return
        Log.w(TAG, "the stock launcher without the script: ${r?.output}")
        SystemProps.set(Device.DISABLED_PROP, "1")
        Device.setHome(Device.STOCK_LAUNCHER)
        try {
            c.startActivity(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)
                .setPackage(Device.STOCK_LAUNCHER).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            Log.w(TAG, "stock launcher: $t")
        }
    }

    // -------------------------------------------------------------------------------------------------- the report
    /**
     * What the last use of the way back came to, once, at the first start after it. rollback-pending, written in this
     * app's data just before a test, tells "the data stayed": a test that went there and back and no longer finds it lost
     * the data. The outcome file lives outside the app and survives whatever happened to the data.
     */
    private fun report(c: Context) {
        val out = sh("cat $RESULT 2>/dev/null", 8_000) ?: return
        val lines = out.lines().map { it.trim() }
        if (lines.none { it.startsWith("EXIT=") } || lines.contains("SEEN")) return
        fun value(key: String) = lines.firstOrNull { it.startsWith("$key=") }?.substringAfter('=').orEmpty()
        val pending = File(c.filesDir, FILE_PENDING)
        val test = value("MODE") == "test"
        state(c) {
            put("lastExit", value("EXIT"))
            put("lastFrom", Regex("versionName=(\\S+)").find(value("FROM"))?.groupValues?.get(1).orEmpty())
            put("lastTo", value("TO"))
            put("lastTest", test)
            put("lastDataStayed", !test || pending.exists())
            put("lastPm", failureText(out))
        }
        try { pending.delete() } catch (_: Throwable) { }
        sh("echo SEEN >> $RESULT")
    }

    private fun refreshState(c: Context): Payload? {
        val (p, shell) = look()
        state(c) {
            if (p != null) {
                put("version", p.version); put("code", p.code); put("sha256", p.sha256); put("size", p.size); put("source", p.source)
                put("why", "")
            } else {
                remove("version")
                put("why", if (shell) Why.LOOKING else Why.NO_ROOT)
            }
            put("detail", "")
        }
        return p
    }

    @Synchronized
    private fun state(c: Context, change: JSONObject.() -> Unit) {
        try {
            val f = File(c.filesDir, FILE_STATE)
            val o = try { JSONObject(f.readText()) } catch (_: Throwable) { JSONObject() }
            change(o)
            val tmp = File(c.filesDir, "$FILE_STATE.tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) tmp.delete()
        } catch (t: Throwable) {
            Log.w(TAG, "state: $t")
        }
    }

    /** For Settings: no root shell, a small file. */
    fun info(c: Context): Info {
        val o = try { JSONObject(File(c.filesDir, FILE_STATE).readText()) } catch (_: Throwable) { JSONObject() }
        val p = if (o.has("version")) Payload(o.optString("version"), o.optLong("code"), o.optString("sha256"), o.optLong("size"), o.optString("source")) else null
        return Info(p, o.optString("why").ifEmpty { Why.LOOKING }, o.optString("detail"), o.optString("lastExit"), o.optString("lastFrom"), o.optString("lastTo"),
            o.optBoolean("lastTest"), o.optBoolean("lastDataStayed", true), o.optString("lastPm"))
    }
}
