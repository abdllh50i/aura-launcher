package com.abdllh.aura.nav

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.abdllh.aura.R
import com.abdllh.aura.home.Screens
import com.abdllh.aura.system.Front
import com.abdllh.aura.system.Gear
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.OverlayWindow
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Surface
import com.abdllh.aura.ui.SurfaceCard
import com.abdllh.aura.ui.Tokens2
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.at
import com.abdllh.aura.ui.box
import com.abdllh.aura.ui.enter
import com.abdllh.aura.ui.exit
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.overlayMs
import com.abdllh.aura.util.LocaleHelper
import com.abdllh.aura.util.Prefs
import com.abdllh.aura.voice.Amri

/**
 * A trip keeps its guidance over another app (SPEC.md section 6.5): at the turn card's place, 364 x 96: the tile with the arrow, the distance, the road. Only with a trip and with AMRI's window not in front; a
 * touch goes back to Maps. Its numbers change with the fix, the only thing it redraws. Not over the reversing camera.
 */
internal object GuidePill {
    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var pill: Pill? = null

    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        NavSession.addListener { main.post { sync() } }
        Front.addOwnListener { sync() }
        Gear.addListener { main.post { sync() } } // (at once off the reversing camera, not at the next fix)
    }

    private fun wanted(c: Context) = NavSession.state == NavSession.State.NAVIGATING && !Front.ownResumed && !Amri.reversing && Settings.canDrawOverlays(c)

    private val showNow = Runnable {
        val c = app ?: return@Runnable
        if (!wanted(c)) return@Runnable
        var p = pill
        if (p == null || p.lang != Prefs.lang) {
            p?.window?.remove()
            p = Pill(LocaleHelper.wrap(c)).also { pill = it }
        }
        p.bind()
        if (p.window.attached) p.stay() else if (p.window.add()) p.enter() else pill = null
    }

    private fun sync() {
        val c = app ?: return
        if (!wanted(c)) {
            main.removeCallbacks(showNow)
            pill?.let { p -> if (p.window.attached) p.leave { if (pill === p) { p.window.remove(); pill = null } } }
            return
        }
        val p = pill
        if (p != null && p.window.attached) {
            p.bind()
            p.stay()
            return
        }
        // (a moment's grace: one of AMRI's own starts passes through a pause, and a quick look at another app is no reason to come up)
        main.removeCallbacks(showNow)
        main.postDelayed(showNow, GRACE_MS)
    }

    private class Pill(c: Context) {
        val lang = Prefs.lang
        val window = OverlayWindow(c, "AuraGuide", 0, 0, 420, 184)
        private val stage = FrameLayout(c)
        private val arrow = ManeuverView(c).apply { color = Palette.onAccent }
        private val side = if (Fonts.arabicUi) Gravity.RIGHT else Gravity.LEFT
        private val far: AText = c.label(Type.etaValue, { Palette.ink }, gravity = side or Gravity.CENTER_VERTICAL, moving = true)
        private val road: AText = c.label(Type.miniTitle, { Palette.ink }, gravity = side or Gravity.CENTER_VERTICAL, moving = true)
        private var leaving = false

        init {
            val card = SurfaceCard(c, Surface.OVERLAY)
            card.isClickable = true
            card.contentDescription = c.getString(R.string.guide_pill_back)
            card.setOnClickListener { Screens.maps(c) }
            stage.addView(card, Surface.OVERLAY.at(X, Y, W, H))
            // (the tile at the reading edge, the words beside it, as the turn card has them)
            val tileX = if (Fonts.arabicUi) X + W - 16 - TILE else X + 16
            val textX = if (Fonts.arabicUi) X + 16 else X + 16 + TILE + 12
            stage.addView(View(c).apply { setBackgroundResource(R.drawable.tile_accent) }, at(tileX - TILE_OUT, Y + (H - TILE) / 2 - TILE_OUT, TILE + 2 * TILE_OUT, TILE + 2 * TILE_OUT))
            stage.addView(arrow, at(tileX + 6, Y + (H - TILE) / 2 + 6, TILE - 12, TILE - 12))
            stage.addView(far, Type.etaValue.box(textX, Y + 8, TEXT_W))
            stage.addView(road, Type.miniTitle.box(textX, Y + 50, TEXT_W))
            window.root.addView(stage, FrameLayout.LayoutParams(420, 184))
            window.visible(X, Y, X + W, Y + H)
        }

        fun bind() {
            window.syncLook()
            val p = NavSession.progress ?: return
            val step = NavSession.route?.steps?.getOrNull(p.next) ?: return
            arrow.show(step)
            val d = Units.distance(p.toNext, Fonts.arabicUi)
            if (far.text.toString() != d) far.text = d
            val r = Instructions.road(step)
            if (road.text.toString() != r) road.text = r
        }

        fun enter() {
            leaving = false
            stage.enter(dy = -ENTER_DY, dur = overlayMs(Tokens2.MS_banner))
        }

        fun stay() {
            if (!leaving) return
            leaving = false
            stage.enter(dy = -ENTER_DY, dur = overlayMs(Tokens2.MS_banner))
        }

        fun leave(done: () -> Unit) {
            if (leaving) return
            leaving = true
            stage.exit(dur = overlayMs(Tokens2.MS_bannerOut), end = done)
        }
    }

    /** The card: x 16..380, y 48..144 on the screen, which is this window's own coordinates. */
    private const val X = 16
    private const val Y = 48
    private const val W = 364
    private const val H = 96
    private const val TILE = 64
    private const val TILE_OUT = 16
    private const val TEXT_W = 256
    private const val ENTER_DY = 24f
    private const val GRACE_MS = 800L
}
