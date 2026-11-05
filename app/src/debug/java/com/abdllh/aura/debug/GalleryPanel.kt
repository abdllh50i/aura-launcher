package com.abdllh.aura.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.abdllh.aura.R
import com.abdllh.aura.chrome.RowDock
import com.abdllh.aura.chrome.RowHome
import com.abdllh.aura.chrome.VoiceButton
import com.abdllh.aura.chrome.VolumeCluster
import com.abdllh.aura.home.HomeActivity
import com.abdllh.aura.home.Panel
import com.abdllh.aura.music.Backdrop
import com.abdllh.aura.settings.addRow
import com.abdllh.aura.settings.addSection
import com.abdllh.aura.settings.chevron
import com.abdllh.aura.settings.controlRow
import com.abdllh.aura.settings.group
import com.abdllh.aura.settings.hint
import com.abdllh.aura.settings.settingRow
import com.abdllh.aura.settings.sliderControl
import com.abdllh.aura.settings.stateDot
import com.abdllh.aura.settings.switchRow
import com.abdllh.aura.settings.valueChevron
import com.abdllh.aura.settings.valueText
import com.abdllh.aura.ui.ABtn
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Asked
import com.abdllh.aura.ui.AuraSlider
import com.abdllh.aura.ui.AuraSwitch
import com.abdllh.aura.ui.Confirm
import com.abdllh.aura.ui.EqGlyph
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.IconButton
import com.abdllh.aura.ui.LimitSign
import com.abdllh.aura.ui.Look
import com.abdllh.aura.ui.MATCH
import com.abdllh.aura.ui.Menu
import com.abdllh.aura.ui.Modal
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Plates
import com.abdllh.aura.ui.PlayPauseView
import com.abdllh.aura.ui.PressGesture
import com.abdllh.aura.ui.ProgressBar
import com.abdllh.aura.ui.SeekView
import com.abdllh.aura.ui.Segmented
import com.abdllh.aura.ui.Sheet
import com.abdllh.aura.ui.Snack
import com.abdllh.aura.ui.Spinner
import com.abdllh.aura.ui.Surface
import com.abdllh.aura.ui.SurfaceCard
import com.abdllh.aura.ui.SurfaceColumn
import com.abdllh.aura.ui.Surfaces
import com.abdllh.aura.ui.TextField
import com.abdllh.aura.ui.Theme
import com.abdllh.aura.ui.ThemeSwap
import com.abdllh.aura.ui.afterNextDraw
import com.abdllh.aura.ui.Tint
import com.abdllh.aura.ui.Tokens2
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.WRAP
import com.abdllh.aura.ui.at
import com.abdllh.aura.ui.box
import com.abdllh.aura.ui.flp
import com.abdllh.aura.ui.glyph
import com.abdllh.aura.ui.isNinePatch
import com.abdllh.aura.ui.ink
import com.abdllh.aura.ui.label
import com.abdllh.aura.ui.look
import com.abdllh.aura.ui.lp
import com.abdllh.aura.ui.scrollFade
import com.abdllh.aura.ui.surfaceImage
import com.abdllh.aura.ui.withUnit
import com.abdllh.aura.util.Fmt
import com.abdllh.aura.util.Prefs

/**
 * Debug builds: every component of the design system, in every state, at real size, one page per group. The pages named r-* put them
 * where the mockups have them, with the mockups' words, so a screenshot can be compared with final2/screens pixel by pixel
 * (scripts/gallery_compare.py). The panel takes a new theme through the real path, in place.
 *   am broadcast -n com.abdllh.aura/.debug.GalleryReceiver --es page controls --es theme night|day|auto --es lang ar|en --ez cut true
 * cut: the theme with no fade, for screenshots. --ez rebuild true: build the page again, to compare with what recoloured in place.
 */
class GalleryPanel(shell: HomeActivity) : Panel(shell, "gallery") {
    override val root = FrameLayout(shell)
    override val opaque: Boolean get() = true
    override val recolours: Boolean get() = true
    private val ctx: Context = shell
    private var page = "buttons"
    private var open: Sheet? = null
    private var menuUp: android.widget.PopupWindow? = null

    override fun onShow(args: Bundle?) = apply(args)

    override fun onArgs(args: Bundle) = apply(args)

    override fun onBack(): Boolean {
        Prefs.theme = Theme.AUTO
        return false
    }

    private fun apply(args: Bundle?) {
        val want = args?.getString("page")
        val rebuild = args?.getBoolean("rebuild") == true
        args?.getString("theme")?.let { t ->
            Prefs.theme = when (t) { "night" -> Theme.DARK; "day" -> Theme.LIGHT; else -> Theme.AUTO }
            if (args.getBoolean("cut")) {
                if (Theme.refresh()) { shell.windowLook(navBar); Look.apply(shell.root); Look.applyToWindows() }
            } else ThemeSwap.run(shell)
        }
        if (want != null || rebuild || root.childCount == 0) {
            if (want != null) page = want
            build()
        }
    }

    private fun build() {
        open?.let { shell.root.sheets.removeView(it) }
        open = null
        shell.panels.closeWindows()
        menuUp?.dismiss()
        menuUp = null
        root.removeAllViews()
        val s = FrameLayout(ctx)
        root.addView(s, flp(MATCH, MATCH))
        s.addView(View(ctx).look { setBackgroundColor(Palette.bg) }, flp(MATCH, MATCH))
        when (page) {
            "type1" -> type(s, 0)
            "type2" -> type(s, 1)
            "type3" -> type(s, 2)
            "type4" -> type(s, 3)
            "digits" -> digits(s)
            "surfaces" -> surfaces(s)
            "plates" -> plates(s)
            "icons" -> icons(s)
            "buttons" -> buttons(s)
            "abtn" -> abtn(s)
            "controls" -> controls(s)
            "bars" -> bars(s)
            "running" -> running(s)
            "rows" -> rows(s)
            "sheet" -> sideSheet(s)
            "modal" -> modal(s)
            "modal-ime" -> modal(s, ime = true)
            "confirm" -> confirm(s)
            "menu" -> menu(s)
            "snack" -> snack(s)
            "snack-words" -> snack(s, "ما فيه إنترنت", 0)
            "snack-long" -> snack(s, "ما قدرت أحفظ المكان لأن الذاكرة ممتلئة، احذف شي من أماكني وجرب مرة ثانية الحين", 0)
            "field" -> field(s)
            "wash" -> wash(s)
            "covers" -> covers(s)
            "dock" -> dockOrder(s)
            "chrome-orders" -> chromeOrders(s)
            "r-chrome-wall" -> s.mapPlate("stage-hole")
            "r-chrome-drive" -> { s.mapPlate("drive-turn"); s.scrims() }
            "r-chrome-browse" -> { s.mapPlate("browse"); s.scrims() }
            "r-chrome-room" -> s.room()
            "r-chrome-bg" -> Unit
            "r-settings-display" -> rSettingsDisplay(s)
            "r-settings-maps" -> rSettingsMaps(s)
            "r-controls" -> rControls(s, false)
            else -> buttons(s)
        }
        Log.i("AuraGallery", "page $page, 9-patches kept: ${ctx.getDrawable(R.drawable.card_night)!!.isNinePatch()}")
        val built = page
        s.afterNextDraw { Log.i("AuraGallery", "drawn $built.") }
    }

    // ------------------------------------------------------------------------------------------ helpers
    private fun FrameLayout.caption(x: Int, y: Int, s: String, w: Int = 200) {
        addView(ctx.label(Type.attribution, { Palette.ink3 }).apply { text = s; textAlignment = View.TEXT_ALIGNMENT_VIEW_START; textDirection = View.TEXT_DIRECTION_LTR; layoutDirection = View.LAYOUT_DIRECTION_LTR }, at(x, y, w, 21))
    }

    private fun FrameLayout.head(s: String, x: Int, y: Int) {
        addView(ctx.label(Type.sectionHead, { Palette.ink2 }).apply { text = s; textDirection = View.TEXT_DIRECTION_LTR; layoutDirection = View.LAYOUT_DIRECTION_LTR }, at(x, y, 1012 - x, 30))
    }

    private fun image(res: Int, token: () -> Int): ImageView = ImageView(ctx).apply { setImageResource(res) }.glyph(token)

    private fun FrameLayout.icon(res: Int, px: Int, x: Int, y: Int, token: () -> Int) = addView(image(res, token), at(x, y, px, px))

    /** A label at a line box, its right edge at [right] (the reading edge in Arabic). */
    private fun FrameLayout.textR(role: Type.Role, s: String, right: Int, line: Int, token: () -> Int, w: Int = 400, moving: Boolean = false): AText {
        val l = ctx.label(role, token, moving = moving).apply { text = s; gravity = Gravity.RIGHT or Gravity.TOP; textAlignment = View.TEXT_ALIGNMENT_GRAVITY }
        addView(l, role.box(right - w, line, w))
        return l
    }

    private fun FrameLayout.textC(role: Type.Role, s: String, x: Int, line: Int, w: Int, token: () -> Int): AText {
        val l = ctx.label(role, token).apply { text = s; gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP; textAlignment = View.TEXT_ALIGNMENT_GRAVITY }
        addView(l, role.box(x, line, w))
        return l
    }

    private fun plateView(radius: Int, token: () -> Int): View = View(ctx).apply {
        val t = Tint(ctx, Plates.res(radius), token)
        background = t.drawable
        look { t.recolour() }
    }

    private fun sliderAt(p: Int): AuraSlider = AuraSlider(ctx).apply { setProgress(p, false) }

    private fun cover(name: String, size: Int, radius: Int, done: (android.graphics.Bitmap) -> Unit) {
        val b = ctx.assets.open("gallery/$name.jpg").use { BitmapFactory.decodeStream(it) }
        Backdrop.roundAsync(ctx, b, size, radius, done)
    }

    private fun coverView(name: String, size: Int, radius: Int): ImageView = ImageView(ctx).apply { cover(name, size, radius) { setImageBitmap(it) } }

    /** The map's picture of the mockup under a replica, pushed by scripts/gallery-shots.ps1; the map's land if it is not there. */
    private fun FrameLayout.mapPlate(name: String) {
        addView(ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            look {
                val f = java.io.File(context.getExternalFilesDir(null), "$name-${if (Palette.dark) "night" else "day"}.png")
                if (f.exists()) setImageBitmap(BitmapFactory.decodeFile(f.path)) else setBackgroundColor(Palette.mapLand)
            }
        }, flp(MATCH, MATCH))
    }

    private fun FrameLayout.room() = addView(ctx.surfaceImage("room"), at(0, 0, 1024, 516))

    private fun FrameLayout.scrims() {
        addView(ctx.surfaceImage(R.drawable.scrim_col_night, R.drawable.scrim_col_day), at(0, 0, 396, 600))
        addView(ctx.surfaceImage(R.drawable.scrim_top_night, R.drawable.scrim_top_day), at(0, 0, 1024, 76))
        addView(ctx.surfaceImage(R.drawable.scrim_bot_night, R.drawable.scrim_bot_day), at(0, 482, 1024, 34))
    }

    /** The status strip as the mockups draw it: only so the replicas read as whole screens. */
    private fun FrameLayout.strip() {
        icon(R.drawable.ic_gps, 22, 16, 9) { Palette.ink2 }
        icon(R.drawable.ic_wifi, 22, 50, 9) { Palette.ink2 }
        icon(R.drawable.ic_iphone, 22, 84, 9) { Palette.ink2 }
        textR(Type.stripTime, "4:18 م", 1008, 0, { Palette.ink }).apply { textDirection = View.TEXT_DIRECTION_RTL }
    }

    /** The bottom row in the mockups' places. [cells]: in order, from the first centre. [active]: its panel's name. */
    private fun FrameLayout.dockRow(active: String?, cells: List<Asked.Cell> = Asked.ORDER_NEAREST) {
        addView(ctx.surfaceImage("dock"), at(0, 500, 1024, 100))
        addView(plateView(18) { Palette.fill }, at(16, 526, 184, 60))
        addView(IconButton(ctx, R.drawable.ic_minus, 34, 86, 84, centerDy = -2, centerDx = 7, token = { Palette.ink }), at(0, 516, 86, 84))
        icon(R.drawable.ic_vol, 24, 98, 530) { Palette.ink }
        textC(Type.volNum, "12", 86, 554, 48) { Palette.ink }.apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }
        addView(IconButton(ctx, R.drawable.ic_plus, 34, 70, 84, centerDy = -2, centerDx = 1, token = { Palette.ink }), at(134, 516, 70, 84))
        addView(ctx.surfaceImageRes(R.drawable.voice_disc), at(186, 502, 108, 108))
        addView(IconButton(ctx, R.drawable.ic_mic, 32, 76, 84, centerDy = -2, centerDx = -2, token = { Palette.onAccent }), at(204, 516, 76, 84))
        for ((i, c) in cells.withIndex()) {
            val x = Asked.centreX(i) - 40
            addView(IconButton(ctx, c.icon, 38, 80, 84, centerDy = -2, pressPlate = true).apply { this.active = c.panel == active }, at(x, 516, 80, 84))
        }
    }

    private fun Context.surfaceImageRes(res: Int): ImageView = ImageView(this).apply { setImageBitmap(Surfaces.res(context, res)) }

    // ------------------------------------------------------------------------------------------ type
    private fun type(s: FrameLayout, page: Int) {
        var on = 0
        var x = 8
        var y = 4
        val colW = 336
        for (r in TypeList.all) {
            val h = r.viewH.coerceAtLeast(r.line) + 19
            if (y + h > 596) {
                y = 4
                x += colW
                if (x + colW > 1024) { x = 8; on++ }
            }
            if (on != page) { y += h; continue }
            s.caption(x, y, "${r.name} ${r.px}/${r.weight} line ${r.line}", colW)
            // the line box, faintly, and the baseline: the label's centre must be the box's
            val top = y + 19 + (r.viewH - r.line).coerceAtLeast(0) / 2
            s.addView(View(ctx).look { setBackgroundColor(Palette.fill) }, at(x, top, colW - 12, r.line))
            s.addView(View(ctx).look { setBackgroundColor(Palette.line) }, at(x, top + r.baseline.toInt(), colW - 12, 1))
            val sample = when {
                r.px >= 50 -> "87 كم"
                r.px >= 34 -> "طريق 600 م"
                else -> "كل إشارة 7:38 م"
            }
            s.addView(ctx.label(r, { Palette.ink }).apply { text = sample }, r.box(x, top, colW - 12))
            y += h
        }
    }

    /** Section 14's question 6: a number alone, with its unit in spans, and shaped in one run with Arabic. scripts/gallery_compare.py --digits measures them. */
    private fun digits(s: FrameLayout) {
        s.addView(ctx.label(Type.dist, { Palette.ink }).apply { text = "600" }, Type.dist.box(600, 40, 400))
        s.addView(ctx.label(Type.dist, { Palette.ink }).look { text = withUnit("600", "م", Type.distUnit, Palette.ink2Move) }, Type.dist.box(600, 140, 400))
        s.addView(ctx.label(Type.dist, { Palette.ink }).apply { text = "600 متر" }, Type.dist.box(600, 240, 400))
        s.addView(ctx.label(Type.dist, { Palette.ink }).apply { text = "600" ; typeface = Fonts.get(600) }, Type.dist.box(100, 40, 400))
        s.addView(ctx.label(Type.speed, { Palette.ink }).apply { text = "120" }, Type.speed.box(100, 140, 400))
        s.addView(ctx.label(Type.etaValue, { Palette.ink }).apply { text = Fmt.clock(java.util.Date(0), false) }, Type.etaValue.box(100, 240, 400))
        s.caption(12, 340, "digits are 0.600 em wide in every weight: these columns must line up", 800)
        val wide = listOf("1111", "0000", "8888", "7:38", "1:11")
        for ((i, w) in wide.withIndex()) {
            s.addView(ctx.label(Type.speed, { Palette.ink }).apply { text = w; textDirection = View.TEXT_DIRECTION_LTR; textAlignment = View.TEXT_ALIGNMENT_VIEW_START; layoutDirection = View.LAYOUT_DIRECTION_LTR }, Type.speed.box(40 + 180 * i, 370, 170))
        }
    }

    // ------------------------------------------------------------------------------------------ surfaces
    private fun surfaces(s: FrameLayout) {
        s.mapPlate("browse")
        // real sizes: a card, a pill, an overlay, each with what can be in them
        s.addView(SurfaceCard(ctx, Surface.CARD), Surface.CARD.at(14, 14, 364, 190))
        s.addView(SurfaceCard(ctx, Surface.CARD), Surface.CARD.at(14, 214, 232, 88))
        s.addView(SurfaceCard(ctx, Surface.CARD), Surface.CARD.at(254, 214, 124, 88))
        s.addView(SurfaceCard(ctx, Surface.PILL), Surface.PILL.at(14, 312, 364, 52))
        s.addView(SurfaceCard(ctx, Surface.PILL), Surface.PILL.at(14, 374, 150, 48))
        s.addView(SurfaceCard(ctx, Surface.PILL), Surface.PILL.at(174, 374, 56, 56))
        s.addView(SurfaceCard(ctx, Surface.OVERLAY), Surface.OVERLAY.at(400, 14, 628 - 20, 98))
        s.addView(SurfaceCard(ctx, Surface.OVERLAY), Surface.OVERLAY.at(400, 124, 440, 200))
        s.addView(ImageView(ctx).look { setImageResource(if (Palette.dark) R.drawable.map_edge_night else R.drawable.map_edge_day) }.apply { scaleType = ImageView.ScaleType.FIT_XY }, at(860, 124, 150, 200))
        s.addView(View(ctx).apply { background = ctx.getDrawable(R.drawable.tile_accent) }, at(14 - 16, 440 - 16, 90 + 32, 90 + 32))
        s.addView(ctx.surfaceImage(R.drawable.turn_glow_night, R.drawable.turn_glow_day), at(120, 430, 300, 156))
        s.addView(ctx.surfaceImageRes(R.drawable.voice_disc), at(430, 440, 108, 108))
        for ((i, n) in listOf(R.drawable.tile_map, R.drawable.tile_music, R.drawable.tile_phone, R.drawable.tile_apps, R.drawable.tile_settings).withIndex()) {
            s.addView(ctx.surfaceImageRes(n), at(550 + 66 * i, 340, 60, 60))
        }
        for ((i, n) in listOf(R.drawable.apptile_blue, R.drawable.apptile_red, R.drawable.apptile_green, R.drawable.apptile_grey).withIndex()) {
            s.addView(ctx.surfaceImageRes(n), at(550 + 112 * i - if (i == 3) 0 else 0, 410, 104, 104))
        }
        for ((i, n) in listOf(R.drawable.apptile_orange, R.drawable.apptile_violet, R.drawable.apptile_teal, R.drawable.apptile_ink).withIndex()) {
            s.addView(ctx.surfaceImageRes(n), at(550 + 112 * i, 518, 76, 76))
        }
    }

    private fun plates(s: FrameLayout) {
        var x = 12
        s.head("plates tinted fill2 on bg, then rings, then gradient buttons at natural height", 12, 4)
        for (r in intArrayOf(12, 16, 18, 20, 23, 26)) {
            s.addView(plateView(r) { Palette.fill2 }, at(x, 40, 100, 70))
            s.caption(x, 112, "plate-$r")
            x += 110
        }
        s.addView(View(ctx).apply { val t = Tint(ctx, R.drawable.ring_18) { Palette.ink2 }; background = t.drawable; look { t.recolour() } }, at(x, 40, 100, 70))
        x += 110
        s.addView(View(ctx).apply { val t = Tint(ctx, R.drawable.ring_18_2) { Palette.redEdge }; background = t.drawable; look { t.recolour() } }, at(x, 40, 100, 70))
        x = 12
        for ((res, h) in listOf(R.drawable.btn_accent_60 to 60, R.drawable.btn_accent_76 to 76, R.drawable.btn_accent_84 to 84, R.drawable.btn_answer_72 to 72, R.drawable.btn_decline_60 to 60, R.drawable.btn_decline_72 to 72, R.drawable.btn_decline_84 to 84)) {
            s.addView(View(ctx).apply { background = ctx.getDrawable(res) }, at(x, 140, 130, h))
            x += 140
        }
        s.addView(View(ctx).apply { background = ctx.getDrawable(R.drawable.nav_on_58) }, at(12, 240, 306, 58))
        s.addView(View(ctx).apply { background = ctx.getDrawable(R.drawable.seg_on_52) }, at(330, 240, 140, 52))
        s.addView(ctx.surfaceImageRes(R.drawable.knob_33), at(480, 240, 53, 53))
        s.addView(ctx.surfaceImageRes(R.drawable.thumb_32), at(540, 240, 52, 52))
        x = 12
        for ((n, d) in listOf(R.drawable.fade_card_top_night to R.drawable.fade_card_top_day, R.drawable.fade_card_bot_night to R.drawable.fade_card_bot_day, R.drawable.fade_overlay_top_night to R.drawable.fade_overlay_top_day, R.drawable.fade_overlay_bot_night to R.drawable.fade_overlay_bot_day)) {
            s.addView(ctx.surfaceImage(n, d), at(x, 320, 200, 28))
            x += 210
        }
        s.addView(ctx.surfaceImage("cover-shadow"), at(12, 350, 240, 240))
        s.addView(ctx.surfaceImage("cap-left"), at(260, 370, 324, 132))
        s.addView(ctx.surfaceImage("cap-chip"), at(600, 370, 392, 132))
        s.addView(ctx.surfaceImage("car-glow"), at(600, 510, 120, 56))
    }

    private fun icons(s: FrameLayout) {
        val all = TypeList.icons
        val cols = 22
        val cell = 46
        val top = 4
        for ((i, n) in all.withIndex()) {
            val res = ctx.resources.getIdentifier("ic_$n", "drawable", ctx.packageName)
            if (res == 0) { Log.e("AuraGallery", "no icon $n"); continue }
            s.icon(res, 36, 4 + (i % cols) * cell + 5, top + (i / cols) * 58) { Palette.icon }
            s.caption(4 + (i % cols) * cell, top + 36 + (i / cols) * 58, n, cell)
        }
        s.head("mirrors: back, chevrons, send", 12, 470)
        for ((i, r) in listOf(R.drawable.ic_back, R.drawable.ic_chev_left, R.drawable.ic_chev_right, R.drawable.ic_send).withIndex()) {
            s.icon(r, 38, 12 + 54 * i, 504) { Palette.ink }
        }
        s.head("sizes: strip 22, card 26, 28, 30, transport 32, 36, dock 38, big 44, lane 34", 300, 470)
        var x = 300
        for (p in intArrayOf(22, 26, 28, 30, 32, 34, 36, 38, 44, 62, 84)) {
            s.icon(R.drawable.ic_turn_right, p, x, 504) { Palette.ink }
            x += p + 10
        }
    }

    // ------------------------------------------------------------------------------------------ one button
    private fun pressed(v: View, scale: Float) {
        v.isPressed = true
        v.scaleX = scale
        v.scaleY = scale
    }

    private fun buttons(s: FrameLayout) {
        s.head("IconButton BARE, TONAL, PILL, ACTIVE: rest, pressed, disabled", 12, 4)
        var y = 40
        for (row in 0..2) {
            var x = 12
            val state = arrayOf("rest", "pressed", "disabled")[row]
            fun put(v: View, w: Int, h: Int, label: String, scale: Float) {
                s.addView(v, at(x, y, w, h))
                when (row) { 1 -> pressed(v, scale); 2 -> v.isEnabled = false }
                s.caption(x, y + h, if (row == 0) label else state, 150)
                x += w + 14
            }
            put(IconButton(ctx, R.drawable.ic_gear, 38, 72, 84, centerDy = -2, pressPlate = true), 72, 84, "dock cell", PressGesture.ICON)
            put(IconButton(ctx, R.drawable.ic_close, 28, 56, 56, token = { Palette.ink2 }), 56, 84, "close", PressGesture.ICON)
            put(IconButton(ctx, R.drawable.ic_pause, 36, 64, 80, IconButton.Kind.TONAL, plateToken = { Palette.fill }, plateRadius = 16), 64, 84, "mini 56", PressGesture.ICON)
            put(IconButton(ctx, R.drawable.ic_shuffle, 28, 76, 76, IconButton.Kind.TONAL, plateW = 76, plateH = 76), 76, 84, "tonal 76", PressGesture.ICON)
            put(IconButton(ctx, R.drawable.ic_plus, 30, 56, 56, IconButton.Kind.PILL, PressGesture.CONTROL), 88, 88, "map button", PressGesture.CONTROL)
            put(IconButton(ctx, R.drawable.ic_locate, 30, 56, 56, IconButton.Kind.PILL, PressGesture.CONTROL, token = { Palette.accentInk }), 88, 88, "locate", PressGesture.CONTROL)
            put(IconButton(ctx, R.drawable.ic_nav, 38, 72, 84, IconButton.Kind.ACTIVE, centerDy = -2, pressPlate = true), 72, 84, "active", PressGesture.ICON)
            put(IconButton(ctx, R.drawable.ic_mic, 30, 76, 84, IconButton.Kind.TONAL, plateW = 64, plateH = 60, plateToken = { Palette.fill }, centerDy = -2), 76, 84, "voice", PressGesture.ICON)
            put(IconButton(ctx, R.drawable.ic_chev_right, 24, 56, 56, token = { Palette.ink3 }), 56, 84, "mirrors", PressGesture.ICON)
            y += 124
        }
        s.head("Press depths: icon .92, control .95, surface .98", 12, y)
        y += 34
        var x = 12
        for ((d, name) in listOf(PressGesture.ICON to "icon", PressGesture.CONTROL to "control", PressGesture.SURFACE to "surface", 1f to "none")) {
            val v = SurfaceCard(ctx, Surface.PILL).apply { addView(ctx.label(Type.chip, { Palette.ink }, gravity = Gravity.CENTER).apply { text = name }, flp(MATCH, MATCH)) }
            s.addView(v, Surface.PILL.at(x, y, 150, 60))
            pressed(v, d)
            x += 170
        }
    }

    // ------------------------------------------------------------------------------------------ the button
    private fun abtn(s: FrameLayout) {
        s.head("ABtn: M 60, L 84; icon 26 gap 10; loading; disabled; pressed", 12, 4)
        val kinds = listOf(ABtn.Kind.PRIMARY to "تأكيد", ABtn.Kind.TONAL to "إلغاء", ABtn.Kind.GHOST to "لاحقًا", ABtn.Kind.DANGER to "إنهاء", ABtn.Kind.DANGER_FILLED to "رفض")
        var y = 40
        fun rowOf(size: ABtn.Size, tweak: (ABtn) -> Unit, name: String) {
            var x = 12
            for ((k, word) in kinds) {
                val b = ABtn(ctx).apply { kind = k; this.size = size; text = word; setOnClickListener { } }
                tweak(b)
                s.addView(b, at(x, y, 186, size.height))
                x += 196
            }
            s.caption(x, y + 10, name, 100)
            y += size.height + 12
        }
        rowOf(ABtn.Size.M, {}, "rest")
        rowOf(ABtn.Size.M, { it.icon(R.drawable.ic_check) }, "icon")
        rowOf(ABtn.Size.M, { it.isEnabled = false }, "disabled")
        rowOf(ABtn.Size.M, { pressed(it, PressGesture.CONTROL) }, "pressed")
        val go = ABtn(ctx).apply { kind = ABtn.Kind.PRIMARY; size = ABtn.Size.L; role = Type.go; text = "انطلق"; icon(R.drawable.ic_nav, 32) }
        s.addView(go, at(12, y, 348, 84))
        val stop = ABtn(ctx).apply { kind = ABtn.Kind.DANGER_FILLED; size = ABtn.Size.L; text = "إنهاء الرحلة" }
        s.addView(stop, at(372, y, 240, 84))
        val tonal = ABtn(ctx).apply { kind = ABtn.Kind.TONAL; size = ABtn.Size.L; text = "إلغاء" }
        s.addView(tonal, at(624, y, 180, 84))
        val call = ABtn(ctx).apply { kind = ABtn.Kind.ANSWER; size = ABtn.Size.CALL; text = "رد"; icon(R.drawable.ic_phone) }
        s.addView(call, at(816, y + 6, 138, 72))
    }

    // ------------------------------------------------------------------------------------------ controls
    private fun controls(s: FrameLayout) {
        s.head("Segmented: 2, 3, 4 choices, each selected; a hug track", 12, 4)
        val three = listOf("تلقائي", "نهار", "ليل")
        for (i in 0..2) s.addView(Segmented(ctx, three, equal = true) {}.apply { select(i, false) }, at(12, 40 + 68 * i, 440, 60))
        s.addView(Segmented(ctx, listOf("الأغاني", "الألبومات", "الفنانين", "المجلدات"), equal = true, maxW = 612, fixedH = 56) {}.apply { select(1, false) }, at(470, 40, 540, 56))
        s.addView(Segmented(ctx, listOf("مائلة", "الشمال فوق"), equal = false) {}.apply { select(0, false) }, at(470, 108, 300, 60))
        s.addView(Segmented(ctx, listOf("Auto", "Day", "Night"), equal = true) {}.apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; select(2, false) }, at(470, 176, 440, 60))
        s.head("AuraSwitch: off, on, pressed, disabled, RTL and LTR", 12, 250)
        var x = 12
        for ((st, name) in listOf(0 to "off", 1 to "on", 2 to "pressed", 3 to "disabled off", 4 to "disabled on")) {
            val sw = AuraSwitch(ctx).apply {
                setChecked(st == 1 || st == 4)
                if (st == 2) isPressed = true
                if (st >= 3) isEnabled = false
            }
            s.addView(sw, AuraSwitch.at(x + 6, 290))
            s.caption(x, 332, name, 110)
            x += 104
        }
        for ((i, on) in listOf(false, true).withIndex()) {
            s.addView(AuraSwitch(ctx).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; setChecked(on) }, AuraSwitch.at(540 + 100 * i, 290))
        }
        s.head("AuraSlider: 0, 30, 70, 100, disabled, LTR", 12, 356)
        for ((i, p) in listOf(0, 30, 70, 100).withIndex()) {
            s.addView(sliderAt(p), AuraSlider.at(40, 392 + 48 * i, 400))
        }
        s.addView(sliderAt(50).apply { isEnabled = false }, AuraSlider.at(520, 392, 440))
        s.addView(sliderAt(30).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }, AuraSlider.at(520, 440, 440))
        s.addView(sliderAt(100).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }, AuraSlider.at(520, 488, 440))
        s.addView(sliderAt(0).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }, AuraSlider.at(520, 536, 440))
    }

    private fun bars(s: FrameLayout) {
        s.head("SeekView 340 x 30, always LTR", 12, 4)
        s.addView(SeekView(ctx).apply { setTimes("1:17", "3:42"); progress = 0.347f }, at(24, 40, 340, 30))
        s.addView(SeekView(ctx).apply { setTimes("0:00", "3:42"); progress = 0f }, at(24, 80, 340, 30))
        s.addView(SeekView(ctx).apply { setTimes("3:42", "3:42"); progress = 1f }, at(24, 120, 340, 30))
        s.addView(SeekView(ctx).apply { setTimes("1:02:33", "1:40:00"); progress = 0.6f; enabledSeek = false }, at(24, 160, 340, 30))
        s.head("PlayPauseView: play, pause, disc 96, glyph 36", 12, 204)
        s.addView(PlayPauseView(ctx).apply { setPlaying(false, false) }, at(12, 240, 44, 44))
        s.addView(PlayPauseView(ctx).apply { setPlaying(true, false) }, at(66, 240, 44, 44))
        s.addView(PlayPauseView(ctx).apply { disc = { Palette.ink }; token = { Palette.wash }; glyphPx = 44; setPlaying(true, false) }, at(120, 240, 96, 96))
        s.addView(PlayPauseView(ctx).apply { disc = { Palette.ink }; token = { Palette.wash }; glyphPx = 44; setPlaying(false, false) }, at(226, 240, 96, 96))
        s.icon(R.drawable.ic_play, 44, 332, 240) { Palette.ink2 }
        s.icon(R.drawable.ic_pause, 44, 382, 240) { Palette.ink2 }
        s.caption(332, 286, "(the icons, to compare)", 120)
        s.head("ProgressBar 6 high: 40 %, 100 %", 460, 204)
        s.addView(ProgressBar(ctx).apply { set(.4f) }, at(460, 244, 200, 6))
        s.addView(ProgressBar(ctx).apply { set(1f) }, at(680, 244, 200, 6))
        s.head("LimitSign: 68, 40, 34, dim", 12, 350)
        var x = 12
        for (kmh in intArrayOf(80, 120)) { s.addView(LimitSign(ctx).apply { this.kmh = kmh }, at(x, 386, 68, 68)); x += 78 }
        for (kmh in intArrayOf(80, 120)) { s.addView(LimitSign(ctx, LimitSign.HOME).apply { this.kmh = kmh }, at(x, 400, 40, 40)); x += 50 }
        for (kmh in intArrayOf(80, 120)) { s.addView(LimitSign(ctx, LimitSign.STRIP).apply { this.kmh = kmh }, at(x, 403, 34, 34)); x += 44 }
        s.addView(LimitSign(ctx, dim = true).apply { kmh = 120 }, at(x, 386, 68, 68))
        x += 88
        s.head("Still eq; state dots", x, 350)
        s.addView(EqGlyph(ctx).look { color = Palette.accentInk }, at(x, 390, 28, 28))
        s.addView(ctx.stateDot("مربوط", true), at(x + 40, 386, 120, 36))
        s.addView(ctx.stateDot("غير متصل", false), at(x + 170, 386, 150, 36))
        s.head("Tones in res/raw: tick, saher, over (tap to hear)", 12, 470)
        for ((i, f) in listOf<() -> Unit>({ com.abdllh.aura.ui.Tones.tick(ctx) }, { com.abdllh.aura.ui.Tones.saher(ctx) }, { com.abdllh.aura.ui.Tones.over(ctx) }).withIndex()) {
            s.addView(ABtn(ctx).apply { text = arrayOf("tick", "saher", "over")[i]; setOnClickListener { f() } }, at(12 + 150 * i, 506, 140, 60))
        }
    }

    /**
     * What keeps asking for frames: a page of its own, last in the script. On an emulator whose composer lags, a window that animates without a
     * pause can time out on its next buffer (dequeueBuffer failed, -110) and lose its surface until a relayout: see docs/DEV.md.
     */
    private fun running(s: FrameLayout) {
        s.head("Running only while seen: ABtn loading, Spinner 24, 36, ProgressBar sweeping", 12, 4)
        var x = 12
        for ((k, word) in listOf(ABtn.Kind.PRIMARY to "تأكيد", ABtn.Kind.TONAL to "إلغاء", ABtn.Kind.GHOST to "لاحقًا", ABtn.Kind.DANGER to "إنهاء", ABtn.Kind.DANGER_FILLED to "رفض")) {
            s.addView(ABtn(ctx).apply { kind = k; text = word; loading = true }, at(x, 40, 186, 60))
            x += 196
        }
        s.addView(Spinner(ctx, 24), at(12, 120, 24, 24))
        s.addView(Spinner(ctx, 36), at(46, 114, 36, 36))
        s.addView(ProgressBar(ctx).apply { setIndeterminate() }, at(100, 130, 300, 6))
    }

    // ------------------------------------------------------------------------------------------ rows
    private fun rows(s: FrameLayout) {
        s.room()
        val g = ctx.group(
            ctx.settingRow(null, "الخريطة بدون إنترنت", "الجبيل · الدمام · الخبر", ctx.valueChevron("جاهزة · 713 ميجا")) {},
            ctx.switchRow(null, "تنبيه ساهر", "صوت واحد قبل الكاميرا", true) {},
            ctx.switchRow(null, "إطفاء كامل مع السيارة", null, false) {},
            ctx.settingRow(R.drawable.ic_language, "اللغة", null, ctx.valueChevron("العربية")) {},
            ctx.settingRow(null, "النسخة", null, ctx.valueText("AMRI OS 2.0"))
        )
        s.addView(g, Surface.CARD.at(14, 44, 666, 432).apply { height = WRAP })
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        s.addView(col, at(700, 44, 310, 460))
        col.addSection("قسم")
        col.addView(ctx.hint("تلميح تحت البطاقة يلف على سطرين إذا طال، بدون مضاعف لارتفاع السطر، ويقرأ من اليمين"), lp(MATCH, WRAP))
        val press = ctx.settingRow(null, "صف مضغوط", "يظهر الطبق ولا يغوص", ctx.chevron()) {}
        press.isPressed = true
        col.addView(FrameLayout(ctx).apply { addView(press, flp(MATCH, WRAP)) }, lp(MATCH, WRAP))
    }

    // ------------------------------------------------------------------------------------------ sheets
    private inner class DemoSheet : Sheet(ctx, side = true) {
        init {
            sideHead("التحكم")
            panel.addView(FrameLayout(ctx).apply {
                addView(ctx.sliderControl(sliderAt(70),
                    ImageView(ctx).apply { setImageResource(R.drawable.ic_sun_dim) }.glyph { Palette.ink }.apply { layoutParams = lp(24, 24) },
                    ImageView(ctx).apply { setImageResource(R.drawable.ic_sun) }.glyph { Palette.ink }.apply { layoutParams = lp(28, 28) }), FrameLayout.LayoutParams(MATCH, 60, Gravity.CENTER_VERTICAL).apply { leftMargin = 22; rightMargin = 22 })
            }, lp(MATCH, 76))
            panel.addView(FrameLayout(ctx).apply {
                addView(Segmented(ctx, listOf("تلقائي", "نهار", "ليل"), equal = true) {}, FrameLayout.LayoutParams(MATCH, 60, Gravity.CENTER_VERTICAL).apply { leftMargin = 14; rightMargin = 14 })
            }, lp(MATCH, 84))
            panel.addView(View(ctx), lp(MATCH, 148))
            val o = Surface.OVERLAY.outset
            panel.setPadding(o, o, o, o)
        }
    }

    private fun sideSheet(s: FrameLayout) {
        s.mapPlate("browse")
        val sheet = DemoSheet()
        shell.root.sheets.addView(sheet, flp(MATCH, MATCH))
        open = sheet
        sheet.onClosed = { shell.root.sheets.removeView(sheet) }
        sheet.post { sheet.open(false) }
    }

    override fun onHide() {
        open?.let { shell.root.sheets.removeView(it) }
        open = null
    }

    // ------------------------------------------------------------------------------------------ dialogs and messages
    private fun modal(s: FrameLayout, ime: Boolean = false) {
        s.mapPlate("browse")
        val card = Modal.card(ctx)
        val m = Modal(ctx, card).followKeyboard()
        card.addView(Modal.title(ctx, "عنوان خادم الكلمات"), lp(MATCH, WRAP))
        val f = TextField(ctx, R.drawable.ic_link, "https://").apply { input.setText("lyric.example") }
        card.addView(f, lp(MATCH, TextField.HEIGHT).apply { topMargin = 14 })
        val yes = ABtn(ctx).apply { kind = ABtn.Kind.PRIMARY; text = "حفظ"; setOnClickListener { m.close() } }
        val no = ABtn(ctx).apply { kind = ABtn.Kind.TONAL; text = "إلغاء"; setOnClickListener { m.close() } }
        card.addView(Modal.buttons(ctx, yes, no), lp(MATCH, WRAP).apply { topMargin = 20 })
        root.post { m.show(); if (ime) f.input.requestFocus() }
    }

    private fun confirm(s: FrameLayout) {
        s.mapPlate("browse")
        root.post { Confirm.show(ctx, "تمسح الخريطة بدون إنترنت؟", "تحملها مرة ثانية متى ما بغيت. تأخذ 713 ميجا من المساحة ولها نسخة واحدة.", "امسحها") {} }
    }

    private fun menu(s: FrameLayout) {
        s.room()
        val a = IconButton(ctx, R.drawable.ic_apps, 38, 104, 104, IconButton.Kind.TONAL, plateW = 104, plateH = 104, plateRadius = 23)
        s.addView(a, at(460, 330, 104, 104))
        root.postDelayed({
            menuUp = Menu.show(a, listOf(
                Menu.Item(R.drawable.ic_info, "معلومات التطبيق") {},
                Menu.Item(R.drawable.ic_trash, "إزالة") {}
            ))
        }, 300)
    }

    /** The Snack in its three shapes: words, an icon and words, words at the most it holds. It comes and goes by itself, so the shot follows the first frame. */
    private fun snack(s: FrameLayout, text: String = "تم الحفظ في أماكني", icon: Int = R.drawable.ic_check) {
        s.mapPlate("browse")
        s.dockRow("maps")
        root.postDelayed({ Snack.show(shell, text, icon = icon, long = true) }, 200)
    }

    private fun field(s: FrameLayout) {
        s.room()
        s.head("TextField 52: empty, Arabic, Latin in Arabic, mixed", 12, 4)
        s.addView(TextField(ctx, R.drawable.ic_search, "إلى أين؟"), at(12, 40, 348, 52))
        s.addView(TextField(ctx, R.drawable.ic_search, "إلى أين؟").apply { input.setText("بند") }, at(12, 104, 348, 52))
        s.addView(TextField(ctx, R.drawable.ic_search).apply { input.setText("Panda Khobar") }, at(12, 168, 348, 52))
        s.addView(TextField(ctx, R.drawable.ic_search).apply { input.setText("طريق King Fahd 600") }, at(12, 232, 348, 52))
        s.addView(TextField(ctx).apply { input.setHint("بدون أيقونة") }, at(400, 40, 348, 52))
        s.addView(TextField(ctx, R.drawable.ic_link).apply { input.setText("https://lyric.abod50i.online"); input.isEnabled = false; alpha = .4f }, at(400, 104, 348, 52))
    }

    // ------------------------------------------------------------------------------------------ song colour
    private fun wash(s: FrameLayout) {
        s.head("Backdrop, computed here in this theme: panel 48 x 28 stretched; widget; word pill", 12, 4)
        val names = listOf("sea", "dusk", "neon", "oud", null)
        for ((i, n) in names.withIndex()) {
            val b = n?.let { name -> ctx.assets.open("gallery/$name.jpg").use { BitmapFactory.decodeStream(it) } }
            val y = 36 + 112 * i
            val panel = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_XY }
            s.addView(panel, at(4, y, 180, 105))
            val widget = ImageView(ctx)
            if (i == 0) s.addView(widget, at(600, 36, 424, 242))
            val pill = View(ctx)
            s.addView(pill, at(630, 320 + 44 * i, 180, 38))
            val word = ctx.label(Type.chip, { Palette.ink }, gravity = Gravity.CENTER).apply { text = "توقفني" }
            s.addView(word, at(630, 318 + 44 * i, 180, 36))
            Backdrop.washAsync(ctx, b, Palette.dark) { w ->
                panel.setImageBitmap(w.panel)
                if (i == 0) widget.setImageBitmap(w.widget)
                pill.background = GradientDrawable().apply { cornerRadius = 14f; setColor(w.wordPill) }
                word.setTextColor(w.wordPillInk)
                Log.i("AuraGallery", "$n fill #${Integer.toHexString(w.fill)} pill #${Integer.toHexString(w.wordPill)} contrast ${"%.1f".format(Palette.contrast(w.wordPill, w.wordPillInk))}")
            }
        }
    }

    private fun covers(s: FrameLayout) {
        s.head("Covers rounded once, no clip: big 204 r 20, widget 84 r 16, 72 r 14, mini 52 r 12, glance 44 r 12, row 56 r 12, album 120 r 16", 12, 4)
        val sizes = listOf(204 to 20, 84 to 16, 72 to 14, 52 to 12, 44 to 12, 56 to 12, 120 to 16)
        var x = 12
        for ((p, r) in sizes) {
            s.addView(coverView("sea", p, r), at(x, 40, p, p))
            x += p + 12
        }
        x = 12
        s.head("No cover: fill2 plate, music glyph in ink2 at 45 %", 12, 256)
        for ((p, r) in sizes) {
            val v = View(ctx).look { background = Backdrop.noArt(ctx, p, r) }
            s.addView(v, at(x, 290, p, p))
            x += p + 12
        }
    }

    private fun dockOrder(s: FrameLayout) {
        s.mapPlate("browse")
        s.head("The dock in the owner's order, Maps selected", 12, 460)
        s.dockRow("maps")
    }

    /** The real rows: Home's and the dock's, one under the other, as they are at the two ends of the row's change. */
    private fun chromeOrders(s: FrameLayout) {
        fun block(y: Int, name: String, home: Boolean) {
            s.caption(14, y, name, 900)
            val row = FrameLayout(ctx)
            if (home) row.addView(RowHome(ctx) { }, at(0, 0, 1024, 108))
            else row.addView(RowDock(ctx) { }.apply { placeOn(Asked.ORDER_NEAREST[1].panel) }, at(0, 8, 1024, 100))
            row.addView(VolumeCluster(ctx).apply { hold(12, false, -1f) }, at(0, 24, 204, 84))
            row.addView(VoiceButton(ctx) { }.apply { face = if (home) 0f else 1f }, at(186, 10, VoiceButton.SIZE, VoiceButton.SIZE))
            s.addView(row, at(0, y + 20, 1024, 108))
        }
        block(0, "Home's row", true)
        block(130, "the dock", false)
    }

    // ------------------------------------------------------------------------------------------ replicas of the mockups
    private fun settingsFrame(s: FrameLayout, page: Int, title: String) {
        s.room()
        s.textR(Type.pageTitle, "الإعدادات", 1000, 44, { Palette.ink })
        val side = listOf(
            R.drawable.ic_screen to "الشاشة والصوت", R.drawable.ic_map to "الخرائط", R.drawable.ic_music to "الموسيقى", R.drawable.ic_mic to "عمري",
            R.drawable.ic_iphone to "الجوال", R.drawable.ic_car to "السيارة والنظام", R.drawable.ic_info to "عن النظام"
        )
        for ((i, e) in side.withIndex()) s.addView(NavItemDemo(e.first, e.second, i == page), at(704, 92 + 60 * i, 306, 58))
        s.textR(Type.pageTitle, title, 658, 44, { Palette.ink })
        s.strip()
        s.dockRow("settings")
    }

    private inner class NavItemDemo(icon: Int, title: String, on: Boolean) : FrameLayout(ctx) {
        init {
            if (on) background = ctx.getDrawable(R.drawable.nav_on_58)
            val tl = View(ctx).look { background = GradientDrawable().apply { cornerRadius = Tokens2.R_navPlate.toFloat(); setColor(if (on) Palette.topLight else Palette.fill2) } }
            addView(tl, FrameLayout.LayoutParams(38, 38, Gravity.CENTER_VERTICAL or Gravity.RIGHT).apply { rightMargin = 10 })
            addView(image(icon) { if (on) Palette.onAccent else Palette.ink }, FrameLayout.LayoutParams(24, 24, Gravity.CENTER_VERTICAL or Gravity.RIGHT).apply { rightMargin = 17 })
            addView(ctx.label(Type.navItem, { if (on) Palette.onAccent else Palette.ink }).apply { text = title }, FrameLayout.LayoutParams(MATCH, Type.navItem.viewH, Gravity.CENTER_VERTICAL or Gravity.RIGHT).apply { rightMargin = 60 })
        }
    }

    private fun rSettingsDisplay(s: FrameLayout) {
        settingsFrame(s, 0, "الشاشة والصوت")
        val sun = ImageView(ctx).apply { setImageResource(R.drawable.ic_sun) }.glyph { Palette.ink }.apply { layoutParams = lp(28, 28) }
        val dim = ImageView(ctx).apply { setImageResource(R.drawable.ic_sun_dim) }.glyph { Palette.ink }.apply { layoutParams = lp(24, 24) }
        val value = ctx.label(Type.rowValue, { Palette.ink2 }, gravity = Gravity.CENTER).apply { text = "8"; layoutParams = lp(34, Type.rowValue.viewH) }
        val g = ctx.group(
            ctx.controlRow("السطوع", ctx.sliderControl(sliderAt(70), dim, sun)),
            ctx.controlRow("الوضع", Segmented(ctx, listOf("تلقائي", "نهار", "ليل"), equal = true) {}),
            ctx.controlRow("صوت التعليمات", ctx.sliderControl(sliderAt(53), value)),
            ctx.controlRow("طريقة العرض", Segmented(ctx, listOf("مائلة", "من فوق", "الشمال فوق"), equal = true) {}),
            fill = 414
        )
        s.addView(g, Surface.CARD.at(14, 92, 666, 414))
    }

    private fun rSettingsMaps(s: FrameLayout) {
        settingsFrame(s, 1, "الخرائط")
        val g = ctx.group(
            ctx.settingRow(null, "الخريطة بدون إنترنت", "الجبيل · الدمام · الخبر", ctx.valueChevron("جاهزة · 713 ميجا")) {},
            ctx.switchRow(null, "تنبيه ساهر", "صوت واحد قبل الكاميرا", true) {},
            ctx.settingRow(null, "تطبيق الملاحة", null, ctx.valueChevron("عمري")) {},
            ctx.settingRow(null, "أماكني", "تفتح الخريطة", ctx.valueChevron("البيت · الدوام")) {},
            fill = 414
        )
        s.addView(g, Surface.CARD.at(14, 92, 666, 414))
    }

    private fun FrameLayout.overlaySheet(x: Int, y: Int, w: Int, h: Int): SurfaceCard {
        val c = SurfaceCard(ctx, Surface.OVERLAY)
        addView(c, Surface.OVERLAY.at(x, y, w, h))
        return c
    }

    private fun rControls(s: FrameLayout, trip: Boolean) {
        s.mapPlate("browse")
        s.scrims()
        s.strip()
        val x = if (trip) 384 else 12
        val c = FrameLayout(ctx)
        s.overlaySheet(x, 44, 440, 376)
        s.addView(c, at(x, 44, 440, 376))
        c.textR(Type.pageTitle, "التحكم", 418, 10, { Palette.ink })
        c.addView(IconButton(ctx, R.drawable.ic_close, 28, 56, 56, token = { Palette.ink2 }), at(8, 8, 56, 56))
        val sun = ImageView(ctx).apply { setImageResource(R.drawable.ic_sun) }.glyph { Palette.ink }.apply { layoutParams = lp(28, 28) }
        val dim = ImageView(ctx).apply { setImageResource(R.drawable.ic_sun_dim) }.glyph { Palette.ink }.apply { layoutParams = lp(24, 24) }
        c.addView(ctx.sliderControl(sliderAt(70), dim, sun, gap = 18), at(22, 72, 396, 60))
        c.addView(View(ctx).look { setBackgroundColor(Palette.line) }, at(22, 140, 396, 1))
        c.addView(Segmented(ctx, listOf("تلقائي", "نهار", "ليل"), equal = true) {}, at(14, 152, 412, 60))
        val tiles = listOf(Triple(R.drawable.ic_wifi, "واي فاي", true), Triple(R.drawable.ic_bluetooth, "بلوتوث", true), Triple(R.drawable.ic_hotspot, "نقطة اتصال", false), Triple(R.drawable.ic_power, "إطفاء الشاشة", false))
        for ((i, t) in tiles.withIndex()) {
            val tx = 329 - 105 * i
            c.addView(plateView(18) { Palette.fill }, at(tx, 232, 97, 132))
            c.addView(plateView(16) { if (t.third) Palette.accent else Palette.fill2 }, at(tx + 24, 242, 48, 48))
            c.icon(t.first, 28, tx + 34, 252) { if (t.third) Palette.onAccent else Palette.ink }
            c.addView(ctx.label(Type.controlLabel, { Palette.ink }, lines = 2, gravity = Gravity.CENTER_HORIZONTAL).apply { text = t.second }, at(tx, 296 + Type.controlLabel.viewTop, 97, 66))
        }
        s.dockRow("maps")
    }
}

/** The lists the pages walk. */
private object TypeList {
    val all: List<Type.Role> = Type::class.java.superclass!!.declaredFields
        .filter { it.type == Type.Role::class.java }
        .map { it.isAccessible = true; it.get(Type) as Type.Role }
    val icons = listOf(
        "home", "nav", "music", "phone", "apps", "gear", "mic", "mic_off", "vol_mute", "vol_low", "vol", "minus", "plus", "close", "clear",
        "chev_left", "chev_right", "chev_up", "back", "play", "pause", "next", "prev", "shuffle", "repeat", "repeat_one", "queue", "lyrics",
        "expand", "collapse", "gps", "gps_off", "wifi", "wifi_off", "iphone", "iphone_off", "bluetooth", "hotspot", "usb", "search", "pin", "star", "star_on",
        "share", "send", "work", "food", "cafe", "fuel", "health", "worship", "parking", "lodging", "bank", "school", "park", "sight", "public",
        "cam", "cam_add", "signal", "bump", "flag", "route", "reroute", "locate", "compass", "overview", "go_straight", "turn_right", "turn_left", "slight_right",
        "slight_left", "sharp_right", "sharp_left", "uturn_right", "uturn_left", "merge_right", "merge_left", "fork_right", "fork_left", "ramp_right", "ramp_left",
        "arrive", "round_right", "round_straight", "round_left", "round_uturn", "lane_straight", "lane_right", "lane_left", "lane_slight_right", "lane_slight_left", "lane_uturn",
        "hang", "person", "keypad", "message", "sun", "sun_dim", "auto", "clock", "screen", "map", "car", "info", "language", "lock", "power",
        "update", "check", "check_round", "warn", "refresh", "trash", "edit", "qr", "link", "brightness", "eq", "bell", "bell_off", "radio", "video", "cam360", "files",
        "steering", "tools", "globe", "gallery", "android", "camera_app", "yt"
    )
}

/** What the window's root knows of drawing. Reads hidden fields: `settings put global hidden_api_policy 1` first. */
private fun probe(s: HomeActivity) {
    try {
        val root = s.window.decorView.parent
        val c = root.javaClass
        fun f(o: Any, n: String): Any? = try { var k: Class<*>? = o.javaClass; var v: Any? = "?"; while (k != null) { try { val x = k.getDeclaredField(n); x.isAccessible = true; v = x.get(o); break } catch (_: NoSuchFieldException) { k = k.superclass } }; v } catch (t: Throwable) { "err $t" }
        val ai = f(root, "mAttachInfo")!!
        val r = f(ai, "mThreadedRenderer")!!
        Log.i("AuraGallery", "probe ${c.simpleName} appVisible=${f(root, "mAppVisible")} stopped=${f(root, "mStopped")} displayState=${f(ai, "mDisplayState")} dirty=${f(root, "mDirty")} willDrawSoon=${f(root, "mWillDrawSoon")} isDrawing=${f(root, "mIsDrawing")} first=${f(root, "mFirst")} full=${f(root, "mFullRedrawNeeded")} paused=${f(root, "mPausedForTransition")} surface=${(f(root, "mSurface") as android.view.Surface).isValid} enabled=${f(r, "mEnabled")} requested=${f(r, "mRequested")} rendererStopped=${f(r, "mStopped")} drawing=${f(ai, "mDrawingTime")} hasWindowFocus=${f(ai, "mHasWindowFocus")} windowVis=${f(ai, "mWindowVisibility")}")
    } catch (t: Throwable) {
        Log.i("AuraGallery", "probe failed: $t")
    }
}

/**
 * Debug builds: the gallery from outside.
 *   am broadcast -n com.abdllh.aura/.debug.GalleryReceiver --es page controls --es theme day --ez cut true
 */
class GalleryReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val s = HomeActivity.current ?: return
        if (i.getBooleanExtra("probe", false)) { probe(s); return }
        // (what a hidden panel does through a theme change: --es over probe opens a panel over the gallery, --es swap day changes the theme under it by the real path, then Back)
        i.getStringExtra("over")?.let { s.panels.show(it); return }
        i.getStringExtra("swap")?.let { t ->
            Prefs.theme = when (t) { "night" -> Theme.DARK; "day" -> Theme.LIGHT; else -> Theme.AUTO }
            ThemeSwap.run(s)
            return
        }
        val args = Bundle(i.extras ?: Bundle())
        // (no page: a theme alone. What is open stays open, to be seen changing; a panel's show closes the sheets)
        val top = s.panels.top
        if (top is GalleryPanel && !i.hasExtra("page") && !i.hasExtra("lang")) { top.onArgs(args); return }
        com.abdllh.aura.chrome.Chrome.debugOn(s, false) // (its pages are the mockups': what they want of the chrome they draw, or ChromeProbe --ez on true puts back)
        s.panels.show("gallery", args)
        i.getStringExtra("lang")?.let { l ->
            if (Prefs.lang != l) {
                Prefs.lang = l
                s.languageChanged(args)
            }
        }
    }
}
