package com.abdllh.aura.music

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.abdllh.aura.system.LocalAdb
import com.abdllh.aura.system.SystemProps
import com.abdllh.aura.util.Prefs
import com.abdllh.aura.update.GitHub
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.R
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A reversible overlay for the exact K2501 AIC8800 daemon. No /system file is written.
 * AT#VI asks the phone for AVRCP GetPlayStatus; the stock BC03 service delivers its MQ reply.
 * Only the reviewed executable's SHA-256 is accepted. Reboot removes the bind mount.
 */
object BtTiming {
    private const val TARGET = "/system/bin/gocsdk_8800"
    private const val DIR = "/data/local/tmp/amri-bt-timing"
    private const val KEY = "btPhoneTiming"
    private const val PREVIOUS_PATCH = "0c7d4e596615420254ad94bc23511270b349d6221e0f77102da506b2e8e52dbf"
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile var active = false
        private set
    @Volatile var detail = ""
        private set
    @Volatile private var stopped = false
    private var app: Context? = null
    private var patch: JSONObject? = null
    private var failures = 0
    @Volatile private var requests = 0
    @Volatile private var replies = 0
    @Volatile private var lastReplyAt = 0L
    private var restartPending = false

    var enabled: Boolean
        get() = Prefs.raw.getBoolean(KEY, true)
        set(v) { Prefs.raw.edit().putBoolean(KEY, v).apply() }

    fun supported() = SystemProps.get("persist.sys.bt.aic8800") == "true" && File(TARGET).isFile

    fun init(ctx: Context) {
        if (app != null || !supported()) return
        app = ctx.applicationContext
        worker.execute { apply() }
        worker.scheduleWithFixedDelay({
            if (!active || stopped || !enabled || !BtMusic.connected) return@scheduleWithFixedDelay
            // This is the existing command pipe owned by BC03, not the hardware UART. Only write; BC03 keeps reading.
            val r = LocalAdb.run("[ \"\$(getprop init.svc.gocsdk_8800)\" = running ] && [ -c /dev/goc_serial ] && printf 'AT#VI\\r\\n' > /dev/goc_serial && echo queried", 5000)
            if (r.ok && r.output.endsWith("queried")) {
                failures = 0
                requests++
                main.post { BtMusic.timingChanged() }
            } else if (++failures >= 3) {
                enabled = false
                val restored = restoreOnWorker()
                detail = "Query failed: ${r.output.take(180)}; original restored=$restored"
                main.post { BtMusic.timingChanged() }
                Log.w("AuraBtTiming", detail)
            }
        }, 2, 2, TimeUnit.SECONDS)
    }

    fun applyChoice() { worker.execute { apply() } }

    internal fun onPhoneProgress() {
        replies++
        lastReplyAt = SystemClock.elapsedRealtime()
    }

    fun statusText(ctx: Context): String {
        val id = when {
            !supported() -> R.string.bt_timing_unsupported
            detail.isNotEmpty() -> R.string.bt_timing_failed
            !enabled -> R.string.bt_timing_off
            !active -> R.string.bt_timing_starting
            !BtMusic.connected -> R.string.bt_timing_connect
            lastReplyAt > 0 && SystemClock.elapsedRealtime() - lastReplyAt < 10000 -> R.string.bt_timing_receiving
            else -> R.string.bt_timing_waiting
        }
        return ctx.getString(id)
    }

    /** Checks the root mount namespace used to install/restart the daemon, rather than an app's cached view. */
    private fun driverHash(): String {
        val r = LocalAdb.run("sha256sum $TARGET", 5000)
        val hash = r.output.substringBefore(' ').trim()
        check(r.ok && hash.matches(Regex("[0-9a-f]{64}"))) { "Could not read Bluetooth driver: ${r.output.take(160)}" }
        return hash
    }

    // GOC creates a fresh /dev/ptmx slave on each start. BC03's UartWorker keeps
    // its opened descriptor forever: it must restart too, then AMRI must rebind.
    private fun runningDriverHash(): String = LocalAdb.run("sha256sum /proc/\$(pidof gocsdk_8800)/exe 2>/dev/null", 5000).output.substringBefore(' ').trim()

    private fun portConnected(): Boolean {
        val r = LocalAdb.run("""
            tty=${'$'}(readlink -f /dev/goc_serial)
            [ -c /dev/goc_serial ] && [ -n "${'$'}tty" ] || exit 1
            bc03_pid=${'$'}(pidof com.bt.bc03)
            [ -n "${'$'}bc03_pid" ] || exit 1
            for fd in /proc/${'$'}bc03_pid/fd/*; do
                if [ "${'$'}(readlink "${'$'}fd")" = "${'$'}tty" ]; then echo port-open; exit 0; fi
            done
        """.trimIndent(), 5000)
        return r.ok && r.output.endsWith("port-open")
    }

    private fun restartStack(expected: String): Boolean {
        // Preserve the service's actual mute state, including when another source
        // is selected. The first bind can still be completing during app startup.
        var muted = BtMusic.muteBeforeDriverRestart()
        repeat(5) {
            if (muted == null) {
                Thread.sleep(200)
                muted = BtMusic.muteBeforeDriverRestart()
            }
        }
        val r = LocalAdb.run("""
            old_pid=${'$'}(pidof gocsdk_8800)
            setprop ctl.restart gocsdk_8800 || exit 1
            ready=0
            for attempt in 1 2 3 4 5 6 7 8 9 10; do
                sleep 1
                new_pid=${'$'}(pidof gocsdk_8800)
                if [ -n "${'$'}new_pid" ] && [ "${'$'}new_pid" != "${'$'}old_pid" ] && [ -c /dev/goc_serial ] && [ "${'$'}(getprop init.svc.gocsdk_8800)" = running ]; then
                    ready=1
                    break
                fi
            done
            [ "${'$'}ready" = 1 ] || exit 1
            [ "${'$'}(sha256sum /proc/${'$'}new_pid/exe | cut -d ' ' -f 1)" = '$expected' ] || exit 1
            am force-stop com.bt.bc03 || exit 1
            am startservice -n com.bt.bc03/com.bt.bc03.BTService || exit 1
            tty=${'$'}(readlink -f /dev/goc_serial)
            for attempt in 1 2 3 4 5; do
                bc03_pid=${'$'}(pidof com.bt.bc03)
                for fd in /proc/${'$'}bc03_pid/fd/*; do
                    if [ "${'$'}(readlink "${'$'}fd")" = "${'$'}tty" ]; then
                        echo stack-ready
                        exit 0
                    fi
                done
                sleep 1
            done
            exit 1
        """.trimIndent(), 20000)
        val ok = r.ok && r.output.endsWith("stack-ready")
        if (ok) {
            restartPending = false
            BtMusic.reconnectAfterDriverRestart(muted)
        } else Log.w("AuraBtTiming", "Stack restart failed: ${r.output}")
        return ok
    }

    fun diagnostics(done: (String) -> Unit) {
        worker.execute {
            val probe = LocalAdb.run("""
                echo uid=${'$'}(id -u)
                echo driver=${'$'}(sha256sum $TARGET | cut -d ' ' -f 1)
                echo init_driver=${'$'}(sha256sum /proc/1/root$TARGET 2>/dev/null | cut -d ' ' -f 1)
                echo daemon=${'$'}(getprop init.svc.gocsdk_8800)
                echo daemon_pid=${'$'}(pidof gocsdk_8800)
                echo running_driver=${'$'}(sha256sum /proc/${'$'}(pidof gocsdk_8800)/exe 2>/dev/null | cut -d ' ' -f 1)
                echo bc03_pid=${'$'}(pidof com.bt.bc03)
                echo tty=${'$'}(readlink -f /dev/goc_serial)
            """.trimIndent(), 7000)
            val age = if (lastReplyAt == 0L) "none" else "${(SystemClock.elapsedRealtime() - lastReplyAt) / 1000}s"
            val report = "AMRI ${BuildConfig.VERSION_NAME}\nenabled=$enabled active=$active\nconnected=${BtMusic.connected} playing=${BtMusic.playing}\nrequests=$requests replies=$replies last_reply=$age\nerror=$detail\n${probe.output}"
            main.post { done(report) }
        }
    }

    private fun config(): JSONObject {
        patch?.let { return it }
        val c = app ?: error("Bluetooth timing not initialized")
        return JSONObject(c.assets.open("bt-timing/patch.json").bufferedReader().use { it.readText() }).also { patch = it }
    }

    private fun apply() {
        if (stopped) return
        if (!enabled) {
            detail = if (restoreOnWorker()) "" else "Could not restore the original Bluetooth driver"
            main.post { BtMusic.timingChanged() }
            return
        }
        detail = ""
        main.post { BtMusic.timingChanged() }
        try {
            val c = app ?: return
            val p = config()
            var sha = driverHash()
            if (sha == PREVIOUS_PATCH) {
                val r = LocalAdb.run("umount $TARGET && echo previous-removed", 7000)
                check(r.ok && r.output.endsWith("previous-removed")) { "Could not remove beta.1 Bluetooth overlay" }
                restartPending = true
                sha = driverHash()
            }
            if (sha == p.getString("patchedSha256")) {
                if (runningDriverHash() != sha || !portConnected()) {
                    restartPending = true
                    check(restartStack(sha)) { "Mounted Bluetooth driver was not running" }
                }
                active = true
                detail = ""
                main.post { BtMusic.timingChanged() }
                return
            }
            check(sha == p.getString("stockSha256")) { "Different Bluetooth driver; timing patch was not applied" }
            val source = File(TARGET)
            check(GitHub.sha256(source) == sha) { "Bluetooth mount views differ" }
            val bytes = source.readBytes().copyOf(p.getInt("size"))
            val records = p.getJSONArray("records")
            for (i in 0 until records.length()) {
                val item = records.getJSONObject(i)
                Base64.decode(item.getString("data"), Base64.DEFAULT).copyInto(bytes, item.getInt("offset"))
            }
            val staged = File(c.filesDir, "bt-timing-prepared")
            staged.outputStream().use { it.write(bytes); it.fd.sync() }
            check(GitHub.sha256(staged) == p.getString("patchedSha256")) { "Bluetooth patch integrity check failed" }
            val r = LocalAdb.run("""
                [ "${'$'}(id -u)" = 0 ] || exit 1
                mkdir -p $DIR || exit 1
                cp '${staged.absolutePath}' $DIR/driver.new && chmod 755 $DIR/driver.new || exit 1
                chcon u:object_r:system_file:s0 $DIR/driver.new || exit 1
                mv $DIR/driver.new $DIR/driver || exit 1
                mount -o bind $DIR/driver $TARGET || exit 1
                echo applied
            """.trimIndent(), 12000)
            restartPending = true
            check(r.ok && r.output.endsWith("applied")) { "Bluetooth timing could not start: ${r.output.take(180)}" }
            check(driverHash() == p.getString("patchedSha256")) { "Bluetooth overlay was not visible" }
            check(restartStack(p.getString("patchedSha256"))) { "Could not reconnect the Bluetooth service to its new command port" }
            active = true
            detail = ""
            failures = 0
        } catch (t: Throwable) {
            active = false
            if (restartPending) restoreOnWorker()
            detail = t.message.orEmpty()
            Log.w("AuraBtTiming", detail, t)
        }
        main.post { BtMusic.timingChanged() }
    }

    private fun restoreOnWorker(): Boolean {
        val wasActive = active
        active = false
        return try {
            val p = config()
            val sha = driverHash()
            if (sha == p.getString("stockSha256")) return !restartPending || restartStack(sha)
            // A different driver which we never patched must not prevent returning to the preceding app.
            if (sha != p.getString("patchedSha256") && sha != PREVIOUS_PATCH) return !wasActive && !restartPending
            val r = LocalAdb.run("umount $TARGET && echo restored", 7000)
            if (!r.ok || !r.output.endsWith("restored")) return false
            restartPending = true
            driverHash() == p.getString("stockSha256") && restartStack(p.getString("stockSha256"))
        } catch (t: Throwable) { Log.w("AuraBtTiming", "restore: $t"); false }
    }

    /** Stop polling and remove the overlay before replacing AMRI with the previous APK. Called off the UI thread. */
    fun restoreForRollback(): Boolean {
        if (app == null) return true
        stopped = true
        return worker.submit<Boolean> { restoreOnWorker() }.get(60, TimeUnit.SECONDS)
    }

    fun resumeAfterFailedRollback() { stopped = false; applyChoice() }
}
