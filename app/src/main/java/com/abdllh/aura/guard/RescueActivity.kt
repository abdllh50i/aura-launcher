package com.abdllh.aura.guard

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.abdllh.aura.BuildConfig
import com.abdllh.aura.R
import com.abdllh.aura.system.Device
import com.abdllh.aura.system.LocalAdb
import com.abdllh.aura.system.SystemProps
import com.abdllh.aura.update.Rollback
import java.io.File
import java.util.Locale

/**
 * AMRI Rescue: the second icon, which the stock launcher's app list shows after a bail-out (AMRI's own list leaves its
 * own package out). In the guard's process, with plain Android views: this is for the day the main process and its
 * design system are what is broken, so nothing of them may be needed here.
 */
class RescueActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var back: Button
    private var payload: Rollback.Payload? = null

    /** The app's own language, if its settings can be read at all. */
    override fun attachBaseContext(base: Context) {
        val code = try { base.getSharedPreferences("aura", Context.MODE_PRIVATE).getString("lang", "system") } catch (_: Throwable) { "system" }
        if (code != "ar" && code != "en") { super.attachBaseContext(base); return }
        val l = Locale(code)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(l)
        cfg.setLayoutDirection(l)
        super.attachBaseContext(base.createConfigurationContext(cfg))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(48), dp(32), dp(48), dp(32))
        }
        col.addView(text(28f).apply { setText(R.string.rescue_name) })
        status = text(18f)
        col.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        back = button(col, getString(R.string.rescue_back)) { askBack() }
        back.isEnabled = false
        button(col, getString(R.string.rescue_stock)) { stock() }
        button(col, getString(R.string.rescue_retry)) { retry() }
        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        look()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(sp: Float) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(0xFFFFFFFF.toInt())
    }

    private fun button(col: LinearLayout, title: String, onClick: () -> Unit): Button {
        val b = Button(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            isAllCaps = false
            setOnClickListener { onClick() }
        }
        col.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)).apply { topMargin = dp(16) })
        return b
    }

    private fun say(s: String) = main.post { if (!isDestroyed) status.text = s }

    /** What is true right now, as far as a root shell can tell; the button goes back to what it says. */
    private fun look() {
        val lines = ArrayList<String>()
        lines.add(getString(R.string.rescue_now, BuildConfig.VERSION_NAME))
        if (SystemProps.get(Device.DISABLED_PROP) == "1") lines.add(getString(R.string.rescue_stock_on))
        status.text = lines.joinToString("\n")
        Thread({
            val ready = Rollback.ensureScript(this)
            val p = if (ready) Rollback.payload() else null
            val ok = Rollback.usable(p)
            main.post {
                if (isDestroyed) return@post
                payload = if (ok) p else null
                back.isEnabled = ok
                back.text = if (ok) getString(R.string.rescue_back_to, p?.version) else getString(R.string.rescue_back)
                lines.add(getString(if (ok) R.string.rescue_data else if (ready) R.string.rescue_no_copy else R.string.rescue_no_root))
                status.text = lines.joinToString("\n")
            }
        }, "AuraRescue").start()
    }

    private fun askBack() {
        val p = payload ?: return
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(getString(R.string.rescue_back_to, p.version))
            .setMessage(R.string.rescue_data)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> goBack() }
            .show()
    }

    private fun goBack() {
        say(getString(R.string.rescue_going))
        back.isEnabled = false
        Thread({
            GuardLog.add(this, "Rescue: going back to ${payload?.version}")
            if (!Rollback.runScript(this, false)) {
                say(getString(R.string.rescue_no_root))
                return@Thread
            }
            val r = Rollback.awaitResult() // (a success ends this process)
            GuardLog.add(this, "Rescue: going back failed: ${Rollback.failureText(r)}")
            say(getString(R.string.rescue_failed, Rollback.failureText(r)))
        }, "AuraRescue").start()
    }

    private fun stock() {
        Thread({
            GuardLog.add(this, "Rescue: stock launcher")
            Rollback.toStock(this, "user")
            main.post { finish() }
        }, "AuraRescue").start()
    }

    /** The kill switch off, the count of failed starts from nothing, whatever is left of the main process ended, and home asked for anew. */
    private fun retry() {
        Thread({
            GuardLog.add(this, "Rescue: trying again")
            try { File(filesDir, "disable_home").delete() } catch (_: Throwable) { }
            try { getSharedPreferences("aura_crash", Context.MODE_PRIVATE).edit().remove("crashDisabled").remove("crashes").commit() } catch (_: Throwable) { }
            val r = LocalAdb.run("setprop ${Device.DISABLED_PROP} 0; setprop ${Device.HOME_PROP} $packageName; rm -f /data/aura_disabled")
            if (!r.ok) { SystemProps.set(Device.DISABLED_PROP, "0"); Device.setHome(packageName) }
            GuardMarks.add(this, GuardMarks.RESET)
            mainPid()?.let { Process.killProcess(it) }
            main.post {
                try {
                    startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Throwable) {
                }
                finish()
            }
        }, "AuraRescue").start()
    }

    private fun mainPid(): Int? = try {
        (getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).runningAppProcesses
            ?.firstOrNull { it.processName == packageName }?.pid
    } catch (_: Throwable) {
        null
    }

    companion object {
        fun open(c: Context) {
            try {
                c.startActivity(Intent(c, RescueActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Throwable) {
            }
        }
    }
}
