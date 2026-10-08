package com.abdllh.aura.home

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import com.abdllh.aura.R
import com.abdllh.aura.media.MediaMonitor
import com.abdllh.aura.settings.SettingsActivity
import com.abdllh.aura.system.CrashGuard
import com.abdllh.aura.system.NwdBridge
import com.abdllh.aura.ui.BackdropView
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.lp
import com.abdllh.aura.update.UpdateManager
import com.abdllh.aura.util.LocaleHelper
import com.abdllh.aura.util.Prefs
import com.abdllh.aura.util.dp

/** The home screen: vehicle card, navigation, media, quick controls, dock and app drawer. */
class HomeActivity : Activity(), HomeHost {
    private lateinit var vehicle: VehicleCard
    private lateinit var nav: NavCard
    private lateinit var media: MediaCard
    private lateinit var controls: ControlsCard
    private lateinit var dock: Dock
    private lateinit var drawer: AppDrawer
    private var introPlayed = false
    private var builtLang = ""
    private var builtAccent = 0
    private var toast: Toast? = null

    private val tick = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (::vehicle.isInitialized) vehicle.refresh()
        }
    }
    private val updateObserver: (UpdateManager.State) -> Unit = {
        if (::dock.isInitialized) { dock.refreshBadge(); controls.refresh() }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (CrashGuard.isCrashLoop()) {
            CrashGuard.bailOut(this)
            finish()
            return
        }
        Fonts.refreshLocale(this)
        builtLang = Prefs.lang
        builtAccent = Prefs.accent
        window.setBackgroundDrawable(ColorDrawable(Palette.bg))
        setContentView(buildUi())
        MediaMonitor.start(this)
        NwdBridge.startStockServicesOncePerBoot(this)
        AppRepo.preload(this) // the app grid opens instantly instead of querying the package manager on the UI thread
        if (Prefs.safeModeNotice) {
            Prefs.safeModeNotice = false
            toast(getString(R.string.safe_mode_notice))
        }
    }

    private fun buildUi(): View {
        val root = FrameLayout(this)
        root.addView(BackdropView(this), MATCH, MATCH)

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16.dp, 12.dp, 16.dp, 4.dp)
        }
        vehicle = VehicleCard(this, this)
        content.addView(vehicle, lp(0, MATCH, 0.36f).apply { marginEnd = 12.dp })

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        nav = NavCard(this, this)
        right.addView(nav, lp(MATCH, 0, 1.08f))
        val low = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        media = MediaCard(this, this)
        controls = ControlsCard(this, this)
        low.addView(media, lp(0, MATCH, 1f).apply { marginEnd = 12.dp })
        low.addView(controls, lp(0, MATCH, 1f))
        right.addView(low, lp(MATCH, 0, 1f).apply { topMargin = 12.dp })
        content.addView(right, lp(0, MATCH, 0.64f))
        col.addView(content, lp(MATCH, 0, 1f))

        dock = Dock(this, this)
        col.addView(dock, lp(MATCH, 80.dp))
        root.addView(col, MATCH, MATCH)

        drawer = AppDrawer(this, this)
        root.addView(drawer, MATCH, MATCH)
        return root
    }

    override fun onStart() {
        super.onStart()
        NwdBridge.notifyHomeForeground(this)
    }

    override fun onResume() {
        super.onResume()
        if (builtLang != Prefs.lang || builtAccent != Prefs.accent) {
            recreate() // language or accent changed in Settings
            return
        }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_LOCALE_CHANGED)
        }
        registerReceiver(tick, f)
        vehicle.refresh()
        nav.refresh()
        controls.refresh()
        dock.rebuild()
        UpdateManager.observe(updateObserver)
        UpdateManager.autoCheckIfDue()
        if (!introPlayed) {
            introPlayed = true
            vehicle.playIntro()
            animateIn()
        }
    }

    private fun animateIn() {
        val cards = listOf<View>(vehicle, nav, media, controls, dock)
        for ((i, v) in cards.withIndex()) {
            v.alpha = 0f
            v.translationY = 24.dp.toFloat()
            v.animate().alpha(1f).translationY(0f).setStartDelay(60L * i).setDuration(420)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.8f)).start()
        }
    }

    override fun onPause() {
        try { unregisterReceiver(tick) } catch (_: Throwable) { }
        UpdateManager.unobserve(updateObserver)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (::drawer.isInitialized && drawer.isOpen) drawer.close()
    }

    @Deprecated("launcher: back does nothing on the home screen")
    override fun onBackPressed() {
        if (::drawer.isInitialized && drawer.isOpen) drawer.close()
    }

    // ---- HomeHost
    override fun openDrawer() = drawer.open()

    override fun openSettings(page: Int) {
        startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PAGE, page))
    }

    override fun toast(msg: String) {
        toast?.cancel()
        toast = Toast.makeText(this, msg, Toast.LENGTH_SHORT).also { it.show() }
    }
}
