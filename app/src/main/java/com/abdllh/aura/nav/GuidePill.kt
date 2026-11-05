package com.abdllh.aura.nav

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.BidiFormatter
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.abdllh.aura.R
import com.abdllh.aura.home.HomeActivity
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
import com.abdllh.aura.ui.plateView
import com.abdllh.aura.util.LocaleHelper
import com.abdllh.aura.util.Prefs
import com.abdllh.aura.voice.Amri

/**
 * A trip keeps its guidance when he leaves Maps (SPEC.md section 6.5): the arrow, the distance, the road, and a touch goes back to Maps. Over another app at the turn card's place, 364 x 96. Over AMRI's panels that
 * show no road, Music, Apps and Settings, in the strip's free middle, one line, where it lies on nothing of theirs: at the turn card's place it hid half of Settings' first row and took its touches. Home has its pills,
 * Maps its column. Its numbers change with the fix, the only thing it redraws. Not over the reversing camera or the Night Panel, which has the turn itself.
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
        Front.addPanelListener { sync() }
        Gear.addListener { main.post { sync() } } // (at once off the reversing camera, not at the next fix)
    }

    private fun wanted(c: Context) = NavSession.state == NavSession.State.NAVIGATING && !Amri.reversing && Settings.canDrawOverlays(c) &&
        (!Front.ownResumed || (Front.panel in NO_ROAD && HomeActivity.current?.root?.night?.isUp != true))

    private val showNow = Runnable {
        val c = app ?: return@Runnable
        if (!wanted(c)) return@Runnable
        var p = pill
        if (p == null || p.lang != Prefs.lang || p.own != Front.ownResumed) {
            p?.window?.remove()
            p = Pill(LocaleHelper.wrap(c), Front.ownResumed).also { pill = it }
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
        if (p != null && p.window.attached && p.own == Front.ownResumed && p.lang == Prefs.lang) {
            p.bind()
            p.stay()
            return
        }
        // (a moment's grace: one of AMRI's own starts passes through a pause, and a quick look at another app is no reason to come up)
        main.removeCallbacks(showNow)
        main.postDelayed(showNow, GRACE_MS)
    }

    /** [own]: over AMRI's window, the one line in the strip. */
    private class Pill(c: Context, val own: Boolean) {
        val lang = Prefs.lang
        val window = if (own) OverlayWindow(c, "AuraGuide", LINE_X - LINE_ROOM, 0, LINE_W + 2 * LINE_ROOM, LINE_Y + LINE_H + LINE_ROOM) else OverlayWindow(c, "AuraGuide", 0, 0, 420, 184)
        private val stage = FrameLayout(c)
        private val arrow = ManeuverView(c).apply { color = Palette.onAccent }
        private val side = if (Fonts.arabicUi) Gravity.RIGHT else Gravity.LEFT
        private val far: AText = c.label(if (own) Type.chipPrayer else Type.etaValue, { Palette.ink }, gravity = side or Gravity.CENTER_VERTICAL, moving = true)
        private val road: AText = c.label(Type.miniTitle, { Palette.ink }, gravity = side or Gravity.CENTER_VERTICAL, moving = true)
        private var shownLine = ""
        private var leaving = false

        init {
            val card = SurfaceCard(c, if (own) Surface.PILL else Surface.OVERLAY)
            card.isClickable = true
            card.contentDescription = c.getString(R.string.guide_pill_back)
            card.setOnClickListener { Screens.maps(c) }
            if (own) {
                stage.addView(card, Surface.PILL.at(LINE_ROOM, LINE_Y, LINE_W, LINE_H))
                val tileX = if (Fonts.arabicUi) LINE_ROOM + LINE_W - LINE_PAD - LINE_TILE else LINE_ROOM + LINE_PAD
                val textX = if (Fonts.arabicUi) LINE_ROOM + LINE_END else LINE_ROOM + LINE_PAD + LINE_TILE + LINE_GAP
                stage.addView(c.plateView(Tokens2.R_small) { Palette.accent }, at(tileX, LINE_Y + LINE_PAD, LINE_TILE, LINE_TILE))
                stage.addView(arrow, at(tileX + 4, LINE_Y + LINE_PAD + 4, LINE_TILE - 8, LINE_TILE - 8))
                stage.addView(far, Type.chipPrayer.box(textX, LINE_Y + LINE_PAD, LINE_W - LINE_PAD - LINE_TILE - LINE_GAP - LINE_END))
                window.root.addView(stage, FrameLayout.LayoutParams(LINE_W + 2 * LINE_ROOM, LINE_Y + LINE_H + LINE_ROOM))
                window.visible(LINE_ROOM, LINE_Y, LINE_ROOM + LINE_W, LINE_Y + LINE_H)
            } else {
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
        }

        fun bind() {
            window.syncLook()
            val p = NavSession.progress ?: return
            val step = NavSession.route?.steps?.getOrNull(p.next) ?: return
            arrow.show(step)
            val d = Units.distance(p.toNext, Fonts.arabicUi)
            val r = Instructions.road(step)
            if (own) {
                val line = "$d|$r"
                if (line != shownLine) { shownLine = line; far.text = line(d, r) }
                return
            }
            if (far.text.toString() != d) far.text = d
            if (road.text.toString() != r) road.text = r
        }

        /** The distance and, smaller, the road, in one label: as on Home's turn pill. */
        private fun line(d: String, r: String): CharSequence {
            val s = SpannableStringBuilder(d).append("  ")
            val from = s.length
            s.append(BidiFormatter.getInstance().unicodeWrap(r))
            s.setSpan(AbsoluteSizeSpan(Type.miniSub.px, false), from, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            s.setSpan(TypefaceSpan(Fonts.of(Type.miniSub, true)), from, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return s
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

    private val NO_ROAD = setOf(Screens.MUSIC, Screens.APPS, Screens.SETTINGS)

    /** The card: x 16..380, y 48..144 on the screen, which is this window's own coordinates. */
    private const val X = 16
    private const val Y = 48
    private const val W = 364
    private const val H = 96
    private const val TILE = 64
    private const val TILE_OUT = 16
    private const val TEXT_W = 256
    /** The one line: x 412..812, y 2..42, between the strip's speed and its time. The room is the pill's own outset. */
    private const val LINE_X = 412
    private const val LINE_Y = 2
    private const val LINE_W = 400
    private const val LINE_H = 40
    private const val LINE_ROOM = 16
    private const val LINE_PAD = 4
    private const val LINE_TILE = 32
    private const val LINE_GAP = 10
    private const val LINE_END = 14
    private const val ENTER_DY = 24f
    private const val GRACE_MS = 800L
}
