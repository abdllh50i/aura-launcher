package com.abdllh.aura.music

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.abdllh.aura.system.LocalAdb
import com.abdllh.aura.system.SystemProps
import com.abdllh.aura.util.Prefs
import com.abdllh.aura.update.GitHub
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
            if (r.ok && r.output.endsWith("queried")) failures = 0 else if (++failures >= 3) {
                detail = "Bluetooth timing stopped; original driver restored"
                enabled = false
                restoreOnWorker()
                Log.w("AuraBtTiming", detail)
            }
        }, 2, 2, TimeUnit.SECONDS)
    }

    fun applyChoice() { worker.execute { apply() } }

    private fun config(): JSONObject {
        patch?.let { return it }
        val c = app ?: error("Bluetooth timing not initialized")
        return JSONObject(c.assets.open("bt-timing/patch.json").bufferedReader().use { it.readText() }).also { patch = it }
    }

    private fun apply() {
        if (stopped) return
        if (!enabled) { restoreOnWorker(); return }
        try {
            val c = app ?: return
            val p = config()
            val source = File(TARGET)
            val sha = GitHub.sha256(source)
            if (sha == p.getString("patchedSha256")) { active = true; return }
            check(sha == p.getString("stockSha256")) { "Different Bluetooth driver; timing patch was not applied" }
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
                setprop ctl.restart gocsdk_8800
                sleep 2
                if [ "${'$'}(getprop init.svc.gocsdk_8800)" != running ]; then
                    umount $TARGET
                    setprop ctl.restart gocsdk_8800
                    exit 1
                fi
                echo applied
            """.trimIndent(), 12000)
            check(r.ok && r.output.endsWith("applied")) { "Bluetooth timing could not start: ${r.output.take(180)}" }
            active = true
            detail = ""
            failures = 0
        } catch (t: Throwable) {
            active = false
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
            val sha = GitHub.sha256(File(TARGET))
            if (sha == p.getString("stockSha256")) return true
            // A different driver which we never patched must not prevent returning to the preceding app.
            if (sha != p.getString("patchedSha256")) return !wasActive
            val r = LocalAdb.run("umount $TARGET && setprop ctl.restart gocsdk_8800 && echo restored", 10000)
            r.ok && r.output.endsWith("restored") && GitHub.sha256(File(TARGET)) == p.getString("stockSha256")
        } catch (t: Throwable) { Log.w("AuraBtTiming", "restore: $t"); false }
    }

    /** Stop polling and remove the overlay before replacing AMRI with the previous APK. Called off the UI thread. */
    fun restoreForRollback(): Boolean {
        if (app == null) return true
        stopped = true
        return worker.submit<Boolean> { restoreOnWorker() }.get(25, TimeUnit.SECONDS)
    }

    fun resumeAfterFailedRollback() { stopped = false; applyChoice() }
}
