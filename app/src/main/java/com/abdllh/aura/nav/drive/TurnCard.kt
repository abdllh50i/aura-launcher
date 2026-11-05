package com.abdllh.aura.nav.drive

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.BidiFormatter
import android.text.Layout
import android.text.TextPaint
import android.view.Gravity
import android.view.View
import com.abdllh.aura.R
import com.abdllh.aura.nav.Lane
import com.abdllh.aura.nav.ManeuverView
import com.abdllh.aura.nav.Step
import com.abdllh.aura.ui.AText
import com.abdllh.aura.ui.Fonts
import com.abdllh.aura.ui.Motion
import com.abdllh.aura.ui.Palette
import com.abdllh.aura.ui.Recolours
import com.abdllh.aura.ui.Surface
import com.abdllh.aura.ui.SurfaceCard
import com.abdllh.aura.ui.Tokens2
import com.abdllh.aura.ui.Type
import com.abdllh.aura.ui.anim
import com.abdllh.aura.ui.at
import com.abdllh.aura.ui.box
import com.abdllh.aura.ui.fadeTo
import com.abdllh.aura.ui.look
import com.abdllh.aura.ui.plateView
import com.abdllh.aura.ui.surfaceImage
import kotlin.math.min

/**
 * The turn card (SPEC.md section 5), 364 x 190 at (12, 44): the tile at the reading edge, the distance, the road, one fact as the hint. Its states are what it is told: a manoeuvre on the blue tile, the quiet
 * tile of a long stretch, a roundabout, lanes in the hint's place, a message (rerouting, no GPS) and the arrival. Numbers are set when they change, in fixed line boxes, so a new fix redraws a line and lays nothing out.
 */
internal class TurnCard(ctx: Context) : SurfaceCard(ctx, Surface.CARD) {
    private val ar = Fonts.arabicUi
    private val tileX = if (ar) 260 else 14
    private val roadX = if (ar) 6 else 118
    private val roadW = if (ar) 240 else 234
    private val hintX = if (ar) 12 else 16

    private val blue = object : View(ctx) { override fun hasOverlappingRendering() = false }.apply { background = ctx.getDrawable(R.drawable.tile_accent) }
    private val quiet = ctx.plateView(TILE_R) { Palette.fill2 }
    private val glyphA = ManeuverView(ctx)
    private val glyphB = ManeuverView(ctx)
    private var front = glyphA
    private var back = glyphB
    private val dist = ctx.line(Type.dist, { Palette.ink })
    private val title = ctx.line(Type.road, { Palette.ink })
    private val road = ctx.line(Type.road, { Palette.ink }).apply { gravity = Gravity.CENTER_VERTICAL }
    private val hint = ctx.line(Type.hint, { Palette.ink2Move })
    private val lanes = LaneRow(ctx)
    private val fit = TextPaint(road.paint)
    private val bidi = BidiFormatter.getInstance(ar)

    private var blueShown = true
    private var keyShown: Any? = null
    private var roadShown: String? = null
    private var roadLong = false
    private var roadUp = 0f
    private var lanesShown: String? = null
    private var glyphKey: Any? = null

    init {
        // (the mockup's glow is 300 wide over the whole height, at the reading edge; flipped in English)
        val glow = ctx.surfaceImage(R.drawable.turn_glow_night, R.drawable.turn_glow_day)
        if (!ar) glow.scaleX = -1f
        addView(glow, at(if (ar) 64 else 0, 0, 300, 190))
        addView(blue, at(tileX - TILE_OUT, 14 - TILE_OUT, 90 + 2 * TILE_OUT, 90 + 2 * TILE_OUT))
        addView(quiet, at(tileX, 14, 90, 90))
        for (g in arrayOf(glyphA, glyphB)) addView(g, at(tileX + 3, 17, 84, 84))
        glyphB.alpha = 0f
        quiet.alpha = 0f
        addView(dist, Type.dist.box(roadX, 2, roadW - 2))
        addView(title, Type.road.box(roadX, 12, roadW))
        title.visibility = View.INVISIBLE
        addView(road, at(roadX, 57, roadW, Type.road.viewH))
        addView(hint, Type.hint.box(hintX, 128, 336))
        addView(lanes, at(12, 124, 340, 48))
        lanes.alpha = 0f
        look {
            colourGlyphs()
            fit.set(road.paint)
            roadShown?.let { r -> roadShown = null; setRoad(r) } // (the weight goes with the theme: what fits may not)
        }
    }

    private fun colourGlyphs() {
        val c = if (blueShown) Palette.onAccent else Palette.ink
        glyphA.color = c
        glyphB.color = c
    }

    /** A manoeuvre: its arrow from [step], or a glyph of the family. [exit]: a roundabout's number the sign may say, 0 when it may not. New [key]: a new manoeuvre comes in. */
    fun manoeuvre(key: Any, step: Step, exit: Int, onBlue: Boolean, distance: CharSequence, roadLine: String, hintLine: String, laneList: List<Lane>?) {
        showDist(distance)
        title.visibility = View.INVISIBLE
        setRoadUp(0f)
        val fresh = key != keyShown
        // (the quiet tile: nothing to do yet, `go-straight`, whatever comes at the end of the stretch)
        val k: Any = if (onBlue) StepGlyph(step, exit) else R.drawable.ic_go_straight
        if (k != glyphKey) {
            setGlyph(k, onBlue, if (onBlue) GLYPH_PX else QUIET_PX) { g ->
                if (onBlue) {
                    g.show(step)
                    g.exit = exit
                } else g.glyph = R.drawable.ic_go_straight
            }
        }
        setRoad(roadLine)
        setHint(hintLine, laneList)
        if (fresh) comeIn(key, Tokens2.MS_maneuver, 8f)
    }

    /** No road to give: rerouting, no fix. The quiet tile holds still with [glyphRes]; nothing spins on a driving screen. */
    fun message(key: Any, glyphRes: Int, roadLine: String, hintLine: String) {
        dist.visibility = View.INVISIBLE
        title.visibility = View.INVISIBLE
        setRoadUp(ROAD_UP)
        val fresh = key != keyShown
        if (glyphRes != glyphKey) setGlyph(glyphRes, false, MESSAGE_PX) { g -> g.glyph = glyphRes }
        setRoad(roadLine)
        setHint(hintLine, null)
        if (fresh) comeIn(key, Tokens2.MS_swap, 0f)
    }

    /** `drive-arrived`: `وصلت`, the name, the trip's time and distance. */
    fun arrived(key: Any, titleLine: String, name: String, tripLine: String) {
        dist.visibility = View.INVISIBLE
        title.visibility = View.VISIBLE
        title.text = titleLine
        setRoadUp(-1f)
        if (R.drawable.ic_arrive != glyphKey) setGlyph(R.drawable.ic_arrive, true, MESSAGE_PX) { g -> g.glyph = R.drawable.ic_arrive }
        setRoad(name)
        setHint(tripLine, null)
        if (key != keyShown) comeIn(key, Tokens2.MS_maneuver, 0f)
    }

    /** It will show anew at once when next told (a trip that comes while it is away). */
    fun forget() {
        keyShown = null
    }

    private fun showDist(d: CharSequence) {
        if (dist.visibility != View.VISIBLE) dist.visibility = View.VISIBLE
        if (dist.text.toString() != d.toString() || dist.tag !== Palette.c) {
            dist.text = d
            dist.tag = Palette.c // (the unit's colour is in its span: again with the theme)
        }
    }

    private fun setRoadUp(up: Float) {
        roadUp = up
        if (road.translationY != up) road.translationY = up
    }

    private fun setRoad(s: String) {
        if (s == roadShown) return
        roadShown = s
        val text = if (s.isBlank()) "" else bidi.unicodeWrap(s)
        // road 36, then roadLong 30, then the ellipsis (section 3)
        fit.textSize = Type.road.sp
        fit.typeface = Fonts.of(Type.road, true)
        val long = Layout.getDesiredWidth(text, fit) > roadW - 2
        if (long != roadLong) {
            roadLong = long
            road.oneLine(if (long) Type.roadLong else Type.road, true)
        }
        road.text = text
    }

    private fun setHint(s: String, list: List<Lane>?) {
        if (hint.text.toString() != s) hint.text = s
        val key = list?.joinToString("|") { "${it.valid}${it.validIndication}${it.indications}" }
        if (key == lanesShown) return
        val had = lanesShown != null
        lanesShown = key
        if (list != null) lanes.set(LaneRow.cells(list))
        val dur = moveMs(Tokens2.MS_lane)
        if (isShown && (had != (list != null))) {
            lanes.fadeTo(if (list != null) 1f else 0f, dur)
            hint.fadeTo(if (list != null) 0f else 1f, dur)
        } else {
            lanes.alpha = if (list != null) 1f else 0f
            hint.alpha = if (list != null) 0f else 1f
        }
    }

    /** Tile and glyph: section 9's "Tile glyph crossfade 120". */
    private fun setGlyph(key: Any, onBlue: Boolean, px: Int, set: (ManeuverView) -> Unit) {
        glyphKey = key
        val dur = moveMs(Tokens2.MS_maneuverGlyph)
        val animate = isShown
        val g = back
        g.animate().cancel()
        g.glyph = 0
        set(g)
        g.scaleX = px / GLYPH_PX.toFloat()
        g.scaleY = g.scaleX
        g.color = if (onBlue) Palette.onAccent else Palette.ink
        if (animate) {
            g.alpha = 0f
            g.fadeTo(1f, dur)
            front.fadeTo(0f, dur)
        } else {
            g.alpha = 1f
            front.animate().cancel()
            front.alpha = 0f
        }
        back = front
        front = g
        if (onBlue != blueShown) {
            blueShown = onBlue
            if (animate) {
                blue.fadeTo(if (onBlue) 1f else 0f, dur)
                quiet.fadeTo(if (onBlue) 0f else 1f, dur)
            } else {
                blue.animate().cancel()
                quiet.animate().cancel()
                blue.alpha = if (onBlue) 1f else 0f
                quiet.alpha = if (onBlue) 0f else 1f
            }
        }
    }

    /** New words: alpha and y +8 to 0 on `enter`. The first time it shows, at once. */
    private fun comeIn(key: Any, ms: Long, dy: Float) {
        val first = keyShown == null
        keyShown = key
        if (first || !isShown) {
            road.translationY = roadUp
            return
        }
        val dur = moveMs(ms)
        for (v in arrayOf<AText>(dist, title, road, hint)) {
            if (v.visibility != View.VISIBLE) continue
            val rest = if (v === road) roadUp else 0f
            val a = if (v === hint && lanesShown != null) 0f else 1f
            v.animate().cancel()
            v.alpha = 0f
            v.translationY = rest + dy
            v.anim(dur, Motion.ENTER).alpha(a).translationY(rest).start()
        }
    }

    /** What decides the arrow: a new one crossfades, the same one does nothing. */
    private data class StepGlyph(val turn: Any, val exit: Int, val angle: Float, val shape: Int) {
        constructor(s: Step, exit: Int) : this(com.abdllh.aura.nav.Instructions.turn(s), exit, com.abdllh.aura.nav.Instructions.exitAngle(s), ManeuverView.shapeOf(s))
    }

    private companion object {
        /** `tile-accent`'s outset. */
        const val TILE_OUT = 16
        const val TILE_R = 20
        const val GLYPH_PX = 84
        const val QUIET_PX = 76
        const val MESSAGE_PX = 72
        /** With no distance over it the road line stands at y 30 of the card. */
        const val ROAD_UP = -27f
    }
}

/**
 * The lanes at the junction (section 5), in the hint's place: cells 58 x 48, radius 10, gap 6, centred, left to right in every language as on the road's own signs. The lanes to take in `accent`
 * with a white glyph, the others `fill`, `ink2`. One view, drawn: it is set once per junction.
 */
internal class LaneRow(ctx: Context) : View(ctx), Recolours {
    class Cell(@JvmField val go: Boolean, @JvmField val icon: Int)

    private var cells = emptyList<Cell>()
    private val icons = HashMap<Int, Drawable>()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun hasOverlappingRendering() = false

    override fun onLook() = invalidate()

    fun set(list: List<Cell>) {
        cells = list
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val n = cells.size
        if (n == 0) return
        val w = min(CELL_W, (width - GAP * (n - 1)) / n)
        var x = (width - w * n - GAP * (n - 1)) / 2f
        for (cell in cells) {
            p.color = if (cell.go) Palette.accent else Palette.fill
            rect.set(x, 0f, x + w, height.toFloat())
            c.drawRoundRect(rect, Tokens2.R_lane.toFloat(), Tokens2.R_lane.toFloat(), p)
            val d = icons.getOrPut(cell.icon) { context.getDrawable(cell.icon)!!.mutate() }
            d.setTint(if (cell.go) Palette.onAccent else Palette.ink2)
            val l = (x + (w - GLYPH) / 2).toInt()
            val t = ((height - GLYPH) / 2f).toInt()
            d.setBounds(l, t, l + GLYPH, t + GLYPH)
            d.draw(c)
            x += w + GAP
        }
    }

    companion object {
        private const val CELL_W = 58f
        private const val GAP = 6f
        private const val GLYPH = 34

        fun cells(lanes: List<Lane>): List<Cell> = lanes.map { l ->
            val way = l.validIndication.ifBlank { l.indications.firstOrNull().orEmpty() }
            Cell(l.valid, icon(if (l.valid) way else l.indications.firstOrNull().orEmpty()))
        }

        private fun icon(way: String) = when (way) {
            "slight left" -> R.drawable.ic_lane_slight_left
            "left", "sharp left" -> R.drawable.ic_lane_left
            "slight right" -> R.drawable.ic_lane_slight_right
            "right", "sharp right" -> R.drawable.ic_lane_right
            "uturn" -> R.drawable.ic_lane_uturn
            else -> R.drawable.ic_lane_straight
        }
    }
}
